//! Is this installation actually usable, and if not, what is wrong with it?
//!
//! The state a profile reports is derived by looking at the disk, never
//! remembered. A remembered "installed" flag is exactly how a launcher ends up
//! insisting an installation is fine while the game refuses to start: the flag
//! and the filesystem disagree, and the flag wins.
//!
//! Verification is the same operation as installation, run without downloading:
//! walk the resolved artifact list, check each file exists and hashes correctly,
//! and report what is missing or wrong. Repair is then just "install the
//! outstanding subset", which is why there is no separate repair pipeline to
//! keep in sync.
//!
//! **Repair never touches profile data.** It works on the shared cache and the
//! profile's `natives/` directory, both of which the launcher owns and can
//! reproduce. Worlds, screenshots, options, mods and config are not ours to
//! delete and are never considered.

use std::path::PathBuf;

use super::download::{verify_file, DownloadRequest};
use super::natives::NativeLibraryManager;
use super::resolver::ResolvedInstall;

/// What a profile's installation looks like right now.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum InstallationState {
    /// Nothing has been installed.
    NotInstalled,
    /// An install is in progress. Only ever reported while one is running; it
    /// is not persisted, so a crash leaves the profile looking incomplete
    /// rather than falsely ready.
    Installing,
    /// Everything present and verified.
    Installed,
    /// Files exist but at least one fails its hash. The bytes on disk are wrong.
    Corrupted,
    /// Files are missing, or natives were never extracted. Nothing is wrong with
    /// what is there; there is simply not enough of it.
    RepairRequired,
}

impl InstallationState {
    /// Whether the game can be started from this state.
    pub fn is_playable(self) -> bool {
        matches!(self, InstallationState::Installed)
    }

    /// Whether running a repair would help.
    pub fn needs_repair(self) -> bool {
        matches!(
            self,
            InstallationState::Corrupted
                | InstallationState::RepairRequired
                | InstallationState::NotInstalled
        )
    }

    pub fn label(self) -> &'static str {
        match self {
            InstallationState::NotInstalled => "Not installed",
            InstallationState::Installing => "Installing",
            InstallationState::Installed => "Installed",
            InstallationState::Corrupted => "Corrupted",
            InstallationState::RepairRequired => "Repair required",
        }
    }

    /// What to tell the user.
    pub fn explanation(self) -> &'static str {
        match self {
            InstallationState::NotInstalled => "The game files have not been downloaded yet.",
            InstallationState::Installing => "Downloading and verifying game files.",
            InstallationState::Installed => "All game files are present and verified.",
            InstallationState::Corrupted => {
                "Some game files do not match the hashes Mojang published. Repairing will \
                 re-download just those files. Your worlds, screenshots and settings are not \
                 affected."
            }
            InstallationState::RepairRequired => {
                "Some game files are missing. Repairing will fetch just what is absent."
            }
        }
    }
}

/// One artifact that is not as it should be.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Defect {
    pub name: String,
    pub path: PathBuf,
    pub kind: DefectKind,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DefectKind {
    /// The file is not there.
    Missing,
    /// The file is there but its content is wrong.
    HashMismatch,
}

/// The result of inspecting an installation.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct VerificationReport {
    pub state: InstallationState,
    pub total: usize,
    pub verified: usize,
    pub defects: Vec<Defect>,
    /// Whether the profile's natives directory has been populated.
    pub natives_extracted: bool,
}

impl VerificationReport {
    pub fn is_complete(&self) -> bool {
        self.state.is_playable()
    }

    /// Bytes that a repair would need to fetch, as far as the metadata declares.
    pub fn outstanding_bytes(&self, install: &ResolvedInstall) -> u64 {
        let broken: std::collections::BTreeSet<&str> =
            self.defects.iter().map(|d| d.name.as_str()).collect();
        install
            .all_downloads()
            .iter()
            .filter(|d| broken.contains(d.name.as_str()))
            .filter_map(|d| d.expect.size)
            .sum()
    }
}

/// Checks an installation against its resolved artifact list.
#[derive(Debug, Clone, Default)]
pub struct InstallationVerifier;

impl InstallationVerifier {
    pub fn new() -> Self {
        Self
    }

    /// Inspect every artifact, hashing what is present.
    ///
    /// This reads every file, so it is proportional to the installation size
    /// (~120 MB for 1.8.9). Use [`InstallationVerifier::quick_check`] for the
    /// cheap existence-only pass.
    pub fn verify(
        &self,
        install: &ResolvedInstall,
        natives_dir: &std::path::Path,
    ) -> VerificationReport {
        let downloads = install.all_downloads();
        let mut defects = Vec::new();
        let mut verified = 0;

        for item in &downloads {
            match classify(item) {
                None => verified += 1,
                Some(kind) => defects.push(Defect {
                    name: item.name.clone(),
                    path: item.target.clone(),
                    kind,
                }),
            }
        }

        let natives_extracted =
            install.natives.is_empty() || NativeLibraryManager::looks_extracted(natives_dir);

        VerificationReport {
            state: state_for(downloads.len(), &defects, natives_extracted),
            total: downloads.len(),
            verified,
            defects,
            natives_extracted,
        }
    }

    /// Existence-only pass, for deciding whether the Play button should offer
    /// to install. Does not hash, so it cannot detect corruption.
    pub fn quick_check(
        &self,
        install: &ResolvedInstall,
        natives_dir: &std::path::Path,
    ) -> VerificationReport {
        let downloads = install.all_downloads();
        let mut defects = Vec::new();
        let mut verified = 0;

        for item in &downloads {
            if item.target.is_file() {
                verified += 1;
            } else {
                defects.push(Defect {
                    name: item.name.clone(),
                    path: item.target.clone(),
                    kind: DefectKind::Missing,
                });
            }
        }

        let natives_extracted =
            install.natives.is_empty() || NativeLibraryManager::looks_extracted(natives_dir);

        VerificationReport {
            state: state_for(downloads.len(), &defects, natives_extracted),
            total: downloads.len(),
            verified,
            defects,
            natives_extracted,
        }
    }

    /// The subset a repair needs to fetch.
    ///
    /// Only the broken ones: a repair of a 110 MB installation with one bad file
    /// downloads one file.
    pub fn repair_set<'a>(
        &self,
        install: &'a ResolvedInstall,
        report: &VerificationReport,
    ) -> Vec<&'a DownloadRequest> {
        let broken: std::collections::BTreeSet<&str> =
            report.defects.iter().map(|d| d.name.as_str()).collect();
        install
            .all_downloads()
            .into_iter()
            .filter(|d| broken.contains(d.name.as_str()))
            .collect()
    }

    /// Delete the files that failed their hash, so the downloader replaces them
    /// rather than skipping them as "already present".
    ///
    /// Only ever removes paths that appear in the resolved artifact list, so it
    /// cannot touch anything the launcher did not put there.
    pub fn discard_corrupt(&self, report: &VerificationReport) -> usize {
        let mut removed = 0;
        for defect in &report.defects {
            if defect.kind == DefectKind::HashMismatch && std::fs::remove_file(&defect.path).is_ok()
            {
                removed += 1;
            }
        }
        removed
    }
}

fn classify(item: &DownloadRequest) -> Option<DefectKind> {
    if !item.target.is_file() {
        return Some(DefectKind::Missing);
    }
    if verify_file(&item.target, &item.expect.sha1) {
        None
    } else {
        Some(DefectKind::HashMismatch)
    }
}

fn state_for(total: usize, defects: &[Defect], natives_extracted: bool) -> InstallationState {
    if defects.iter().any(|d| d.kind == DefectKind::HashMismatch) {
        // Corruption outranks absence: wrong bytes are a more serious finding
        // than missing ones, and the user should be told which they have.
        return InstallationState::Corrupted;
    }
    if defects.len() == total && total > 0 {
        return InstallationState::NotInstalled;
    }
    if defects.is_empty() && natives_extracted {
        return InstallationState::Installed;
    }
    InstallationState::RepairRequired
}

#[cfg(test)]
#[allow(
    clippy::indexing_slicing,
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic
)]
mod tests {
    use super::*;
    use crate::install::download::{sha1_hex, Expectation};
    use crate::network::{Destination, Feature, Purpose, RequestDescriptor};
    use std::path::Path;

    fn request(name: &str, target: PathBuf, content: &[u8]) -> DownloadRequest {
        DownloadRequest {
            name: name.to_owned(),
            request: RequestDescriptor::new(
                Destination::LibrariesMinecraft,
                Purpose::DownloadGameArtifact,
                Feature::ProfileInstall,
                "/x",
            ),
            expect: Expectation::new(sha1_hex(content), Some(content.len() as u64)),
            target,
        }
    }

    /// A minimal install with a client jar and two libraries, none written yet.
    fn install(root: &Path) -> ResolvedInstall {
        ResolvedInstall {
            version_id: "1.8.9".into(),
            main_class: "net.minecraft.client.main.Main".into(),
            argument_template: Some("--username ${auth_player_name}".into()),
            required_java_major: 8,
            client: request(
                "1.8.9.jar",
                root.join("versions/1.8.9/1.8.9.jar"),
                b"client",
            ),
            libraries: vec![
                request("guava", root.join("libraries/guava.jar"), b"guava"),
                request("lwjgl", root.join("libraries/lwjgl.jar"), b"lwjgl"),
            ],
            natives: Vec::new(),
            asset_index: None,
            asset_index_id: None,
            assets: Vec::new(),
            logging: None,
        }
    }

    fn write(item: &DownloadRequest, content: &[u8]) {
        std::fs::create_dir_all(item.target.parent().unwrap()).unwrap();
        std::fs::write(&item.target, content).unwrap();
    }

    #[test]
    fn an_empty_directory_is_not_installed() {
        let tmp = tempfile::tempdir().unwrap();
        let plan = install(tmp.path());
        let report = InstallationVerifier::new().verify(&plan, &tmp.path().join("natives"));

        assert_eq!(report.state, InstallationState::NotInstalled);
        assert!(!report.state.is_playable());
        assert!(report.state.needs_repair());
        assert_eq!(report.verified, 0);
        assert_eq!(report.defects.len(), 3);
    }

    #[test]
    fn a_complete_installation_verifies() {
        let tmp = tempfile::tempdir().unwrap();
        let plan = install(tmp.path());
        write(&plan.client, b"client");
        write(&plan.libraries[0], b"guava");
        write(&plan.libraries[1], b"lwjgl");

        let report = InstallationVerifier::new().verify(&plan, &tmp.path().join("natives"));
        assert_eq!(report.state, InstallationState::Installed);
        assert!(report.is_complete());
        assert_eq!(report.verified, 3);
        assert!(report.defects.is_empty());
    }

    #[test]
    fn a_missing_file_requires_repair_rather_than_reporting_corruption() {
        let tmp = tempfile::tempdir().unwrap();
        let plan = install(tmp.path());
        write(&plan.client, b"client");
        write(&plan.libraries[0], b"guava");
        // lwjgl absent.

        let report = InstallationVerifier::new().verify(&plan, &tmp.path().join("natives"));
        assert_eq!(report.state, InstallationState::RepairRequired);
        assert_eq!(report.defects.len(), 1);
        assert_eq!(report.defects[0].kind, DefectKind::Missing);
        assert_eq!(report.defects[0].name, "lwjgl");
    }

    #[test]
    fn a_file_with_the_wrong_bytes_is_reported_as_corrupted() {
        let tmp = tempfile::tempdir().unwrap();
        let plan = install(tmp.path());
        write(&plan.client, b"client");
        write(&plan.libraries[0], b"guava");
        write(&plan.libraries[1], b"TAMPERED");

        let report = InstallationVerifier::new().verify(&plan, &tmp.path().join("natives"));
        assert_eq!(report.state, InstallationState::Corrupted);
        assert_eq!(report.defects.len(), 1);
        assert_eq!(report.defects[0].kind, DefectKind::HashMismatch);
        assert!(!report.state.is_playable());
    }

    #[test]
    fn corruption_outranks_absence_in_the_reported_state() {
        let tmp = tempfile::tempdir().unwrap();
        let plan = install(tmp.path());
        write(&plan.client, b"client");
        write(&plan.libraries[0], b"WRONG");
        // lwjgl missing as well.

        let report = InstallationVerifier::new().verify(&plan, &tmp.path().join("natives"));
        assert_eq!(
            report.state,
            InstallationState::Corrupted,
            "wrong bytes are the more serious finding and should be what we say"
        );
        assert_eq!(report.defects.len(), 2);
    }

    #[test]
    fn the_repair_set_is_only_what_is_broken() {
        let tmp = tempfile::tempdir().unwrap();
        let plan = install(tmp.path());
        write(&plan.client, b"client");
        write(&plan.libraries[0], b"guava");
        write(&plan.libraries[1], b"TAMPERED");

        let v = InstallationVerifier::new();
        let report = v.verify(&plan, &tmp.path().join("natives"));
        let set = v.repair_set(&plan, &report);

        assert_eq!(
            set.len(),
            1,
            "a repair fetches one file, not the whole install"
        );
        assert_eq!(set[0].name, "lwjgl");
        assert_eq!(report.outstanding_bytes(&plan), b"lwjgl".len() as u64);
    }

    #[test]
    fn discarding_corrupt_files_removes_only_the_bad_ones() {
        let tmp = tempfile::tempdir().unwrap();
        let plan = install(tmp.path());
        write(&plan.client, b"client");
        write(&plan.libraries[0], b"guava");
        write(&plan.libraries[1], b"TAMPERED");

        let v = InstallationVerifier::new();
        let report = v.verify(&plan, &tmp.path().join("natives"));
        assert_eq!(v.discard_corrupt(&report), 1);

        assert!(plan.client.target.exists(), "good files survive");
        assert!(plan.libraries[0].target.exists());
        assert!(
            !plan.libraries[1].target.exists(),
            "the corrupt file is removed so the downloader replaces it"
        );
    }

    #[test]
    fn repair_never_looks_at_profile_data() {
        // The verifier only ever inspects paths from the resolved artifact list.
        // Nothing in that list is profile-owned, so worlds and screenshots
        // cannot be considered, let alone deleted.
        let tmp = tempfile::tempdir().unwrap();
        let plan = install(tmp.path());

        let world = tmp
            .path()
            .join("profiles/main/minecraft/saves/MyWorld/level.dat");
        std::fs::create_dir_all(world.parent().unwrap()).unwrap();
        std::fs::write(&world, b"precious").unwrap();

        let v = InstallationVerifier::new();
        let report = v.verify(&plan, &tmp.path().join("natives"));
        v.discard_corrupt(&report);

        assert!(
            world.exists(),
            "user data must never be touched by a repair"
        );
        assert!(!report.defects.iter().any(|d| d.path == world));
    }

    #[test]
    fn quick_check_finds_absence_but_not_corruption() {
        let tmp = tempfile::tempdir().unwrap();
        let plan = install(tmp.path());
        write(&plan.client, b"client");
        write(&plan.libraries[0], b"guava");
        write(&plan.libraries[1], b"TAMPERED");

        let v = InstallationVerifier::new();
        let quick = v.quick_check(&plan, &tmp.path().join("natives"));
        assert_eq!(
            quick.state,
            InstallationState::Installed,
            "the cheap pass only checks existence, and says so in its name"
        );

        let full = v.verify(&plan, &tmp.path().join("natives"));
        assert_eq!(full.state, InstallationState::Corrupted);
    }

    #[test]
    fn unextracted_natives_require_repair_even_when_every_file_is_present() {
        let tmp = tempfile::tempdir().unwrap();
        let mut plan = install(tmp.path());
        plan.natives.push(crate::install::resolver::NativeArtifact {
            library: "lwjgl-platform".into(),
            download: request("lwjgl natives", tmp.path().join("libraries/n.jar"), b"n"),
            exclude: vec![],
        });

        write(&plan.client, b"client");
        write(&plan.libraries[0], b"guava");
        write(&plan.libraries[1], b"lwjgl");
        write(&plan.natives[0].download, b"n");

        let natives_dir = tmp.path().join("natives");
        let report = InstallationVerifier::new().verify(&plan, &natives_dir);
        assert_eq!(report.state, InstallationState::RepairRequired);
        assert!(report.defects.is_empty(), "no file is wrong");
        assert!(!report.natives_extracted, "but nothing was unpacked");

        // Once a library is unpacked, the installation is complete.
        std::fs::create_dir_all(&natives_dir).unwrap();
        std::fs::write(natives_dir.join("lwjgl64.dll"), b"x").unwrap();
        let after = InstallationVerifier::new().verify(&plan, &natives_dir);
        assert_eq!(after.state, InstallationState::Installed);
    }

    #[test]
    fn state_labels_and_explanations_are_distinct_and_useful() {
        let all = [
            InstallationState::NotInstalled,
            InstallationState::Installing,
            InstallationState::Installed,
            InstallationState::Corrupted,
            InstallationState::RepairRequired,
        ];
        let mut labels: Vec<&str> = all.iter().map(|s| s.label()).collect();
        labels.sort_unstable();
        let before = labels.len();
        labels.dedup();
        assert_eq!(labels.len(), before);

        // The corruption message must reassure about user data, because that is
        // the first thing anyone asks before pressing Repair.
        assert!(InstallationState::Corrupted
            .explanation()
            .contains("worlds"));
    }
}

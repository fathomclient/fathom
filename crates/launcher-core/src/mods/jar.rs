//! Is this file actually a jar?
//!
//! # What this checks, and what it deliberately does not
//!
//! `mods/mod.rs` states the rule: **we do not parse attacker-controlled
//! archives to have them describe themselves.** That rule stands. This does not
//! read entry names, does not decompress anything, and does not look inside
//! `META-INF`.
//!
//! What it does is check the *envelope*: that the file begins with a local file
//! header, that it ends with a central directory record, that the record's
//! offsets point inside the file, and that it claims at least one entry. That is
//! four integers and two magic numbers — a structural check, not a parse.
//!
//! # Why bother at all
//!
//! Because the alternative is a launcher that accepts an HTML error page saved
//! as `OptiFine_1.8.9_HD_U_M5.jar`, copies it into the mod store, records its
//! hash, adds it to a profile, and produces a game that refuses to start with a
//! stack trace naming a class loader. That has a real cost and it lands a long
//! way from its cause.
//!
//! Downloading `OptiFine` is a manual walk through a page with several download
//! buttons on it, some of which are advertisements. Getting the wrong file is
//! not a hypothetical.
//!
//! # What a pass means
//!
//! That the file is a zip with entries in it. Not that it is `OptiFine`, not that
//! it is safe, not that it will load. It is the difference between "this is a
//! jar" and "this is not a jar", which is the only question worth answering at
//! the moment somebody drops a file on a window.

use std::fs::File;
use std::io::{Read, Seek, SeekFrom};
use std::path::Path;

/// Nothing smaller than this can be a zip with an entry in it.
const MINIMUM: u64 = 22;

/// Larger than any 1.8.9 mod, and small enough that a mistake is caught rather
/// than copied. `OptiFine` for 1.8.9 is about 2 MB; the largest thing anyone
/// reasonably adds to this game is a resource pack, and those do not go here.
const MAXIMUM: u64 = 256 * 1024 * 1024;

/// A zip's End of Central Directory record. 22 bytes, plus a comment of up to
/// 65535, so it lives somewhere in the last 64 KB and a little.
const EOCD_SIGNATURE: [u8; 4] = [0x50, 0x4B, 0x05, 0x06];
const LOCAL_HEADER: [u8; 4] = [0x50, 0x4B, 0x03, 0x04];
const EOCD_SEARCH: u64 = 66 * 1024;

/// Why a file was refused.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum JarProblem {
    #[error("that file is empty")]
    Empty,
    #[error("that file is {size} bytes, which is too large to be a mod")]
    TooLarge { size: u64 },
    #[error("that is not a jar — a jar is a zip archive, and this file does not start like one")]
    NotAZip,
    #[error("that jar looks truncated — the archive's index is missing or points past the end of the file")]
    Truncated,
    #[error("that archive is empty — there is nothing in it to load")]
    NoEntries,
    #[error("could not read that file: {0}")]
    Unreadable(String),
}

/// What a file turned out to be.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct JarFacts {
    pub size: u64,
    /// How many entries the archive says it contains.
    pub entries: u16,
}

/// Check the envelope.
///
/// Returns what the archive claims about itself, or the first thing wrong with
/// it. Reads at most 66 KB regardless of the file's size.
pub fn verify(path: &Path) -> Result<JarFacts, JarProblem> {
    let mut file = File::open(path).map_err(|error| JarProblem::Unreadable(error.to_string()))?;
    let size = file
        .metadata()
        .map_err(|error| JarProblem::Unreadable(error.to_string()))?
        .len();

    if size == 0 {
        return Err(JarProblem::Empty);
    }
    if size > MAXIMUM {
        return Err(JarProblem::TooLarge { size });
    }
    if size < MINIMUM {
        return Err(JarProblem::NotAZip);
    }

    let mut start = [0u8; 4];
    file.read_exact(&mut start)
        .map_err(|error| JarProblem::Unreadable(error.to_string()))?;
    // A jar always begins with an entry. The other legal zip opening — an
    // immediate end-of-directory record — is an empty archive, which is caught
    // below with a better sentence than "not a zip".
    if start != LOCAL_HEADER && start != EOCD_SIGNATURE {
        return Err(JarProblem::NotAZip);
    }

    let window = EOCD_SEARCH.min(size);
    // `EOCD_SEARCH` is 66 KB, so the minimum of it and any file size fits a
    // `usize` on every target this builds for. Converted rather than cast, so
    // that stays true if the constant ever changes.
    let capacity = usize::try_from(window).map_err(|_| JarProblem::Truncated)?;
    let mut tail = vec![0u8; capacity];
    file.seek(SeekFrom::Start(size - window))
        .map_err(|error| JarProblem::Unreadable(error.to_string()))?;
    file.read_exact(&mut tail)
        .map_err(|error| JarProblem::Unreadable(error.to_string()))?;

    // Searched backwards: the record is at the end, and a file whose *content*
    // happens to contain the signature would otherwise be read at the wrong
    // offset. Zip readers all do this, and for this reason.
    let eocd = tail
        .windows(4)
        .rposition(|slice| slice == EOCD_SIGNATURE)
        .ok_or(JarProblem::Truncated)?;
    if tail.len() - eocd < 22 {
        return Err(JarProblem::Truncated);
    }

    // Read through `get`, not by indexing. The bounds were checked two lines
    // ago and indexing would be correct today; it would also be one refactor
    // away from a panic inside a file the user chose, which is the failure mode
    // the whole module exists to avoid. The alternative costs three `?`s.
    let record = tail.get(eocd..).ok_or(JarProblem::Truncated)?;
    let entries = u16::from_le_bytes([
        *record.get(10).ok_or(JarProblem::Truncated)?,
        *record.get(11).ok_or(JarProblem::Truncated)?,
    ]);
    let directory_size = u64::from(u32::from_le_bytes([
        *record.get(12).ok_or(JarProblem::Truncated)?,
        *record.get(13).ok_or(JarProblem::Truncated)?,
        *record.get(14).ok_or(JarProblem::Truncated)?,
        *record.get(15).ok_or(JarProblem::Truncated)?,
    ]));
    let directory_offset = u64::from(u32::from_le_bytes([
        *record.get(16).ok_or(JarProblem::Truncated)?,
        *record.get(17).ok_or(JarProblem::Truncated)?,
        *record.get(18).ok_or(JarProblem::Truncated)?,
        *record.get(19).ok_or(JarProblem::Truncated)?,
    ]));

    // Zip64 writes 0xFFFF / 0xFFFFFFFF here and puts the real values in a
    // separate record. Nothing that belongs in a mods folder is a zip64
    // archive, and following one properly is a second parser; a file that says
    // this is refused rather than half-read.
    if entries == 0xFFFF
        || directory_size == u64::from(u32::MAX)
        || directory_offset == u64::from(u32::MAX)
    {
        return Err(JarProblem::Truncated);
    }
    if entries == 0 {
        return Err(JarProblem::NoEntries);
    }
    // The index has to be inside the file. This is the check that catches a
    // download cut short: the record survives at the end of a partial file far
    // less often than the numbers in it stop making sense.
    if directory_offset
        .checked_add(directory_size)
        .is_none_or(|end| end > size)
    {
        return Err(JarProblem::Truncated);
    }

    Ok(JarFacts { size, entries })
}

#[cfg(test)]
#[allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::cast_possible_truncation
)]
mod tests {
    use super::*;
    use std::io::Write;

    /// The smallest legal zip: an end-of-central-directory record and nothing
    /// else. Written by hand rather than with a zip crate, because the point is
    /// to control the bytes.
    fn eocd(entries: u16, directory_size: u32, directory_offset: u32) -> Vec<u8> {
        let mut out = Vec::new();
        out.extend_from_slice(&EOCD_SIGNATURE);
        out.extend_from_slice(&0u16.to_le_bytes()); // this disk
        out.extend_from_slice(&0u16.to_le_bytes()); // disk with the directory
        out.extend_from_slice(&entries.to_le_bytes()); // entries on this disk
        out.extend_from_slice(&entries.to_le_bytes()); // entries total
        out.extend_from_slice(&directory_size.to_le_bytes());
        out.extend_from_slice(&directory_offset.to_le_bytes());
        out.extend_from_slice(&0u16.to_le_bytes()); // comment length
        out
    }

    fn write(bytes: &[u8]) -> (tempfile::TempDir, std::path::PathBuf) {
        let directory = tempfile::tempdir().unwrap();
        let path = directory.path().join("candidate.jar");
        let mut file = File::create(&path).unwrap();
        file.write_all(bytes).unwrap();
        (directory, path)
    }

    fn plausible_jar() -> Vec<u8> {
        // A local header, some bytes standing in for an entry, then a record
        // that describes an index sitting inside the file.
        let mut out = Vec::new();
        out.extend_from_slice(&LOCAL_HEADER);
        out.extend_from_slice(&[0u8; 60]);
        let offset = out.len() as u32;
        out.extend_from_slice(&[0u8; 40]); // stand-in for the central directory
        out.extend_from_slice(&eocd(3, 40, offset));
        out
    }

    #[test]
    fn a_well_formed_jar_passes_and_says_what_it_holds() {
        let (_directory, path) = write(&plausible_jar());
        let facts = verify(&path).unwrap();
        assert_eq!(facts.entries, 3);
        assert!(facts.size > 100);
    }

    #[test]
    fn an_html_error_page_saved_as_a_jar_is_refused() {
        // The case this exists for. OptiFine's download page has several
        // buttons on it and not all of them are the file.
        let (_directory, path) = write(b"<!doctype html><html><body>Not found</body></html>");
        assert_eq!(verify(&path), Err(JarProblem::NotAZip));
    }

    #[test]
    fn an_empty_file_is_refused_by_name() {
        let (_directory, path) = write(b"");
        assert_eq!(verify(&path), Err(JarProblem::Empty));
    }

    #[test]
    fn a_truncated_download_is_refused() {
        // The first half of a jar: it starts correctly and has no index.
        let full = plausible_jar();
        let (_directory, path) = write(&full[..full.len() / 2]);
        assert_eq!(verify(&path), Err(JarProblem::Truncated));
    }

    #[test]
    fn an_index_pointing_past_the_end_is_refused() {
        let mut bytes = Vec::new();
        bytes.extend_from_slice(&LOCAL_HEADER);
        bytes.extend_from_slice(&[0u8; 60]);
        bytes.extend_from_slice(&eocd(2, 40, 900_000));
        let (_directory, path) = write(&bytes);
        assert_eq!(verify(&path), Err(JarProblem::Truncated));
    }

    #[test]
    fn an_archive_with_nothing_in_it_says_so() {
        let mut bytes = Vec::new();
        bytes.extend_from_slice(&eocd(0, 0, 0));
        let (_directory, path) = write(&bytes);
        assert_eq!(verify(&path), Err(JarProblem::NoEntries));
    }

    #[test]
    fn a_file_that_is_not_there_reports_rather_than_panics() {
        let problem = verify(Path::new("no-such-file-anywhere.jar")).unwrap_err();
        assert!(matches!(problem, JarProblem::Unreadable(_)));
    }

    #[test]
    fn nothing_inside_the_archive_is_read() {
        // The guarantee, expressed as a test: an archive whose entry names are
        // hostile passes exactly as an ordinary one does, because the names are
        // never looked at.
        let mut bytes = Vec::new();
        bytes.extend_from_slice(&LOCAL_HEADER);
        bytes.extend_from_slice(b"../../../../etc/passwd\0\0\0\0\0\0\0\0\0\0");
        bytes.extend_from_slice(&[0u8; 30]);
        let offset = bytes.len() as u32;
        bytes.extend_from_slice(&[0u8; 20]);
        bytes.extend_from_slice(&eocd(1, 20, offset));
        let (_directory, path) = write(&bytes);
        assert!(verify(&path).is_ok());
    }
}

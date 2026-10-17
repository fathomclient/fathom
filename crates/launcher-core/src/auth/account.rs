//! The signed-in account as Fathom shows it: a username, a UUID and a face.
//!
//! # What is kept, and where
//!
//! Only what `GET /minecraft/profile` returns, which is public: the UUID, the
//! username, the address of the skin being worn and the name of the cape. It is
//! written to `accounts/<uuid>.json` in the data directory, next to a small PNG
//! of the player's head cut out of their skin.
//!
//! No token of any kind is in either file. The refresh token lives in the OS
//! credential store and nowhere else; the Minecraft access token lives in
//! memory. The account record exists so that a restart can show who was signed
//! in *before* the stored session has been refreshed, and so the game menu can
//! show the same face the launcher does.
//!
//! # Refresh
//!
//! The profile itself is fetched on every sign-in and every session restore,
//! because it is part of establishing the session. The skin is fetched only
//! when the skin being worn changed, the head is missing, or the cached head is
//! older than [`SKIN_REFRESH`] — a skin changed on minecraft.net under the same
//! address is rare, and a day is soon enough to notice.
//!
//! # The skin is untrusted input
//!
//! It is a PNG from a server. It is decoded with a size ceiling, its
//! dimensions are checked against the shapes a Minecraft skin can have, and
//! only 128 pixels of it are used. A skin that fails any of that produces no
//! head, and the interface falls back to the initial it already shows.

use std::path::PathBuf;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};

use super::AccountProfile;
use crate::fs_safety;
use crate::paths::DataPaths;

/// The largest skin response read. Real skins are a few kilobytes.
pub const MAX_SKIN_BYTES: usize = 1024 * 1024;

/// How long a cached head is trusted when the skin address has not changed.
pub const SKIN_REFRESH: Duration = Duration::from_secs(24 * 60 * 60);

/// The rendered head's edge, in pixels. Eight face pixels, each 8×8, so it is
/// crisp when shown at the 20–40 px the interface uses.
pub const HEAD_SIZE: u32 = 64;

/// Largest skin edge accepted. HD skins exist in resource packs, not on
/// Mojang's texture host, but a generous bound costs nothing.
const MAX_SKIN_EDGE: u32 = 1024;

const SCHEMA: u32 = 1;

/// The public record of a signed-in account.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StoredAccount {
    pub schema: u32,
    /// The Minecraft UUID, 32 lowercase hex characters without dashes.
    pub id: String,
    pub name: String,
    /// The address of the skin being worn, if not a default one.
    #[serde(default)]
    pub skin_url: Option<String>,
    /// `CLASSIC` or `SLIM`.
    #[serde(default)]
    pub skin_variant: Option<String>,
    /// The active cape's name.
    #[serde(default)]
    pub cape: Option<String>,
    /// When the profile was last fetched, in seconds since the epoch.
    pub updated_at: u64,
    /// When the head was last rendered from the skin, if it was.
    #[serde(default)]
    pub head_updated_at: Option<u64>,
}

impl StoredAccount {
    pub fn from_profile(profile: &AccountProfile, now: SystemTime) -> Option<Self> {
        let id = normalise_uuid(&profile.id)?;
        let skin = profile.active_skin();
        Some(Self {
            schema: SCHEMA,
            id,
            name: profile.name.chars().take(32).collect(),
            skin_url: skin
                .map(|skin| skin.url.clone())
                .filter(|url| texture_path(url).is_some()),
            skin_variant: skin.and_then(|skin| skin.variant.clone()),
            cape: profile.active_cape().and_then(|cape| cape.alias.clone()),
            updated_at: seconds(now),
            head_updated_at: None,
        })
    }

    /// The UUID in the dashed form most tools print.
    pub fn dashed_id(&self) -> String {
        dashed(&self.id)
    }
}

/// Where account records live, and the operations on them.
#[derive(Debug, Clone)]
pub struct AccountCache {
    root: PathBuf,
}

impl AccountCache {
    pub fn new(paths: &DataPaths) -> Self {
        Self {
            root: paths.accounts_dir(),
        }
    }

    fn record_path(&self, id: &str) -> Option<PathBuf> {
        normalise_uuid(id).map(|id| self.root.join(format!("{id}.json")))
    }

    fn head_path_for(&self, id: &str) -> Option<PathBuf> {
        normalise_uuid(id).map(|id| self.root.join(format!("{id}-head.png")))
    }

    pub fn load(&self, id: &str) -> Option<StoredAccount> {
        let path = self.record_path(id)?;
        let bytes = std::fs::read(path).ok()?;
        if bytes.len() > 64 * 1024 {
            return None;
        }
        let record: StoredAccount = serde_json::from_slice(&bytes).ok()?;
        (record.schema == SCHEMA && normalise_uuid(&record.id).as_deref() == Some(&*record.id))
            .then_some(record)
    }

    /// Every valid public account record kept on this machine, newest first.
    ///
    /// Refresh tokens are deliberately not enumerable on every credential
    /// backend, so the public cache is the account picker. A record only gets
    /// here after entitlement verification and is removed with its credential.
    pub fn list(&self) -> Vec<StoredAccount> {
        let Ok(entries) = std::fs::read_dir(&self.root) else {
            return Vec::new();
        };
        let mut accounts: Vec<StoredAccount> = entries
            .filter_map(Result::ok)
            .filter_map(|entry| {
                let path = entry.path();
                let name = path.file_name()?.to_str()?;
                let id = name.strip_suffix(".json")?;
                // Head images and unrelated files are ignored, and loading by
                // the id repeats the UUID and schema validation in one place.
                normalise_uuid(id)?;
                self.load(id)
            })
            .collect();
        accounts.sort_by(|left, right| {
            right
                .updated_at
                .cmp(&left.updated_at)
                .then_with(|| left.name.cmp(&right.name))
        });
        accounts
    }

    pub fn save(&self, record: &StoredAccount) -> Result<(), fs_safety::FsError> {
        let Some(path) = self.record_path(&record.id) else {
            return Ok(());
        };
        let bytes = serde_json::to_vec_pretty(record).unwrap_or_default();
        fs_safety::atomic_write(&path, &bytes)
    }

    /// The cached head, as PNG bytes.
    pub fn head(&self, id: &str) -> Option<Vec<u8>> {
        let bytes = std::fs::read(self.head_path_for(id)?).ok()?;
        (bytes.starts_with(b"\x89PNG") && bytes.len() < 256 * 1024).then_some(bytes)
    }

    pub fn head_path(&self, id: &str) -> Option<PathBuf> {
        self.head_path_for(id).filter(|path| path.is_file())
    }

    pub fn save_head(&self, id: &str, png: &[u8]) -> Result<(), fs_safety::FsError> {
        let Some(path) = self.head_path_for(id) else {
            return Ok(());
        };
        fs_safety::atomic_write(&path, png)
    }

    /// Whether the skin should be downloaded again for this profile.
    pub fn needs_skin(&self, profile: &AccountProfile, now: SystemTime) -> bool {
        let Some(fresh) = StoredAccount::from_profile(profile, now) else {
            return false;
        };
        if fresh.skin_url.is_none() {
            // Default skin: nothing to download.
            return false;
        }
        let Some(cached) = self.load(&fresh.id) else {
            return true;
        };
        if cached.skin_url != fresh.skin_url || self.head(&fresh.id).is_none() {
            return true;
        }
        cached
            .head_updated_at
            .is_none_or(|at| seconds(now).saturating_sub(at) >= SKIN_REFRESH.as_secs())
    }

    /// Record a fetched profile, and a freshly rendered head if there is one.
    ///
    /// When the profile now wears a default skin, any old head is removed so a
    /// skin the player took off does not linger on screen.
    pub fn record(
        &self,
        profile: &AccountProfile,
        head: Option<&[u8]>,
        now: SystemTime,
    ) -> Result<Option<StoredAccount>, fs_safety::FsError> {
        let Some(mut fresh) = StoredAccount::from_profile(profile, now) else {
            return Ok(None);
        };
        let previous = self.load(&fresh.id);
        match head {
            Some(png) => {
                self.save_head(&fresh.id, png)?;
                fresh.head_updated_at = Some(seconds(now));
            }
            None if fresh.skin_url.is_none() => {
                if let Some(path) = self.head_path_for(&fresh.id) {
                    let _ = std::fs::remove_file(path);
                }
            }
            None => {
                // Not re-fetched this time: keep what the last render said,
                // provided it was a render of the same skin.
                fresh.head_updated_at = previous
                    .filter(|previous| previous.skin_url == fresh.skin_url)
                    .and_then(|previous| previous.head_updated_at);
            }
        }
        self.save(&fresh)?;
        Ok(Some(fresh))
    }

    /// Remove everything kept about an account.
    pub fn forget(&self, id: &str) {
        for path in [self.record_path(id), self.head_path_for(id)]
            .into_iter()
            .flatten()
        {
            let _ = std::fs::remove_file(path);
        }
    }
}

fn seconds(when: SystemTime) -> u64 {
    when.duration_since(UNIX_EPOCH).map_or(0, |d| d.as_secs())
}

/// A UUID as 32 lowercase hex characters, or `None` if it is not one.
///
/// Account ids become file names, so this is the check that keeps a hostile
/// profile response from naming a path.
pub fn normalise_uuid(raw: &str) -> Option<String> {
    let compact: String = raw.chars().filter(|c| *c != '-').collect();
    (compact.len() == 32 && compact.chars().all(|c| c.is_ascii_hexdigit()))
        .then(|| compact.to_ascii_lowercase())
}

fn dashed(id: &str) -> String {
    if id.len() != 32 {
        return id.to_owned();
    }
    format!(
        "{}-{}-{}-{}-{}",
        &id[0..8],
        &id[8..12],
        &id[12..16],
        &id[16..20],
        &id[20..32]
    )
}

/// The request path for a skin URL, if the URL is one Fathom will fetch.
///
/// Mojang's profile API hands out `http://textures.minecraft.net/texture/<hex>`.
/// The same object is served over HTTPS, which is the only scheme used; the
/// path is rebuilt from the hex id rather than copied, so nothing else from the
/// response reaches the request.
pub fn texture_path(url: &str) -> Option<String> {
    let rest = url
        .strip_prefix("https://textures.minecraft.net/texture/")
        .or_else(|| url.strip_prefix("http://textures.minecraft.net/texture/"))?;
    (!rest.is_empty() && rest.len() <= 128 && rest.chars().all(|c| c.is_ascii_hexdigit()))
        .then(|| format!("/texture/{rest}"))
}

#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum HeadError {
    #[error("the skin is not a PNG this launcher can read")]
    Undecodable,
    #[error("the skin is {width}x{height}, which is not a Minecraft skin shape")]
    WrongShape { width: u32, height: u32 },
}

/// Cut the head out of a skin: the face, with the hat layer composited over it,
/// scaled up without smoothing to [`HEAD_SIZE`].
pub fn head_from_skin(skin_png: &[u8]) -> Result<Vec<u8>, HeadError> {
    if skin_png.len() > MAX_SKIN_BYTES {
        return Err(HeadError::Undecodable);
    }
    let (width, height, rgba) = decode_rgba(skin_png)?;
    // 64x64 (1.8+) or 64x32 (legacy), or an integer multiple of either.
    let valid = (64..=MAX_SKIN_EDGE).contains(&width)
        && width % 64 == 0
        && (height == width || height * 2 == width);
    if !valid {
        return Err(HeadError::WrongShape { width, height });
    }
    let scale = width / 64;
    let pixel = |x: u32, y: u32| -> [u8; 4] {
        let at = ((y * width + x) * 4) as usize;
        match rgba.get(at..at + 4) {
            Some(&[r, g, b, a]) => [r, g, b, a],
            _ => [0, 0, 0, 0],
        }
    };

    let mut face = [[0_u8; 4]; 64];
    for fy in 0..8 {
        for fx in 0..8 {
            let [r, g, b, _] = pixel((8 + fx) * scale, (8 + fy) * scale);
            // The face layer is opaque by definition; a transparent pixel in
            // it is drawn black, the way the game draws it.
            let mut out = [r, g, b, 255_u8];
            let [hr, hg, hb, ha] = pixel((40 + fx) * scale, (8 + fy) * scale);
            if ha > 0 {
                let alpha = u32::from(ha);
                let blend = |over: u8, under: u8| -> u8 {
                    u8::try_from((u32::from(over) * alpha + u32::from(under) * (255 - alpha)) / 255)
                        .unwrap_or(u8::MAX)
                };
                out = [blend(hr, r), blend(hg, g), blend(hb, b), 255];
            }
            if let Some(slot) = face.get_mut((fy * 8 + fx) as usize) {
                *slot = out;
            }
        }
    }

    let factor = HEAD_SIZE / 8;
    let mut output = Vec::with_capacity((HEAD_SIZE * HEAD_SIZE * 4) as usize);
    for y in 0..HEAD_SIZE {
        for x in 0..HEAD_SIZE {
            let cell = face
                .get(((y / factor) * 8 + x / factor) as usize)
                .copied()
                .unwrap_or([0, 0, 0, 255]);
            output.extend_from_slice(&cell);
        }
    }
    encode_rgba(HEAD_SIZE, HEAD_SIZE, &output)
}

fn decode_rgba(bytes: &[u8]) -> Result<(u32, u32, Vec<u8>), HeadError> {
    let mut decoder = png::Decoder::new(bytes);
    decoder.set_transformations(png::Transformations::EXPAND | png::Transformations::STRIP_16);
    let limits = png::Limits {
        bytes: (MAX_SKIN_EDGE * MAX_SKIN_EDGE * 4) as usize,
    };
    decoder.set_limits(limits);
    let mut reader = decoder.read_info().map_err(|_| HeadError::Undecodable)?;
    let (width, height) = {
        let info = reader.info();
        (info.width, info.height)
    };
    if width == 0 || height == 0 || width > MAX_SKIN_EDGE || height > MAX_SKIN_EDGE {
        return Err(HeadError::WrongShape { width, height });
    }
    let mut buffer = vec![0; reader.output_buffer_size()];
    let frame = reader
        .next_frame(&mut buffer)
        .map_err(|_| HeadError::Undecodable)?;
    let data = buffer
        .get(..frame.buffer_size())
        .ok_or(HeadError::Undecodable)?;

    let pixels = (width * height) as usize;
    let rgba = match frame.color_type {
        png::ColorType::Rgba => data.to_vec(),
        png::ColorType::Rgb => data
            .chunks_exact(3)
            .flat_map(|c| match c {
                [r, g, b] => [*r, *g, *b, 255],
                _ => [0, 0, 0, 0],
            })
            .collect(),
        png::ColorType::GrayscaleAlpha => data
            .chunks_exact(2)
            .flat_map(|c| match c {
                [v, a] => [*v, *v, *v, *a],
                _ => [0, 0, 0, 0],
            })
            .collect(),
        png::ColorType::Grayscale => data.iter().flat_map(|&v| [v, v, v, 255]).collect(),
        png::ColorType::Indexed => return Err(HeadError::Undecodable),
    };
    if rgba.len() != pixels * 4 {
        return Err(HeadError::Undecodable);
    }
    Ok((width, height, rgba))
}

fn encode_rgba(width: u32, height: u32, rgba: &[u8]) -> Result<Vec<u8>, HeadError> {
    let mut out = Vec::new();
    {
        let mut encoder = png::Encoder::new(&mut out, width, height);
        encoder.set_color(png::ColorType::Rgba);
        encoder.set_depth(png::BitDepth::Eight);
        let mut writer = encoder.write_header().map_err(|_| HeadError::Undecodable)?;
        writer
            .write_image_data(rgba)
            .map_err(|_| HeadError::Undecodable)?;
    }
    Ok(out)
}

#[cfg(test)]
#[allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing
)]
mod tests {
    use super::*;
    use crate::auth::ProfileTexture;

    fn skin(width: u32, height: u32, face: [u8; 4], hat: [u8; 4]) -> Vec<u8> {
        let mut rgba = vec![0_u8; (width * height * 4) as usize];
        let scale = width / 64;
        let mut paint = |x0: u32, y0: u32, colour: [u8; 4]| {
            for y in y0 * scale..(y0 + 8) * scale {
                for x in x0 * scale..(x0 + 8) * scale {
                    let at = ((y * width + x) * 4) as usize;
                    rgba[at..at + 4].copy_from_slice(&colour);
                }
            }
        };
        paint(8, 8, face);
        paint(40, 8, hat);
        encode_rgba(width, height, &rgba).unwrap()
    }

    fn decode(png: &[u8]) -> (u32, u32, Vec<u8>) {
        decode_rgba(png).unwrap()
    }

    fn profile(skin_url: Option<&str>) -> AccountProfile {
        AccountProfile {
            id: "069a79f4-44e9-4726-a5be-fca90e38aaf5".into(),
            name: "Notch".into(),
            skins: skin_url
                .map(|url| ProfileTexture {
                    id: "s".into(),
                    state: "ACTIVE".into(),
                    url: url.into(),
                    variant: Some("CLASSIC".into()),
                    alias: None,
                })
                .into_iter()
                .collect(),
            capes: Vec::new(),
        }
    }

    #[test]
    fn the_head_is_the_face_under_the_hat() {
        let transparent_hat = skin(64, 64, [200, 100, 50, 255], [0, 0, 0, 0]);
        let (w, h, pixels) = decode(&head_from_skin(&transparent_hat).unwrap());
        assert_eq!((w, h), (HEAD_SIZE, HEAD_SIZE));
        assert_eq!(&pixels[0..4], &[200, 100, 50, 255]);

        let opaque_hat = skin(64, 64, [200, 100, 50, 255], [10, 20, 30, 255]);
        let (_, _, pixels) = decode(&head_from_skin(&opaque_hat).unwrap());
        assert_eq!(&pixels[0..4], &[10, 20, 30, 255]);
    }

    #[test]
    fn legacy_and_hd_skins_are_accepted_and_odd_shapes_are_not() {
        assert!(head_from_skin(&skin(64, 32, [1, 2, 3, 255], [0, 0, 0, 0])).is_ok());
        assert!(head_from_skin(&skin(128, 128, [1, 2, 3, 255], [0, 0, 0, 0])).is_ok());
        let odd = encode_rgba(10, 10, &[0; 400]).unwrap();
        assert!(matches!(
            head_from_skin(&odd),
            Err(HeadError::WrongShape { .. })
        ));
        assert_eq!(head_from_skin(b"not a png"), Err(HeadError::Undecodable));
    }

    #[test]
    fn only_mojangs_texture_host_is_fetched_and_the_path_is_rebuilt() {
        assert_eq!(
            texture_path("http://textures.minecraft.net/texture/abc123").as_deref(),
            Some("/texture/abc123")
        );
        for hostile in [
            "https://evil.example/texture/abc",
            "http://textures.minecraft.net/texture/../../x",
            "http://textures.minecraft.net/texture/abc?x=1",
            "http://textures.minecraft.net.evil.example/texture/abc",
            "http://textures.minecraft.net/texture/",
        ] {
            assert!(texture_path(hostile).is_none(), "{hostile}");
        }
    }

    #[test]
    fn a_uuid_is_the_only_thing_that_becomes_a_file_name() {
        assert_eq!(
            normalise_uuid("069A79F4-44E9-4726-A5BE-FCA90E38AAF5").as_deref(),
            Some("069a79f444e94726a5befca90e38aaf5")
        );
        for hostile in [
            "../../x",
            "",
            "zz9a79f444e94726a5befca90e38aaf5",
            "069a79f4",
        ] {
            assert!(normalise_uuid(hostile).is_none(), "{hostile}");
        }
    }

    #[test]
    fn the_record_holds_public_data_and_survives_a_reload() {
        let tmp = tempfile::tempdir().unwrap();
        let cache = AccountCache::new(&DataPaths::rooted_at(tmp.path()));
        let url = "http://textures.minecraft.net/texture/abc123";
        let now = SystemTime::now();
        let p = profile(Some(url));

        assert!(cache.needs_skin(&p, now), "nothing cached yet");
        let head = head_from_skin(&skin(64, 64, [1, 2, 3, 255], [0, 0, 0, 0])).unwrap();
        let stored = cache.record(&p, Some(&head), now).unwrap().unwrap();
        assert_eq!(stored.id, "069a79f444e94726a5befca90e38aaf5");
        assert_eq!(stored.dashed_id(), "069a79f4-44e9-4726-a5be-fca90e38aaf5");
        assert_eq!(cache.load(&stored.id).unwrap(), stored);
        assert!(cache.head(&stored.id).is_some());
        assert!(!cache.needs_skin(&p, now), "same skin, fresh head");
        assert!(
            cache.needs_skin(&p, now + SKIN_REFRESH),
            "a day later it is checked again"
        );
        assert!(cache.needs_skin(
            &profile(Some("http://textures.minecraft.net/texture/def")),
            now
        ));

        let json = std::fs::read_to_string(
            tmp.path()
                .join("accounts")
                .join(format!("{}.json", stored.id)),
        )
        .unwrap();
        assert!(!json.to_lowercase().contains("token"));

        // Taking the skin off removes the head.
        cache.record(&profile(None), None, now).unwrap();
        assert!(cache.head(&stored.id).is_none());

        cache.forget(&stored.id);
        assert!(cache.load(&stored.id).is_none());
    }
}

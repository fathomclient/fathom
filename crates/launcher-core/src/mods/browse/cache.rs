//! Remembering what an index said, so the browser does not ask again.
//!
//! # Two caches, because they have different failure modes
//!
//! **In memory**, with a short life: search pages, project details and version
//! lists. These go stale — a project gets a new build, a search gets a new
//! result — so they expire in minutes and are bounded by count. Losing one
//! costs a request.
//!
//! **On disk**, with a long life: icons and screenshots. An icon is immutable
//! for practical purposes and expensive to fetch a second time, so it is kept
//! between runs under the launcher's own cache directory, bounded by total
//! bytes and swept when it grows past them.
//!
//! # What is never cached
//!
//! Anything about the user. There is no history of what was searched, no record
//! of which project was opened, and nothing keyed by profile. The search cache
//! is keyed by the query text and target, which is exactly what would be sent
//! to the provider anyway, and it dies with the process.
//!
//! # Why bounds are not optional
//!
//! Both caches are filled by responses from somebody else. Without a ceiling,
//! "browse for a while" is a memory leak an index controls and "scroll a
//! gallery" is a disk filler. The ceilings here are deliberately small enough
//! that the worst case is boring.

use std::collections::HashMap;
use std::hash::Hash;
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::time::{Duration, Instant};

use super::model::ModProject;
use super::provider::{BrowseError, SearchPage};
use crate::mods::browse::model::{ModFile, ProjectRef, Provider};
use crate::network::{descriptor_from_url, Feature, HttpClient, PerActionGrant, Purpose};
use crate::paths::DataPaths;
use crate::tasks::Cancel;

/// How long a page of search results stays usable.
///
/// Short: typing the same query twice in a minute should not cost two requests,
/// and a result that is two minutes stale is a result that has not changed.
pub const SEARCH_TTL: Duration = Duration::from_secs(180);

/// How long project details stay usable.
pub const PROJECT_TTL: Duration = Duration::from_secs(900);

/// How long a version list stays usable.
///
/// Shorter than the project: this is what an update check reads, and an update
/// that appeared ten minutes ago should be visible.
pub const FILES_TTL: Duration = Duration::from_secs(600);

/// How long a cached image is used before it is fetched again.
pub const IMAGE_TTL: Duration = Duration::from_secs(60 * 60 * 24 * 14);

/// The largest image this launcher will keep.
pub const MAX_IMAGE_BYTES: u64 = 4 * 1024 * 1024;

/// How much disk the image cache may use before the oldest are swept.
pub const MAX_IMAGE_CACHE_BYTES: u64 = 64 * 1024 * 1024;

const MAX_SEARCH_ENTRIES: usize = 64;
const MAX_PROJECT_ENTRIES: usize = 256;
const MAX_FILE_ENTRIES: usize = 256;

// ---------------------------------------------------------------------------
// A small expiring map
// ---------------------------------------------------------------------------

#[derive(Debug, Clone)]
struct Entry<V> {
    value: V,
    stored: Instant,
    /// Bumped on every read, so eviction drops what nobody is looking at
    /// rather than what happened to be stored first.
    touched: Instant,
}

/// A bounded map whose entries expire.
///
/// Not a general cache: no weights, no async refresh, no statistics. It holds a
/// few hundred small values for a few minutes, which is all the browser needs,
/// and being this small means the eviction rule is one obvious line.
#[derive(Debug)]
pub struct Expiring<K, V> {
    entries: HashMap<K, Entry<V>>,
    ttl: Duration,
    capacity: usize,
}

impl<K: Eq + Hash + Clone, V: Clone> Expiring<K, V> {
    pub fn new(ttl: Duration, capacity: usize) -> Self {
        Self {
            entries: HashMap::new(),
            ttl,
            capacity: capacity.max(1),
        }
    }

    /// The value, if it is there and still fresh. Expired entries are dropped
    /// on the way past rather than by a timer.
    pub fn get(&mut self, key: &K) -> Option<V> {
        let now = Instant::now();
        let ttl = self.ttl;
        match self.entries.get_mut(key) {
            Some(entry) if now.duration_since(entry.stored) < ttl => {
                entry.touched = now;
                Some(entry.value.clone())
            }
            Some(_) => {
                self.entries.remove(key);
                None
            }
            None => None,
        }
    }

    pub fn insert(&mut self, key: K, value: V) {
        let now = Instant::now();
        self.entries.insert(
            key,
            Entry {
                value,
                stored: now,
                touched: now,
            },
        );
        self.evict();
    }

    /// Forget everything about one key. Used after an install, where the
    /// version list the browser is holding has just become wrong.
    pub fn invalidate(&mut self, key: &K) {
        self.entries.remove(key);
    }

    pub fn clear(&mut self) {
        self.entries.clear();
    }

    pub fn len(&self) -> usize {
        self.entries.len()
    }

    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }

    /// Drop expired entries, then the least recently read, until it fits.
    fn evict(&mut self) {
        let now = Instant::now();
        let ttl = self.ttl;
        self.entries
            .retain(|_, entry| now.duration_since(entry.stored) < ttl);

        while self.entries.len() > self.capacity {
            let oldest = self
                .entries
                .iter()
                .min_by_key(|(_, entry)| entry.touched)
                .map(|(key, _)| key.clone());
            match oldest {
                Some(key) => {
                    self.entries.remove(&key);
                }
                None => break,
            }
        }
    }
}

// ---------------------------------------------------------------------------
// The catalog's cache
// ---------------------------------------------------------------------------

/// Everything the browser remembers between requests.
///
/// Shared by clones of the catalog: one process, one cache, so two screens
/// asking the same question ask the network once.
#[derive(Debug)]
pub struct CatalogCache {
    search: Mutex<Expiring<String, SearchPage>>,
    projects: Mutex<Expiring<String, ModProject>>,
    files: Mutex<Expiring<String, Vec<ModFile>>>,
    images: ImageCache,
}

impl CatalogCache {
    pub fn new(paths: DataPaths) -> Self {
        Self {
            search: Mutex::new(Expiring::new(SEARCH_TTL, MAX_SEARCH_ENTRIES)),
            projects: Mutex::new(Expiring::new(PROJECT_TTL, MAX_PROJECT_ENTRIES)),
            files: Mutex::new(Expiring::new(FILES_TTL, MAX_FILE_ENTRIES)),
            images: ImageCache::new(paths),
        }
    }

    pub fn images(&self) -> &ImageCache {
        &self.images
    }

    pub fn search_page(&self, provider: Provider, key: &str) -> Option<SearchPage> {
        let key = format!("{}|{key}", provider.id());
        self.search.lock().ok()?.get(&key)
    }

    pub fn store_search_page(&self, provider: Provider, key: &str, page: &SearchPage) {
        if let Ok(mut cache) = self.search.lock() {
            cache.insert(format!("{}|{key}", provider.id()), page.clone());
        }
    }

    pub fn project(&self, provider: Provider, id: &str) -> Option<ModProject> {
        let key = ProjectRef::new(provider, id).qualified();
        self.projects.lock().ok()?.get(&key)
    }

    pub fn store_project(&self, project: &ModProject) {
        if let Ok(mut cache) = self.projects.lock() {
            cache.insert(project.reference.qualified(), project.clone());
        }
    }

    pub fn files(&self, provider: Provider, id: &str, target_key: &str) -> Option<Vec<ModFile>> {
        let key = format!("{}|{id}|{target_key}", provider.id());
        self.files.lock().ok()?.get(&key)
    }

    pub fn store_files(&self, provider: Provider, id: &str, target_key: &str, files: &[ModFile]) {
        if let Ok(mut cache) = self.files.lock() {
            cache.insert(
                format!("{}|{id}|{target_key}", provider.id()),
                files.to_vec(),
            );
        }
    }

    /// Forget what is known about one project.
    ///
    /// Called after an install or an update: the version list the browser is
    /// holding says the newest build is the one it just installed, and the
    /// state shown beside it has changed.
    pub fn invalidate_project(&self, provider: Provider, id: &str) {
        let key = ProjectRef::new(provider, id).qualified();
        if let Ok(mut cache) = self.projects.lock() {
            cache.invalidate(&key);
        }
        if let Ok(mut cache) = self.files.lock() {
            let prefix = format!("{}|{id}|", provider.id());
            let keys: Vec<String> = cache
                .entries
                .keys()
                .filter(|existing| existing.starts_with(&prefix))
                .cloned()
                .collect();
            for key in keys {
                cache.invalidate(&key);
            }
        }
    }

    /// Drop everything held in memory. The disk image cache is untouched.
    pub fn clear(&self) {
        if let Ok(mut cache) = self.search.lock() {
            cache.clear();
        }
        if let Ok(mut cache) = self.projects.lock() {
            cache.clear();
        }
        if let Ok(mut cache) = self.files.lock() {
            cache.clear();
        }
    }

    /// How many entries are held, for diagnostics.
    pub fn sizes(&self) -> (usize, usize, usize) {
        (
            self.search.lock().map(|c| c.len()).unwrap_or_default(),
            self.projects.lock().map(|c| c.len()).unwrap_or_default(),
            self.files.lock().map(|c| c.len()).unwrap_or_default(),
        )
    }
}

// ---------------------------------------------------------------------------
// Images
// ---------------------------------------------------------------------------

/// Icons and screenshots, kept on disk between runs.
///
/// The file name is the SHA-1 of the URL, which does two things: it cannot
/// contain anything a remote name might (it is forty hex characters by
/// construction), and the same image asked for twice is the same file.
#[derive(Debug, Clone)]
pub struct ImageCache {
    paths: DataPaths,
}

impl ImageCache {
    pub fn new(paths: DataPaths) -> Self {
        Self { paths }
    }

    pub fn root(&self) -> PathBuf {
        self.paths.cache_dir().join("mod-images")
    }

    /// Where an image would live. Never derived from anything but the hash.
    pub fn path_for(&self, url: &str) -> PathBuf {
        self.root().join(format!("{}.img", url_key(url)))
    }

    /// The cached file, if it is present and not past [`IMAGE_TTL`].
    pub fn cached(&self, url: &str) -> Option<PathBuf> {
        let path = self.path_for(url);
        let metadata = std::fs::metadata(&path).ok()?;
        if !metadata.is_file() || metadata.len() == 0 {
            return None;
        }
        let age = metadata
            .modified()
            .ok()
            .and_then(|when| when.elapsed().ok())
            .unwrap_or_default();
        (age < IMAGE_TTL).then_some(path)
    }

    /// The cached file, fetching it first if it is not there.
    ///
    /// Blocking, like everything else in this module: it is called from a
    /// worker thread, never from a thread that draws.
    pub fn fetch(
        &self,
        url: &str,
        http: &HttpClient,
        grant: &PerActionGrant,
        cancel: &Cancel,
        provider: Provider,
    ) -> Result<PathBuf, BrowseError> {
        if let Some(hit) = self.cached(url) {
            return Ok(hit);
        }
        if cancel.is_cancelled() {
            return Err(BrowseError::Cancelled);
        }

        let request = descriptor_from_url(url, Purpose::ResolveModMetadata, Feature::ModBrowse)
            .map_err(|rejection| {
                BrowseError::Refused(format!("that image is not on an allowed host: {rejection}"))
            })?;

        let target = self.path_for(url);
        let parent = target.parent().unwrap_or_else(|| Path::new("."));
        std::fs::create_dir_all(parent).map_err(|error| BrowseError::Refused(error.to_string()))?;

        // Into memory first, with a ceiling, and only then to disk: an image is
        // small, and a partial file in an image cache is a broken icon that
        // survives restarts.
        let mut bytes: Vec<u8> = Vec::new();
        http.stream_cancellable(&request, Some(grant), MAX_IMAGE_BYTES, &mut bytes, &|| {
            !cancel.is_cancelled()
        })
        .map_err(|error| BrowseError::from_network(provider, &error))?;

        crate::fs_safety::atomic_write(&target, &bytes)
            .map_err(|error| BrowseError::Refused(error.to_string()))?;
        self.prune();
        Ok(target)
    }

    /// Keep the cache under [`MAX_IMAGE_CACHE_BYTES`], oldest first.
    ///
    /// Returns how many files were removed, which is what the recovery screen
    /// reports when somebody clears caches by hand.
    pub fn prune(&self) -> usize {
        let Ok(entries) = std::fs::read_dir(self.root()) else {
            return 0;
        };
        let mut files: Vec<(std::time::SystemTime, u64, PathBuf)> = Vec::new();
        let mut total: u64 = 0;
        for entry in entries.flatten() {
            let Ok(metadata) = entry.metadata() else {
                continue;
            };
            if !metadata.is_file() {
                continue;
            }
            let modified = metadata
                .modified()
                .unwrap_or(std::time::SystemTime::UNIX_EPOCH);
            total = total.saturating_add(metadata.len());
            files.push((modified, metadata.len(), entry.path()));
        }
        if total <= MAX_IMAGE_CACHE_BYTES {
            return 0;
        }
        files.sort_by_key(|(modified, _, _)| *modified);

        let mut removed = 0;
        for (_, size, path) in files {
            if total <= MAX_IMAGE_CACHE_BYTES {
                break;
            }
            if std::fs::remove_file(&path).is_ok() {
                total = total.saturating_sub(size);
                removed += 1;
            }
        }
        removed
    }

    /// Delete every cached image. For the recovery screen.
    pub fn clear(&self) -> usize {
        let Ok(entries) = std::fs::read_dir(self.root()) else {
            return 0;
        };
        let mut removed = 0;
        for entry in entries.flatten() {
            if entry.path().is_file() && std::fs::remove_file(entry.path()).is_ok() {
                removed += 1;
            }
        }
        removed
    }
}

/// Forty hex characters derived from a URL. Never any part of the URL itself.
fn url_key(url: &str) -> String {
    crate::install::download::sha1_hex(url.as_bytes())
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]
mod tests {
    use std::collections::BTreeSet;

    use super::*;

    #[test]
    fn an_entry_expires_and_is_forgotten_rather_than_returned_stale() {
        let mut cache: Expiring<String, u32> = Expiring::new(Duration::from_millis(30), 8);
        cache.insert("a".to_owned(), 1);
        assert_eq!(cache.get(&"a".to_owned()), Some(1));
        std::thread::sleep(Duration::from_millis(45));
        assert_eq!(cache.get(&"a".to_owned()), None);
        assert!(cache.is_empty(), "the expired entry was dropped, not kept");
    }

    #[test]
    fn the_cache_is_bounded_and_evicts_what_nobody_is_reading() {
        let mut cache: Expiring<String, u32> = Expiring::new(Duration::from_secs(60), 3);
        for index in 0..3 {
            cache.insert(format!("k{index}"), index);
        }
        // Read the first two, so the third is the least recently used.
        assert!(cache.get(&"k0".to_owned()).is_some());
        assert!(cache.get(&"k1".to_owned()).is_some());

        cache.insert("k3".to_owned(), 3);
        assert_eq!(cache.len(), 3, "capacity is a hard bound");
        assert!(cache.get(&"k2".to_owned()).is_none(), "the unread one went");
        assert!(cache.get(&"k0".to_owned()).is_some());
        assert!(cache.get(&"k3".to_owned()).is_some());
    }

    #[test]
    fn invalidating_a_project_drops_its_versions_too() {
        let tmp = tempfile::tempdir().unwrap();
        let cache = CatalogCache::new(DataPaths::rooted_at(tmp.path()));
        let project = ModProject {
            reference: super::super::model::ProjectRef::new(Provider::Modrinth, "abc"),
            slug: "abc".into(),
            name: "ABC".into(),
            summary: String::new(),
            description: None,
            authors: Vec::new(),
            icon: None,
            gallery: Vec::new(),
            downloads: 0,
            categories: Vec::new(),
            project_url: "https://modrinth.com/mod/abc".into(),
            source_url: None,
            issues_url: None,
            license: crate::mods::definition::License::Unknown,
            minecraft_versions: BTreeSet::default(),
            loaders: BTreeSet::default(),
            updated: None,
            distribution: super::super::model::Distribution::Allowed,
            detailed: true,
        };
        cache.store_project(&project);
        cache.store_files(Provider::Modrinth, "abc", "1.8.9|legacy", &[]);
        assert!(cache.project(Provider::Modrinth, "abc").is_some());
        assert!(cache
            .files(Provider::Modrinth, "abc", "1.8.9|legacy")
            .is_some());

        cache.invalidate_project(Provider::Modrinth, "abc");
        assert!(cache.project(Provider::Modrinth, "abc").is_none());
        assert!(
            cache
                .files(Provider::Modrinth, "abc", "1.8.9|legacy")
                .is_none(),
            "an install changes the version list as well as the project"
        );
    }

    #[test]
    fn an_image_path_is_derived_from_the_hash_and_never_from_the_url() {
        let tmp = tempfile::tempdir().unwrap();
        let images = ImageCache::new(DataPaths::rooted_at(tmp.path()));
        let hostile = "https://cdn.modrinth.com/../../../etc/passwd";
        let path = images.path_for(hostile);
        assert!(path.starts_with(images.root()));
        let name = path.file_name().unwrap().to_string_lossy().to_string();
        assert!(std::path::Path::new(&name)
            .extension()
            .is_some_and(|e| e == "img"));
        assert_eq!(name.len(), 44, "forty hex characters and an extension");
        assert!(name
            .trim_end_matches(".img")
            .chars()
            .all(|c| c.is_ascii_hexdigit()));
    }

    #[test]
    fn the_image_cache_sweeps_itself_back_under_its_ceiling() {
        let tmp = tempfile::tempdir().unwrap();
        let images = ImageCache::new(DataPaths::rooted_at(tmp.path()));
        std::fs::create_dir_all(images.root()).unwrap();

        // Two files over the ceiling between them.
        let big = vec![0u8; (MAX_IMAGE_CACHE_BYTES / 2) as usize + 1024];
        for index in 0..2 {
            std::fs::write(images.root().join(format!("{index}.img")), &big).unwrap();
            // Distinct modification times, so "oldest first" means something.
            std::thread::sleep(Duration::from_millis(20));
        }
        assert_eq!(images.prune(), 1, "one file removed to get back under");
        assert!(images.root().join("1.img").is_file(), "the newest is kept");
    }

    #[test]
    fn a_fresh_cache_has_nothing_in_it_and_no_files_on_disk() {
        let tmp = tempfile::tempdir().unwrap();
        let cache = CatalogCache::new(DataPaths::rooted_at(tmp.path()));
        assert_eq!(cache.sizes(), (0, 0, 0));
        assert!(cache
            .images()
            .cached("https://cdn.modrinth.com/x.png")
            .is_none());
    }
}

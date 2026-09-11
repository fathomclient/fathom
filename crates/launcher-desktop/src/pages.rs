//! Opening a page in the user's browser.
//!
//! # Why this takes an id and not a URL
//!
//! Because "open this URL" is a capability, and the WebView must not have it.
//! A command that accepted a string would hand whatever ends up in that string
//! -- a mod description, a catalogue entry, anything that arrived over a
//! network -- to the operating system's shell. A command that accepts an id can
//! only ever open one of the pages in the table below, all of them written
//! here, in this file, by us.
//!
//! This is the same reasoning that keeps the shell plugin out of the
//! dependency list. It is not that opening a browser is dangerous; it is that a
//! *general* way to open a browser is.
//!
//! # Why not a plugin
//!
//! `tauri-plugin-opener` would add exactly the general capability above to the
//! WebView's permission surface. Two dozen lines here keep the boundary at a
//! table of constants.

/// Every page the launcher will open, by id.
///
/// Adding one is adding a line here. There is no other way in.
const PAGES: [(&str, &str); 8] = [
    ("optifine", "https://optifine.net/downloads"),
    ("legacy-fabric", "https://legacyfabric.net/"),
    ("fabric-api", "https://github.com/Legacy-Fabric/fabric"),
    ("adoptium", "https://adoptium.net/"),
    // Creator credits for background footage recorded in their worlds. Settings
    // → Appearance links to these; `desktop/src/lib/credits.ts` names the ids and
    // ATTRIBUTION.md is the full record.
    (
        "credit-tideworn",
        "https://www.curseforge.com/minecraft/worlds/tideworn-8-sqkm-forgotten-flooded-city",
    ),
    (
        "credit-milkshake-mp3",
        "https://www.curseforge.com/members/milkshake_mp3/projects",
    ),
    (
        "credit-tokyo-inspired-city",
        "https://www.curseforge.com/minecraft/worlds/tokyo-inspired-city",
    ),
    (
        "credit-coincoffer",
        "https://www.curseforge.com/members/coincoffer/projects",
    ),
];

pub fn url_for(id: &str) -> Option<&'static str> {
    PAGES
        .iter()
        .find(|(candidate, _)| *candidate == id)
        .map(|(_, url)| *url)
}

/// Open one of the known pages.
pub fn open(id: &str) -> Result<(), String> {
    let url = url_for(id).ok_or_else(|| format!("unknown page: {id}"))?;
    launch_browser(url)
}

/// Open a mod's page on the repository the launcher browses.
///
/// # Why this is not simply another entry in the table
///
/// Because the set is unbounded: any project somebody finds in the Browse tab
/// has a page, and none of them can be written down in advance. That is exactly
/// the situation the id table exists to avoid, so the rule has to come from
/// somewhere else.
///
/// It comes from the shape of the slug. The URL is **built here** from a host
/// this file spells out and a slug that has been checked, character by
/// character, against the small alphabet Modrinth uses. Nothing from a response
/// body is concatenated in: a slug containing a slash, a dot, a colon, a space
/// or a non-ASCII byte is refused, so the result cannot be steered to another
/// host, another scheme, or a path with a query on it.
///
/// The capability the WebView gains is therefore "open a Modrinth project page
/// by slug", not "open a URL" — which is the same boundary the table draws,
/// expressed as a rule instead of a list.
pub fn open_project(slug: &str) -> Result<(), String> {
    let url = project_url(slug).ok_or_else(|| format!("not a project slug: {slug:?}"))?;
    launch_browser(&url)
}

/// The bounded builder behind [`open_project`], separated so it can be tested
/// without opening anything.
pub fn project_url(slug: &str) -> Option<String> {
    // Modrinth slugs are lowercase alphanumerics with hyphens and underscores,
    // between 3 and 64 characters. Anything else is not a slug, whatever it is.
    if slug.len() < 3 || slug.len() > 64 {
        return None;
    }
    if !slug
        .chars()
        .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == '-' || c == '_')
    {
        return None;
    }
    // A leading or trailing hyphen is not a slug either, and refusing it keeps
    // the accepted set to things that can actually exist.
    if slug.starts_with('-') || slug.ends_with('-') {
        return None;
    }
    Some(format!("https://modrinth.com/mod/{slug}"))
}

#[cfg(target_os = "windows")]
fn launch_browser(url: &str) -> Result<(), String> {
    use std::os::windows::process::CommandExt;
    // `rundll32 url.dll,FileProtocolHandler` rather than `cmd /c start`: no
    // shell is involved, so there is no quoting or metacharacter question to
    // get right. The URL is a compile-time constant either way, but the
    // property worth having is that it *could not* matter.
    const CREATE_NO_WINDOW: u32 = 0x0800_0000;
    std::process::Command::new("rundll32.exe")
        .arg("url.dll,FileProtocolHandler")
        .arg(url)
        .creation_flags(CREATE_NO_WINDOW)
        .spawn()
        .map(|_| ())
        .map_err(|error| format!("could not open the page: {error}"))
}

#[cfg(target_os = "macos")]
fn launch_browser(url: &str) -> Result<(), String> {
    std::process::Command::new("open")
        .arg(url)
        .spawn()
        .map(|_| ())
        .map_err(|error| format!("could not open the page: {error}"))
}

#[cfg(all(unix, not(target_os = "macos")))]
fn launch_browser(url: &str) -> Result<(), String> {
    std::process::Command::new("xdg-open")
        .arg(url)
        .spawn()
        .map(|_| ())
        .map_err(|error| format!("could not open the page: {error}"))
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The property the whole file exists for: nothing outside the table can be
    /// opened, however it is spelled.
    #[test]
    fn only_the_listed_pages_can_be_opened() {
        assert_eq!(url_for("optifine"), Some("https://optifine.net/downloads"));
        for hostile in [
            "https://example.com",
            "file:///C:/Windows/System32/calc.exe",
            "",
            "OptiFine",
            "optifine ",
            "../optifine",
        ] {
            assert_eq!(url_for(hostile), None, "{hostile} should not resolve");
        }
    }

    #[test]
    fn every_page_is_https_and_unique() {
        let mut ids: Vec<&str> = PAGES.iter().map(|(id, _)| *id).collect();
        let before = ids.len();
        ids.sort_unstable();
        ids.dedup();
        assert_eq!(ids.len(), before, "page ids must be unique");

        for (id, url) in PAGES {
            assert!(url.starts_with("https://"), "{id} is not https");
        }
    }

    /// The background credits are part of the terms the footage is used under,
    /// so losing one of these ids would silently break a link in Settings.
    #[test]
    fn every_background_credit_resolves_to_the_creators_curseforge_page() {
        for id in [
            "credit-tideworn",
            "credit-milkshake-mp3",
            "credit-tokyo-inspired-city",
            "credit-coincoffer",
        ] {
            let url = url_for(id).unwrap_or_else(|| panic!("{id} must resolve"));
            assert!(
                url.starts_with("https://www.curseforge.com/"),
                "{id} should point at CurseForge, not {url}"
            );
        }
    }

    /// The OptiFine page is the one the setup flow sends people to, and it has
    /// to be the author's own downloads page rather than any mirror.
    #[test]
    fn optifine_points_at_the_authors_own_downloads_page() {
        assert_eq!(url_for("optifine"), Some("https://optifine.net/downloads"));
    }

    /// A project page is built from a checked slug, so the unbounded set of
    /// projects does not become an unbounded set of URLs.
    #[test]
    fn a_project_page_is_built_only_from_something_shaped_like_a_slug() {
        assert_eq!(
            project_url("legacy-fabric-api").as_deref(),
            Some("https://modrinth.com/mod/legacy-fabric-api")
        );
        assert_eq!(
            project_url("ksyxis").as_deref(),
            Some("https://modrinth.com/mod/ksyxis")
        );

        for hostile in [
            "",
            "ab",
            "../../etc/passwd",
            "a/b",
            "a.b",
            "a:b",
            "a b",
            "UPPER",
            "https://evil.example",
            "slug?x=1",
            "slug#fragment",
            "-leading",
            "trailing-",
            "sl\u{00fc}g",
            &"x".repeat(65),
        ] {
            assert_eq!(project_url(hostile), None, "{hostile:?} should be refused");
        }
    }

    /// Whatever a slug contains, the host is the one written in this file.
    #[test]
    fn a_built_project_url_always_names_the_repository() {
        for slug in ["a-b", "abc", "mod_name", "x1y2z3"] {
            let url = project_url(slug).expect("a valid slug");
            assert!(
                url.starts_with("https://modrinth.com/mod/"),
                "{url} escaped the host"
            );
        }
    }
}

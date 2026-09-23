//! Compatibility command for Fathom's canonical desktop launcher.
//!
//! `cargo run -p launcher-ui` used to compile a separate iced application and
//! could therefore display an obsolete launcher. The package name remains so
//! existing developer commands keep working, but it now starts the exact same
//! Tauri/Svelte application as the production executable.

#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

fn main() {
    launcher_desktop::run();
}

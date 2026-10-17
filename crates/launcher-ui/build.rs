fn main() {
    // The compatibility executable still needs Tauri's Windows application
    // manifest (notably Common Controls v6). Without it Windows loads an older
    // comctl32 and exits before Rust's main with STATUS_ENTRYPOINT_NOT_FOUND.
    tauri_build::build();
}

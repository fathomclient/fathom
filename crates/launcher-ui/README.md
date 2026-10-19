# launcher-ui

A thin wrapper kept so `cargo run -p launcher-ui` still works. It just calls
`launcher_desktop::run()` with the built frontend from `desktop/dist`, so it
behaves exactly like the real launcher. The old UI that used to live here is in
the git history.

For frontend work with hot reload, use `tools/dev.ps1` instead.

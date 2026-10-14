# launcher-ui compatibility package

`launcher-ui` is intentionally not a second launcher implementation. It keeps
the established development command working:

```powershell
cargo run -p launcher-ui
```

That command calls `launcher_desktop::run()` and embeds the current
`desktop/dist` bundle, exactly like the production launcher. The iced-era
sources and assets that used to live here are in the git history.

For hot frontend development, use `tools/dev.ps1`; it runs the same desktop
shell against Vite's development server.

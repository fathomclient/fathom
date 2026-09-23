# launcher-ui compatibility package

`launcher-ui` is intentionally not a second launcher implementation. It keeps
the established development command working:

```powershell
cargo run -p launcher-ui
```

That command calls `launcher_desktop::run()` and embeds the current
`desktop/dist` bundle, exactly like the production launcher. The remaining
iced-era source and asset files in this directory are quarantined historical
material: Cargo does not reference or compile them, and release packaging does
not copy them.

For hot frontend development, use `tools/dev.ps1`; it runs the same desktop
shell against Vite's development server.

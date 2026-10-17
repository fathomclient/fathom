# Windows distribution

## Release command

Run from a release-builder checkout:

```powershell
pwsh tools/build-installer.ps1
```

The builder needs Rust, Node.js/npm, and Gradle. The installed client needs none
of them. The script uses `package-lock.json`, builds and tests the first-party
Minecraft client, builds the current Svelte frontend, stages only the loadable
client jar, and invokes the pinned Tauri CLI to create an NSIS installer.

The distributable artifact is written to `dist/installer`. A repository ZIP or
the portable developer directory is not a release artifact.

## Installed layout

The installer is per-user and does not request administrator rights:

| Data | Location | Lifetime |
| --- | --- | --- |
| Launcher executable, embedded frontend, client jar, uninstaller | `%LOCALAPPDATA%\Fathom Client` | Replaced by update; removed by uninstall |
| Settings, account metadata, profiles and worlds | `%LOCALAPPDATA%\Fathom\data` | Preserved |
| Managed Java | `%LOCALAPPDATA%\Fathom\data\java` | Preserved |
| Minecraft libraries/assets/versions | `%LOCALAPPDATA%\Fathom\data\shared` | Preserved; verified and reacquirable |
| Caches and temporary files | `%LOCALAPPDATA%\Fathom\data\cache`, `tmp` | Disposable |
| Launcher and game logs | `%LOCALAPPDATA%\Fathom\data\logs`, profile log directories | Rotatable |
| Secrets | Windows Credential Manager | User-specific |

The product name deliberately differs from the data root. Tauri's current-user
NSIS template removes its install directory recursively; installing into
`%LOCALAPPDATA%\Fathom` would therefore make uninstall capable of deleting the
managed instance and worlds.

## WebView2 startup

Release builds use Tauri's embedded asset protocol. The frontend is compiled
into the launcher executable; there is no localhost server, port, child server
process, readiness race, or firewall exception. The build fails if
`desktop/dist/index.html` is missing. The installer embeds Microsoft's WebView2
bootstrapper so a clean supported Windows installation can acquire the runtime.

Frontend hot reload is intentionally separate. `tools/dev.ps1` obtains an
ephemeral loopback port, binds Vite to `127.0.0.1` with strict port ownership,
and uses the Tauri CLI readiness wait before creating the development WebView.

## Updates and uninstall

The stable `net.fathom.launcher` identifier and disabled downgrades allow a
newer installer to replace application binaries without touching durable data.
NSIS registers the normal Windows uninstall entry and creates the Fathom Start
Menu shortcut. User data remains outside the installer-owned directory.

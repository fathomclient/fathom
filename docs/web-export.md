# Website data export

The Fathom website lives in its own repository. Everything it says about the
client that the client repository can prove — versions, platform, sign-in,
modules, optimizations, benchmark results, backgrounds, product media — comes
from one generated bundle, never from values typed into the website.

## Generate

```
cd minecraft/fathom-optimizations
gradle exportWebData
```

(`gradle` is the repository's Gradle, as `tools/build-all.ps1` finds it:
`.tmp-gradle-8.10.2/gradle-8.10.2/bin/gradle`.)

It writes `web-export/` at the repository root, deleting what was there first.
The same repository state always produces byte-identical files: no timestamps,
no machine names, fixed key order. `web-export/` is git-ignored; it is an
output, not a source.

The export **fails, and leaves no bundle**, if any output looks private:
Windows paths, home directories, `AppData`, this machine's user name, e-mail
addresses, `localhost`/loopback addresses, addresses with ports, JWTs, UUIDs,
long hex identifiers, `-dirty` build stamps, credential words (`access_token`,
`client_id`, `xuid`, `password` …) or private-sounding JSON keys. The scanner
tests itself against known-bad samples on every run.

## Output

| File | Contents | Source of truth |
|---|---|---|
| `index.json` | Every file in the bundle with its size and SHA-256 | — |
| `product.json` | Name, tagline, launcher and client versions, release status, Minecraft version and loader, platform, sign-in type, working first-party modules, counts, and `brand`: the app icon, mark, wordmark and lockups (files under `brand/`) with the inks they are drawn in, so the site's accents can be the icon's | `tauri.conf.json`, `gradle.properties`, `launcher-core` (`PRODUCT_NAME`, the Microsoft auth provider), the module catalogue, git tags, `assets/brand/` and `system.brand` in the theme manifest |
| `performance.json` | Benchmark runs: date, build version, machine, render settings, method, and per scene and configuration the median, mean, p95, p99 frame time, FPS equivalents and the paired comparison with the default configuration | `benchmarks/reports/` |
| `backgrounds.json` + `backgrounds/` | The user-selectable backgrounds: id, name, type, default, status, video, still, preview thumbnail, size, accent colour, credit | `assets/backgrounds/manifest.json`, files from `desktop/public/backgrounds/` |
| `optimizations.json` | Optimizations that run in the game: id, name, category, description, enabled by default, where it is set, output-identical or a trade-off, evidence level, whether it stays active with OptiFine | the optimization registry (`assets/optimizations/registry.json`, regenerated first) |
| `media-manifest.json` + `media/` | Product media slots — launcher, RShift hub, module settings, appearance picker, Gameplay Glass, gameplay — each `available` with a file or `unavailable` | `assets/web/media.json` |

Every JSON file carries `"schema": 1`; it changes only for a change an older
website cannot read.

### What is deliberately left out

- **Benchmarks:** any scene or configuration with fewer than 1000 frames, any
  rejected frame beyond 1%, unfocused frames beyond 5%, or a report without its
  build, GPU, display and video lines. Instrumentation configurations
  (`probes-off`, `drain`). The **sky-only scene, always** — and `reload` and
  `live`, whose numbers are not frame rates of a described scene. Commit
  hashes. Every left-out item is printed when the export runs.
- **Optimizations** that do not run in the game (planned, core-only).
- **Modules** that are unavailable or experimental, and any not owned by Fathom.
- **Backgrounds** not in the manifest's user-selectable set.
- **Media** that is not an approved file in this repository. Nothing is
  generated or mocked up.

### Scenarios

Each result has `category` and `representative`:

| `category` | `representative` | Scenes |
|---|---|---|
| `singleplayer` | true | `sp`, `horizon` |
| `multiplayer` | true | `mp-empty`, `mp-few`, `lobby`, `combat` |
| `multiplayer-stress` | false | `combat-particles`, `nametags`, `hud-heavy`, `chunks` |
| `synthetic` | false | `entities`, `particles`, `translucent` |
| `camera-study` | false | `down45`, `down90` |

`representative: false` means a stress test or a study of one cost: do not
present its frame rate as typical. Multiplayer scenes ran against a local test
server with scripted players, and their descriptions say so.

## Feeding it

- **A benchmark:** copy the report pair (`fathom-view-*.txt/.csv` or
  `fathom-mp-*.txt/.csv`) from the game directory's `logs/` into
  `benchmarks/reports/`, unedited, add a row to that folder's README, run the
  export. A new scene id needs an entry in `WebExport.SCENARIOS` before it is
  exported at all.
- **Media:** put the real capture in the repository and set its slot's `source`
  in `assets/web/media.json` to the file's repository path. Approving media is
  that commit.
- **Everything else** follows the code: change the registry, the manifest or
  the module catalogue and the next export reflects it.

## Consuming it in the website repository

Treat the bundle as a versioned data drop:

1. Run the export in a checkout of the client repository at the commit being
   published (locally or in CI).
2. Copy `web-export/` into the website repository, for example as
   `data/fathom/`, replacing the previous copy wholesale. Commit it there, or
   publish it as a CI artifact the website build downloads.
3. In the website build, read `index.json`, check `schema` is a version it
   supports, and optionally verify each file's SHA-256 before using it.
4. Read values only from the JSON. Never patch a value by hand; if it is wrong,
   it is wrong in the client repository, and is fixed there.

```ts
import product from "./data/fathom/product.json";
import performance from "./data/fathom/performance.json";

if (product.schema !== 1 || performance.schema !== 1) throw new Error("unsupported Fathom data");

// Headline numbers: representative scenes, default configuration, newest run first.
const headline = performance.runs
  .slice().reverse()
  .flatMap(run => run.results.map(result => ({ run, result })))
  .filter(({ result }) => result.representative && result.configuration.id === "default");
```

Present frame times next to FPS, show the scene description and the machine with
every number, and never pair a stress-test result with the word "typical".

# Start here

**Double-click `Fathom.exe` in this folder.** That is the launcher, built from this
working tree, and it launches Minecraft with every change in it.

If `Fathom.exe` is not there, or you have changed code since it was built:

```powershell
powershell -ExecutionPolicy Bypass -File tools\build-all.ps1
```

That builds the Minecraft module, stages it where the launcher reads it, builds the
launcher, and puts `Fathom.exe` back in this folder. It takes a few minutes.

---

## Getting the most frames

### 1. The frame limit — check this first

Minecraft 1.8.9 ships with `maxFps:120` and VSync **on**, and Fathom used to inherit
both. That alone holds the client at ~120 fps no matter how fast it could run:
measured on this machine, the same scene ran **120 fps capped and 1746 fps uncapped**.

- **New profiles** are now created uncapped. Nothing to do.
- **Profiles you already had** keep their settings, because `options.txt` is yours.
  Open **Performance** in the launcher — if it is capped, there is a notice at the top
  with a **Remove the limit** button. It changes those two settings and nothing else.

### 2. OptiFine

OptiFine is not a Fabric mod and cannot simply be dropped into a mods folder. Fathom
runs it through **Legacy OptiFabric**, an MPL-2.0 compatibility layer that patches
OptiFine into the game at load time.

1. Download OptiFine 1.8.9 yourself from <https://optifine.net/downloads>. Fathom will
   never download it for you — its licence does not permit redistribution.
2. In the launcher, go to **Add OptiFine** and drop the jar in. Fathom reads the jar's
   own bytecode to confirm what it is and which Minecraft version it is for, so a
   renamed file still works and a file that merely *looks* like OptiFine is refused.
3. The screen will say OptiFabric is also needed. Press **Install OptiFabric** on the
   profile you want, then **Add to profile**.
4. Play.

If OptiFine is present without OptiFabric, the launcher **refuses to start** and says
which piece is missing, rather than letting Minecraft die inside class transformation.

Fathom stands its own overlapping renderer patches down when OptiFine is present —
they target methods OptiFine replaces — and says so in the log.

### 3. Everything else

Fathom's own optimizations are on the **Performance** page. Fathom is fully usable
without OptiFine; nothing here requires it.

---

## Changing the size of Fathom's interface

In game, press **Right Shift** to open the Hub, then **Quick** → **Fathom UI scale**:

```
Auto   75%   85%   100%   115%   125%
```

**Auto** derives the scale from your framebuffer, so the interface is the same physical
size on a 1080p laptop and a 1440p monitor. The percentages multiply that.

This is separate from Minecraft's own GUI Scale on purpose — playing on Large should not
give you a Hub with 40-pixel type — and it applies to **every** Fathom surface: the Hub,
the custom main menu, the HUD widgets and the HUD editor.

---

## Measuring, rather than guessing

Two harnesses, and they answer different questions.

**Fathom's own switches**, alternated inside one session in ABBA order:

```powershell
.tmp-gradle-8.10.2\gradle-8.10.2\bin\gradle runClient -PperfBench=ab -PperfAb=<id1>,<id2>
```

**OptiFine on against off.** OptiFine is patched in at class-load, so it cannot be
toggled mid-session and has to be interleaved at the level of runs:

```powershell
powershell -File tools\bench-ab.ps1 -OptiFine <path to your jar> -OptiFabric <path> -Cycles 2
python tools\bench-ab-summary.py minecraft\fathom-optimizations\run-ab\fathom-bench ab
```

The summary reports each arm's median and, next to it, the spread between that arm's own
runs. **If the two arms differ by less than the runs within an arm differ from each
other, the comparison has not measured anything** and the summary says so.

A plain measured run of the current build:

```powershell
.tmp-gradle-8.10.2\gradle-8.10.2\bin\gradle runClient -PperfBench=world -PclientWidth=1920 -PclientHeight=1080
```

It waits for the world to stop generating before it measures anything, and prints
`world settled after N s` when it starts. **A run whose log does not contain that line
did not settle, and its first scenario should be discarded** — see
`docs/performance/benchmark-methodology.md` §3.0.

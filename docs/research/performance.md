# Minecraft 1.8.9 Performance Research

Status: **research / pre-implementation**.
Target: Minecraft Java Edition **1.8.9**, `jre-legacy` (Java 8), LWJGL 2.9.4.
Date of research: 2026-08-23.

Every claim about *what* the optimization projects do is sourced from their public documentation,
issue trackers, or licence-permitted source. Where a claim is my analysis rather than a cited fact,
it is marked **[analysis]**. Where something is genuinely uncertain it is marked **[unverified]** and
listed again in the open-questions section.

> **Licensing note up front:** reading these projects tells us *which problems matter*. It does not
> give us the right to copy their code. See `mod-policies.md`. Every optimization we implement must
> be written from the problem description, not from someone else's diff.

---

## 1. Why 1.8.9 is slow: the short version

1.8 was the release where Mojang rewrote the renderer, and it regressed badly relative to 1.7.10.
The performance story is not one big problem; it is roughly five:

1. **Draw-call explosion in the 2D/immediate-mode paths.** Font rendering, GUI, HUD, particles,
   item models and tile entities all issue far more OpenGL commands than they need to. On a
   text-and-hologram-heavy server (Hypixel) this dominates the frame.
2. **No entity occlusion culling.** 1.8 culls entities by frustum and distance only. Server lobbies
   with hundreds of armor stands render every one of them through a wall.
3. **Chunk rebuild storms.** The 1.8 renderer rebuilds 16x16x16 sections on a small worker pool;
   joining a world or crossing a chunk boundary floods that queue and stalls the render thread.
4. **Allocation pressure feeding a badly-tuned GC.** 1.8 allocates aggressively in tick and render
   hot paths, and the community-standard fix ("give it 8-16 GB") makes the stalls *worse*, not
   better.
5. **LWJGL 2 input and frame pacing.** Frame limiting, VSync interaction, fullscreen mode switching
   and mouse handling are all worse than they need to be, and one specific vanilla bug (MC-67665)
   makes the game feel like it has input lag when it does not.

The rest of this document goes through each area.

---

## 2. Rendering

### 2.1 Font rendering -- the single biggest 2D win

`FontRenderer` in 1.8.9 renders text by, for each glyph, beginning a tessellator draw, emitting a
quad, and calling `Tessellator.draw()`. **A draw call per character.** A Hypixel scoreboard, tab
list, chat backlog, and a screenful of nametags can be several thousand characters per frame, which
means several thousand GL draw calls purely for text.

On top of that, `getStringWidth` and the formatting-code parser re-walk every string every frame,
and text is re-laid-out from scratch even when it has not changed.

Both PolyPatcher features here -- "Optimized Font Renderer (use modern rendering techniques)" and
"Cache Font Data (cache font data, allowing for it to be reused multiple times before needing
recalculation)" -- attack exactly this. Their own demo footage of the two together is linked from
their README.

**What we should do [analysis]:** batch all glyphs sharing the font atlas texture into a single
buffer and issue one draw per text-run (or per frame, if we defer flushing), and memoize
width/layout per (string, style) with an invalidation hook on resource reload. This is a pure win:
identical output, far fewer GL calls.

**Related, cheaper:** "Disable Text Shadow" removes a full second pass over every glyph. That is a
*visual* change, so it is OPTIONAL, not default.

### 2.2 GUI and HUD rendering

`GuiIngame.renderGameOverlay` recomputes and re-emits the entire HUD every frame -- hotbar,
health/armor/hunger icons, experience bar, scoreboard, boss bar, tab list -- with each element
typically its own `drawTexturedModalRect` / `drawRect` and therefore its own draw call.

Most of it does not change between frames.

PolyPatcher offers "HUD Caching" (render the HUD to a texture and reuse it for N frames) but files it
under **Experimental** and warns that animated elements become choppy. That classification is
correct and we should copy the *judgement*, not the code: caching whole-HUD frames trades
correctness for throughput.

**What we should do [analysis]:** the safe version is draw-call batching -- collect all HUD quads
sharing a texture into one buffer per frame. Same output, same responsiveness, far fewer calls.
Whole-frame HUD caching stays OPTIONAL/EXPERIMENTAL behind a toggle.

### 2.3 Chunk rendering

1.8 replaced 1.7's `RenderList`/display-list approach with `RenderChunk` sections driven by
`ChunkRenderDispatcher` and a small pool of `ChunkRenderWorker` threads, plus a VBO path selectable
in options. Community reports from the 1.8/1.9 era are consistent that the transition was a
regression for many users: chunks *load* faster with VBOs, but overall frame performance was
sometimes worse than 1.7's "Advanced OpenGL," and the recommended workaround was frequently to turn
VBOs *off*.

1.8 also replaced 1.7's hardware occlusion queries ("Advanced OpenGL") with a **software** occlusion
culling scheme computed per section during rebuild. It is hardware-independent and better on
non-NVIDIA GPUs, but it costs CPU during every rebuild, and it has known false negatives -- chunks
that should be visible are culled and appear as holes in the world (MC-63020, MC-70850). PolyPatcher
ships a "Culling Fix" for exactly these and honestly notes it *"can negatively impact performance."*
That tradeoff -- correctness versus speed -- must be surfaced to the user, not silently chosen.

The other chunk problem is **rebuild storms**. When you join a server, teleport, or cross into new
terrain, hundreds of sections are queued at once. The render thread stalls waiting on the dispatcher,
producing the classic 1.8 join-stutter. PolyPatcher's response is a blunt "Limit Chunk Updates /
Chunk Update Limit" throttle, which smooths the stutter at the cost of chunks appearing later.

**What we should do [analysis]:**
- Prioritise the rebuild queue by distance and view direction so what you are looking at converges
  first, instead of throttling globally. Better result, more work.
- Avoid redundant rebuilds: 1.8 marks whole sections dirty on block updates that cannot change the
  mesh.
- Size the worker pool sensibly for the actual machine rather than 1.8's conservative default.
- Expose the throttle as an OPTIONAL user control, off by default.

### 2.4 Entity rendering

1.8 culls entities by **frustum and render distance only**. There is no occlusion test. On a Hypixel
lobby, every armor stand behind every wall still goes through `RenderManager`, model setup, texture
binds, and matrix pushes.

This is why "Entity Culling" is the marquee feature of Patcher/PolyPatcher, and why their feature
list is so granular: an occlusion check has *semantic* consequences, so they carve out exceptions
for ender dragons, withers, player nametags, entity nametags, armor stand nametags, and armor stands
with special rules. That exception list is itself the research finding: **naive occlusion culling
breaks things, and the breakages are specific and known.**

They also expose per-category render distances (hostile / passive / player / tile entity / global)
and a long list of "just do not draw this" toggles (armor stands, item frames, skulls, grounded
arrows, enchantment books, falling blocks, end portals, enchantment glint, semitransparent players).

Note the important distinction:

- **Occlusion culling** = same visual result, less work. **SAFE-ish** (with the exception list).
- **"Disable armor stands"** = different visual result, and on many servers armor stands *are* the
  NPC nametags and holograms, so this changes what information you can see. **OPTIONAL at most**,
  and arguably a competitive-fairness question we should think about before shipping.

Two more entity-side costs worth naming:

- **Enchantment glint** (`RenderItem.renderEffect`) renders the item model two extra times with
  animated texture-matrix passes. Three times the geometry for one glowing sword. Disabling it is
  visually obvious, so OPTIONAL.
- **Batch model rendering** -- PolyPatcher's "Render models in a single draw call," on by default.
  Per-quad submission for entity and item models is pure overhead. **[analysis]** This is a safe,
  output-identical win and belongs in our default set.

### 2.5 Tile entities

`TileEntityRendererDispatcher` renders each tile entity individually: matrix push, texture bind,
immediate-mode geometry, matrix pop. No batching, no instancing.

PolyPatcher's 1.10.0 changelog is unusually informative here and names three separate defects:

- special tile entities were being rendered **twice per frame** (beacons cited);
- tile entities were unloading too slowly;
- adding normals to vertex formats was unnecessarily expensive.

**[analysis]** The double-render is a straightforward bug: 1.8 walks both the per-section tile-entity
list and the separate "destroy progress / special" list without deduplicating. Fixing it is
output-identical and free. Batching tile entities that share a texture is the larger win.

They also add a "Tile Entity Render Distance" cut-off, which is a visual change and therefore
OPTIONAL.

### 2.6 Particles

`EffectRenderer.renderParticles` does batch by texture layer, so 1.8 is not catastrophic here. The
per-particle cost is the problem: each particle performs a **world lighting lookup every frame** to
compute its brightness.

Patcher's historic "Static Particle Color -- disable particle lighting checks each frame" targeted
exactly this and was **on by default**. (In current PolyPatcher this moved out to the separate
OverflowParticles mod, along with the particle-count limiter and block-breaking-particle removal.)

**[analysis]** Caching a particle's brightness for its lifetime, or refreshing it every N ticks
rather than every frame, is a good OPTIONAL default -- the visual difference is a particle not
re-lighting as it drifts across a light boundary, which is essentially invisible in motion. A hard
particle cap is a bigger visual change and should stay user-controlled.

### 2.7 Transparency, texture and model handling

- **Translucency sorting.** 1.8 re-sorts translucent geometry per section when the camera moves
  (`resortTransparency`). It is correct but costly in water-heavy scenes. Not a good target for us;
  breaking it produces visible artifacts.
- **Texture atlases.** 1.8 stitches block/item textures into atlases with 4 mipmap levels by default.
  Mipmap level is already a user option; there is nothing clever to do that OptiFine has not.
- **Model memory.** Each `BakedQuad` in 1.8+ holds a raw `int[]` vertex array. In heavily modded
  packs this dominates heap. This is precisely FoamFix's core contribution -- deduplicating and
  compacting those structures. Its impact on **vanilla 1.8.9 is much smaller than on modded 1.12**,
  because vanilla simply has far fewer models. **[analysis]** We should be honest with users about
  this rather than shipping "FoamFix-style optimizations" as a headline for a vanilla PvP client.

### 2.8 GL state

1.8 introduced `GlStateManager`, which caches GL state and avoids redundant state changes -- so the
worst of the 1.7-era state thrashing is already handled by vanilla. The residual problem is that
plenty of vanilla and mod code still calls raw `GL11.*` directly, desynchronising the cache and
forcing conservative re-sets.

**[analysis]** Auditing our own code to route everything through `GlStateManager` is table stakes.
Trying to police *other* mods' raw GL calls is not worth it.

---

## 3. CPU

### 3.1 Client tick workload

The client ticks at 20 Hz. Notable costs inside `Minecraft.runTick`:

- **`WorldClient.doVoidFogParticles`** performs ~1000 randomised block samples around the player
  every tick, calling `Block.randomDisplayTick` on each, to spawn ambient particles. This is a
  meaningful, constant CPU cost for cosmetic effects. **[analysis]** Reducing the sample radius or
  rate is an easy OPTIONAL win; the visible effect is fewer ambient particles.
- **Entity updates.** `WorldClient.updateEntities` iterates all loaded entities. On a lobby with
  hundreds of armor stands this is significant even though armor stands do very little.
- **Collision.** `World.getCollidingBoundingBoxes` allocates a fresh `ArrayList` and fresh
  `AxisAlignedBB` objects per entity per tick. This is one of the largest allocation sources in the
  game.
- **Lighting.** 1.8's `World.checkLightFor` propagation is slow and is a large part of chunk-load
  cost. (This is the problem Phosphor solves on later versions; there is no 1.8.9 equivalent.)

### 3.2 Allocation and collections

1.8 leans heavily on short-lived objects in hot paths:

- `BlockPos` allocated per lookup rather than reusing `MutableBlockPos`.
- `Vec3` allocated throughout ray tracing and movement.
- `AxisAlignedBB` allocated per collision test (1.8's AABBs are immutable, so every `expand`,
  `offset`, or `addCoord` produces a new object).
- Autoboxing and iterator allocation in per-tick loops.

**[analysis]** These matter less for raw throughput than for **GC pressure**, which is what actually
produces the stutter users complain about. Reducing allocation in the top few call sites is worth
more than micro-optimising any of them.

### 3.3 Math

1.8's `MathHelper.sin`/`cos` use a precomputed **65536-entry float lookup table** (256 KB).

BetterFps's entire premise is that this can be beaten, and it ships **seven** alternative
algorithms (Riven's, Full, Half, LibGDX, Taylor, Java Math, vanilla), with Riven/Full/Half generally
fastest -- while explicitly noting that Taylor and Java Math *may perform worse than vanilla*, and
that "each one of them may or may not help you, it really depends on the hardware." That is why the
mod ships a **benchmark** and auto-selects, rather than hardcoding a winner.

**[analysis] This is the most over-rated optimization in the 1.8.9 space.** A 256 KB table does not
fit in L1 or L2 alongside the working set, so every lookup risks a cache miss, while modern
out-of-order CPUs plus a warmed-up JIT compute `sin` fast enough that the table is often a net loss.
But the reverse is also true on some hardware -- which is exactly what BetterFps's per-machine
benchmark is telling us. The honest conclusion: **the effect is real but small, hardware-dependent,
and unsigned.** It belongs in EXPERIMENTAL with an opt-in benchmark, never in a default preset.

### 3.4 Reflection and startup cost

Forge's mod discovery walks **every class in every mod jar** looking for the `@Mod` annotation.
PolyPatcher's "Entrypoint Caching" caches the result, notes it *"requires two restarts to take full
effect,"* and files it under **Experimental**.

**[analysis]** This is a Forge-specific problem. Legacy Fabric declares entrypoints in
`fabric.mod.json` and does no annotation scan, so choosing Legacy Fabric (see
`architecture-options.md`) makes the problem disappear rather than needing a cache. That is a real
argument for the loader choice, not just a licensing one.

### 3.5 Forge overhead more generally

Forge 1.8.9 adds event-bus dispatch to a large number of vanilla hot paths (rendering, ticks, block
access), plus its own capability/registry indirection. PolyPatcher additionally ships bug fixes for
Forge *regressions* against vanilla behaviour -- "Forge Chest Behavior," "Vanilla Held Item Lighting
(amends a Forge bug causing item sides to have incorrect lighting compared to Vanilla 1.8)."

**[analysis]** The cleanest way to avoid Forge overhead is to not run Forge. Another point for the
Legacy Fabric default.

---

## 4. Memory

### 4.1 Known leaks

MemoryFix (archived, 1.8.9-specific) names three concrete defects, and its README is the best
short summary of 1.8.9's memory problems in existence:

1. **The OptiFine cape leak** -- described as "the main memory leak, anyone who has played minigames
   for a few hours would have experienced it," tracked at sp614x/optifine#526. Cape textures for
   players who join and leave are never released, so a session of lobby-hopping accumulates
   hundreds of MB.
2. **Oversized resource pack icons** -- `pack.png` files are loaded at full resolution as GPU
   textures, one per installed pack. Users with hundreds of packs, some with very large icons, lose
   hundreds of MB before ever entering a world. PolyPatcher's counterpart, "Downscale Pack Images
   (change all pack icons to 64x64)," is **on by default** there.
3. **`System.gc()` on world change** -- vanilla explicitly calls `System.gc()` when loading a world,
   which forces a full stop-the-world collection and "causes world changes to take a few seconds."
   MemoryFix's README notes the same effect is achievable purely from the launcher with
   `-XX:+DisableExplicitGC`. PolyPatcher's "Optimized World Swapping" is the in-game version.

Item 3 is important to us specifically: **it is a launcher-side fix.** One JVM flag, no bytecode, no
mod, no visual change, and it turns multi-second world-swap freezes into near-instant ones. That is
the ideal shape of an optimization for this project.

### 4.2 Texture and model memory

- Atlas textures with mipmaps are the largest single GPU/heap consumer in vanilla.
- Per-quad `int[]` arrays (see §2.7) dominate in modded setups. FoamFix's target; small effect on
  vanilla 1.8.9.
- Every GUI texture is a separate `SimpleTexture` retained for the session.

### 4.3 Caches that are not worth their memory

**[analysis]** 1.8 retains a number of caches with no eviction: chat history (unbounded by default
in some paths), rendered nametag textures, and the font width table. None are individually large;
together with the above they are why "1.8.9 uses 2 GB idle" is a common complaint. Auditing rather
than guessing is the right approach here -- we should profile before claiming any of these matter.

---

## 5. JVM

### 5.1 Java version

The version manifest is unambiguous: 1.8.9 declares
`javaVersion: { component: "jre-legacy", majorVersion: 8 }`. Vanilla 1.8.9 with LWJGL 2.9.4 is a
Java 8 program, and every mainstream 1.8.9 setup runs on Java 8.

This matters enormously, because **most modern JVM tuning advice does not apply to Java 8.**

The modernization path exists (`legacy-lwjgl3`, tested on 1.8.9 under Legacy Fabric; `lwjgl3ify`,
maintained but for 1.7.10 on Forge) and it is genuinely transformative if it works, because it
unlocks everything in §5.3. It is also the riskiest thing on our roadmap. Treat as a spike.

### 5.2 Heap sizing -- the most damaging piece of folk wisdom in the community

The standard advice players give each other is "allocate more RAM." It is usually wrong.

- Vanilla 1.8.9's actual working set is on the order of 0.6-1.5 GB.
- A larger heap does not make the game faster; it makes each collection cover more memory, so
  **pauses get longer**.
- The general guidance in the tuning literature is to allocate only what the game actually needs,
  observed from real usage, "which is usually less than 8G," and that going past ~12 GB begins to
  produce noticeably longer GC pauses regardless.

**Our default should be a modest heap (roughly 2-3 GB for vanilla 1.8.9) with `-Xms` equal to
`-Xmx`**, and the settings UI should say plainly that raising it will not increase FPS. Equal
`-Xms`/`-Xmx` avoids heap-resize pauses at no cost.

### 5.3 Garbage collectors

| Collector | Java 8 | Verdict for a 1.8.9 client |
|---|---|---|
| ParallelGC | default | High throughput, long stop-the-world pauses. This is the source of the classic 1.8 freeze. |
| G1 | available, but the Java 8 G1 is much weaker than modern G1 | Reasonable default for pause-sensitivity on Java 8; needs modest, not aggressive, tuning |
| Shenandoah | **not in most Java 8 builds** [unverified -- backported by Red Hat to their OpenJDK 8; availability in Temurin/Corretto 8 needs checking] | Recommended for clients by the benchmarking literature (`-XX:+UseShenandoahGC -XX:ShenandoahGCMode=iu`) *on Java 11+* |
| ZGC | not in Java 8 | **Avoid for clients** -- the benchmark write-up reports "a significant client FPS hit" |

**Aikar's flags are for servers and should not be copy-pasted onto a client.** The tuning literature
is explicit that they effectively clamp `MaxGCPauseMillis` by setting `G1NewSizePercent` very high,
"producing long stutters on some clients," and that they collect old-gen far too aggressively for a
client, which produces much less old-gen garbage than a populated server. Shipping Aikar's flags as
a client default -- which several launchers do -- is a mistake we should not repeat.

### 5.4 JIT behaviour

The most interesting client-side JIT flag is **`-XX:-DontCompileHugeMethods`**.

HotSpot refuses to JIT-compile methods above a bytecode-size limit (`HugeMethodLimit`, 8000 bytes).
Minecraft has several methods above it, which therefore **run interpreted forever**. Disabling the
limit lets them compile.

The cost is real: more code-cache pressure and more compilation work, which is why it is usually
paired with a raised `-XX:ReservedCodeCacheSize` (400M in the benchmarked flag set). Other flags in
that set -- `-XX:+AlwaysPreTouch`, `-XX:AllocatePrefetchStyle=3` -- have narrower and more
hardware-dependent effects, and several require `-XX:+UnlockExperimentalVMOptions`.

**[analysis]** `-XX:-DontCompileHugeMethods` plus a raised code cache is a strong OPTIONAL. It is not
SAFE-by-default because the interaction with mods that generate large methods is not something we
can characterise in advance.

**Explicitly rejected:** `-XX:+UseLargePages`. The benchmarking source itself warns that it requires
administrator privileges on Windows and carries security implications. Granting our process
`SeLockMemoryPrivilege` to gain a few percent is exactly the kind of trade this product exists to
refuse.

---

## 6. Input and responsiveness

### 6.1 MC-67665 -- the "input lag" that is not input lag

This is the highest-value fix in the entire document, because it is a *correctness* bug that users
experience as latency.

Since 1.8, `EntityLivingBase.getLook(partialTicks)` derives the look vector from **`rotationYawHead`**
rather than `rotationYaw`. Head yaw interpolates toward body yaw rather than tracking the mouse
directly, so during fast turns **the ray used for attacks and interaction lags behind where your
crosshair is pointing.** As MouseDelayFix's author puts it: the angle your head faces is not the same
as your crosshair location.

Mojang fixed this in **1.11** by overriding `getLook` in `EntityPlayerSP` to use `rotationYaw`:

```java
// EntityPlayerSP, Minecraft 1.11 -- Mojang's own fix
public Vec3d getLook(float partialTicks) {
    return this.getVectorForRotation(this.rotationPitch, this.rotationYaw);
}
```

MouseDelayFix achieves the same result on 1.8-1.10 by making `EntityLivingBase.getLook` delegate to
`Entity.getLook` (which already uses `rotationYaw`) when `this instanceof EntityPlayerSP`.

**Licensing consequence, and it is a good one:** the bug is publicly documented in Mojang's own
tracker, and **Mojang's own fix is the reference implementation**. We can implement this from
Mojang's 1.11 behaviour without touching MouseDelayFix's (unlicensed -- see `mod-policies.md`) source
at all. Behaviour is not copyrightable; the two-line override is the obvious expression of it.

### 6.2 Mouse input path

1.8.9 polls mouse deltas through LWJGL 2 (`Mouse.getDX/getDY`) once per frame inside
`EntityRenderer.updateCameraAndRender`. Two consequences:

- **Input granularity is tied to framerate.** At 60 FPS you sample the mouse 60 times a second
  regardless of the mouse's 1000 Hz polling rate. This is a real and often-overlooked reason
  uncapping FPS "feels" better in 1.8 PvP.
- **LWJGL 2 has no raw-mouse-motion support.** LWJGL 3 / GLFW does
  (`GLFW_RAW_MOUSE_MOTION`), which bypasses OS pointer acceleration entirely.

**[analysis]** Raw input is a genuinely valuable feature for a PvP-oriented client, and it is
*only* reachable through the LWJGL 3 path. Another entry on the ledger for that spike.

Note the thing we must **not** do: "fixing" mouse acceleration by changing Windows' global pointer
settings. That is a system-wide modification and is rejected outright (see `security-model.md`).

### 6.3 Frame limiting and pacing

1.8's framerate limiter calls LWJGL 2's `Display.sync(fps)` when the limit is below the "unlimited"
threshold. `Display.sync` uses a sleep-plus-spin loop whose accuracy depends on the platform timer.
The result is uneven frame pacing -- the *average* framerate hits the cap, but individual frame times
vary, which is exactly what is perceived as stutter.

Compounding this: enabling **VSync as well as** a framerate cap double-limits the frame, and the two
mechanisms fight. **[analysis]** The correct behaviour is: if VSync is on, the limiter should be
disabled; if the limiter is on, it should use a high-resolution timing source and target frame
*times*, not frame counts.

**Explicitly rejected:** raising the Windows global timer resolution (`timeBeginPeriod`) to make the
sleep more accurate. That is a system-wide change with battery-life consequences, and the correct fix
is a better limiter, not a worse OS.

### 6.4 Fullscreen and window behaviour

1.8.9 uses LWJGL 2 exclusive fullscreen (`Display.setFullscreen(true)`), which forces a display mode
change. Symptoms: slow alt-tab, black screens on mode switch, and multi-monitor problems.

PolyPatcher ships both "Windowed Fullscreen (allowing you to drag your mouse outside the window)" and
"Instant Fullscreen (instant switching between fullscreen and non-fullscreen modes)."

**[analysis]** Borderless windowed fullscreen is strictly better for the alt-tab-heavy way people
actually play, at a small cost in frame latency versus true exclusive fullscreen on some drivers.
Offer both, default to borderless. This is a launcher-and-mod-owned window property -- category A/D
work, nothing system-wide.

### 6.5 Unfocused behaviour

"Unfocused FPS" (cap framerate when the window is not focused) and "Unfocused Sounds" are simple,
safe, and immediately valuable: alt-tabbing to Discord should not keep your GPU at 100%.

**[analysis]** SAFE, and a good default at a conservative cap (e.g. 30 FPS unfocused). Must be
user-overridable -- some people record or stream from an unfocused window.

---

## 7. What overlaps between the existing projects

Reading the projects side by side, the optimizations cluster:

| Problem | Patcher / PolyPatcher | OptiFine | FoamFix | BetterFps | MemoryFix | MouseDelayFix |
|---|---|---|---|---|---|---|
| Entity occlusion culling | **yes** | partial | - | - | - | - |
| Font renderer batching + caching | **yes** | partial | - | - | - | - |
| Tile entity batching / double-render fix | **yes** | - | - | - | - | - |
| Chunk update throttling | yes | yes (different approach) | - | - | - | - |
| Model/quad memory compaction | - | yes | **yes** | - | - | - |
| sin/cos replacement | - | - | - | **yes** | - | - |
| Cape leak / pack icon leak | pack icons (yes) | (is the cause) | - | - | **yes** | - |
| `System.gc()` on world change | yes | - | - | - | **yes** | - |
| MC-67665 look vector | - | - | - | - | - | **yes** |
| Render distance / draw-toggle options | **yes** | **yes** | - | - | - | - |
| Shaders, dynamic lighting, zoom, custom skies | - | **yes** | - | - | - | - |

Conclusions:

- **PolyPatcher is the single most overlapping project with our intent** -- and it is CC BY-NC-SA,
  i.e. the least reusable. We must independently implement, not port.
- **OptiFine's unique value (shaders, dynamic lights, connected textures, zoom) is not something we
  should try to replicate.** It should remain an optional third-party mod the user installs
  themselves.
- **MemoryFix and MouseDelayFix are tiny, precisely-scoped, and describe their fixes in prose in
  their READMEs.** They are the easiest to reimplement cleanly and among the highest value.
- **BetterFps is the lowest-value-per-risk** of the set for our purposes.
- **FoamFix's value is concentrated in heavily modded setups**, which is not our v1 audience.

---

## 8. Open questions

1. Is Shenandoah actually present in the OpenJDK 8 builds we would ship (Temurin 8, Corretto 8), or
   only in Red Hat's? This determines whether GC choice is a real lever on the Java 8 path.
2. Does `legacy-lwjgl3` still work against current Legacy Fabric on 1.8.9? Last pushed 2023.
3. How much of the 1.8.9 "2 GB idle" complaint is atlas/model memory versus retained caches? Needs
   profiling, not speculation.
4. What is the actual measured draw-call count on a Hypixel lobby frame, broken down by
   font / HUD / entity / tile entity / chunk? Every priority in this document is an estimate until
   we have that profile.
5. Does entity occlusion culling create a competitive-integrity concern (seeing or not seeing
   players) that servers might object to? Worth asking anti-cheat-adjacent server operators before
   shipping it on by default.
6. Does 1.8.9 on a modern JVM change the JIT flag recommendations materially? Almost certainly yes;
   the benchmarked flag set targets Java 11+.

---

## 9. Sources

- Minecraft 1.8.9 version JSON -- https://piston-meta.mojang.com/v1/packages/d546f1707a3f2b7d034eece5ea2e311eda875787/1.8.9.json
- MouseDelayFix README (MC-67665 analysis, Mojang's 1.11 fix) -- https://github.com/prplz/MouseDelayFix
- MC-67665 -- https://bugs.mojang.com/browse/MC-67665
- MemoryFix README (cape leak, pack icons, `System.gc()`) -- https://github.com/prplz/MemoryFix
- OptiFine cape leak issue -- https://github.com/sp614x/optifine/issues/526
- PolyPatcher README, full feature list and changelog -- https://github.com/Polyfrost/PolyPatcher
- MC-63020 / MC-70850 (frustum culling false negatives) -- https://bugs.mojang.com/browse/MC-63020
- BetterFps (7 sin/cos algorithms, per-machine benchmark) -- https://github.com/Guichaguri/BetterFps and https://ftb.fandom.com/wiki/BetterFps
- FoamFix -- https://github.com/asiekierka/FoamFix
- Minecraft Performance Flags Benchmarks (client GC/JIT flags, Aikar-on-client critique, Large Pages warning) -- https://github.com/brucethemoose/Minecraft-Performance-Flags-Benchmarks
- 1.8/1.9 VBO and Advanced OpenGL regression discussion -- https://www.minecraftforum.net/forums/minecraft-java-edition/discussion/2899843-resolved-ive-been-away-since-1-8-has-the-lag
- Zarzelcow/legacy-lwjgl3 (LWJGL 3 on 1.8.9) -- https://github.com/Zarzelcow/legacy-lwjgl3
- GTNewHorizons/lwjgl3ify -- https://github.com/GTNewHorizons/lwjgl3ify

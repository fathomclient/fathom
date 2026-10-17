# Fathom in-game animation architecture

Animation stays above the renderer boundary. Components submit ordinary values
through `Renderer`; the animation package never calls the SDF compositor or
OpenGL. Layout, colors, type, interaction semantics, and persisted settings are
independent of the clock.

## One clock and four small primitives

- `Clock` is advanced once at the start of a Fathom screen frame. It uses
  monotonic nanoseconds, exposes elapsed seconds for tweens, and bounds delta
  time for simulations.
- `Timing` is the complete duration vocabulary: hover in/out, press, toggle,
  navigation, standard, panel, screen enter/exit, tooltip, and stagger.
- `Motion` is an interruptible scalar tween. It samples its current value before
  retargeting, reaches the exact destination, rejects non-finite targets, and can
  map its normalized value to opacity, color, position, scale, or radius.
- `Transition` composes independent entry and exit motions for screens. Starting
  an exit freezes entry at the visible value, so closing during opening cannot
  snap or continue brightening.
- `Spring` is an allocation-free analytical damped oscillator. `smooth` is
  critically damped and bounded for scrolling. `accent` permits restrained
  overshoot and is reserved for small markers such as navigation selection.

`Ease.Curve` intentionally has only three ordinary curves: `STANDARD`, `SMOOTH`,
and `EXIT`. Spring motion is explicit rather than hidden inside an easing curve.

## Component rules

1. Sample `Gfx.clock().seconds()` for `Motion` and `Transition`.
2. Use the frame delta for `Spring`; it clamps long frames internally.
3. Call `to` freely every frame. An unchanged target does not restart a motion.
4. Use `Timing`; do not add component-local millisecond constants without a
   behavior that cannot fit an existing intent.
5. Animate values passed to `Renderer`. Do not add raw OpenGL calls to controls.
6. Disable interaction as soon as a screen begins closing, and replace the
   screen only after the renderer has ended its final frame.

## Allocation and lifetime

`Motion`, `Transition`, and `Spring` allocate only when their owner is created.
They have no timers, listeners, threads, or native resources. `Controls` reuses
its transient `State` results after the first busy frame and prunes inactive
animation slots periodically. Tooltip state releases its retained text and
anchor after the exit settles.

## Intentional non-transition timing

The keybind listening pulse and renderer showcase oscillations are continuous,
time-based indicators rather than interpolation. Video decode/playback clocks,
profiling timestamps, click windows, and destructive-action confirmation windows
are domain timers and are not UI animation state.

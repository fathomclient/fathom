# In-game visual effects

Fathom's depth effects are defined by
`fabric/ui/VisualEffects.java`. Components should use these helpers instead of
inventing blur sizes or opacity values locally.

## Effect vocabulary

- **Corner accent** is Fathom's signature geometry: one top-right quarter-circle
  using the component's real radius. It identifies selected or enabled modules, primary
  actions, connected drawers, and elevated overlays without changing their
  layout or relying on the logo.
- **Layered surface** adds a very small directional tonal shift from the top of
  a surface to its lower face. It keeps the near-black hierarchy visible while
  avoiding large decorative gradients.
- **Accent glow** belongs only to enabled, selected, focused, or primary
  interaction states. It uses the deep Fathom red (`#841c24`) at low opacity.
- **Top light** is a very faint white-to-transparent vertical gradient over the
  same full-size rounded path as its surface.
- **Panel shadow** separates a large resting panel from the world.
- **Overlay shadow** separates dropdowns, tooltips, drawers, and inspectors from
  ordinary content.
- **Modal shadow** is the strongest preset and is reserved for modal panels.

The shared tokens cover glow radius and opacity, hover brightness, focus-ring
intensity, top-light opacity, signature dimensions, and the three shadow
strengths. Raising one of these values changes the whole hierarchy coherently.

Enabled module cards remain neutral charcoal. Their state is carried by a
localized icon glow, a fine red edge, the depth bracket, and a compact square
status marker. Selection strengthens those existing cues rather than filling
the whole card red. The settings drawer mirrors the bracket and remains mounted
during its close animation, so its visual connection to the selected card does
not snap or leave stale state.

## Rendering cost

Accent glow is a single NanoVG box-gradient fill. The component surface is
drawn immediately afterward and covers the gradient's centre, so repeated
glows do not need a stencil, framebuffer read, Gaussian blur, image, or
offscreen target. On the compatibility OpenGL renderer, the same helper uses
the renderer's cached falloff atlas and a single shadow draw; it does not build
a new texture or raster image per component. Top light is one ordinary
vertical-gradient fill.

The corner accent is one inset NanoVG path. On capsule buttons it is a pure
quarter-circle with no horizontal or vertical tails. The fallback clips the
same rounded outline to that corner. Layered surfaces reuse the renderer's gradient primitive,
and the compact depth marker uses scalar bounds so it introduces no temporary
rectangle allocation in the per-frame path.

Neutral panel, overlay, and modal shadows use the existing box-gradient shadow
presets. Cutout work is limited to the small number of genuinely elevated
surfaces rather than repeated on every card. No effect helper creates textures,
rebuilds resources, or performs CPU rasterization per frame.

## Dense-card QA

Run the Mods capture with every visible card rendered in the enabled state:

```text
gradle runClient -Pnanovg -PcaptureUi=Mods -PqaAllModules
```

This flag is visual-only. It does not enable modules or write configuration.
The capture log reports mean UI render time; compare it with a normal Mods
capture at the same window size and UI-quality setting.

To capture the connected settings state without changing saved configuration:

```text
gradle runClient -Pnanovg -PcaptureUi=Mods -PqaOpenSettings
```

The original effects pass was checked at 1280×720 with the high-quality NanoVG
backend: the normal Mods capture averaged 1.18 ms of UI time and the all-enabled
capture averaged 1.21 ms. The branded-depth pass measured 1.23 ms with every
module shown enabled and 1.37 ms with the settings drawer open. These values are
machine-specific, but confirm that the identity treatment remains a small set
of single-pass vector operations rather than a blur pipeline.

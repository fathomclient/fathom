# In-game visual effects

Fathom's depth effects are defined by
`fabric/ui/VisualEffects.java`. Components should use these helpers instead of
inventing blur sizes or opacity values locally.

## Effect vocabulary

- **Accent glow** belongs only to enabled, selected, focused, or primary
  interaction states. It uses the deep Fathom red (`#841c24`) at low opacity.
- **Top light** is a very faint white-to-transparent vertical gradient over the
  same full-size rounded path as its surface.
- **Panel shadow** separates a large resting panel from the world.
- **Overlay shadow** separates dropdowns, tooltips, drawers, and inspectors from
  ordinary content.
- **Modal shadow** is the strongest preset and is reserved for modal panels.

The shared tokens cover glow radius and opacity, hover brightness, focus-ring
intensity, top-light opacity, and the three shadow strengths. Raising one of
these values changes the whole hierarchy coherently.

## Rendering cost

Accent glow is a single NanoVG box-gradient fill. The component surface is
drawn immediately afterward and covers the gradient's centre, so repeated
glows do not need a stencil, framebuffer read, Gaussian blur, image, or
offscreen target. On the compatibility OpenGL renderer, the same helper uses
the renderer's cached falloff atlas and a single shadow draw; it does not build
a new texture or raster image per component. Top light is one ordinary
vertical-gradient fill.

Neutral panel, overlay, and modal shadows use the existing box-gradient shadow
presets. Cutout work is limited to the small number of genuinely elevated
surfaces rather than repeated on every card. No effect helper creates textures,
rebuilds resources, or performs CPU rasterization per frame.

## Dense-card QA

Run the Mods capture with every visible card rendered in the enabled state:

```text
gradle runClient -PcaptureUi=Mods -PqaAllModules
```

This flag is visual-only. It does not enable modules or write configuration.
The capture log reports mean UI render time; compare it with a normal Mods
capture at the same window size and UI-quality setting.

The implementation pass was checked at 1280×720 with the high-quality NanoVG
backend: the normal Mods capture averaged 1.18 ms of UI time and the all-enabled
capture averaged 1.21 ms. The roughly 0.03 ms difference is machine-specific,
but confirms that repeated glow remains a single-pass cost rather than scaling
into a blur pipeline.

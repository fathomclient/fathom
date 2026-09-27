# Fathom icons

`manifest.json` is the stable Fathom icon-ID map. The SVG files in this folder
are extracted from the exact Lucide package used by the launcher. They are the
shared artwork consumed by both products:

- the launcher draws them inline from `desktop/src/lib/icons.generated.ts`
  (`FathomIcon.svelte`), so the global stroke rule applies to them;
- Minecraft receives generated SVG path strings and rasterises them into its
  antialiased icon atlas at the active framebuffer scale.

Regenerate all derivatives after changing the manifest:

```powershell
node tools/generate-fathom-icons.mjs
```

The generated files record their source and must not be edited by hand. Lucide
is distributed under the ISC license, with Feather-derived portions under MIT.

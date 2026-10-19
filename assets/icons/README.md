# Icons

The SVGs here come from the Lucide package the launcher uses, and
`manifest.json` maps Fathom's icon names to them. Both the launcher and the
game draw from this one set: the launcher inlines them
(`desktop/src/lib/icons.generated.ts`), and the game rasterises them into its
icon atlas at the current scale.

After changing the manifest, regenerate the derived files (don't edit those by
hand):

```powershell
node tools/generate-fathom-icons.mjs
```

Lucide is ISC-licensed, with Feather-derived parts under MIT; see
[ATTRIBUTION.md](../../ATTRIBUTION.md).

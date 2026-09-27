<!--
  A Fathom icon, by name.

  Drawn inline rather than as a CSS mask so that the global icon rule in
  `theme.css` (`svg.lucide`) sets its stroke in screen pixels, exactly as it
  does for every Lucide component. A masked SVG scales its stroke with its
  size, which is how a 13px icon used to end up with a hairline and a 19px one
  with a heavy line.

  Sizes are the system's roles (`ICON` in `metrics.generated.ts`): 20
  navigation and tiles, 16 inline, 12 inside caption text.
-->
<script lang="ts">
  import { FATHOM_ICON_BODY, FATHOM_ICON_FILLED, type FathomIconName } from '../icons.generated';

  export let name: FathomIconName;
  export let size: 12 | 16 | 20 = 16;
</script>

<svg
  class="lucide fathom-icon"
  width={size}
  height={size}
  viewBox="0 0 24 24"
  fill={FATHOM_ICON_FILLED.has(name) ? 'currentColor' : 'none'}
  stroke="currentColor"
  stroke-width="2"
  stroke-linecap="round"
  stroke-linejoin="round"
  aria-hidden="true"
>
  {@html FATHOM_ICON_BODY[name]}
</svg>

<style>
  .fathom-icon {
    display: block;
    flex: 0 0 auto;
  }
</style>

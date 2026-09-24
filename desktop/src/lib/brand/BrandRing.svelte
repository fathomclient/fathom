<!--
  The offset ring: the wordmark's O, and the primary Fathom icon.

  Its counter is dropped, so the wall is heaviest at the top and lightest at the
  bottom; that offset is what keeps it from reading as a generic circle, and
  `ringGeometry` guarantees it survives at 16px. The ring is ivory above the
  surface and ivory-beneath below it -- it never takes the wordmark's horizontal
  step. The counter shows whatever is behind: the deep tile, or with
  `tile={false}` the photograph, for hero use over the controlled background.

  Tiers follow the size: one tone under 24px, two tones to 47px, and the
  bronze edge detail from 48px.
-->
<script lang="ts">
  import { BRAND } from './brand.generated.ts';
  import { ringGeometry, ringPath, tilePath } from './surface.ts';

  export let size = 32;
  /** The deep tile. Keep it wherever contrast is not controlled. */
  export let tile = true;
  export let label = 'Fathom';

  const id = `ring-${Math.random().toString(36).slice(2, 9)}`;

  $: g = tile ? ringGeometry(size) : ringGeometry(size, size);
  $: d = ringPath(g);
</script>

<svg
  class="ring"
  width={size}
  height={size}
  viewBox="0 0 {size} {size}"
  role="img"
  aria-label={label}
>
  {#if tile}
    <path fill={BRAND.tile} d={tilePath(size)} />
  {/if}

  {#if g.tier === 'one'}
    <path fill={BRAND.air} fill-opacity={BRAND.airOpacity} fill-rule="evenodd" {d} />
  {:else}
    <defs>
      <clipPath id="{id}-air"><rect x="0" y="0" width={size} height={g.surfaceY} /></clipPath>
      <clipPath id="{id}-beneath">
        <rect x="0" y={g.surfaceY} width={size} height={size - g.surfaceY} />
      </clipPath>
    </defs>
    <g opacity={BRAND.airOpacity}>
      {#if g.edge}
        <g clip-path="url(#{id}-air)">
          <path fill={BRAND.edge} fill-rule="evenodd" transform="translate({g.edge} {g.edge})" {d} />
        </g>
      {/if}
      <g clip-path="url(#{id}-air)"><path fill={BRAND.air} fill-rule="evenodd" {d} /></g>
    </g>
    <g opacity={BRAND.beneathOpacity} clip-path="url(#{id}-beneath)">
      <path fill={BRAND.beneath} fill-rule="evenodd" {d} />
    </g>
  {/if}
</svg>

<style>
  .ring {
    display: block;
    flex-shrink: 0;
  }
</style>

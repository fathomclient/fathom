<!--
  The Sounding Line: one stem crossing the surface and stepping right beneath
  it -- the brand's refraction reduced to a single stroke. Fathom's loading and
  progress symbol, and its smallest mark.

  - `value` 0–1: known progress. The line's depth below the surface is the
    measure; above the surface it is always whole.
  - `value={null}`: working, with no measure. The beneath segment breathes very
    slowly between two densities. Nothing spins, nothing travels, nothing
    glows; under reduced motion it holds still.
  - `value` left out: the static mark.

  The step is a whole pixel at every size, in the same direction as the
  wordmark's.
-->
<script lang="ts">
  import { BRAND } from './brand.generated.ts';
  import { soundingGeometry, soundingPaths } from './surface.ts';

  /** Height in CSS pixels. */
  export let height = 16;
  export let value: number | null | undefined = undefined;
  export let label = 'Loading';

  $: g = soundingGeometry(height);
  $: depth = typeof value === 'number' ? value : 1;
  $: paths = soundingPaths(g, depth);
  $: indeterminate = value === null;
  $: measured = typeof value === 'number';
</script>

<svg
  class="sounding"
  class:indeterminate
  width={g.width}
  height={g.height}
  viewBox="0 0 {g.width} {g.height}"
  role={measured ? 'progressbar' : indeterminate ? 'status' : 'img'}
  aria-label={label}
  aria-valuemin={measured ? 0 : undefined}
  aria-valuemax={measured ? 100 : undefined}
  aria-valuenow={measured ? Math.round(depth * 100) : undefined}
>
  <path fill={BRAND.air} fill-opacity={BRAND.airOpacity} d={paths.air} />
  {#if paths.beneath}
    <path class="beneath" fill={BRAND.beneath} fill-opacity={BRAND.beneathOpacity} d={paths.beneath} />
  {/if}
</svg>

<style>
  .sounding {
    display: inline-block;
    flex-shrink: 0;
    vertical-align: middle;
  }

  .indeterminate .beneath {
    animation: sound 1.8s var(--ease-standard) infinite alternate;
  }

  @keyframes sound {
    from {
      opacity: 1;
    }
    to {
      opacity: 0.4;
    }
  }

  @media (prefers-reduced-motion: reduce) {
    .indeterminate .beneath {
      animation: none;
      opacity: 0.6;
    }
  }
</style>

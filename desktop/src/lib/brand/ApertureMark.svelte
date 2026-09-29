<!--
  The mark as Fathom's loading and progress symbol.

  - `value` 0–1: known progress. The open corner closes as the work completes;
    the part still to go is drawn at the track density, so the measure is
    visible from the start.
  - `value={null}`: working, with no measure. The plane breathes very slowly
    between two densities. Nothing spins, nothing travels, nothing glows; under
    reduced motion it holds still.
  - `value` left out: the static mark.
  - `status`: `done` closes the frame and shows the plane in the success ink;
    `failed` stops where it was and shows the plane in the danger ink.

  `height` is in CSS pixels. The unit is solved in device pixels, so the walls
  stay whole pixels at 100%, 125% and 150%; the mark is six units tall and
  draws at the largest six-unit size that fits.
-->
<script lang="ts">
  import { onMount } from 'svelte';
  import { BRAND } from './brand.generated.ts';
  import { frameFillRule, frameOutlines, markGeometry, outlinePath, planeOutline } from './aperture.ts';

  /** Height in CSS pixels. */
  export let height = 16;
  export let value: number | null | undefined = undefined;
  export let status: 'done' | 'failed' | undefined = undefined;
  export let label = 'Loading';

  let dpr = 1;

  onMount(() => {
    const sync = () => {
      dpr = Math.max(1, window.devicePixelRatio || 1);
    };
    sync();
    window.addEventListener('resize', sync);
    return () => window.removeEventListener('resize', sync);
  });

  $: g = markGeometry(Math.max(2, Math.floor((height * dpr) / BRAND.grid)));
  $: measured = typeof value === 'number';
  $: indeterminate = value === null && !status;
  $: closing = status === 'done' ? 1 : measured ? Math.max(0, Math.min(1, value ?? 0)) : 0;
  $: frame = frameOutlines(g, closing).map(outlinePath).join('');
  $: track = measured && closing < 1 ? frameOutlines(g, 1).map(outlinePath).join('') : '';
  $: plane = outlinePath(planeOutline(g));
  $: size = g.size / dpr;
</script>

<svg
  class="aperture"
  class:indeterminate
  width={size}
  height={size}
  viewBox="0 0 {g.size} {g.size}"
  role={measured ? 'progressbar' : indeterminate ? 'status' : 'img'}
  aria-label={label}
  aria-valuemin={measured ? 0 : undefined}
  aria-valuemax={measured ? 100 : undefined}
  aria-valuenow={measured ? Math.round(closing * 100) : undefined}
>
  {#if track}
    <path fill={BRAND.frameInk} fill-opacity={BRAND.measureTrack} fill-rule="evenodd" d={track} />
  {/if}
  <path fill={BRAND.frameInk} fill-rule={frameFillRule(closing)} d={frame} />
  <path
    class="plane"
    class:done={status === 'done'}
    class:failed={status === 'failed'}
    fill={BRAND.planeInk}
    d={plane}
  />
</svg>

<style>
  .aperture {
    display: inline-block;
    flex-shrink: 0;
    vertical-align: middle;
  }

  .plane.done {
    fill: var(--g-status-ok);
  }

  .plane.failed {
    fill: var(--g-status-error);
  }

  .indeterminate .plane {
    animation: breathe 1.8s var(--ease-standard) infinite alternate;
  }

  @keyframes breathe {
    from {
      opacity: 1;
    }
    to {
      opacity: 0.4;
    }
  }

  @media (prefers-reduced-motion: reduce) {
    .indeterminate .plane {
      animation: none;
      opacity: 0.6;
    }
  }
</style>

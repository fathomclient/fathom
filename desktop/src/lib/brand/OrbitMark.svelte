<!--
  The mark without its tile: the arc and the star.

  `height` is the mark's box in CSS pixels (the arc and the star; the star's
  glow and the halo spill past it, as light does, so nothing round it should
  clip). The width follows the mark's own proportions.

  - `halo`: the faint blue light behind the mark, as on the app icon.
  - `working`: the star breathes slowly, for a wait with no measure. Nothing
    travels or spins; under reduced motion it holds still.
  - `ground="light"`: the deeper inks, for a light surface.

  Drawn from `orbit.ts`, the same paths as the icon files and the game.
-->
<script lang="ts" context="module">
  let next = 0;
</script>

<script lang="ts">
  import { BRAND } from './brand.generated.ts';
  import {
    ARC,
    DARK_INK,
    HALO,
    LIGHT_INK,
    MARK_ASPECT,
    ORBIT_STOPS,
    ORBIT_VECTOR,
    STAR,
    STAR_CORE,
    STAR_CORE_WIDTH,
    STAR_GLOW,
    TAPER,
    markViewBox,
  } from './orbit.ts';

  /** The mark's height in CSS pixels. */
  export let height = 16;
  export let halo = false;
  export let working = false;
  export let ground: 'dark' | 'light' = 'dark';
  export let label = 'Fathom';
  /** Decorative beside a wordmark that already names it. */
  export let decorative = false;

  const id = `fathom-mark-${(next += 1)}`;
  const core = STAR_CORE.map(([x1, y1, x2, y2]) => `M${x1} ${y1}L${x2} ${y2}`).join('');

  $: ink = ground === 'light' ? DARK_INK : LIGHT_INK;
</script>

<svg
  class="orbit-mark"
  class:working
  width={height * MARK_ASPECT}
  {height}
  viewBox={markViewBox()}
  role={decorative ? undefined : working ? 'status' : 'img'}
  aria-hidden={decorative ? 'true' : undefined}
  aria-label={decorative ? undefined : label}
>
  <defs>
    <linearGradient id="{id}-orbit" x1={ORBIT_VECTOR.x1} y1={ORBIT_VECTOR.y1} x2={ORBIT_VECTOR.x2} y2={ORBIT_VECTOR.y2}>
      {#each ORBIT_STOPS as offset, i}
        <stop {offset} stop-color={ink.orbit[i]} />
      {/each}
    </linearGradient>
    {#if halo}
      <radialGradient id="{id}-halo">
        <stop offset="0" stop-color={BRAND.halo} stop-opacity={BRAND.haloAlpha} />
        <stop offset="1" stop-color={BRAND.halo} stop-opacity="0" />
      </radialGradient>
    {/if}
    {#if ink.glow}
      <filter id="{id}-glow" x="-100%" y="-100%" width="300%" height="300%">
        <feGaussianBlur stdDeviation={BRAND.starGlowBlur} />
      </filter>
    {/if}
  </defs>
  {#if halo}
    <ellipse cx={HALO.cx} cy={HALO.cy} rx={HALO.rx} ry={HALO.ry} fill="url(#{id}-halo)" />
  {/if}
  <path d={ARC} fill="url(#{id}-orbit)" />
  <path d={TAPER} fill={ink.taper} opacity={BRAND.taperAlpha} />
  <g class="star">
    {#if ink.glow}
      <path d={STAR_GLOW} fill={ink.glow} opacity={BRAND.starGlowAlpha} filter="url(#{id}-glow)" />
    {/if}
    <path d={STAR} fill={ink.star} />
    {#if ink.core}
      <path d={core} stroke={ink.core} stroke-width={STAR_CORE_WIDTH} opacity={BRAND.starCoreAlpha} />
    {/if}
  </g>
</svg>

<style>
  .orbit-mark {
    display: inline-block;
    flex-shrink: 0;
    vertical-align: middle;
    overflow: visible;
  }

  /* About the star's own centre on the 512 grid. */
  .star {
    transform-box: view-box;
    transform-origin: 282px 285px;
  }

  .working .star {
    animation: breathe 1.8s var(--ease-standard) infinite alternate;
  }

  @keyframes breathe {
    from {
      opacity: 1;
      transform: scale(1);
    }
    to {
      opacity: 0.45;
      transform: scale(0.82);
    }
  }

  @media (prefers-reduced-motion: reduce) {
    .working .star {
      animation: none;
      opacity: 0.7;
    }
  }
</style>

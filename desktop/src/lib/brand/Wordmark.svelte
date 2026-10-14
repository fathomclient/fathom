<!--
  The wordmark: "fathom", lowercase, outlined from Geist.

  Drawn from the generated outlines rather than set as text, so it is the same
  drawing as the icon files and the game. `height` is the ascender in CSS
  pixels (Geist's ascender is its cap height). The optical tier and the
  x-height snapping are decided in device pixels: the header's 13px wordmark is
  SemiBold on a 100% display and Medium from 125%, each the right weight for
  the pixels it is drawn with.

  Its ink is the brand's: ivory, taking the arc's blue over the last letters
  (`word-tint` from `word-tint-from` of the way along). `ground="light"` is the
  deeper pair for a light surface.

  Always lowercase. The Standard capitalization setting changes interface text,
  not the wordmark, and its accessible name is "Fathom" either way.
-->
<script lang="ts" context="module">
  let next = 0;
</script>

<script lang="ts">
  import { onMount } from 'svelte';
  import { BRAND } from './brand.generated.ts';
  import { DARK_WORD, LIGHT_WORD, wordmarkLayout } from './orbit.ts';

  /** Ascender height in CSS pixels. */
  export let height = 13;
  export let label = 'Fathom';
  export let ground: 'dark' | 'light' = 'dark';

  const id = `fathom-word-${(next += 1)}`;
  let dpr = 1;

  onMount(() => {
    const sync = () => {
      dpr = Math.max(1, window.devicePixelRatio || 1);
    };
    sync();
    window.addEventListener('resize', sync);
    return () => window.removeEventListener('resize', sync);
  });

  $: layout = wordmarkLayout(height * dpr);
  $: ink = ground === 'light' ? DARK_WORD : LIGHT_WORD;
</script>

<svg
  width={layout.width / dpr}
  height={layout.height / dpr}
  viewBox={`0 0 ${layout.units} 100`}
  role="img"
  aria-label={label}
>
  <defs>
    <linearGradient id={id} gradientUnits="userSpaceOnUse" x1="0" y1="0" x2={layout.units} y2="0">
      <stop offset="0" stop-color={ink.ink} />
      <stop offset={BRAND.wordTintFrom} stop-color={ink.ink} />
      <stop offset="1" stop-color={ink.tint} />
    </linearGradient>
  </defs>
  <path d={layout.d} fill="url(#{id})" />
</svg>

<style>
  svg {
    display: block;
    /* The round letters overshoot the baseline slightly. */
    overflow: visible;
  }
</style>

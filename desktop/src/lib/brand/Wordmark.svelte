<!--
  The wordmark: "fathom", lowercase, outlined from Geist.

  Drawn from the generated outlines rather than set as text, so it is the same
  drawing as the icon files and the game. `height` is the ascender in CSS
  pixels (Geist's ascender is its cap height). The optical tier and the
  x-height snapping are decided in device pixels: the header's 13px wordmark is
  SemiBold on a 100% display and Medium from 125%, each the right weight for
  the pixels it is drawn with.

  Always lowercase. The Standard capitalization setting changes interface text,
  not the wordmark, and its accessible name is "Fathom" either way.
-->
<script lang="ts">
  import { onMount } from 'svelte';
  import { wordmarkLayout } from './aperture.ts';

  /** Ascender height in CSS pixels. */
  export let height = 13;
  export let label = 'Fathom';

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
</script>

<svg
  width={layout.width / dpr}
  height={layout.height / dpr}
  viewBox={`0 0 ${layout.width / layout.scale} 100`}
  role="img"
  aria-label={label}
>
  <path d={layout.d} fill="currentColor" />
</svg>

<style>
  svg {
    display: block;
    /* The round letters overshoot the baseline slightly. */
    overflow: visible;
    color: var(--g-text-primary);
  }
</style>

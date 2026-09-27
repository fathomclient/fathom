<script lang="ts">
  import { onMount } from 'svelte';
  import Background from '../Background.svelte';
  import grain from './grain.png';

  let grainCssPixels = 256;

  onMount(() => {
    const syncDevicePixels = () => {
      grainCssPixels = 256 / Math.max(1, window.devicePixelRatio || 1);
    };
    syncDevicePixels();
    window.addEventListener('resize', syncDevicePixels);
    return () => window.removeEventListener('resize', syncDevicePixels);
  });
</script>

<!--
  The full-resolution Rainforest Storm still is shared by every launcher page.
  This layer adds only a restrained vignette and static grain.
-->
<div class="graphite-backdrop" aria-hidden="true">
  <Background choice="bg_5rain" />
  <div class="vignette"></div>
  <div
    class="grain"
    style={`--graphite-grain: url(${grain}); --graphite-grain-size: ${grainCssPixels}px`}
  ></div>
</div>

<style>
  .graphite-backdrop,
  .graphite-backdrop > * {
    position: absolute;
    inset: 0;
  }

  .graphite-backdrop {
    overflow: hidden;
    background: var(--g-ink-1);
    pointer-events: none;
  }

  .vignette {
    background: radial-gradient(
      ellipse at center,
      transparent 0%,
      transparent 60%,
      rgba(5, 9, 11, 0.16) 100%
    );
  }

  .grain {
    background-image: var(--graphite-grain);
    background-repeat: repeat;
    /* The source tile remains 256 x 256 device pixels at every display scale. */
    background-size: var(--graphite-grain-size) var(--graphite-grain-size);
    opacity: var(--g-grain-alpha);
  }
</style>

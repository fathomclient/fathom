<script lang="ts">
  import { onMount } from 'svelte';
  import Background from '../Background.svelte';
  import grain from './grain.png';
  import { DEFAULT_BACKGROUND, optionFor } from '../backgrounds';

  /** The user's background. This used to be hard-coded to Rainforest Storm. */
  export let choice: string = DEFAULT_BACKGROUND;
  /** Where the light gathers: behind Home's launch panel, or low and centred
      under a page's content. */
  export let focus: 'home' | 'page' = 'page';

  /* The scene's accent lights the whole interface (glow.css). */
  $: accent = optionFor(choice).accent;
  $: if (typeof document !== 'undefined') document.documentElement.style.setProperty('--glow', accent);

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
  The user's chosen background is shared by every launcher page. This layer
  adds a restrained vignette, the bloom, and static grain. None of it re-grades
  the picture: the bloom is light in the picture's own accent, laid over it.

  THE BLOOM
  Two round, soft pools of the accent, like light from somewhere below the
  frame: the main one behind whatever you came here to do (Home's launch
  panel, or a page's content), a fainter echo high on the opposite side so
  the light has somewhere to come from. Each is a circle with a gaussian-like
  falloff, drifting a few percent over most of a minute -- slow enough to read
  as the scene breathing rather than as animation. The grain above dithers
  them, so a large dark gradient never bands.
-->
<div class="graphite-backdrop" aria-hidden="true" data-focus={focus}>
  <Background {choice} />
  <div class="vignette"></div>
  <div class="bloom main"></div>
  <div class="bloom echo"></div>
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

  .bloom {
    --size: calc(var(--g-glow-bloom-size) * 100vmax);
    --alpha: var(--g-glow-bloom-alpha);
    inset: auto;
    width: var(--size);
    height: var(--size);
    border-radius: 50%;
    background: radial-gradient(
      circle closest-side,
      color-mix(in srgb, var(--glow) calc(var(--alpha) * 100%), transparent) 0%,
      color-mix(in srgb, var(--glow) calc(var(--alpha) * 78%), transparent) 22%,
      color-mix(in srgb, var(--glow) calc(var(--alpha) * 44%), transparent) 46%,
      color-mix(in srgb, var(--glow) calc(var(--alpha) * 16%), transparent) 70%,
      color-mix(in srgb, var(--glow) calc(var(--alpha) * 3%), transparent) 88%,
      transparent 100%
    );
    animation: drift var(--g-glow-drift) ease-in-out infinite alternate;
  }

  /* Home: centred on the launch panel (the rail column, 28 in, 440 wide,
     40 up from the bottom). */
  [data-focus='home'] .bloom.main {
    left: calc(var(--layout-shell-rail, 76px) + 28px + 220px - var(--size) / 2);
    top: calc(100% - 40px - 180px - var(--size) / 2);
  }

  /* A page: low and centred, rising from below the frame like a horizon. */
  [data-focus='page'] .bloom.main {
    left: calc(50% + var(--layout-shell-rail, 76px) / 2 - var(--size) / 2);
    top: calc(100% - var(--size) * 0.32);
  }

  .bloom.echo {
    --size: calc(var(--g-glow-bloom-size) * 70vmax);
    --alpha: var(--g-glow-bloom-echo-alpha);
    right: calc(var(--size) * -0.28);
    top: calc(var(--size) * -0.36);
    animation-delay: calc(var(--g-glow-drift) / -2);
  }

  @keyframes drift {
    from {
      transform: translate3d(-2%, 1%, 0) scale(0.97);
    }
    to {
      transform: translate3d(2%, -1.5%, 0) scale(1.03);
    }
  }

  @media (prefers-reduced-motion: reduce) {
    .bloom {
      animation: none;
    }
  }

  .grain {
    background-image: var(--graphite-grain);
    background-repeat: repeat;
    /* The source tile remains 256 x 256 device pixels at every display scale. */
    background-size: var(--graphite-grain-size) var(--graphite-grain-size);
    opacity: var(--g-grain-alpha);
  }
</style>

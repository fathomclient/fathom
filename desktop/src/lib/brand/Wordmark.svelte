<!--
  The Surface wordmark.

  Drawn from `layoutWordmark`, never from text: each letter is its real outline,
  split at the surface into an air band and a beneath band, with the beneath
  band displaced right by a whole number of pixels for this size. Two
  densities; above 48px a 1px bronze detail along the lower-right edges of the
  air band. Under 20px it is the plain letters, and the icon carries the rest.

  THE REVEAL (`reveal`, once)

  It starts unshifted, at the air density. An implied pane then passes left to
  right: as it reaches each letter, that letter's lower band steps into its
  displacement -- in one jump, so it never sits at a fractional pixel -- and
  settles to the beneath density. About a second in all, and it never repeats.
  With reduced motion it is a plain fade instead, with no refraction.

  `start` holds the reveal until the window is actually on screen: the launcher
  window opens hidden, and a reveal played behind it would be wasted.

  `tone="dark"` is the deep-brown variant for crops where bright gold sits
  behind the letters. `veil` lays a very soft local darkening behind a hero-size
  wordmark -- at most 18% at its centre, feathered to nothing, never a panel.
-->
<script lang="ts">
  import { onMount } from 'svelte';
  import { BRAND } from './brand.generated.ts';
  import { DARK_INK, LIGHT_INK, layoutWordmark } from './surface.ts';

  /** Cap height in CSS pixels. */
  export let height = 20;
  export let tone: 'light' | 'dark' = 'light';
  /** Play the refraction reveal once, when `start` becomes true. */
  export let reveal = false;
  export let start = true;
  export let veil = false;
  export let label = 'Fathom';

  const id = `wm-${Math.random().toString(36).slice(2, 9)}`;

  $: layout = layoutWordmark(height);
  $: ink = tone === 'dark' ? DARK_INK : LIGHT_INK;
  $: all = layout.letters.map((letter) => letter.path).join('');

  /* Where the implied pane reaches each letter. The sweep leaves room for the
     last letter's density change to finish inside the total. */
  const LEAD = 80;
  $: sweep = BRAND.reveal - BRAND.revealFade - LEAD;
  $: delays = layout.letters.map((letter) => LEAD + (letter.centre / layout.width) * sweep);

  /** `pending` before the reveal, `moving` during it, `settled` after. */
  let phase: 'pending' | 'moving' | 'settled' = reveal ? 'pending' : 'settled';
  let fading = false;
  let mounted = false;

  onMount(() => {
    mounted = true;
  });

  $: if (mounted && reveal && start && phase === 'pending') begin();

  function begin() {
    const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    // A transition started in a hidden document never runs; land on the final
    // state rather than leave the wordmark unshifted.
    if (reduced || document.visibilityState !== 'visible') {
      phase = 'settled';
      fading = reduced;
      return;
    }
    // One frame in the pending state first, so the transitions have a start.
    requestAnimationFrame(() => {
      phase = 'moving';
      window.setTimeout(() => (phase = 'settled'), BRAND.reveal + 50);
    });
  }

  $: shifted = phase !== 'pending';
</script>

<span
  class="wordmark"
  class:veil
  class:fading
  style="width: {layout.width}px; height: {layout.height}px; --fade: {BRAND.revealFade}ms"
>
  <svg
    width={layout.width}
    height={layout.height}
    viewBox="0 0 {layout.width} {layout.height}"
    role="img"
    aria-label={label}
  >
    {#if layout.tier === 'plain'}
      <path fill={ink.air} fill-opacity={BRAND.airOpacity} fill-rule="evenodd" d={all} />
    {:else}
      <defs>
        <clipPath id="{id}-air">
          <rect x="-1" y="-1" width={layout.width + 2} height={layout.surfaceY + 1} />
        </clipPath>
        <clipPath id="{id}-beneath">
          <rect x="-1" y={layout.surfaceY} width={layout.width + 2} height={layout.height - layout.surfaceY + 1} />
        </clipPath>
      </defs>

      <!-- Above the surface: never moves. -->
      <g opacity={BRAND.airOpacity}>
        {#if layout.edge}
          <g clip-path="url(#{id}-air)">
            <path
              fill={ink.edge}
              fill-rule="evenodd"
              transform="translate({layout.edge} {layout.edge})"
              d={all}
            />
          </g>
        {/if}
        <g clip-path="url(#{id}-air)">
          <path fill={ink.air} fill-rule="evenodd" d={all} />
        </g>
      </g>

      <!-- Beneath: per letter, so the reveal can reach each one in turn. -->
      {#each layout.letters as letter, index}
        <g
          class="beneath"
          class:animate={phase === 'moving'}
          style="--delay: {delays[index]}ms; transform: translateX({shifted ? layout.shift : 0}px); opacity: {shifted
            ? BRAND.beneathOpacity
            : BRAND.airOpacity}"
        >
          <g clip-path="url(#{id}-beneath)">
            <path fill-rule="evenodd" style="fill: {shifted ? ink.beneath : ink.air}" d={letter.path} />
          </g>
        </g>
      {/each}
    {/if}
  </svg>
</span>

<style>
  .wordmark {
    position: relative;
    display: inline-block;
    flex-shrink: 0;
    line-height: 0;
  }

  svg {
    position: relative;
    display: block;
    overflow: visible;
  }

  /* The displacement is a jump at the moment the pane arrives: a zero-length
     transition with a delay, so the band is never between two pixels. Only the
     density eases. */
  .beneath.animate {
    transition:
      transform 0s linear var(--delay),
      opacity var(--fade) var(--ease-standard) var(--delay);
  }

  .beneath.animate path {
    transition: fill var(--fade) var(--ease-standard) var(--delay);
  }

  .fading {
    animation: wordmark-fade var(--fade) var(--ease-standard) both;
  }

  @keyframes wordmark-fade {
    from {
      opacity: 0;
    }
    to {
      opacity: 1;
    }
  }

  /* The hero veil: a broad, feathered deepening centred on the letters. No
     edge, no glow -- it is darker than the photograph, never lighter. */
  .veil::before {
    content: '';
    position: absolute;
    inset: -90% -35%;
    background: radial-gradient(closest-side, var(--brand-veil), transparent);
    pointer-events: none;
  }
</style>

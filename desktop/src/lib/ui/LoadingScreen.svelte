<!--
  The loading screen, while the launcher gathers what Home needs.

  It continues the static splash in `index.html` exactly -- the icon's ground
  and halo, the same mark and wordmark, the same sweeping line -- so the hand-over from HTML to Svelte is
  invisible, and adds one line saying what is still being waited for. That
  line names the slowest thing still outstanding, not a script of fake steps:
  if Java is being looked for, it says so.

  It sits over the shell rather than instead of it, so everything behind it is
  already laid out, decoding its background and ready the moment it fades.
-->
<script lang="ts">
  import { fade } from 'svelte/transition';
  import Wordmark from '../brand/Wordmark.svelte';
  import OrbitMark from '../brand/OrbitMark.svelte';
  import { BRAND } from '../brand/brand.generated';

  const ASCENDER = 18;

  /** What is still being loaded, most important first. Empty when done. */
  export let pending: string[] = [];
  export let visible = true;

  $: step = pending[0] ?? '';
</script>

{#if visible}
  <div class="loading" role="status" aria-live="polite" out:fade={{ duration: 260 }}>
    <div class="mark">
      <div class="lockup">
        <OrbitMark height={ASCENDER * BRAND.stackedMark} halo decorative />
        <Wordmark height={ASCENDER} />
      </div>
      <div class="line" aria-hidden="true"></div>
      <p class="step">{step}</p>
    </div>
  </div>
{/if}

<style>
  /* Kept in step with `#splash` in index.html. */
  .loading {
    position: fixed;
    inset: 0;
    z-index: 900;
    display: grid;
    place-items: center;
    background:
      radial-gradient(ellipse 460px 340px at 50% 47%, rgba(76, 154, 251, 0.1), rgba(76, 154, 251, 0) 70%),
      linear-gradient(180deg, var(--brand-tile-top) 0%, var(--brand-tile-mid) 48%, var(--brand-tile-bottom) 100%);
    color: var(--color-text);
  }

  .mark {
    display: grid;
    justify-items: center;
    gap: 22px;
  }

  .lockup {
    display: grid;
    justify-items: center;
    gap: calc(18px * var(--brand-stacked-gap));
  }

  .line {
    position: relative;
    width: 132px;
    height: 2px;
    overflow: hidden;
    border-radius: var(--radius-pill);
    background: rgba(165, 214, 255, 0.12);
  }

  .line::after {
    content: '';
    position: absolute;
    inset: 0;
    width: 40%;
    border-radius: inherit;
    background: linear-gradient(90deg, rgba(91, 156, 235, 0), var(--brand-orbit-light) 60%, var(--brand-orbit-tip));
    animation: sweep 1.1s cubic-bezier(0.4, 0, 0.2, 1) infinite;
  }

  @keyframes sweep {
    from {
      transform: translateX(-100%);
    }
    to {
      transform: translateX(250%);
    }
  }

  @media (prefers-reduced-motion: reduce) {
    .line::after {
      animation-duration: 2.4s;
    }
  }

  /* Reserved height, so the mark does not jump when the line appears. */
  .step {
    min-height: calc(var(--type-caption-size) * 1.4);
    margin: -8px 0 0;
    font-size: var(--type-caption-size);
    color: var(--color-text-secondary);
  }
</style>

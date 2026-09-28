<!--
  The loading screen, while the launcher gathers what Home needs.

  It continues the static splash in `index.html` exactly -- same field, same
  wordmark, same sweeping line -- so the hand-over from HTML to Svelte is
  invisible, and adds one line saying what is still being waited for. That
  line names the slowest thing still outstanding, not a script of fake steps:
  if Java is being looked for, it says so.

  It sits over the shell rather than instead of it, so everything behind it is
  already laid out, decoding its background and ready the moment it fades.
-->
<script lang="ts">
  import { fade } from 'svelte/transition';
  import GraphiteWordmark from '../graphite/GraphiteWordmark.svelte';

  /** What is still being loaded, most important first. Empty when done. */
  export let pending: string[] = [];
  export let visible = true;

  $: step = pending[0] ?? '';
</script>

{#if visible}
  <div class="loading" role="status" aria-live="polite" out:fade={{ duration: 260 }}>
    <div class="mark">
      <GraphiteWordmark height={18} />
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
    background: linear-gradient(180deg, #172630 0%, #0d181e 56%, #071015 100%);
    color: var(--color-text);
  }

  .mark {
    display: grid;
    justify-items: center;
    gap: 22px;
  }

  .mark :global(svg) {
    opacity: 0.94;
  }

  .line {
    position: relative;
    width: 132px;
    height: 2px;
    overflow: hidden;
    border-radius: var(--radius-pill);
    background: rgba(255, 255, 255, 0.1);
  }

  .line::after {
    content: '';
    position: absolute;
    inset: 0;
    width: 40%;
    border-radius: inherit;
    background: rgba(237, 237, 237, 0.78);
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

<!--
  A segmented control.

  The selection is a single element that SLIDES between segments rather than a
  background colour that switches on the newly-selected one. That distinction is
  the entire reason this component exists instead of four buttons: a highlight
  that moves is one object changing position, which the eye tracks, and a
  highlight that teleports is two objects changing colour, which it does not.

  The moving element is positioned from a measured segment rather than from
  `width / count`, because the segments are sized by their labels — "Max FPS" is
  wider than "Custom" — and an even split would leave the indicator increasingly
  wrong as the labels diverge.
-->
<script lang="ts">
  import { onMount, tick } from 'svelte';

  export let options: { id: string; label: string }[] = [];
  export let value: string;
  export let disabled = false;
  export let onChange: (id: string) => void;

  let track: HTMLDivElement | null = null;
  let buttons: HTMLButtonElement[] = [];
  let indicator = { left: 0, width: 0 };
  /* The first paint must not animate: an indicator sliding in from x=0 on
     mount looks like a bug rather than a transition. */
  let ready = false;

  async function measure() {
    await tick();
    const index = options.findIndex((option) => option.id === value);
    const element = buttons[index < 0 ? 0 : index];
    if (!element || !track) return;
    indicator = {
      left: element.offsetLeft,
      width: element.offsetWidth,
    };
  }

  onMount(() => {
    void (async () => {
      await measure();
      // A frame later, so the position above is painted before transitions
      // are allowed to apply to it.
      requestAnimationFrame(() => (ready = true));
    })();

    // Labels reflow when the window resizes, and an indicator measured at one
    // width is wrong at another.
    const observer = new ResizeObserver(() => void measure());
    if (track) observer.observe(track);
    return () => observer.disconnect();
  });

  $: if (value || options) void measure();
</script>

<div class="track" bind:this={track} role="tablist">
  <span
    class="indicator"
    class:ready
    style="transform: translateX({indicator.left}px); width: {indicator.width}px"
  ></span>

  {#each options as option, index}
    <button
      bind:this={buttons[index]}
      class="segment"
      class:selected={option.id === value}
      role="tab"
      aria-selected={option.id === value}
      {disabled}
      on:click={() => !disabled && option.id !== value && onChange(option.id)}
    >
      {option.label}
    </button>
  {/each}
</div>

<style>
  .track {
    position: relative;
    display: inline-flex;
    align-items: center;
    gap: var(--s-1);
    padding: 3px;
    border-radius: var(--r-md);
    background: var(--fill-sunken);
    border: 1px solid var(--chrome-line);
  }

  .indicator {
    position: absolute;
    top: 3px;
    bottom: 3px;
    left: 0;
    border-radius: var(--r-sm);
    /* The selected segment is the one place a small amount of accent goes a
       long way: a filled red pill would shout, a red-tinted surface with a lit
       edge reads as "this one". */
    background: linear-gradient(180deg, rgba(224, 80, 92, 0.30), rgba(132, 28, 36, 0.30));
    border: 1px solid rgba(224, 80, 92, 0.42);
    box-shadow:
      inset 0 1px 0 rgba(255, 255, 255, 0.12),
      0 2px 10px rgba(0, 0, 0, 0.35);
    pointer-events: none;
  }

  .indicator.ready {
    transition:
      transform var(--m-normal) var(--ease),
      width var(--m-normal) var(--ease);
  }

  .segment {
    position: relative;
    z-index: 1;
    padding: 0 14px;
    height: 28px;
    border-radius: var(--r-sm);
    font-size: var(--t-body);
    font-weight: var(--w-medium);
    color: var(--text-muted);
    white-space: nowrap;
    transition: color var(--m-fast) var(--ease);
  }

  .segment:hover:not(:disabled):not(.selected) {
    color: var(--text);
  }

  .segment.selected {
    color: var(--text);
    font-weight: var(--w-semibold);
  }

  .segment:disabled {
    cursor: default;
    opacity: 0.5;
  }
</style>

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
  /* Sized by its options, never stretched by a column parent: a stretched
     track leaves an empty length of glass past the last option. */
  .track {
    position: relative;
    display: inline-flex;
    width: fit-content;
    max-width: 100%;
    align-items: center;
    gap: 0;
    height: var(--metric-segmented);
    padding: var(--metric-segment-inset);
    border-radius: var(--metric-radius-control);
    background-color: var(--control-fill-faint);
    box-shadow: var(--control-rim);
  }

  /* The selection is a denser neutral overlay inside the light track, with a
     stronger rim and full-strength type. No colour, no shadow. */
  /* Filled, not outlined: a rim here would sit two pixels inside the track's
     own rim and read as a double border. Selection is density and type. */
  .indicator {
    position: absolute;
    top: var(--metric-segment-inset);
    bottom: var(--metric-segment-inset);
    left: 0;
    border-radius: var(--metric-radius-small);
    background-color: var(--control-fill-selected);
    pointer-events: none;
  }

  .indicator.ready {
    transition:
      transform var(--motion-state) var(--ease-standard),
      width var(--motion-state) var(--ease-standard);
  }

  .segment {
    position: relative;
    z-index: 1;
    height: calc(var(--metric-segmented) - 2 * var(--metric-segment-inset));
    padding: 0 var(--space-4);
    border-radius: var(--metric-radius-small);
    font-size: var(--type-secondary-size);
    font-weight: var(--type-button-weight);
    color: var(--color-text-secondary);
    white-space: nowrap;
    transition:
      color var(--motion-hover) var(--ease-standard),
      background-color var(--motion-hover) var(--ease-standard);
  }

  .segment:hover:not(:disabled):not(.selected) {
    color: var(--color-text);
    background-color: var(--control-fill-faint);
  }

  .segment.selected {
    color: var(--color-text);
  }

  .segment:focus-visible {
    outline: var(--focus-ring-width) solid var(--color-focus);
    outline-offset: -1px;
  }

  .segment:disabled {
    cursor: default;
    color: var(--color-text-disabled);
  }
</style>

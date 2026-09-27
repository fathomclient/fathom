<!--
  Choosing a background.

  ONE component, two sizes. The bottom bar shows it inside a popover at
  `compact`; Settings → Appearance shows the same thing at `large`. They were
  previously two separate implementations that drifted, which is the specific
  problem this milestone exists to remove.

  Thumbnails are the approved images themselves, so what you see here is the
  background you are choosing, not an illustration of it.

  Not shown while there is only one approved background (`PICKER_AVAILABLE`);
  it is kept so a small curated set can be offered again without new work.
-->
<script lang="ts">
  import { fade } from 'svelte/transition';
  import { Check } from '@lucide/svelte';
  import { FAST, EASE } from '../motion';
  import { imageFor, type BackgroundOption } from '../backgrounds';

  export let options: BackgroundOption[] = [];
  export let value: string;
  export let size: 'compact' | 'large' = 'compact';
  export let onChoose: (id: string) => void;

  /* Shown here rather than only in Settings, because this is where someone
     else's photograph is actually chosen. */
  $: credit = options.find((option) => option.id === value)?.credit ?? '';
</script>

<div class="grid" class:large={size === 'large'}>
  {#each options as option}
    <button
      class="item"
      class:selected={option.id === value}
      aria-pressed={option.id === value}
      on:click={() => onChoose(option.id)}
    >
      <!-- The image sits over the warm fallback, so a thumbnail whose file is
           missing shows the same substrate the background itself would, rather
           than an empty tile. -->
      <span
        class="thumb"
        style={`background-image:url(${imageFor(option.id)}), var(--backdrop-fallback)`}
      >
        {#if option.id === value}
          <span class="tick" transition:fade={{ duration: FAST, easing: EASE }}>
            <Check size={size === 'large' ? 13 : 11} />
          </span>
        {/if}
      </span>
      <span class="label">{option.label}</span>
    </button>
  {/each}
</div>

{#if credit}
  <p class="credit" class:large={size === 'large'} transition:fade={{ duration: FAST, easing: EASE }}>
    {credit}
  </p>
{/if}

<style>
  .grid {
    display: grid;
    grid-template-columns: repeat(2, 1fr);
    gap: var(--s-2);
  }

  .grid.large {
    grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
    gap: var(--s-3);
  }

  .item {
    display: flex;
    flex-direction: column;
    gap: var(--s-1);
    text-align: left;
  }

  .thumb {
    position: relative;
    display: block;
    aspect-ratio: 16 / 9;
    border-radius: var(--radius-sm);
    background: var(--backdrop-fallback);
    background-size: cover;
    background-position: center;
    box-shadow: var(--control-rim);
    transition: box-shadow var(--motion-hover) var(--ease-standard);
  }

  .large .thumb {
    border-radius: var(--radius-md);
  }

  /* Hover brightens the rim; nothing scales. Selected is the strong rim and a
     check -- neutral, never the old accent. */
  .item:hover .thumb {
    box-shadow: var(--control-rim-strong);
  }

  .item.selected .thumb {
    box-shadow:
      var(--control-rim-strong),
      0 0 0 2px var(--color-text);
  }

  .item:focus-visible {
    outline: none;
  }

  .item:focus-visible .thumb {
    outline: var(--focus-ring-width) solid var(--color-focus);
    outline-offset: 3px;
  }

  .tick {
    position: absolute;
    top: var(--space-1);
    right: var(--space-1);
    display: grid;
    place-items: center;
    width: 18px;
    height: 18px;
    border-radius: var(--radius-pill);
    background: var(--control-prominent);
    color: var(--color-prominent-text);
  }

  .large .tick {
    width: 20px;
    height: 20px;
  }

  .label {
    font-size: var(--t-meta);
    color: var(--text-muted);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .large .label {
    font-size: var(--t-caption);
  }

  .item.selected .label {
    color: var(--text);
  }

  .credit {
    margin: var(--s-2) 0 0;
    font-size: var(--t-meta);
    color: var(--text-faint);
    line-height: 1.4;
  }

  .credit.large {
    margin-top: var(--s-3);
    font-size: var(--t-caption);
  }
</style>

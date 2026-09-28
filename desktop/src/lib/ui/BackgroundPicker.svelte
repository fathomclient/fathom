<!--
  Choosing a background.

  ONE component, two sizes. The bottom bar shows it inside a popover at
  `compact`; Settings -> Appearance shows the same thing at `large`.

  A simple grid, not a gallery: each tile is the background's own thumbnail,
  its name, and -- for a moving one -- a small video mark. Clicking applies it
  at once (the window behind is the preview) and the choice is saved; there is
  no separate confirm step to forget.

  Selection follows the control system's rule: a brighter rim and a check,
  never a thicker outline.
-->
<script lang="ts">
  import { fade } from 'svelte/transition';
  import { Check, Film } from '@lucide/svelte';
  import { FAST, EASE } from '../motion';
  import { ICON } from '../metrics.generated';
  import type { BackgroundOption } from '../backgrounds';

  export let options: BackgroundOption[] = [];
  export let value: string;
  export let size: 'compact' | 'large' = 'compact';
  export let onChoose: (id: string) => void;

  /* Shown here rather than only in Settings, because this is where someone
     else's recording is actually chosen. */
  $: credit = options.find((option) => option.id === value)?.credit ?? '';
</script>

<div class="grid" class:large={size === 'large'} role="radiogroup" aria-label="Background">
  {#each options as option (option.id)}
    <button
      class="item"
      class:selected={option.id === value}
      role="radio"
      aria-checked={option.id === value}
      style={`--own-glow: ${option.accent}`}
      on:click={() => onChoose(option.id)}
    >
      <!-- The thumbnail sits over the fallback field, so a missing file shows
           the same substrate the background itself would. -->
      <span class="thumb" style={`background-image:url(${option.thumbnail}), var(--backdrop-fallback)`}>
        {#if option.type === 'video'}
          <span class="kind" title="Moving background"><Film size={ICON.caption} /></span>
        {/if}
        {#if option.id === value}
          <span class="tick" transition:fade={{ duration: FAST, easing: EASE }}>
            <Check size={ICON.caption} />
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
    gap: var(--space-2);
  }

  .grid.large {
    grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
    gap: var(--space-3);
  }

  .item {
    display: flex;
    flex-direction: column;
    gap: var(--space-2);
    min-width: 0;
    text-align: left;
  }

  .thumb {
    position: relative;
    display: block;
    aspect-ratio: 16 / 9;
    border-radius: var(--metric-radius-small);
    background: var(--backdrop-fallback);
    background-size: cover;
    background-position: center;
    box-shadow: var(--control-rim);
    transition: box-shadow var(--motion-hover) var(--ease-standard);
  }

  /* Hover and selection brighten the one rim; neither changes its width.
     Each thumbnail glows in its own accent -- the light the interface will
     take if you choose it: a hint on hover, a soft halo once chosen. */
  .item:hover .thumb {
    box-shadow:
      var(--control-rim-strong),
      0 6px 18px -8px color-mix(in srgb, var(--own-glow) calc(var(--g-glow-selected-alpha) * 150%), transparent);
  }

  .item.selected .thumb {
    box-shadow:
      var(--control-rim-strong),
      0 8px 22px -8px color-mix(in srgb, var(--own-glow) calc(var(--g-glow-halo-alpha) * 120%), transparent);
  }

  .item:focus-visible {
    outline: none;
  }

  .item:focus-visible .thumb {
    outline: var(--focus-ring-width) solid var(--color-focus);
    outline-offset: 2px;
  }

  .kind,
  .tick {
    position: absolute;
    top: var(--space-1);
    display: grid;
    place-items: center;
    width: 20px;
    height: 20px;
    border-radius: var(--radius-pill);
  }

  .kind {
    left: var(--space-1);
    background: var(--mat-dense-fill);
    color: var(--color-text-secondary);
  }

  .tick {
    right: var(--space-1);
    background: var(--control-prominent);
    color: var(--color-prominent-text);
  }

  .label {
    font-size: var(--type-caption-size);
    font-weight: var(--type-label-weight);
    color: var(--color-text-secondary);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .large .label {
    font-size: var(--type-secondary-size);
  }

  .item.selected .label {
    color: var(--color-text);
  }

  .credit {
    margin: var(--space-2) 0 0;
    font-size: var(--type-caption-size);
    color: var(--color-text-tertiary);
    line-height: var(--type-caption-leading);
  }

  .credit.large {
    margin-top: var(--space-3);
  }
</style>

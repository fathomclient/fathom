<!--
  Choosing a background.

  ONE component, two sizes. The bottom bar shows it inside a popover at
  `compact`; Settings → Appearance shows the same thing at `large`. They were
  previously two separate implementations that drifted, which is the specific
  problem this milestone exists to remove.

  Thumbnails are the real poster frames — `tools/prepare-background-video.ps1`
  writes a JPEG beside every WebM from the same graded, defocused source, so what
  you see here is a frame of the clip you are choosing, not an illustration of
  it.
-->
<script lang="ts">
  import { fade } from 'svelte/transition';
  import { Check } from '@lucide/svelte';
  import { FAST, EASE } from '../motion';
  import { creditFor } from '../credits';

  export let options: { id: string; label: string }[] = [];
  export let value: string;
  export let size: 'compact' | 'large' = 'compact';
  export let onChoose: (id: string) => void;

  /* Shown here rather than only in Settings, because this is where footage
     recorded in someone else's world is actually chosen. */
  $: credit = creditFor(value);
</script>

<div class="grid" class:large={size === 'large'}>
  {#each options as option}
    <button
      class="item"
      class:selected={option.id === value}
      aria-pressed={option.id === value}
      on:click={() => onChoose(option.id)}
    >
      <span
        class="thumb"
        class:midnight={option.id === 'static'}
        style={option.id === 'static'
          ? ''
          : `background-image:url(/backgrounds/${option.id}.jpg)`}
      >
        {#if option.id === value}
          <span class="tick" transition:fade={{ duration: FAST, easing: EASE }}>
            <Check size={size === 'large' ? 13 : 11} strokeWidth={3} />
          </span>
        {/if}
      </span>
      <span class="label">{option.label}</span>
    </button>
  {/each}
</div>

{#if credit}
  <p class="credit" class:large={size === 'large'} transition:fade={{ duration: FAST, easing: EASE }}>
    Recorded in {credit.world}, a world by {credit.creator}
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
    border-radius: var(--r-sm);
    background-size: cover;
    background-position: center;
    border: 1px solid var(--chrome-line-strong);
    box-shadow: 0 2px 10px rgba(0, 0, 0, 0.45);
    transition:
      transform var(--m-fast) var(--ease),
      border-color var(--m-normal) var(--ease),
      box-shadow var(--m-normal) var(--ease);
  }

  .large .thumb {
    border-radius: var(--r-md);
  }

  /* Scales from the bottom edge, so a hovered tile grows upward into the
     popover rather than pushing against the row below it. */
  .item:hover .thumb {
    transform: scale(1.035);
    border-color: rgba(255, 255, 255, 0.28);
  }

  .item.selected .thumb {
    border-color: var(--accent-strong);
    box-shadow:
      0 0 0 1px rgba(224, 80, 92, 0.35),
      0 4px 16px rgba(0, 0, 0, 0.5),
      0 0 20px var(--accent-glow);
  }

  .thumb.midnight {
    background: linear-gradient(160deg, var(--midnight-top), var(--midnight-bottom));
  }

  .tick {
    position: absolute;
    top: var(--s-1);
    right: var(--s-1);
    display: grid;
    place-items: center;
    width: 17px;
    height: 17px;
    border-radius: var(--r-pill);
    background: var(--accent);
    color: #fff;
    box-shadow: 0 1px 5px rgba(0, 0, 0, 0.55);
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

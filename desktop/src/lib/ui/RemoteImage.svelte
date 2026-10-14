<!--
  An image from a mod index, loaded through Rust when it scrolls into view.

  Until it arrives (and if it never does) the slot shows a quiet fallback: the
  mod's initial for an icon, the caption for a screenshot. Nothing here fetches
  anything itself; see `lib/catalogImages.ts`.
-->
<script lang="ts">
  import { onMount } from 'svelte';
  import { fade } from 'svelte/transition';
  import type { CatalogProvider } from '../api';
  import { loadImage } from '../catalogImages';
  import { MOTION_HOVER } from '../motion';

  export let provider: CatalogProvider;
  export let url: string | null;
  export let alt = '';
  /** Shown while loading and when there is no image. */
  export let fallback = '';
  /** `icon` is a square tile; `shot` fills its box and keeps its ratio. */
  export let kind: 'icon' | 'shot' = 'icon';

  let element: HTMLElement;
  let source: string | null = null;
  let failed = false;
  let visible = false;

  onMount(() => {
    if (typeof IntersectionObserver === 'undefined') {
      visible = true;
      return;
    }
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting)) {
          visible = true;
          observer.disconnect();
        }
      },
      { rootMargin: '200px' },
    );
    observer.observe(element);
    return () => observer.disconnect();
  });

  let requested: string | null = null;
  $: if (visible && url && url !== requested) {
    requested = url;
    const wanted = url;
    source = null;
    failed = false;
    void loadImage(provider, wanted).then((result) => {
      if (requested !== wanted) return;
      source = result;
      failed = result === null;
    });
  }
</script>

<span class="frame {kind}" class:empty={!source} bind:this={element}>
  {#if source}
    <img src={source} {alt} draggable="false" in:fade={{ duration: MOTION_HOVER }} />
  {:else if kind === 'icon'}
    <span class="initial" aria-hidden="true">{fallback}</span>
  {:else if failed || !url}
    <span class="caption">{fallback || alt}</span>
  {/if}
</span>

<style>
  .frame {
    position: relative;
    display: grid;
    place-items: center;
    overflow: hidden;
    background-color: var(--control-fill-selected);
    box-shadow: var(--control-rim);
  }

  .icon {
    width: var(--image-size, var(--metric-tile));
    height: var(--image-size, var(--metric-tile));
    flex: none;
    border-radius: var(--metric-radius-small);
  }

  .shot {
    width: 100%;
    height: 100%;
    border-radius: var(--radius-md);
    background-color: var(--control-fill-sunken);
  }

  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
    user-select: none;
  }

  .icon img {
    /* Pixel art is the norm for Minecraft mod icons. */
    image-rendering: pixelated;
    object-fit: contain;
  }

  .initial {
    font-size: var(--type-body-size);
    font-weight: var(--type-heading-weight);
    color: var(--color-text-secondary);
  }

  .caption {
    padding: var(--space-3);
    font-size: var(--type-caption-size);
    line-height: var(--type-secondary-leading);
    color: var(--color-text-tertiary);
    text-align: center;
  }
</style>

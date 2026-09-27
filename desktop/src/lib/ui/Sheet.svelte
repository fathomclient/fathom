<!--
  A modal sheet.

  The one modal surface: a pane of dense glass that slides in from the right
  edge and floats inset from it, over a light warm scrim. Account, new
  installation and any future dialog render through this, so a modal is always
  the same object, closes the same way, and sits at the same depth.

  The scrim only deepens the window a little. Enough that the sheet is plainly
  in front, not so much that the photograph behind stops being the palette.

  Closing: the X, a click on the scrim, or Escape. The body scrolls on its own;
  the header and an optional `footer` slot stay put.
-->
<script lang="ts">
  import { fade, fly } from 'svelte/transition';
  import { X } from '@lucide/svelte';
  import { EASE, EASE_EXIT, MOTION_EXIT, MOTION_SURFACE } from '../motion';
  import { tooltip } from '../tooltip';

  export let title: string;
  export let onClose: () => void;
  /** The sheet's width in px, capped by the window. */
  export let width = 380;
  /** Remove the body's own padding, for content that manages its own. */
  export let flush = false;

  const titleId = `sheet-${Math.random().toString(36).slice(2, 9)}`;

  function onKey(event: KeyboardEvent) {
    if (event.key === 'Escape') onClose();
  }
</script>

<svelte:window on:keydown={onKey} />

<div
  class="scrim"
  role="presentation"
  on:click={onClose}
  in:fade={{ duration: MOTION_SURFACE, easing: EASE }}
  out:fade={{ duration: MOTION_EXIT, easing: EASE_EXIT }}
></div>

<div
  class="sheet material-dense"
  role="dialog"
  aria-modal="true"
  aria-labelledby={titleId}
  style="--sheet-width: {width}px"
  in:fly={{ x: 8, duration: MOTION_SURFACE, easing: EASE }}
  out:fade={{ duration: MOTION_EXIT, easing: EASE_EXIT }}
>
  <header>
    <h2 id={titleId}>{title}</h2>
    <button class="ui-icon-button small" aria-label="Close" use:tooltip={'Close'} on:click={onClose}>
      <X size={16} />
    </button>
  </header>

  <div class="body" class:flush>
    <slot />
  </div>

  {#if $$slots.footer}
    <footer>
      <slot name="footer" />
    </footer>
  {/if}
</div>

<style>
  .scrim {
    position: fixed;
    inset: 0;
    z-index: 40;
    background: var(--backdrop-modal-scrim);
  }

  .sheet {
    --surface-radius: var(--radius-xl);
    --surface-inset: var(--space-5);
    position: fixed;
    top: var(--space-3);
    right: var(--space-3);
    bottom: var(--space-3);
    z-index: 41;
    display: flex;
    flex-direction: column;
    width: min(var(--sheet-width), calc(100vw - var(--space-6)));
    overflow: hidden;
  }

  header {
    display: flex;
    align-items: center;
    gap: var(--space-3);
    padding: var(--space-5) var(--space-4) var(--space-3) var(--space-5);
  }

  h2 {
    flex: 1;
    min-width: 0;
    margin: 0;
    font-size: var(--type-heading-size);
    font-weight: var(--type-heading-weight);
    letter-spacing: var(--type-heading-tracking);
    line-height: var(--type-heading-leading);
  }

  .body {
    flex: 1;
    min-height: 0;
    display: flex;
    flex-direction: column;
    gap: var(--space-4);
    padding: var(--space-2) var(--space-5) var(--space-5);
    overflow-y: auto;
    overscroll-behavior: contain;
  }

  .body.flush {
    padding: 0;
    gap: 0;
  }

  footer {
    display: flex;
    flex-direction: column;
    gap: var(--space-2);
    padding: var(--space-4) var(--space-5) var(--space-5);
    box-shadow: 0 calc(-1 * var(--hairline)) 0 var(--control-hairline);
  }
</style>

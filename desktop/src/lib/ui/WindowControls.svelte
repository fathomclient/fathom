<!--
  Minimize, maximize and close.

  One component for every page, so the window's own controls cannot drift
  between Home and the document pages. 32px targets with 16px glyphs in the
  shared icon weight; hover is the same neutral veil as any icon button. Close
  alone warms towards the danger tone, which is the one place the platform's
  own convention (a red close) is worth echoing -- quietly.
-->
<script lang="ts">
  import { ICON } from '../metrics.generated';
  import { Minus, Square, X } from '@lucide/svelte';
  import { tooltip } from '../tooltip';

  export let onMinimize: () => void;
  export let onMaximize: () => void;
  export let onClose: () => void;
</script>

<div class="window-controls">
  <button class="control" aria-label="Minimize" use:tooltip={'Minimize'} on:click={onMinimize}>
    <Minus size={ICON.inline} />
  </button>
  <button class="control" aria-label="Maximize" use:tooltip={'Maximize'} on:click={onMaximize}>
    <!-- Lucide's square is drawn at 18 units; at 14px it matches the optical
         size of the minus and the cross beside it. -->
    <Square size={ICON.inline} />
  </button>
  <button class="control close" aria-label="Close" use:tooltip={'Close'} on:click={onClose}>
    <X size={ICON.inline} />
  </button>
</div>

<style>
  .window-controls {
    display: flex;
    align-items: center;
    gap: 2px;
  }

  .control {
    display: grid;
    place-items: center;
    width: var(--metric-control-compact);
    height: var(--metric-control-compact);
    border-radius: var(--metric-radius-control);
    color: var(--color-text-secondary);
    transition:
      color var(--motion-hover) var(--ease-standard),
      background-color var(--motion-hover) var(--ease-standard);
  }

  .control:hover {
    color: var(--color-text);
    background-color: var(--control-fill-hover);
  }

  .control:active {
    background-color: var(--control-fill-selected);
  }

  .control.close:hover {
    color: var(--color-danger-text);
    background-color: var(--color-danger-tint);
  }

  .control:focus-visible {
    outline: var(--focus-ring-width) solid var(--color-focus);
    outline-offset: 1px;
  }
</style>

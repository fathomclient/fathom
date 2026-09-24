<!--
  A notice: status that stays on the surface where it happened.

  Fathom has no toast system on purpose -- an outcome that vanishes before it is
  read is not reported -- so this is the notification surface. It sits inline,
  in the flow of the page or sheet it concerns, and stays until the state it
  describes changes or it is dismissed.

  Tones are the only place a hue carries meaning: `neutral` is plain light
  glass, and `success`, `warning` and `danger` are a wash and a rim in their
  status colour. `floating` lifts it onto the photograph as its own pane, for
  status that has no page to sit in.
-->
<script lang="ts">
  import { X } from '@lucide/svelte';

  export let tone: 'neutral' | 'success' | 'warning' | 'danger' = 'neutral';
  export let floating = false;
  /** Shows a dismiss control when set. */
  export let onDismiss: (() => void) | null = null;
</script>

<div
  class="notice"
  class:tone-success={tone === 'success'}
  class:tone-warning={tone === 'warning'}
  class:tone-danger={tone === 'danger'}
  class:neutral={tone === 'neutral'}
  class:material-standard={floating}
  role={tone === 'danger' || tone === 'warning' ? 'alert' : 'status'}
>
  <div class="content"><slot /></div>
  {#if onDismiss}
    <button class="ui-icon-button small" aria-label="Dismiss" on:click={onDismiss}>
      <X size={14} strokeWidth={2.2} />
    </button>
  {/if}
</div>

<style>
  .notice {
    --surface-radius: var(--radius-md);
    display: flex;
    align-items: flex-start;
    gap: var(--space-2);
    padding: var(--space-3) var(--space-4);
    border-radius: var(--radius-md);
    font-size: var(--type-secondary-size);
    line-height: var(--type-secondary-leading);
  }

  .notice.neutral:not(.material-standard) {
    background-color: var(--control-fill);
    box-shadow: var(--control-rim);
    color: var(--color-text-secondary);
  }

  .content {
    flex: 1;
    min-width: 0;
    overflow-wrap: anywhere;
  }

  .notice .ui-icon-button {
    margin: -4px -8px -4px 0;
    color: inherit;
  }
</style>

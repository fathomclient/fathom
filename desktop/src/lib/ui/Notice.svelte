<!--
  A notice: status that stays on the surface where it happened.

  Fathom has no toast system on purpose -- an outcome that vanishes before it is
  read is not reported -- so this is the notification surface. It sits inline,
  in the flow of the page or sheet it concerns, and stays until the state it
  describes changes or it is dismissed.

  The surface is always neutral glass. A tone is a small icon in its status
  colour and nothing more: a panel is never washed green, amber or red, because
  status colour is for communication, not for decoration, and a coloured slab
  says "something is wrong" louder than the words inside it can say what.

    neutral  an info glyph, or none with `icon={false}`
    success  a check in sage
    warning  a triangle in amber
    danger   a circle-alert in terracotta

  `floating` adds the pane's shadow, for status that has no page to sit in.
-->
<script lang="ts">
  import { ICON } from '../metrics.generated';
  import type { Component } from 'svelte';
  import { CircleAlert, CircleCheck, Info, TriangleAlert, X, type IconProps } from '@lucide/svelte';
  import { tooltip } from '../tooltip';

  export let tone: 'neutral' | 'success' | 'warning' | 'danger' = 'neutral';
  export let floating = false;
  /** Show the tone's glyph. Neutral notices that are plain explanation can drop it. */
  export let icon = true;
  /** A more specific glyph than the tone's own, such as a Wi-Fi-off mark. */
  export let glyph: Component<IconProps> | null = null;
  /** Shows a dismiss control when set. */
  export let onDismiss: (() => void) | null = null;

  $: shown =
    glyph ??
    (tone === 'success' ? CircleCheck : tone === 'warning' ? TriangleAlert : tone === 'danger' ? CircleAlert : Info);
</script>

<div
  class="notice {tone}"
  class:material-standard={floating}
  role={tone === 'danger' || tone === 'warning' ? 'alert' : 'status'}
>
  {#if icon}
    <span class="glyph" aria-hidden="true"><svelte:component this={shown} size={ICON.inline} /></span>
  {/if}
  <div class="content"><slot /></div>
  {#if onDismiss}
    <button class="ui-icon-button small" aria-label="Dismiss" use:tooltip={'Dismiss'} on:click={onDismiss}>
      <X size={ICON.inline} />
    </button>
  {/if}
</div>

<style>
  /* Standard glass when it sits on the photograph, so its text holds over
     bright sky. */
  .notice {
    --surface-radius: var(--radius-md);
    display: flex;
    align-items: flex-start;
    gap: var(--space-3);
    padding: var(--space-3) var(--space-4);
    border-radius: var(--radius-md);
    background-color: var(--mat-standard-fill);
    -webkit-backdrop-filter: var(--mat-standard-filter);
    backdrop-filter: var(--mat-standard-filter);
    box-shadow: var(--mat-standard-rim);
    color: var(--color-text-secondary);
    font-size: var(--type-secondary-size);
    line-height: var(--type-secondary-leading);
  }

  .notice.material-standard {
    box-shadow: var(--mat-standard-rim), var(--mat-standard-shadow);
  }

  /* Inside a pane that is already glass -- a sheet, a popover, a section -- a
     second blur would sample the first one's blur for nothing. The notice goes
     flat there: a faint control fill and rim. */
  :global(:is(.material-light, .material-standard, .material-dense, .ui-popover)) .notice {
    background-color: var(--control-fill-faint);
    -webkit-backdrop-filter: none;
    backdrop-filter: none;
    box-shadow: var(--control-rim);
  }

  /* Centred on the first line of text: 16px glyph, 13px type at 1.45. */
  .glyph {
    display: grid;
    place-items: center;
    height: 19px;
    flex-shrink: 0;
    color: var(--color-text-tertiary);
  }

  .success .glyph {
    color: var(--color-success-text);
  }

  .warning .glyph {
    color: var(--color-warning-text);
  }

  .danger .glyph {
    color: var(--color-danger-text);
  }

  /* A danger or warning notice leads with its first line at full strength:
     what failed. The rest is the explanation and the way out. */
  .warning .content,
  .danger .content {
    color: var(--color-text);
  }

  .content {
    flex: 1;
    min-width: 0;
    overflow-wrap: anywhere;
  }

  .notice .ui-icon-button {
    margin: -5px -8px -5px 0;
    color: var(--color-text-tertiary);
  }
</style>

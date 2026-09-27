<!--
  One row: an optional icon, a short title, one line under it, a control.

  `detail` is meant to be one short line; anything longer belongs in the
  `details` slot, behind a disclosure. It wraps rather than truncating, though:
  a detail cut off mid-word hides exactly the part somebody needed, and the
  layout should not punish a sentence for being a few characters too long.

  Rows are separated by a hairline and have no enclosing box. Hover thickens the
  fill very slightly, which is enough to show the row is a target without
  drawing a rectangle around it.
-->
<script lang="ts">
  import { slide, fade } from 'svelte/transition';
  import { ChevronDown } from '@lucide/svelte';
  import { EASE, FAST, NORMAL } from '../motion';

  export let title: string;
  export let detail = '';
  /** A short state word beside the title: "Yours", "Needs module". */
  export let badge = '';
  export let tone: 'neutral' | 'safe' | 'optional' | 'experimental' | 'good' | 'warn' | 'bad' =
    'neutral';
  /** Shows the disclosure. Set when the `details` slot has content. */
  export let expandable = false;
  export let warning: string | null = null;
  export let dimmed = false;
  /** Tighter vertical rhythm, for long lists. */
  export let compact = false;

  let open = false;
</script>

<div class="row" class:dimmed class:compact>
  <div class="line">
    {#if $$slots.icon}
      <div class="icon"><slot name="icon" /></div>
    {/if}

    <div class="text">
      <div class="title-line">
        <span class="title">{title}</span>
        {#if badge}<span class="badge {tone}">{badge}</span>{/if}
      </div>
      {#if detail}<p class="detail">{detail}</p>{/if}
      {#if warning}
        <p class="warning" transition:slide={{ duration: FAST, easing: EASE }}>{warning}</p>
      {/if}
    </div>

    <div class="control">
      <slot name="control" />
      {#if expandable}
        <button
          class="disclose"
          class:open
          title={open ? 'Hide details' : 'Details'}
          on:click={() => (open = !open)}
        >
          <ChevronDown size={14} strokeWidth={2.2} />
        </button>
      {/if}
    </div>
  </div>

  {#if open}
    <div class="details" transition:slide={{ duration: NORMAL, easing: EASE }}>
      <div in:fade={{ duration: FAST, delay: 60 }}>
        <slot name="details" />
      </div>
    </div>
  {/if}
</div>

<style>
  .row {
    padding: var(--space-3) var(--space-4);
    border-radius: max(var(--radius-inner-min), calc(var(--surface-radius, var(--radius-lg)) - 2px));
    /* Hairline between rows, none above the first: the separator belongs to
       the gap between two rows, not to either one of them. */
    box-shadow: 0 -1px 0 var(--control-hairline);
    transition: background-color var(--motion-hover) var(--ease-standard);
  }

  .row:first-child {
    box-shadow: none;
  }

  .row.compact {
    padding: 10px var(--space-4);
  }

  .row:hover {
    background-color: var(--control-fill-faint);
  }

  .row.dimmed {
    opacity: 0.5;
  }

  /* The control drops under the text when the row is too narrow for both,
     rather than squeezing the description into a column of single words. */
  .line {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: var(--space-2) var(--space-3);
  }

  .icon {
    display: grid;
    place-items: center;
    width: var(--space-8);
    height: var(--space-8);
    flex-shrink: 0;
    border-radius: var(--radius-sm);
    background-color: var(--control-fill);
    box-shadow: var(--control-rim);
    color: var(--color-text-secondary);
  }

  .text {
    flex: 1 1 220px;
    min-width: 0;
  }

  .title-line {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: var(--space-2);
  }

  .title {
    font-size: var(--type-body-size);
    font-weight: var(--type-heading-weight);
    letter-spacing: var(--type-heading-tracking);
    color: var(--color-text);
  }

  .detail {
    margin: 3px 0 0;
    font-size: var(--type-secondary-size);
    line-height: var(--type-secondary-leading);
    color: var(--color-text-secondary);
    overflow-wrap: anywhere;
  }

  .warning {
    margin: 6px 0 0;
    font-size: var(--type-caption-size);
    color: var(--color-warning-text);
  }

  .control {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    flex-shrink: 0;
    margin-left: auto;
  }

  .control:empty {
    display: none;
  }

  .badge {
    padding: 2px var(--space-2);
    border-radius: var(--radius-pill);
    font-size: var(--type-caption-size);
    font-weight: var(--type-label-weight);
    color: var(--color-text-tertiary);
    background-color: var(--control-fill);
    white-space: nowrap;
  }

  /* Tones are the catalogue's own vocabulary, so a badge cannot say something
     the data does not. */
  .badge.safe,
  .badge.good {
    color: var(--color-success-text);
    background-color: var(--color-success-tint);
  }

  .badge.optional,
  .badge.warn {
    color: var(--color-warning-text);
    background-color: var(--color-warning-tint);
  }

  .badge.experimental,
  .badge.bad {
    color: var(--color-danger-text);
    background-color: var(--color-danger-tint);
  }

  .disclose {
    display: grid;
    place-items: center;
    width: var(--control-height-sm);
    height: var(--control-height-sm);
    border-radius: var(--radius-pill);
    color: var(--color-text-tertiary);
    transition:
      color var(--motion-hover) var(--ease-standard),
      background-color var(--motion-hover) var(--ease-standard),
      transform var(--motion-state) var(--ease-standard);
  }

  .disclose:hover {
    color: var(--color-text);
    background-color: var(--control-fill-hover);
  }

  .disclose.open {
    color: var(--color-text-secondary);
    transform: rotate(180deg);
  }

  .details {
    margin-top: var(--space-3);
    padding: var(--space-3) var(--space-4);
    border-radius: var(--radius-md);
    background-color: var(--control-fill-sunken);
    box-shadow: var(--control-rim);
    font-size: var(--type-secondary-size);
    color: var(--color-text-secondary);
    line-height: 1.5;
  }
</style>

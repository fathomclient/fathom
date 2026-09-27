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
  import { ICON } from '../metrics.generated';
  import { slide, fade } from 'svelte/transition';
  import { ChevronDown } from '@lucide/svelte';
  import { EASE, FAST, NORMAL } from '../motion';
  import { tooltip } from '../tooltip';

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
          aria-label={open ? 'Hide details' : 'Details'}
          aria-expanded={open}
          use:tooltip={open ? 'Hide details' : 'Details'}
          on:click={() => (open = !open)}
        >
          <ChevronDown size={ICON.inline} />
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
  /* One row height: 48, or 40 compact -- with or without a detail line, so
     two rows in one group are never different heights. A title (~19) and a
     detail (~19) fit in 48 with 4 above and below; only text that wraps
     grows the row. Flush with its pane, so the pane's radius. */
  .row {
    display: flex;
    flex-direction: column;
    justify-content: center;
    min-height: var(--metric-row);
    padding: var(--space-1) var(--space-4);
    border-radius: var(--metric-radius-panel);
    /* Hairline between rows, none above the first: the separator belongs to
       the gap between two rows, not to either one of them. One device pixel. */
    box-shadow: 0 calc(-1 * var(--hairline)) 0 var(--control-hairline);
    transition: background-color var(--motion-hover) var(--ease-standard);
  }

  .row:first-child {
    box-shadow: none;
  }

  .row.compact {
    min-height: var(--metric-row-dense);
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
    width: var(--metric-tile);
    height: var(--metric-tile);
    flex-shrink: 0;
    border-radius: var(--metric-radius-small);
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
    margin: 0;
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

  .warning::before {
    content: '';
    display: inline-block;
    width: 6px;
    height: 6px;
    margin: 0 6px 1px 0;
    border-radius: var(--radius-pill);
    background-color: currentColor;
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

  /* A short state word in a quiet neutral pill. A tone adds a small dot in
     its status colour and nothing else: the word stays neutral, so a list of
     rows never turns into a column of coloured slabs. Tones are the catalogue's
     own vocabulary, so a badge cannot say something the data does not. */
  .badge {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    height: 20px;
    padding: 0 var(--space-2);
    border-radius: var(--radius-pill);
    font-size: var(--type-caption-size);
    font-weight: var(--type-label-weight);
    line-height: 1;
    color: var(--color-text-secondary);
    background-color: var(--control-fill-faint);
    box-shadow: var(--control-rim);
    white-space: nowrap;
  }

  .badge:not(.neutral)::before {
    content: '';
    width: 6px;
    height: 6px;
    flex-shrink: 0;
    border-radius: var(--radius-pill);
    background-color: var(--badge-dot);
  }

  .badge.safe,
  .badge.good {
    --badge-dot: var(--color-success-text);
  }

  .badge.optional,
  .badge.warn {
    --badge-dot: var(--color-warning-text);
  }

  .badge.experimental,
  .badge.bad {
    --badge-dot: var(--color-danger-text);
  }

  .disclose {
    display: grid;
    place-items: center;
    width: var(--metric-control-compact);
    height: var(--metric-control-compact);
    border-radius: var(--metric-radius-small);
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
    border-radius: var(--metric-radius-small);
    background-color: var(--control-fill-sunken);
    box-shadow: var(--control-rim);
    font-size: var(--type-secondary-size);
    color: var(--color-text-secondary);
    line-height: 1.5;
  }
</style>

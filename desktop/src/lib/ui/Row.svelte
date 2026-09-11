<!--
  One row: an optional icon, a short title, one line under it, a control.

  This is the copy rule made structural. There is nowhere here to put a
  paragraph — `detail` is one line and it truncates — so the only place a longer
  explanation can go is the `details` slot, behind a disclosure. A component
  that made it easy to write four sentences would get four sentences, on every
  row, forever.

  Rows are separated by a hairline and have no enclosing box. Hover lifts the
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
    padding: 12px 14px;
    border-radius: var(--r-md);
    /* Hairline between rows, none above the first: the separator belongs to
       the gap between two rows, not to either one of them. */
    box-shadow: 0 -1px 0 var(--chrome-line);
    transition: background var(--m-fast) var(--ease);
  }

  .row:first-child {
    box-shadow: none;
  }

  .row.compact {
    padding: 9px 14px;
  }

  .row:hover {
    background: var(--fill);
  }

  .row.dimmed {
    opacity: 0.5;
  }

  .line {
    display: flex;
    align-items: center;
    gap: var(--s-3);
  }

  .icon {
    display: grid;
    place-items: center;
    width: 30px;
    height: 30px;
    flex-shrink: 0;
    border-radius: var(--r-sm);
    background: var(--fill);
    color: var(--text-muted);
  }

  .text {
    flex: 1;
    min-width: 0;
  }

  .title-line {
    display: flex;
    align-items: center;
    gap: var(--s-2);
  }

  .title {
    font-size: var(--t-row);
    font-weight: var(--w-semibold);
    letter-spacing: var(--track-snug);
    color: var(--text);
  }

  .detail {
    margin: 2px 0 0;
    font-size: var(--t-body);
    color: var(--text-muted);
    /* One line, always. The overflow rule IS the copy rule, enforced by the
       layout: if it does not fit on one line it belongs in `details`. */
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .warning {
    margin: 6px 0 0;
    font-size: var(--t-caption);
    color: var(--warn);
  }

  .control {
    display: flex;
    align-items: center;
    gap: var(--s-2);
    flex-shrink: 0;
  }

  .badge {
    padding: 2px 7px;
    border-radius: var(--r-pill);
    font-size: var(--t-meta);
    font-weight: var(--w-medium);
    color: var(--text-faint);
    background: var(--fill);
    white-space: nowrap;
  }

  /* Tones are the catalogue's own vocabulary, so a badge cannot say something
     the data does not. */
  .badge.safe,
  .badge.good {
    color: var(--good);
    background: rgba(79, 201, 138, 0.13);
  }
  .badge.optional,
  .badge.warn {
    color: var(--warn);
    background: rgba(224, 174, 87, 0.13);
  }
  .badge.experimental,
  .badge.bad {
    color: var(--bad);
    background: rgba(232, 114, 114, 0.13);
  }

  .disclose {
    display: grid;
    place-items: center;
    width: 24px;
    height: 24px;
    border-radius: var(--r-xs);
    color: var(--text-disabled);
    transition:
      color var(--m-fast) var(--ease),
      background var(--m-fast) var(--ease),
      transform var(--m-normal) var(--ease);
  }

  .disclose:hover {
    color: var(--text-muted);
    background: var(--fill-hover);
  }

  .disclose.open {
    color: var(--text-muted);
    transform: rotate(180deg);
  }

  .details {
    margin-top: var(--s-3);
    padding: 11px 13px;
    border-radius: var(--r-md);
    background: var(--fill-sunken);
    border: 1px solid var(--chrome-line);
    font-size: var(--t-body);
    color: var(--text-muted);
    line-height: 1.5;
  }
</style>

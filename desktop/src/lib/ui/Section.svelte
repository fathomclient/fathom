<!--
  A labelled group of rows.

  NO CARD. The previous version wrapped every section in a bordered,
  blurred panel, so a page with five settings groups was five stacked
  rectangles — the exact "cards dominate" failure. Here the label does the
  grouping and the rows are separated from each other by hairlines, with
  nothing enclosing them.

  A collapsed section keeps its summary. A disclosure that hides both the
  content and any indication of what is inside is just a thing to click.
-->
<script lang="ts">
  import { slide } from 'svelte/transition';
  import { ChevronRight } from '@lucide/svelte';
  import { EASE, NORMAL } from '../motion';

  export let label = '';
  /** Right-aligned state on the header line: "3 of 5 on". */
  export let summary = '';
  export let collapsible = false;
  export let open = true;
  /** Wrap the rows in glass. For status that must float, not for grouping. */
  export let material = false;
</script>

<section>
  {#if label}
    {#if collapsible}
      <button class="head interactive" on:click={() => (open = !open)}>
        <span class="chevron" class:open><ChevronRight size={14} strokeWidth={2.4} /></span>
        <span class="label on-footage">{label}</span>
        {#if summary}<span class="summary on-footage tnum">{summary}</span>{/if}
      </button>
    {:else}
      <div class="head">
        <span class="label on-footage">{label}</span>
        {#if summary}<span class="summary on-footage tnum">{summary}</span>{/if}
      </div>
    {/if}
  {/if}

  {#if open}
    <div
      class="rows"
      class:material
      class:glass={material}
      transition:slide={{ duration: NORMAL, easing: EASE }}
    >
      <slot />
    </div>
  {/if}
</section>

<style>
  section {
    display: flex;
    flex-direction: column;
  }

  .head {
    display: flex;
    align-items: center;
    gap: var(--s-2);
    width: 100%;
    padding: 0 2px 9px;
    text-align: left;
    color: inherit;
  }

  .head.interactive:hover .label,
  .head.interactive:hover .chevron {
    color: var(--text);
  }

  .label {
    font-size: var(--t-meta);
    font-weight: var(--w-semibold);
    letter-spacing: var(--track-caps);
    text-transform: uppercase;
    color: var(--text-label);
    transition: color var(--m-fast) var(--ease);
  }

  .summary {
    margin-left: auto;
    font-size: var(--t-meta);
    color: var(--text-label);
  }

  .chevron {
    display: grid;
    place-items: center;
    color: var(--text-disabled);
    /* Rotation rather than swapping two glyphs: one element that turns reads
       as the same thing changing state. */
    transition:
      transform var(--m-normal) var(--ease),
      color var(--m-fast) var(--ease);
  }

  .chevron.open {
    transform: rotate(90deg);
  }

  /* Rows sit directly on the background, separated by hairlines. The section
     has no border and no fill of its own. */
  .rows {
    display: flex;
    flex-direction: column;
  }

  .rows.material {
    padding: 2px 0;
    overflow: hidden;
  }
</style>

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
  import { ICON } from '../metrics.generated';
  import { slide } from 'svelte/transition';
  import { ChevronRight } from '@lucide/svelte';
  import { EASE, NORMAL } from '../motion';

  export let label = '';
  /** Right-aligned state on the header line: "3 of 5 on". */
  export let summary = '';
  export let collapsible = false;
  export let open = true;
  /** Wrap the rows in a pane of standard glass. */
  export let material = false;
</script>

<section>
  {#if label}
    {#if collapsible}
      <button class="head interactive" on:click={() => (open = !open)}>
        <span class="chevron" class:open><ChevronRight size={ICON.inline} /></span>
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
      class:material-standard={material}
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
    gap: var(--space-2);
    width: 100%;
    padding: 0 var(--space-1) var(--space-2);
    text-align: left;
    color: inherit;
  }

  .head.interactive:hover .label,
  .head.interactive:hover .chevron {
    color: var(--color-text);
  }

  /* Sentence case, on the photograph, with the dark edge that keeps it legible
     over bright sky. */
  .label {
    font-size: var(--type-label-size);
    font-weight: var(--type-heading-weight);
    letter-spacing: var(--type-label-tracking);
    color: var(--color-text-secondary);
    transition: color var(--motion-hover) var(--ease-standard);
  }

  .summary {
    margin-left: auto;
    font-size: var(--type-label-size);
    color: var(--color-text-tertiary);
  }

  .chevron {
    display: grid;
    place-items: center;
    color: var(--color-text-tertiary);
    /* Rotation rather than swapping two glyphs: one element that turns reads
       as the same thing changing state. */
    transition:
      transform var(--motion-state) var(--ease-standard),
      color var(--motion-hover) var(--ease-standard);
  }

  .chevron.open {
    transform: rotate(90deg);
  }

  .rows {
    display: flex;
    flex-direction: column;
  }

  /* The pane. Its radius is published so the rows inside round concentrically
     with it. */
  .rows.material {
    --surface-radius: var(--radius-lg);
    --surface-inset: 2px;
    padding: 2px;
    overflow: hidden;
  }
</style>

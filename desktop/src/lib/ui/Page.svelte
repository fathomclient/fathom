<!--
  The frame every screen except Home sits in.

  Home is a composition and owns its whole canvas. Everything else is a
  document: a title, one line of state, and content in a capped column.

  WHAT CHANGED, AND WHY

  The previous version stacked a title, then a summary, then a stack of
  bordered sections — which is exactly the "developer utility" read. Two things
  fix it here:

  - The title sits on a line WITH its actions and its state, not above them.
    One horizontal band of information instead of three stacked rows.
  - There is no card around the body. Sections group with type and spacing;
    glass is reserved for things that need to float.
-->
<script lang="ts">
  import { fly } from 'svelte/transition';
  import { EASE, NORMAL } from '../motion';

  export let title: string;
  /** One line beside the title. State, not explanation. */
  export let summary = '';
  /** Widen for pages that are genuinely tabular. */
  export let wide = false;
  /** Remove the column cap entirely, for pages with their own layout. */
  export let full = false;
</script>

<div class="frame">
  <!-- The readability veil, behind the scroller rather than on it, so its edges
       can fade into the photograph without fading the content with them. -->
  <div class="veil" aria-hidden="true"></div>
  <div class="scroll">
    <div class="column" class:wide class:full>
      <header in:fly={{ y: 6, duration: NORMAL, easing: EASE }}>
        <h1 class="on-footage">{title}</h1>
        {#if summary}
          <span class="summary on-footage">{summary}</span>
        {/if}
        <div class="spacer"></div>
        <div class="actions"><slot name="actions" /></div>
      </header>

      <div class="body" in:fly={{ y: 10, duration: NORMAL, delay: 30, easing: EASE }}>
        <slot />
      </div>
    </div>
  </div>
</div>

<style>
  .frame {
    position: relative;
    height: 100%;
  }

  /* THE READABILITY VEIL.
     Document pages are text, and parts of the photograph are bright sky, so a
     page gets a warm deepening behind its column. It is a veil and not a
     panel: it has no edge. It fades out at every side of the content area, so
     the photograph continues around it and the page does not become a
     rectangle inside the window. Home does not use `Page`, because Home IS the
     photograph. */
  .veil {
    position: absolute;
    inset: 0;
    background: var(--backdrop-document);
    -webkit-mask-image:
      linear-gradient(to right, transparent, #000 var(--space-20), #000 calc(100% - var(--space-20)), transparent),
      linear-gradient(to bottom, transparent, #000 var(--space-16));
    -webkit-mask-composite: source-in;
    mask-image:
      linear-gradient(to right, transparent, #000 var(--space-20), #000 calc(100% - var(--space-20)), transparent),
      linear-gradient(to bottom, transparent, #000 var(--space-16));
    mask-composite: intersect;
    pointer-events: none;
  }

  .scroll {
    position: relative;
    height: 100%;
    overflow-y: auto;
    /* Room at the bottom so the last row is not welded to the window's edge. */
    padding: var(--space-6) var(--layout-page-pad-x) var(--space-12);
  }

  .column {
    max-width: var(--layout-column);
    margin: 0 auto;
  }

  .column.wide {
    max-width: var(--layout-column-wide);
  }

  .column.full {
    max-width: none;
  }

  /* Title, state and actions on ONE line, wrapping when the window is narrow
     rather than squeezing the title. */
  header {
    display: flex;
    flex-wrap: wrap;
    align-items: baseline;
    gap: var(--space-2) var(--space-3);
    margin-bottom: var(--space-6);
  }

  h1 {
    margin: 0;
    font-size: var(--type-title-size);
    font-weight: var(--type-title-weight);
    letter-spacing: var(--type-title-tracking);
    line-height: var(--type-title-leading);
    color: var(--color-text);
  }

  .summary {
    font-size: var(--type-secondary-size);
    color: var(--color-text-secondary);
    /* Pushed down a touch so it sits on the title's baseline rather than
       floating level with its cap height. */
    position: relative;
    top: -1px;
  }

  .spacer {
    flex: 1;
  }

  .actions {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: var(--space-2);
    /* Actions align to the control's centre, not the title's baseline. */
    align-self: center;
  }

  .body {
    display: flex;
    flex-direction: column;
    gap: var(--layout-section-gap);
  }
</style>

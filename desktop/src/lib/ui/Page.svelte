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

<style>
  .scroll {
    height: 100%;
    overflow-y: auto;
    /* Room at the bottom so the last row is not welded to the bottom bar. */
    padding: 26px 30px 44px;

    /* THE READABILITY SUBSTRATE.
       The background is deliberately only lightly blurred now, which means
       parts of it are bright -- sunlit grass in bg_1, snow in bg_2 -- and a
       faint uppercase label over sunlit grass is invisible. Verified: the first
       capture of this build had unreadable section labels on every document
       page.
       Home does not get this, because Home IS the footage. Document pages do,
       because they are text. The gradient weakens toward the bottom so the
       lower half of the scene stays visible below the content. */
    background: linear-gradient(
      180deg,
      rgba(5, 6, 10, 0.66) 0%,
      rgba(5, 6, 10, 0.58) 45%,
      rgba(5, 6, 10, 0.4) 100%
    );
  }

  .column {
    max-width: 760px;
    margin: 0 auto;
  }

  .column.wide {
    max-width: 980px;
  }

  .column.full {
    max-width: none;
  }

  /* Title, state and actions on ONE line. The old layout put the summary on
     its own row under the title and the actions on a third, which turned every
     page header into three lines of nothing before the content began. */
  header {
    display: flex;
    align-items: baseline;
    gap: var(--s-3);
    margin-bottom: var(--s-5);
  }

  h1 {
    margin: 0;
    font-size: var(--t-page);
    font-weight: var(--w-bold);
    letter-spacing: var(--track-tight);
    line-height: 1.15;
  }

  .summary {
    font-size: var(--t-body);
    color: var(--text-muted);
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
    align-items: center;
    gap: var(--s-2);
    flex-shrink: 0;
    /* Actions align to the control's centre, not the title's baseline. */
    align-self: center;
  }

  .body {
    display: flex;
    flex-direction: column;
    gap: var(--s-6);
  }
</style>

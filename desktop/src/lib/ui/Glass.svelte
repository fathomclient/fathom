<!--
  A pane of Fathom's glass.

  Exists so that "the material" is one thing rather than a convention. Every
  translucent surface in the product renders through here or through the
  `.glass` class it shares, which is what stops eight components drifting into
  eight slightly different greys.

  USE IT FOR: the launch control, popovers, drawers, dialogs, the profile
  selector, and status that genuinely matters.

  DO NOT USE IT FOR: every section of every page. A page made of glass panes is
  the same mistake as a page made of cards, with a more expensive paint. Most
  content should sit directly on the background and be grouped by type and
  spacing.
-->
<script lang="ts">
  /** `strong` is for surfaces that float above other surfaces. */
  export let level: 'normal' | 'strong' = 'normal';
  /** Corner radius token name, without the `--r-` prefix. */
  export let radius: 'sm' | 'md' | 'lg' | 'xl' = 'lg';
  export let padded = true;
</script>

<div
  class="pane {level === 'strong' ? 'glass-strong' : 'glass'}"
  class:padded
  style="border-radius: var(--r-{radius})"
>
  <slot />
</div>

<style>
  .pane {
    position: relative;
    /* `isolation` gives the pane its own stacking context, so a child's
       z-index cannot escape and land on top of a popover elsewhere. */
    isolation: isolate;
  }

  .padded {
    padding: 14px 16px;
  }
</style>

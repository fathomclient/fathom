<!--
  A pane of Fathom's glass.

  The one component for a surface. Every translucent pane renders through here
  or through the `.material-*` classes it applies, which is what stops eight
  components drifting into eight slightly different tints.

  FOUR LEVELS, ONE SUBSTANCE. Levels differ in density -- opacity, luminance,
  blur, rim and shadow -- never in hue. The photograph behind supplies the
  colour; a denser level simply lets less of it through.

    clear     chips and icon buttons sitting directly on the photograph
    light     quiet grouping; a surface you should barely notice
    standard  the default pane: content groups, popovers, list platters
    dense     things that must read over anything: drawers, dialogs, the
              primary control, status that matters

  Reduced blur and reduced transparency are handled by the tokens, not here.

  GEOMETRY. `radius` picks the pane's corner; `inset` is its padding. Both are
  published as `--surface-radius` / `--surface-inset`, so a child with
  `class="concentric"` gets a radius that stays concentric with this one.

  `normal` and `strong` are the previous dark glass, kept so screens that have
  not been rebuilt render exactly as before. Nothing new should use them.
-->
<script lang="ts">
  type Level = 'clear' | 'light' | 'standard' | 'dense';
  type Legacy = 'normal' | 'strong';

  export let level: Level | Legacy = 'standard';
  /** Corner radius token name, without the `--radius-` prefix. */
  export let radius: 'sm' | 'md' | 'lg' | 'xl' | '2xl' = 'lg';
  /** Padding, as a spacing step. `0` for a pane whose children bring their own. */
  export let inset: 0 | 2 | 3 | 4 | 5 | 6 = 4;
  /** Hover veil and press compression, for a pane that is itself a control. */
  export let interactive = false;
  /** @deprecated Use `inset={0}`. Kept for the legacy levels' callers. */
  export let padded = true;

  $: legacy = level === 'normal' || level === 'strong';
  $: materialClass = legacy
    ? level === 'strong'
      ? 'glass-strong'
      : 'glass'
    : `material-${level}`;
  $: padding = !padded || inset === 0 ? '0px' : `var(--space-${inset})`;
</script>

<div
  class="pane {materialClass}"
  class:material-interactive={interactive && !legacy}
  style="--surface-radius: var(--radius-{radius}); --surface-inset: {padding}; padding: {padding};{legacy
    ? ` border-radius: var(--r-${radius === '2xl' ? 'xl' : radius})`
    : ''}"
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
</style>

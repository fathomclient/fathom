<!--
  The one button.

  Four variants, all the same capsule, differing in density rather than colour:

    primary  the one thing on the surface you are expected to do: the bright
             neutral fill with dark type. Used sparingly -- one per surface
    normal   translucent glass with a subtle rim: the ordinary action
    ghost    no surface until the pointer is on it: secondary actions beside a
             primary one, and inline actions that used to be underlined text
    caution  normal until hovered, when it warms towards the danger tone

  Only the primary glows: a soft halo of the scene's accent beneath it
  (glow.css), a little stronger on hover. None of them lifts or scales. Press is a denser fill and a
  very slight compression. They carry no blur of their own, because they sit on
  panes that already have one.

  `title` is the tooltip (the shared `use:tooltip`, never the native one).
-->
<script lang="ts">
  import { tooltip } from '../tooltip';

  export let variant: 'primary' | 'normal' | 'ghost' | 'caution' = 'normal';
  export let disabled = false;
  export let title = '';
  /** The 28px size, for dense rows and inline actions. */
  export let small = false;
  /** Fills the width of its container, for drawers and menus. */
  export let block = false;
  export let onClick: () => void;
</script>

<button
  class="btn ui-capsule {variant}"
  class:small
  class:block
  {disabled}
  use:tooltip={title}
  on:click|stopPropagation={() => !disabled && onClick()}
>
  <slot />
</button>

<style>
  .btn {
    min-width: 0;
  }

  .block {
    width: 100%;
    height: var(--control-height-lg);
  }

  .btn.primary {
    background-color: var(--control-prominent);
    box-shadow: var(--glow-halo);
    color: var(--color-prominent-text);
    font-weight: var(--type-heading-weight);
  }

  .btn.primary:hover:not(:disabled) {
    background-color: var(--control-prominent-hover);
    box-shadow: var(--glow-halo-strong);
  }

  .btn.primary:active:not(:disabled) {
    background-color: var(--palette-champagne-300);
  }

  .btn.primary:focus-visible {
    outline-offset: 3px;
  }

  .btn.caution:hover:not(:disabled) {
    color: var(--color-danger-text);
    background-color: var(--color-danger-tint);
  }

  /* A disabled primary loses its fill rather than fading it: a washed-out
     bright slab reads as a rendering fault; a thin neutral one reads as "not
     available", which is what it is. */
  .btn.primary:disabled {
    background-color: var(--control-fill-faint);
    box-shadow: var(--control-rim);
    color: var(--color-text-disabled);
    font-weight: var(--type-button-weight);
  }
</style>

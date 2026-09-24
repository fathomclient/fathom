<!--
  The one button.

  Three variants and no more. A codebase with a `primary`, a `secondary`, a
  `subtle`, a `ghost`, a `quiet` and a `link` variant has six ways to say the
  same thing and no way to tell which one a screen should use.

  All three are the same capsule; they differ in density, not colour.

    normal   a thin ivory fill: the ordinary action
    primary  denser, darker glass with a stronger rim and heavier type -- the
             one thing on the surface you are expected to do
    caution  normal until hovered, when it warms towards the danger tone

  None of them glows, and hover never turns one white: it thickens the fill.
  They carry no blur of their own, because they sit on panes that already have
  one.
-->
<script lang="ts">
  export let variant: 'primary' | 'normal' | 'caution' = 'normal';
  export let disabled = false;
  export let title = '';
  /** Fills the width of its container, for drawers and menus. */
  export let block = false;
  export let onClick: () => void;
</script>

<button
  class="btn ui-capsule {variant}"
  class:block
  {disabled}
  {title}
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
    box-shadow: var(--control-rim-strong), var(--shadow-sm);
    font-weight: var(--type-heading-weight);
  }

  .btn.primary:hover:not(:disabled) {
    background-color: var(--control-prominent-hover);
  }

  .btn.primary:active:not(:disabled) {
    background-color: var(--control-prominent-hover);
  }

  .btn.caution:hover:not(:disabled) {
    color: var(--color-danger-text);
    background-color: var(--color-danger-tint);
  }

  /* A disabled primary loses its density rather than fading it: a washed-out
     dark slab reads as a rendering fault; a thin neutral one reads as "not
     available", which is what it is. */
  .btn.primary:disabled {
    background-color: var(--control-fill-pressed);
    box-shadow: var(--control-rim);
    font-weight: var(--type-button-weight);
  }
</style>

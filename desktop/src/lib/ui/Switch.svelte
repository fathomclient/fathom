<!--
  A switch.

  The knob slides and the track fills over the same duration, so the control
  reads as one object changing rather than two properties animating. This is the
  most-repeated interaction in the product, which is exactly why it gets a real
  transition and a real material instead of a coloured rectangle.

  Off is a sunken channel; on is the same channel filled with champagne light.
  That is a change in luminance, not a switch to a saturated colour, and it is
  still unmistakable at a glance. The knob is ivory in both states, compresses
  slightly while held, and never glows.
-->
<script lang="ts">
  export let checked: boolean;
  export let disabled = false;
  export let label = '';
  export let onChange: (value: boolean) => void;
</script>

<button
  class="switch"
  class:on={checked}
  {disabled}
  role="switch"
  aria-checked={checked}
  aria-label={label}
  on:click={() => !disabled && onChange(!checked)}
>
  <span class="knob"></span>
</button>

<style>
  .switch {
    position: relative;
    width: 42px;
    height: 24px;
    flex-shrink: 0;
    border-radius: var(--radius-pill);
    background-color: var(--control-fill-sunken);
    box-shadow: var(--control-rim);
    transition:
      background-color var(--motion-state) var(--ease-standard),
      box-shadow var(--motion-state) var(--ease-standard);
  }

  .switch:hover:not(:disabled) {
    background-color: var(--control-fill-sunken-hover);
  }

  .switch.on {
    background-color: var(--palette-champagne-300);
    box-shadow: var(--control-rim-strong);
  }

  .switch.on:hover:not(:disabled) {
    background-color: var(--palette-champagne-200);
  }

  .switch:disabled {
    opacity: 0.45;
    cursor: default;
  }

  .switch:focus-visible {
    outline: 2px solid var(--color-focus);
    outline-offset: 2px;
  }

  .knob {
    position: absolute;
    top: 3px;
    left: 3px;
    width: 18px;
    height: 18px;
    border-radius: var(--radius-pill);
    background-color: var(--palette-ivory-50);
    box-shadow: var(--shadow-knob);
    transition:
      transform var(--motion-state) var(--ease-standard),
      width var(--motion-press) var(--ease-standard);
  }

  .switch.on .knob {
    transform: translateX(18px);
  }

  /* Squashes while held, like a real switch under a thumb. The `on` variant
     shifts its origin so the knob stretches backwards from the right edge
     rather than sliding past it. */
  .switch:not(:disabled):active .knob {
    width: 22px;
  }

  .switch.on:not(:disabled):active .knob {
    transform: translateX(14px);
  }
</style>

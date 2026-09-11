<!--
  A switch.

  The knob slides and the track fills over the same duration, so the control
  reads as one object changing rather than two properties animating. This is the
  most-repeated interaction in the product, which is exactly why it gets a real
  transition and a real material instead of a coloured rectangle.

  The knob is a physical object: it has a highlight on top, a shadow under it,
  and it compresses slightly while held. The off track is sunken, the on track
  is lit. Both are what make it read as a control rather than a div.
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
    width: 38px;
    height: 22px;
    border-radius: var(--r-pill);
    background: var(--fill-sunken);
    border: 1px solid var(--chrome-line-strong);
    box-shadow: inset 0 1px 3px rgba(0, 0, 0, 0.4);
    flex-shrink: 0;
    transition:
      background var(--m-normal) var(--ease),
      border-color var(--m-normal) var(--ease),
      box-shadow var(--m-normal) var(--ease);
  }

  .switch:not(:disabled):hover {
    border-color: rgba(255, 255, 255, 0.2);
  }

  .switch.on {
    background: linear-gradient(180deg, var(--accent-strong), var(--accent));
    border-color: rgba(224, 80, 92, 0.6);
    box-shadow:
      inset 0 1px 0 rgba(255, 255, 255, 0.22),
      0 0 16px var(--accent-glow);
  }

  .switch.on:not(:disabled):hover {
    filter: brightness(1.08);
  }

  .switch:disabled {
    opacity: 0.4;
    cursor: default;
  }

  .knob {
    position: absolute;
    top: 2px;
    left: 2px;
    width: 16px;
    height: 16px;
    border-radius: var(--r-pill);
    background: linear-gradient(180deg, #ffffff, #dfe3ea);
    box-shadow:
      0 1px 3px rgba(0, 0, 0, 0.5),
      inset 0 1px 0 rgba(255, 255, 255, 0.9);
    transition:
      transform var(--m-normal) var(--ease),
      width var(--m-fast) var(--ease);
  }

  .switch.on .knob {
    transform: translateX(16px);
  }

  /* Squashes while held, like a real switch under a thumb. The `on` variant
     shifts its origin so the knob stretches backwards from the right edge
     rather than sliding past it. */
  .switch:not(:disabled):active .knob {
    width: 20px;
  }

  .switch.on:not(:disabled):active .knob {
    transform: translateX(12px);
  }

  .switch:focus-visible {
    border-color: var(--accent-strong);
  }
</style>

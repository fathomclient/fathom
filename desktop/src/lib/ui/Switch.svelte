<!--
  A switch.

  38 x 22, fully rounded, the same geometry as the game's (`Graphite.switchControl`).
  Off is a faint neutral glass track with a grey knob at the left; on is a
  brighter track with a near-white knob at the right. The whole change is
  luminance -- no hue, no glow -- and the knob's own brightness carries most of
  it, so the state still reads at a glance on a bright part of the photograph.

  Knob position and track fill move together over 120ms, so the control reads
  as one object changing rather than two properties animating.
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
    flex-shrink: 0;
    border-radius: var(--radius-pill);
    background-color: var(--control-switch-track);
    box-shadow: var(--control-rim);
    transition:
      background-color var(--motion-hover) var(--ease-standard),
      box-shadow var(--motion-hover) var(--ease-standard);
  }

  .switch:hover:not(:disabled) {
    background-color: var(--control-fill-hover);
  }

  .switch.on {
    background-color: var(--control-switch-track-on);
    box-shadow: var(--control-rim-strong);
  }

  .switch.on:hover:not(:disabled) {
    background-color: var(--control-switch-track-on);
  }

  .switch:disabled {
    opacity: 0.45;
    cursor: default;
  }

  .switch:focus-visible {
    outline: var(--focus-ring-width) solid var(--color-focus);
    outline-offset: 2px;
  }

  .knob {
    position: absolute;
    top: 3px;
    left: 3px;
    width: 16px;
    height: 16px;
    border-radius: var(--radius-pill);
    background-color: var(--control-switch-knob);
    box-shadow: var(--shadow-knob);
    transition:
      transform var(--motion-hover) var(--ease-standard),
      background-color var(--motion-hover) var(--ease-standard);
  }

  .switch:hover:not(:disabled) .knob {
    background-color: var(--palette-sand-400);
  }

  .switch.on .knob,
  .switch.on:hover:not(:disabled) .knob {
    transform: translateX(16px);
    background-color: var(--control-switch-knob-on);
  }
</style>

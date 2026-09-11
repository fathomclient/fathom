<!--
  The one button.

  Three variants and no more. A codebase with a `primary`, a `secondary`, a
  `subtle`, a `ghost`, a `quiet` and a `link` variant has six ways to say the
  same thing and no way to tell which one a screen should use.

  Every variant is a material rather than a flat fill: a gradient from light to
  dark across its height, a lit top edge, and a shadow beneath. That is what
  separates a control from a coloured rectangle, and it is the specific thing
  the previous build was missing.
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
  class="btn {variant}"
  class:block
  {disabled}
  {title}
  on:click|stopPropagation={() => !disabled && onClick()}
>
  <slot />
</button>

<style>
  .btn {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    gap: var(--s-2);
    height: 30px;
    padding: 0 13px;
    border-radius: var(--r-sm);
    font-size: var(--t-body);
    font-weight: var(--w-medium);
    color: var(--text-muted);
    white-space: nowrap;
    background: linear-gradient(180deg, rgba(255, 255, 255, 0.085), rgba(255, 255, 255, 0.04));
    border: 1px solid var(--chrome-line-strong);
    box-shadow:
      inset 0 1px 0 rgba(255, 255, 255, 0.06),
      0 1px 3px rgba(0, 0, 0, 0.3);
    transition:
      background var(--m-fast) var(--ease),
      color var(--m-fast) var(--ease),
      border-color var(--m-fast) var(--ease),
      transform var(--m-fast) var(--ease),
      box-shadow var(--m-fast) var(--ease);
  }

  .block {
    width: 100%;
  }

  .btn:not(:disabled):hover {
    color: var(--text);
    background: linear-gradient(180deg, rgba(255, 255, 255, 0.14), rgba(255, 255, 255, 0.07));
    border-color: rgba(255, 255, 255, 0.18);
  }

  /* Presses down AND loses its shadow, because a control that has moved
     towards the surface should not still be floating above it. */
  .btn:not(:disabled):active {
    transform: translateY(1px);
    box-shadow: inset 0 1px 4px rgba(0, 0, 0, 0.35);
  }

  .btn.primary {
    color: #fff;
    background: linear-gradient(180deg, var(--accent-strong), var(--accent-deep));
    border-color: rgba(224, 80, 92, 0.55);
    box-shadow:
      inset 0 1px 0 rgba(255, 255, 255, 0.2),
      0 2px 10px rgba(0, 0, 0, 0.35),
      0 0 18px var(--accent-glow);
    font-weight: var(--w-semibold);
  }

  .btn.primary:not(:disabled):hover {
    color: #fff;
    filter: brightness(1.1);
    border-color: rgba(224, 80, 92, 0.75);
  }

  .btn.caution:not(:disabled):hover {
    color: var(--bad);
    background: linear-gradient(180deg, rgba(232, 114, 114, 0.2), rgba(232, 114, 114, 0.1));
    border-color: rgba(232, 114, 114, 0.4);
  }

  .btn:disabled {
    opacity: 0.5;
    cursor: default;
  }

  /* A disabled primary loses the accent entirely rather than fading it. A
     washed-out red slab reads as a rendering fault; a neutral one reads as
     "not available", which is what it is. */
  .btn.primary:disabled {
    color: var(--text-faint);
    background: linear-gradient(180deg, rgba(255, 255, 255, 0.06), rgba(255, 255, 255, 0.03));
    border-color: var(--chrome-line-strong);
    box-shadow: none;
  }
</style>

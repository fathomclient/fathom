<!--
  Progress.

  A sunken channel with an ivory fill. `value` is 0–1; `null` means the work has
  no measurable fraction, and the fill drifts across the channel instead of
  parking at a made-up percentage -- a bar that lies about how far along it is
  is worse than no bar.

  Quiet by design: no gradient, no shimmer, no glow. Under reduced motion the
  indeterminate state is a steady half-lit channel.
-->
<script lang="ts">
  export let value: number | null = null;
  /** A short description for assistive technology. */
  export let label = 'Progress';
  /** `inset` sits flush along the bottom edge of a control; `bar` stands alone. */
  export let variant: 'bar' | 'inset' = 'bar';

  $: clamped = value === null ? null : Math.min(1, Math.max(0, value));
</script>

<span
  class="channel {variant}"
  class:indeterminate={clamped === null}
  role="progressbar"
  aria-label={label}
  aria-valuemin={0}
  aria-valuemax={100}
  aria-valuenow={clamped === null ? undefined : Math.round(clamped * 100)}
>
  <span class="fill" style={clamped === null ? '' : `width: ${clamped * 100}%`}></span>
</span>

<style>
  .channel {
    position: relative;
    display: block;
    overflow: hidden;
    background-color: var(--control-fill-sunken);
  }

  .channel.bar {
    width: 100%;
    height: 4px;
    border-radius: var(--radius-pill);
    box-shadow: var(--control-rim);
  }

  /* Along the bottom edge of a control, inheriting its corners. */
  .channel.inset {
    position: absolute;
    left: 0;
    right: 0;
    bottom: 0;
    height: 3px;
    background-color: var(--control-fill);
  }

  .fill {
    display: block;
    height: 100%;
    border-radius: inherit;
    background-color: var(--palette-ivory-100);
    transition: width var(--motion-state) var(--ease-standard);
  }

  .indeterminate .fill {
    width: 32%;
    animation: drift 1.6s var(--ease-standard) infinite;
  }

  @keyframes drift {
    from {
      transform: translateX(-110%);
    }
    to {
      transform: translateX(320%);
    }
  }

  @media (prefers-reduced-motion: reduce) {
    .indeterminate .fill {
      width: 100%;
      animation: none;
      opacity: 0.45;
    }
  }
</style>

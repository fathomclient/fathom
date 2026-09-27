<script lang="ts">
  import { GRAPHITE_WORDMARK } from '../brand/graphiteWordmark';

  /** Cap height in CSS pixels. */
  export let height = 13;
  export let label = 'Fathom';

  $: width = (GRAPHITE_WORDMARK.width / GRAPHITE_WORDMARK.cap) * height;
</script>

<svg
  {width}
  {height}
  viewBox={`0 0 ${GRAPHITE_WORDMARK.width} ${GRAPHITE_WORDMARK.cap}`}
  role="img"
  aria-label={label}
>
  <path d={GRAPHITE_WORDMARK.d} fill="currentColor" fill-rule="evenodd" />
</svg>

<style>
  svg {
    display: block;
    overflow: visible;
    color: var(--g-text-primary);
  }
</style>

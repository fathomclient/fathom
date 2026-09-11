<script lang="ts">
  export let source: string;
  export let size = 18;
</script>

<span
  class="fathom-icon"
  style:width="{size}px"
  style:height="{size}px"
  style:--fathom-icon="url('{source}')"
  aria-hidden="true"
></span>

<style>
  .fathom-icon {
    display: inline-block;
    flex: 0 0 auto;
    background: currentColor;
    -webkit-mask: var(--fathom-icon) center / contain no-repeat;
    mask: var(--fathom-icon) center / contain no-repeat;
  }
</style>

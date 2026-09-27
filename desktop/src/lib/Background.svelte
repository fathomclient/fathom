<!-- The one approved full-resolution Rainforest Storm environment. -->
<script lang="ts">
  import {
    approvedOrDefault,
    DEFAULT_BACKGROUND,
    imageFor,
  } from './backgrounds';

  export let choice: string = DEFAULT_BACKGROUND;

  $: approved = approvedOrDefault(choice);
  $: source = imageFor(approved);
</script>

<div class="backdrop" aria-hidden="true">
  <div class="fallback"></div>
  <img class="media" src={source} alt="" draggable="false" />
  <div class="scrim"></div>
</div>

<style>
  .backdrop,
  .backdrop > * {
    position: absolute;
    inset: 0;
  }

  .backdrop {
    overflow: hidden;
    background: var(--backdrop-base);
    pointer-events: none;
  }

  .fallback {
    background: var(--backdrop-fallback);
  }

  .media {
    width: 100%;
    height: 100%;
    object-fit: cover;
    filter: var(--backdrop-image-filter);
  }

  .scrim {
    background: var(--backdrop-scrim);
  }
</style>

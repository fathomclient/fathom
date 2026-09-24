<!--
  The background.

  The one place the launcher's backdrop is decided. In the golden-hour system
  the background is the palette -- every glass surface borrows its colour from
  what is drawn here -- so nothing else in the interface paints a backdrop of
  its own.

  WHAT IS DRAWN, BOTTOM TO TOP

  1. The warm fallback (`--backdrop-fallback`), always painted. It is the
     failure-state substrate, not a choice: if the approved image cannot load,
     this is what shows, and no state can produce a black or empty window.
  2. The approved photograph, in one of two slots.
  3. The scrim, which only deepens the photograph where the window's edge
     meets it.

  The chain is exactly that: approved image, then the warm fallback. An id
  that is not approved (a retired background left in an old preference) is
  shown as the default image, never as nothing.

  WHY TWO SLOTS FOR ONE IMAGE

  So a switch between curated images can crossfade without a gap, if the set
  grows again. The incoming image loads in the hidden slot and only fades in
  once it has decoded. With one image this runs once, fading the photograph in
  over the fallback on start.

  Video backgrounds are retired. There is no <video> element on this path, so
  no decoder starts, and nothing needs pausing while the game runs.
-->
<script lang="ts">
  import { onMount, tick } from 'svelte';
  import { approvedOrDefault, DEFAULT_BACKGROUND, imageFor } from './backgrounds';
  import { MOTION_BACKDROP } from './motion';

  export let choice: string = DEFAULT_BACKGROUND;

  type Slot = { src: string; visible: boolean };

  let slots: [Slot, Slot] = [
    { src: '', visible: false },
    { src: '', visible: false },
  ];
  let elements: (HTMLImageElement | null)[] = [null, null];
  let front = 0;
  /* The reactive statement below runs once before the DOM exists. Without this
     guard it would set `current` while both elements are still null, and the
     onMount call would then dedupe against it and do nothing. */
  let mounted = false;
  /* Whether the document is visible, which decides whether the fade animates or
     snaps. See the note on `transition-duration` in the markup. */
  let docVisible = true;

  /* An outgoing image is released only once it has finished fading out. */
  const RELEASE_AFTER = MOTION_BACKDROP + 120;

  let current = '';

  /**
   * Wait until a slot's image has decoded, or has failed.
   *
   * A stall is treated as ready: an image that is merely slow must not leave
   * the background stuck mid-switch. Only a real error counts as failure.
   */
  function decoded(index: number): Promise<'ready' | 'failed'> {
    return new Promise((resolve) => {
      let settled = false;
      const finish = (result: 'ready' | 'failed') => {
        if (settled) return;
        settled = true;
        resolve(result);
      };
      const started = Date.now();
      const attach = () => {
        if (settled) return;
        const image = elements[index];
        if (image) {
          // An error that happened before this listener existed is read off
          // the element: a completed image with no width did not decode.
          if (image.complete) return finish(image.naturalWidth > 0 ? 'ready' : 'failed');
          image.addEventListener('load', () => finish('ready'), { once: true });
          image.addEventListener('error', () => finish('failed'), { once: true });
          return;
        }
        if (Date.now() - started > 1000) return finish('ready');
        requestAnimationFrame(attach);
      };
      attach();
      window.setTimeout(() => finish('ready'), 1500);
    });
  }

  /** Fade the front slot out to the fallback, then release it. */
  function revealFallback() {
    slots[front].visible = false;
    slots = slots;
    const hidden = front;
    const expected = current;
    window.setTimeout(() => {
      if (current !== expected || slots[hidden].visible) return;
      slots[hidden].src = '';
      slots = slots;
    }, RELEASE_AFTER);
  }

  async function show(requested: string) {
    const value = approvedOrDefault(requested);
    if (value === current) return;
    current = value;

    const back = front === 0 ? 1 : 0;
    slots[back].src = imageFor(value);
    slots = slots;
    await tick();

    const result = await decoded(back);
    if (current !== value) return; // superseded while loading

    if (result === 'failed') {
      // The approved image is missing or unreadable. Say so once and leave the
      // warm fallback showing: the one thing this must never do is go black.
      console.warn(`[background] ${imageFor(value)} could not be shown; using the warm fallback`);
      slots[back].src = '';
      slots = slots;
      revealFallback();
      return;
    }

    slots[back].visible = true;
    slots[front].visible = false;
    slots = slots;
    const outgoing = front;
    front = back;
    window.setTimeout(() => {
      if (front === outgoing) return;
      slots[outgoing].src = '';
      slots = slots;
    }, RELEASE_AFTER);
  }

  $: if (mounted) void show(choice);

  onMount(() => {
    mounted = true;
    docVisible = document.visibilityState === 'visible';
    const onVisibility = () => (docVisible = document.visibilityState === 'visible');
    document.addEventListener('visibilitychange', onVisibility);
    void show(choice);
    return () => document.removeEventListener('visibilitychange', onVisibility);
  });
</script>

<div class="backdrop">
  <div class="fallback"></div>

  {#each slots as slot, index}
    {#if slot.src}
      <!-- Opacity is an inline style rather than a class, and the transition is
           switched off entirely while the document is hidden.

           Both halves matter. Chromium freezes a running CSS transition in a
           hidden document at currentTime 0 and does not resume it when the
           document becomes visible again, so the fade stays stuck. This fade
           fires at startup, so any launch into an unfocused or minimised window
           hit it. With a zero duration there is no transition to freeze. -->
      <img
        bind:this={elements[index]}
        class="image"
        style="opacity: {slot.visible ? 1 : 0}; transition-duration: {docVisible
          ? `${MOTION_BACKDROP}ms`
          : '0ms'}"
        src={slot.src}
        alt=""
        aria-hidden="true"
        draggable="false"
      />
    {/if}
  {/each}

  <div class="scrim"></div>
</div>

<style>
  .backdrop {
    position: absolute;
    inset: 0;
    overflow: hidden;
    background: var(--backdrop-base);
  }

  .fallback {
    position: absolute;
    inset: 0;
    background: var(--backdrop-fallback);
  }

  .image {
    position: absolute;
    inset: 0;
    width: 100%;
    height: 100%;
    /* Cover, never stretch: a 16:9 photograph in a 4:3 window loses its edges
       instead of becoming squashed. */
    object-fit: cover;
    transition-property: opacity;
    transition-timing-function: var(--ease-standard);
    filter: var(--backdrop-image-filter);
  }

  .scrim {
    position: absolute;
    inset: 0;
    background: var(--backdrop-scrim);
    pointer-events: none;
  }
</style>

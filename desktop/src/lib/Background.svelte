<!--
  The background: the user's chosen scene, behind the whole launcher.

  WHY IT LOOKED FROZEN AND SOFT

  It was a still. The two-slot video player this file used to be was replaced by
  a single <img> of the Rainforest Storm poster, so nothing could move; and the
  video files it had played were 1600x900 with a Gaussian blur baked in, so
  they were soft even when they did. See docs/backgrounds.md.

  HOW IT PLAYS NOW

  Two layers per background, and nothing drawn by script:

  1. The STILL: the background's own full-resolution frame. Painted at once and
     never removed, so there is never an empty or black window -- while the
     decoder starts, and for good if the video cannot play (a codec the
     WebView lacks, a missing file, a decode error).
  2. The VIDEO, for a video background: <video autoplay muted loop playsinline>
     at its native resolution, `object-fit: cover`, decoded by the WebView's
     own (hardware, where available) VP9 path. It stays transparent until it
     reports `playing`, then fades in over the still, which is frame 0 of the
     same clip -- so the hand-over is invisible.

  It pauses while the document is hidden (minimised, or covered by the game in
  full screen) and while the game is running (`quiet`), never merely because
  the window lost focus: pausing on blur is what makes a launcher that sits
  beside the menu look frozen, but decoding video behind a game that is being
  played only takes GPU time from it. The still stays, so nothing goes black.

  Changing background crossfades the whole pair. The treatment on top is a light
  scrim and nothing else: each background keeps its colour, detail and motion,
  and the control system above is what stays constant.
-->
<script lang="ts">
  import { onMount } from 'svelte';
  import { fade } from 'svelte/transition';
  import { optionFor, DEFAULT_BACKGROUND } from './backgrounds';
  import { MOTION_SURFACE } from './motion';

  export let choice: string = DEFAULT_BACKGROUND;
  /** The game is running: hold the current frame instead of decoding more. */
  export let quiet = false;

  $: option = optionFor(choice);

  let video: HTMLVideoElement | null = null;
  let playing = false;
  let failed = false;
  let hidden = false;
  /* Minimised: WebView2 leaves the document "visible", so the shell says so. */
  let minimised = false;

  /* A new background starts over: its still, then its video once it plays. */
  $: option, reset();
  function reset() {
    playing = false;
    failed = false;
  }

  /* `autoplay` is honoured for muted video, but a play() that the WebView
     rejected (a policy, a decoder still starting) must be retried rather than
     leave the still in place forever. */
  function start() {
    if (!video || hidden || minimised || quiet || failed) return;
    const attempt = video.play();
    if (attempt) attempt.catch(() => {});
  }

  $: if (video) {
    if (hidden || minimised || quiet) video.pause();
    else start();
  }

  onMount(() => {
    const sync = () => (hidden = document.visibilityState === 'hidden');
    sync();
    document.addEventListener('visibilitychange', sync);
    let stop: (() => void) | null = null;
    void (async () => {
      try {
        const { listen } = await import('@tauri-apps/api/event');
        stop = await listen<boolean>('fathom://minimised', (event) => {
          minimised = event.payload;
          /* The rest of the backdrop's motion (glow.css, the bloom) pauses on this too. */
          document.documentElement.toggleAttribute('data-minimised', minimised);
        });
      } catch {
        /* Not in the app shell: the browser's own visibility is enough. */
      }
    })();
    return () => {
      document.removeEventListener('visibilitychange', sync);
      stop?.();
    };
  });
</script>

<div class="backdrop" aria-hidden="true" data-background={option.id} data-kind={option.type}>
  <div class="fallback"></div>
  {#key option.id}
    <div class="scene" transition:fade={{ duration: MOTION_SURFACE }}>
      <img class="media" src={option.still} alt="" draggable="false" />
      {#if option.type === 'video' && !failed}
        <video
          bind:this={video}
          class="media video"
          class:shown={playing}
          src={option.asset}
          width={option.width}
          height={option.height}
          autoplay
          muted
          loop
          playsinline
          disablepictureinpicture
          preload="auto"
          on:canplay={start}
          on:playing={() => (playing = true)}
          on:error={() => (failed = true)}
        ></video>
      {/if}
    </div>
  {/key}
  <div class="scrim"></div>
</div>

<style>
  .backdrop,
  .backdrop > *,
  .scene > * {
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

  /* Cover, never stretch, at the asset's own resolution: the browser scales
     the native frame once, straight to the window. */
  .media {
    width: 100%;
    height: 100%;
    object-fit: cover;
    filter: var(--backdrop-image-filter);
  }

  .video {
    opacity: 0;
    transition: opacity var(--motion-surface) var(--ease-standard);
  }

  .video.shown {
    opacity: 1;
  }

  /* The light normalisation: a gentle top/bottom deepening and vignette so
     type holds over any scene, and no blur. */
  .scrim {
    background: var(--backdrop-scrim);
  }
</style>

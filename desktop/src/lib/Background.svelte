<!--
  The background.

  Gameplay footage behind the whole application. This is the piece that decides
  whether Fathom looks like a client or like a settings dialog, so it is treated
  as part of the composition rather than as wallpaper.

  HOW THE CROSSFADE WORKS

  Two <video> elements, not one. Switching assigns the incoming clip to
  whichever slot is hidden, waits for it to actually have frames, and only then
  fades the opacity across. A single element would leave a gap between `src`
  changing and the first frame decoding, which is the black flash this exists to
  avoid. The outgoing clip is paused once invisible, so only one video decodes
  at rest.

  A slot with no clip renders no element at all. A media element with src="" is
  not an empty one: the empty string resolves against the document URL and the
  element tries to decode the HTML page.

  WHAT IS BAKED AND WHAT IS CSS

  The blur and the per-clip grade are encoded into the files (see
  tools/prepare-background-video.ps1), because a full-screen blur every frame is
  real work on integrated graphics and this costs nothing once. The darkening
  scrim and the saturation trim are CSS, because those are the parts most likely
  to need adjusting against real interface colours, and re-encoding every video
  to try a different scrim would be absurd.
-->
<script lang="ts">
  import { onMount } from 'svelte';

  export let choice: string = 'bg_1';
  /** False while Minecraft runs or the window is hidden. */
  export let playing: boolean = true;

  type Slot = { src: string; visible: boolean };

  let slots: [Slot, Slot] = [
    { src: '', visible: false },
    { src: '', visible: false },
  ];
  let elements: (HTMLVideoElement | null)[] = [null, null];
  let front = 0;
  /* The reactive statement below runs once before the DOM exists. Without this
     guard it would set `current` while both elements are still null, and the
     onMount call would then dedupe against it and do nothing. */
  let mounted = false;
  /* Whether the document is visible, which decides whether the fade animates or
     snaps. See the note on `transition-duration` in the markup. */
  let docVisible = true;

  const isStatic = (value: string) => value === 'static';
  const sourceFor = (value: string) => `/backgrounds/${value}.webm`;

  let current = '';

  async function show(value: string) {
    if (value === current) return;
    current = value;

    if (isStatic(value)) {
      // Fade the footage out to reveal the midnight surface underneath, then
      // stop decoding it.
      slots[front].visible = false;
      slots = slots;
      window.setTimeout(() => {
        if (current !== 'static') return;
        elements[front]?.pause();
        slots[front].src = '';
        slots = slots;
      }, 280);
      return;
    }

    const back = front === 0 ? 1 : 0;
    slots[back].src = sourceFor(value);
    slots = slots;

    // Wait for real frames before crossing over, so the incoming slot never
    // fades in while still empty.
    await new Promise<void>((resolve) => {
      let settled = false;
      const done = () => {
        if (settled) return;
        settled = true;
        resolve();
      };
      // The element appears on the next flush, because a slot with no source
      // renders nothing. Poll for it rather than assuming it is already there.
      const started = Date.now();
      const attach = () => {
        if (settled) return;
        const element = elements[back];
        if (element) {
          if (element.readyState >= 2) {
            done();
            return;
          }
          element.addEventListener('canplay', done, { once: true });
          element.addEventListener('error', done, { once: true });
          return;
        }
        if (Date.now() - started > 1000) {
          done();
          return;
        }
        requestAnimationFrame(attach);
      };
      attach();
      // A stall must not leave the background stuck mid-fade.
      window.setTimeout(done, 1500);
    });

    if (current !== value) return; // superseded while loading
    if (playing) void elements[back]?.play().catch(() => {});

    slots[back].visible = true;
    slots[front].visible = false;
    slots = slots;

    const outgoing = front;
    front = back;

    // Release the previous decoder once it is fully hidden. Only one clip
    // decodes at rest, which is the whole reason for two slots.
    window.setTimeout(() => {
      if (front === outgoing) return;
      elements[outgoing]?.pause();
      slots[outgoing].src = '';
      slots = slots;
    }, 320);
  }

  $: if (mounted) void show(choice);

  $: {
    // Pausing is not politeness: a decoding video is GPU and power the game
    // should have while it is running.
    const element = elements[front];
    if (element && slots[front].src) {
      if (playing) void element.play().catch(() => {});
      else element.pause();
    }
  }

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
  <!-- Always painted, under everything. This is why no state can produce a
       black window: there is never a moment with nothing drawn. -->
  <div class="midnight"></div>

  {#each slots as slot, index}
    {#if slot.src}
      <!-- Opacity is an inline style rather than a class, and the transition is
           switched off entirely while the document is hidden.

           Both halves matter. Chromium freezes a running CSS transition in a
           hidden document at currentTime 0 and does not resume it when the
           document becomes visible again, so the fade stays stuck and the
           background is black forever. This fade fires at startup, so any
           launch into an unfocused or minimised window hit it. With a zero
           duration there is no transition to freeze. -->
      <video
        bind:this={elements[index]}
        class="footage"
        style="opacity: {slot.visible ? 1 : 0}; transition-duration: {docVisible
          ? 'var(--m-slow)'
          : '0ms'}"
        src={slot.src}
        muted
        loop
        playsinline
        preload="auto"
        aria-hidden="true"
      ></video>
    {/if}
  {/each}

  <!-- The scrim. A vertical fall-off rather than a flat wash: the middle is
       where the launch composition sits and where the gameplay should be most
       visible, and the edges meet black chrome. -->
  <div class="scrim"></div>
</div>

<style>
  .backdrop {
    position: absolute;
    inset: 0;
    overflow: hidden;
    background: var(--chrome);
  }

  .midnight {
    position: absolute;
    inset: 0;
    background: linear-gradient(
      170deg,
      var(--midnight-top) 0%,
      var(--midnight-mid) 55%,
      var(--midnight-bottom) 100%
    );
  }

  .footage {
    position: absolute;
    inset: 0;
    width: 100%;
    height: 100%;
    /* Cover, never stretch: scales to the larger axis and lets the other
       overflow, so a 16:9 clip in a 4:3 window loses its edges instead of
       becoming squashed. */
    object-fit: cover;
    /* Opacity and duration come from the inline style above; only the property
       and the curve live here. */
    transition-property: opacity;
    transition-timing-function: var(--ease);
    /* The runtime treatment, deliberately light.
       The previous values desaturated the footage on top of a baked
       gblur=sigma=10, and the combination is what turned recognisable
       Minecraft scenes into coloured blobs. Saturation now goes UP slightly:
       the blur already washes colour out, and the scene needs to be
       identifiable as Minecraft to be worth showing at all. */
    filter: saturate(1.08) contrast(1.06) brightness(1.04);
  }

  .scrim {
    position: absolute;
    inset: 0;
    background:
      /* Darkest where the chrome meets the canvas and lightest through the
         middle, so the footage is most visible exactly where the composition
         sits. Every stop is lighter than the previous build's: readability is
         now the foreground material's job, not the scrim's. */
      linear-gradient(
        to bottom,
        rgba(3, 4, 8, 0.5) 0%,
        rgba(3, 4, 8, 0.2) 24%,
        rgba(3, 4, 8, 0.14) 52%,
        rgba(3, 4, 8, 0.26) 80%,
        rgba(3, 4, 8, 0.56) 100%
      ),
      /* A gentle vignette. Pulls the eye to the centre, which is where the
         launch control is. */
      radial-gradient(
        125% 95% at 50% 46%,
        rgba(0, 0, 0, 0) 45%,
        rgba(0, 0, 0, 0.3) 100%
      );
    pointer-events: none;
  }
</style>

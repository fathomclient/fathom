<!--
  Home.

  A cinematic launch canvas, not a page. The footage is the hero and the entire
  centre is one composition:

              SELECTED PROFILE
              Minecraft 1.8.9
           Legacy Fabric · Balanced
                  PLAY
               small status

  No bordered card, no three loose facts under the button, no explanatory
  paragraph. Files, Java and heap are real information and they belong in
  Recovery and Performance, where somebody goes to look for them.

  THE LAUNCH CONTROL

  It is glass, not a filled rectangle. A dark translucent slab with a lit top
  edge, a real blur, and the footage visible through it — so it reads as an
  object resting on the scene rather than a button drawn over it. When launch is
  possible it takes the accent; when it is not, it stays neutral and says what
  is missing. Same object, two states, never two different controls.

  Implementation detail stays out of the composition entirely. "This build has
  no Microsoft application id" is true, useful to exactly one person, and
  meaningless to everyone else standing in front of a launch screen — so the
  Rust side routes it to `developerNote` and it appears behind a small
  affordance.
-->
<script lang="ts">
  import { fly, fade } from 'svelte/transition';
  import { Info, Play, X } from '@lucide/svelte';
  import { NO_LAUNCH, type HomeView, type LaunchProgress } from '../lib/api';
  import ProfileSwitcher from '../lib/ui/ProfileSwitcher.svelte';
  import FathomProfilePicker from '../lib/ui/FathomProfilePicker.svelte';
  import { EASE, FAST, NORMAL } from '../lib/motion';

  export let view: HomeView;
  export let onAction: (command: string) => void;
  /** The launch in flight. `NO_LAUNCH` when there is not one. */
  export let launch: LaunchProgress = NO_LAUNCH;
  export let onCancel: () => void = () => {};
  export let onDismiss: () => void = () => {};
  export let onProfileChanged: () => void = () => {};
  /** Bumped by the shell when the profile list changed. */
  export let epoch = 0;

  let noteOpen = false;

  $: profile = view.profile;
  $: action = view.action;

  /* The control has three modes, not three controls: the launch button, the
     same object working, and the same object after it settled. Which is why
     every one of these is a boolean over one element rather than an {#if}
     chain that swaps the button out and takes its transition with it. */
  $: busy = launch.active;
  $: settled =
    launch.phase === 'failed' || launch.phase === 'cancelled' || launch.phase === 'finished';
  $: label = busy ? launch.headline : action.label;
  $: line = busy || settled ? launch.detail : action.detail;
  $: bad = settled ? launch.failure !== null : !action.ready;
  /* Type first: "Fathom" answers more of a reader's questions than the loader
     name does, and a Vanilla profile saying so is the point of the setting.

     Then de-duplicated, because two of these fields can legitimately carry the
     same word and printing it twice reads as a rendering fault rather than as
     two facts. A Vanilla profile said "Vanilla · Vanilla"; a profile with no
     preset said "Custom · Vanilla · Custom". */
  $: facts = profile
    ? [
        profile.kindLabel,
        profile.loader,
        profile.preset,
        `${profile.enabledMods} ${profile.enabledMods === 1 ? 'mod' : 'mods'}`,
      ].filter((fact, index, all) => all.indexOf(fact) === index)
    : [];
</script>

<div class="stage">
  {#if profile}
    <div class="composition">
      <!-- The eyebrow already said "Selected profile", so making it the
           control costs no space and removes a trip to another screen. -->
      <div class="switcher" in:fly={{ y: 10, duration: NORMAL, easing: EASE }}>
        <ProfileSwitcher size="hero" align="down" {epoch} onChanged={onProfileChanged} />
        <!-- The loadout inside that installation. A separate control because it is
             a separate thing: the installation is how Minecraft starts, the
             profile is which modules and HUD it starts with. -->
        <FathomProfilePicker align="down" />
      </div>

      <h1 class="hero on-footage" in:fly={{ y: 14, duration: NORMAL, delay: 40, easing: EASE }}>
        {profile.nameIsRedundant ? `Minecraft ${profile.version}` : profile.name}
      </h1>

      <div class="facts on-footage" in:fly={{ y: 12, duration: NORMAL, delay: 80, easing: EASE }}>
        {#each facts as fact, index}
          {#if index > 0}<span class="dot">·</span>{/if}
          <span>{fact}</span>
        {/each}
      </div>

      <div class="launch-wrap" in:fly={{ y: 16, duration: NORMAL, delay: 120, easing: EASE }}>
        <button
          class="launch"
          class:ready={action.ready && !busy}
          class:busy
          disabled={busy || !action.command}
          on:click={() => action.command && onAction(action.command)}
        >
          {#if action.ready && !busy}
            <Play size={22} strokeWidth={2.6} fill="currentColor" />
          {/if}
          <span class="launch-label">{label}</span>

          {#if busy}
            <!-- Sits inside the slab rather than under it: the bar is the
                 button filling up, not a second object that appeared. An
                 unmeasurable stage gets the sweep instead of a number, because
                 a bar parked at a made-up percentage is worse than no bar. -->
            <span class="bar" class:indeterminate={launch.fraction === null}>
              <span
                class="fill"
                style={launch.fraction === null
                  ? ''
                  : `width: ${Math.round(launch.fraction * 100)}%`}
              ></span>
            </span>
          {/if}
        </button>
      </div>

      <div
        class="status on-footage"
        class:warn={bad}
        in:fly={{ y: 10, duration: NORMAL, delay: 160, easing: EASE }}
      >
        <span>{line}</span>

        {#if launch.cancellable}
          <button class="stop" on:click|stopPropagation={onCancel}>Stop</button>
        {/if}

        {#if settled}
          <button class="note-toggle" title="Dismiss" on:click|stopPropagation={onDismiss}>
            <X size={13} strokeWidth={2.2} />
          </button>
        {/if}

        {#if action.developerNote && !busy && !settled}
          <button
            class="note-toggle"
            title="Developer detail"
            on:click|stopPropagation={() => (noteOpen = !noteOpen)}
          >
            <Info size={13} strokeWidth={2.2} />
          </button>
        {/if}
      </div>

      {#if noteOpen && action.developerNote}
        <div class="note glass" transition:fade={{ duration: FAST }}>
          {action.developerNote}
        </div>
      {/if}
    </div>
  {/if}
</div>

<style>
  .stage {
    position: relative;
    display: grid;
    place-items: center;
    height: 100%;
    width: 100%;
    /* Nudged above true centre. Optical centring: a composition placed at exact
       centre reads as slightly low, and the bottom bar adds weight below. */
    padding-bottom: 4vh;
  }

  .composition {
    display: flex;
    flex-direction: column;
    align-items: center;
    text-align: center;
  }


  .switcher {
    display: flex;
    justify-content: center;
    margin-bottom: var(--s-2);
  }

  .hero {
    margin: 10px 0 0;
    /* Scales with the window. A composition fixed in pixels is sized for one
       monitor: at 1366 it fills the canvas and at 1920 it strands the launch
       control in an empty field. The clamp keeps it sensible in either
       direction, and the ceiling keeps it from becoming a poster. */
    font-size: clamp(38px, 3.9vw, 60px);
    font-weight: var(--w-bold);
    letter-spacing: var(--track-tight);
    line-height: 1;
    color: #fff;
    text-shadow:
      0 2px 4px rgba(0, 0, 0, 0.5),
      0 8px 40px rgba(0, 0, 0, 0.7);
  }

  .facts {
    display: flex;
    align-items: center;
    gap: var(--s-2);
    margin-top: var(--s-3);
    font-size: var(--t-body-lg);
    font-weight: var(--w-medium);
    /* Brighter than `muted`: this line sits directly on footage with no
       material behind it. */
    color: #cfd4dd;
  }

  .dot {
    color: var(--text-disabled);
  }

  /* -- the launch control -------------------------------------------------
     Glass, sized generously, and the strongest object on the screen. */
  .launch-wrap {
    margin-top: clamp(28px, 3.4vh, 48px);
  }

  .launch {
    position: relative;
    overflow: hidden;
    display: flex;
    align-items: center;
    justify-content: center;
    gap: var(--s-3);
    min-width: clamp(280px, 23vw, 380px);
    padding: clamp(16px, 1.4vw, 21px) clamp(40px, 3.4vw, 60px);
    border-radius: var(--r-xl);
    font-size: clamp(19px, 1.45vw, 25px);
    font-weight: var(--w-bold);
    letter-spacing: 0.02em;
    color: var(--text);

    /* The material. Neutral state: dark glass over the footage.
       Deliberately lighter than `--glass-fill-strong`: at 0.80 the slab was so
       opaque that the blur behind it could not be seen, which makes an
       expensive material look like a flat rectangle. */
    background: rgba(12, 14, 19, 0.68);
    backdrop-filter: blur(var(--glass-blur-strong)) saturate(1.3);
    -webkit-backdrop-filter: blur(var(--glass-blur-strong)) saturate(1.3);
    border: 1px solid var(--glass-edge-strong);
    box-shadow:
      var(--glass-sheen-strong),
      var(--glass-shadow-strong);

    transition:
      transform var(--m-fast) var(--ease),
      box-shadow var(--m-normal) var(--ease),
      background var(--m-normal) var(--ease),
      border-color var(--m-normal) var(--ease),
      filter var(--m-fast) var(--ease);
  }

  /* Ready: the same slab, tinted. Not a different button -- a state change of
     one object, which is why the transition above covers background and border
     rather than swapping a class of styles. */
  .launch.ready {
    color: #fff;
    background: linear-gradient(180deg, rgba(224, 80, 92, 0.88), rgba(132, 28, 36, 0.9));
    border-color: rgba(255, 160, 168, 0.4);
    box-shadow:
      inset 0 1px 0 rgba(255, 255, 255, 0.28),
      0 20px 54px rgba(0, 0, 0, 0.5),
      0 0 60px var(--accent-glow);
  }

  .launch:not(:disabled):hover {
    transform: translateY(-1px);
    filter: brightness(1.09);
  }

  /* Presses down and loses its lift for the duration -- the one place a
     physical response is worth the pixels. */
  .launch:not(:disabled):active {
    transform: translateY(1px);
    filter: brightness(0.96);
    box-shadow:
      inset 0 2px 8px rgba(0, 0, 0, 0.45),
      0 4px 12px rgba(0, 0, 0, 0.4);
  }

  .launch:disabled {
    cursor: default;
    color: var(--text-muted);
  }

  /* Working. Not clickable, but not greyed either: the label is live
     information about what is happening, and dimming it would make the one
     thing worth reading the hardest thing to read. Source order after
     `:disabled` on purpose -- the two have equal specificity. */
  .launch.busy {
    color: var(--text);
    cursor: progress;
  }

  .launch-label {
    display: block;
    line-height: 1;
  }


  /* The progress bar, inside the slab. */
  .bar {
    position: absolute;
    left: 0;
    right: 0;
    bottom: 0;
    height: 3px;
    overflow: hidden;
    border-radius: 0 0 var(--r-xl) var(--r-xl);
    background: rgba(255, 255, 255, 0.08);
  }

  .fill {
    display: block;
    height: 100%;
    background: var(--accent);
    transition: width var(--m-normal) var(--ease);
  }

  /* No number to show, so the bar says "working" instead of lying about how
     far along it is. */
  .bar.indeterminate .fill {
    width: 34%;
    animation: sweep 1.4s var(--ease) infinite;
  }

  @keyframes sweep {
    from {
      transform: translateX(-110%);
    }
    to {
      transform: translateX(310%);
    }
  }

  @media (prefers-reduced-motion: reduce) {
    .bar.indeterminate .fill {
      width: 100%;
      animation: none;
      opacity: 0.5;
    }
  }

  .stop {
    height: 22px;
    padding: 0 10px;
    border-radius: var(--r-pill);
    font-size: var(--t-caption);
    font-weight: var(--w-medium);
    color: var(--text-muted);
    background: rgba(255, 255, 255, 0.08);
    border: 1px solid var(--chrome-line-strong);
    transition:
      color var(--m-fast) var(--ease),
      background var(--m-fast) var(--ease);
  }

  .stop:hover {
    color: var(--text);
    background: rgba(255, 255, 255, 0.14);
  }

  .status {
    display: flex;
    align-items: center;
    gap: var(--s-2);
    margin-top: clamp(15px, 1.7vh, 24px);
    font-size: var(--t-body-lg);
    color: var(--text-muted);
  }

  .status.warn {
    color: var(--warn);
  }

  .note-toggle {
    display: grid;
    place-items: center;
    width: 21px;
    height: 21px;
    border-radius: var(--r-pill);
    color: var(--text-faint);
    transition:
      color var(--m-fast) var(--ease),
      background var(--m-fast) var(--ease);
  }

  .note-toggle:hover {
    color: var(--text);
    background: var(--fill-hover);
  }

  .note {
    max-width: 430px;
    margin-top: var(--s-4);
    padding: 11px 14px;
    font-size: var(--t-body);
    color: var(--text-muted);
    text-align: left;
    line-height: 1.5;
  }
  /* Installation and profile side by side, wrapping on a narrow window. */
  .switcher {
    display: flex;
    flex-wrap: wrap;
    justify-content: center;
    gap: var(--s-2);
  }
</style>

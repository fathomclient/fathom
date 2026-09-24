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

  The most important object on the screen, and still made of the same glass as
  everything else. Its importance comes from scale, position, type and density,
  not from a colour: when launch is possible it is the densest glass in the
  window with a brighter rim and full ivory type; when it is not, it thins to
  standard glass and says what is missing. Same object, two states, never two
  different controls, and never a solid gold slab.

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
  import Progress from '../lib/ui/Progress.svelte';
  import Notice from '../lib/ui/Notice.svelte';
  import { tooltip } from '../lib/tooltip';
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
          class="launch material-interactive"
          class:material-dense={(action.ready && !busy) || busy}
          class:material-standard={!action.ready && !busy}
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
                 unmeasurable stage drifts instead of showing a number, because
                 a bar parked at a made-up percentage is worse than no bar. -->
            <Progress variant="inset" value={launch.fraction} label={launch.headline || 'Launching'} />
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
          <button class="ui-capsule small material-clear material-interactive" on:click|stopPropagation={onCancel}>Stop</button>
        {/if}

        {#if settled}
          <button class="ui-icon-button small" aria-label="Dismiss" use:tooltip={'Dismiss'} on:click|stopPropagation={onDismiss}>
            <X size={13} strokeWidth={2.2} />
          </button>
        {/if}

        {#if action.developerNote && !busy && !settled}
          <button
            class="ui-icon-button small"
            aria-label="Developer detail"
            use:tooltip={'Developer detail'}
            on:click|stopPropagation={() => (noteOpen = !noteOpen)}
          >
            <Info size={13} strokeWidth={2.2} />
          </button>
        {/if}
      </div>

      {#if noteOpen && action.developerNote}
        <div class="note" transition:fade={{ duration: FAST }}>
          <Notice floating>{action.developerNote}</Notice>
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
    margin: var(--space-3) 0 0;
    /* Scales with the window. A composition fixed in pixels is sized for one
       monitor: at 1366 it fills the canvas and at 1920 it strands the launch
       control in an empty field. */
    font-size: var(--type-display-size);
    font-weight: var(--type-display-weight);
    letter-spacing: var(--type-display-tracking);
    line-height: var(--type-display-leading);
    color: var(--color-text);
    overflow-wrap: anywhere;
  }

  .facts {
    display: flex;
    flex-wrap: wrap;
    justify-content: center;
    align-items: center;
    gap: var(--space-2);
    margin-top: var(--space-3);
    font-size: var(--type-body-size);
    font-weight: var(--type-button-weight);
    color: var(--color-text-secondary);
  }

  .dot {
    color: var(--color-text-tertiary);
  }

  /* -- the launch control ------------------------------------------------- */
  .launch-wrap {
    margin-top: clamp(28px, 3.6vh, 52px);
  }

  .launch {
    --surface-radius: var(--radius-2xl);
    position: relative;
    overflow: hidden;
    display: flex;
    align-items: center;
    justify-content: center;
    gap: var(--space-3);
    min-width: clamp(280px, 22vw, 360px);
    max-width: min(560px, 80vw);
    min-height: clamp(58px, 4.6vw, 68px);
    padding: 0 clamp(36px, 3vw, 52px);
    font-size: clamp(17px, 1.3vw, 21px);
    font-weight: var(--type-title-weight);
    letter-spacing: 0.04em;
    color: var(--color-text-secondary);
  }

  /* Ready: the densest glass in the window, with a brighter lit edge, a
     deeper shadow and full ivory type. Restrained luminance, not colour. */
  .launch.ready {
    color: var(--color-text);
    box-shadow:
      var(--control-rim-strong),
      inset 0 0 0 100vmax var(--mat-veil, transparent),
      var(--shadow-lg);
  }

  /* Not ready: thinner glass, and the label is the thing to read, so it stays
     legible rather than greying out. */
  .launch:disabled {
    cursor: default;
    color: var(--color-text-secondary);
    transform: none;
  }

  /* Working. Not clickable, but not dimmed either: the label is live
     information about what is happening. Source order after `:disabled` on
     purpose -- the two have equal specificity. */
  .launch.busy {
    color: var(--color-text);
    cursor: progress;
  }

  .launch-label {
    display: block;
    line-height: 1.1;
    text-align: center;
  }

  .status {
    display: flex;
    flex-wrap: wrap;
    justify-content: center;
    align-items: center;
    gap: var(--space-2);
    max-width: min(640px, 90vw);
    margin-top: clamp(14px, 1.8vh, 24px);
    font-size: var(--type-body-size);
    color: var(--color-text-secondary);
  }

  .status.warn {
    color: var(--color-warning-text);
  }

  .note {
    max-width: 460px;
    margin-top: var(--space-4);
    text-align: left;
  }

  /* Installation and profile side by side, wrapping on a narrow window. */
  .switcher {
    display: flex;
    flex-wrap: wrap;
    justify-content: center;
    gap: var(--space-2);
  }
</style>

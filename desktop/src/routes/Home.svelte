<script lang="ts">
  import { ICON } from '../lib/metrics.generated';
  import { fade, fly } from 'svelte/transition';
  import { CircleAlert } from '@lucide/svelte';
  import { NO_LAUNCH, type HomeView, type LaunchProgress } from '../lib/api';
  import GraphiteProfileSwitcher from '../lib/graphite/GraphiteProfileSwitcher.svelte';
  import Progress from '../lib/ui/Progress.svelte';
  import FrameTimeline from '../lib/ui/FrameTimeline.svelte';
  import SessionFigures from '../lib/ui/SessionFigures.svelte';
  import { elapsed, gigabytes } from '../lib/session';
  import { EASE, MOTION_EXIT, MOTION_PAGE, MOTION_HOVER } from '../lib/motion';
  import { spotlight } from '../lib/spotlight';

  export let view: HomeView;
  export let onAction: (command: string) => void;
  export let launch: LaunchProgress = NO_LAUNCH;
  export let onCancel: () => void = () => {};
  export let onDismiss: () => void = () => {};
  /** Open the mod recovery page. Offered when a failure may be about mods. */
  export let onRecover: () => void = () => {};
  /** Start again without external mods. */
  export let onSafeLaunch: () => void = () => {};
  /** Open the Logs page, after a launch that went wrong. */
  export let onLogs: () => void = () => {};
  export let onProfileChanged: () => void = () => {};
  export let epoch = 0;

  $: profile = view.profile;
  $: action = view.action;
  /* While the game is up the launch is still "active", but nothing is being
     worked on: the button says so and holds still, and there is no progress
     line, so nothing on this page animates for the length of a session. */
  $: running = launch.phase === 'running';
  $: busy = launch.active && !running;
  $: settled = ['failed', 'cancelled', 'finished'].includes(launch.phase);
  $: title = profile
    ? profile.nameIsRedundant
      ? `Minecraft ${profile.version}`
      : profile.name
    : 'No profile selected';
  $: build = profile
    ? `${profile.loader.toLowerCase()} · ${profile.enabledMods} ${profile.enabledMods === 1 ? 'mod' : 'mods'}`
    : 'Choose a profile to begin';
  /* The backend names its states in capitals ("SIGN IN REQUIRED"); every label
     in the interface is sentence case. */
  const sentence = (value: string) =>
    value ? value.charAt(0).toUpperCase() + value.slice(1).toLowerCase() : value;
  $: label = running ? 'Playing' : busy ? 'Launching' : action.ready ? 'Play' : sentence(action.label);
  $: disabled = launch.active || !action.command;
  /* A failed launch is the one settled state worth a status mark; a cancelled
     or finished one just reports what happened. */
  $: failed = launch.phase === 'failed';
  /* Minecraft ran and then stopped badly: a crash, not a refusal to start. */
  $: crashed = launch.phase === 'finished' && !!launch.failure;

  /* The steps a launch goes through, shown under Play while it works. The
     furthest one reached is held, so a verification pass after a download does
     not walk the line backwards; a step that was not needed is simply passed. */
  const STEPS = ['Check', 'Download', 'Start', 'Confirm'];
  let reached = 0;
  $: if (!launch.active) reached = 0;
  $: if (busy) reached = Math.max(reached, launch.step ?? 0);

  /* The session block replaces the installation summary while the game runs
     and after it ends, until dismissed. */
  $: pulse = launch.pulse ?? null;
  $: session = running || (launch.phase === 'finished' && launch.elapsedSeconds != null);
  /* Evidence, not a diagnosis: said only when the game's own last reading
     shows the heap nearly full as it stopped. */
  $: memoryTight = crashed && !!pulse && pulse.heapMaxMb > 0 && pulse.heapUsedMb / pulse.heapMaxMb >= 0.9;
</script>

<section class="home-stage">
  <div
    class="launch-composition"
    class:wide={session && !!pulse}
    use:spotlight
    in:fly={{ y: 8, duration: MOTION_PAGE, easing: EASE }}
  >
    <div class="heading">
      <span class="eyebrow">{session ? 'This session' : 'Current installation'}</span>
      <h1>{title}</h1>
      <p class="build">
        {#if profile}<span class="tnum">{profile.version}</span><span class="sep">·</span>{/if}{build}
      </p>
    </div>

    <div class="actions">
      <div class="primary-wrap">
        <!-- Three looks for three situations: ready (the bright primary),
             something to do first such as signing in (glass, full-strength
             type -- it is still a live button), and working (glass, the label
             says so, the line below shows how far). -->
        <button
          class="primary"
          class:ready={action.ready && !launch.active}
          class:pending={!action.ready && !launch.active && !!action.command}
          class:busy={launch.active}
          class:running
          {disabled}
          on:click={() => action.command && onAction(action.command)}
        >
          {label}
        </button>
        <!-- Reserved space, so the line appearing does not move anything. A
             launch with no measurable fraction drifts rather than parking at a
             made-up percentage. -->
        <div class="progress">
          {#if busy}
            <div transition:fade={{ duration: MOTION_HOVER }}>
              <Progress value={launch.fraction} label={launch.headline || 'Launching'} />
            </div>
          {/if}
        </div>
        {#if busy}
          <!-- Where the launch is, in four words. Nothing waits on this line:
               it follows the work, and a step not needed is passed over. -->
          <ol class="steps" aria-label="Launch steps" transition:fade={{ duration: MOTION_HOVER }}>
            {#each STEPS as step, index}
              <li class:done={index < reached} class:current={index === reached} aria-current={index === reached ? 'step' : undefined}>
                {step}
              </li>
            {/each}
          </ol>
        {/if}
      </div>

      <GraphiteProfileSwitcher {epoch} onChanged={onProfileChanged} />
    </div>

    <!-- The outcome, or what the launch is doing, directly under the control
         that started it: what happened reads before the details below. -->
    {#if settled && launch.detail}
      <div class="result" class:failed={failed || crashed} in:fade={{ duration: MOTION_HOVER }} out:fade={{ duration: MOTION_EXIT }}>
        <p class="result-line">
          {#if failed || crashed}<span class="mark"><CircleAlert size={ICON.inline} /></span>{/if}
          <span class="text">
            {launch.detail}
            {#if memoryTight && pulse}
              <!-- What the game's last reading showed, not a verdict on why. -->
              <span class="evidence">
                Its last reading had memory nearly full: {gigabytes(pulse.heapUsedMb)} of {gigabytes(pulse.heapMaxMb)} GB.
              </span>
            {/if}
          </span>
        </p>
        <div class="result-actions">
          {#if failed || crashed}
            <button class="ui-capsule small" on:click={onLogs}>View log</button>
          {/if}
          {#if launch.failure && launch.recovery}
            <button class="ui-capsule small" on:click={onRecover}>What changed?</button>
            {#if !launch.safe}
              <button class="ui-capsule small" on:click={onSafeLaunch}>Launch without external mods</button>
            {/if}
          {/if}
          <button class="ui-capsule small ghost" on:click={onDismiss}>Dismiss</button>
        </div>
      </div>
    {:else if busy && launch.detail}
      <div class="result" in:fade={{ duration: MOTION_HOVER }} out:fade={{ duration: MOTION_EXIT }}>
        <p class="result-line">
          <span class="text tnum">{launch.detail}{launch.safe ? ' · without external mods' : ''}</span>
          {#if launch.cancellable}
            <button class="ui-capsule small ghost" on:click={onCancel}>Stop</button>
          {/if}
        </p>
      </div>
    {:else if running && launch.safe}
      <p class="result-line quiet" in:fade={{ duration: MOTION_HOVER }}>Running without external mods</p>
    {/if}

    {#if session}
      <!-- The session: how long, and how it is going or went, as the game
           measured it. Replaces the installation summary for as long as there
           is a session to describe. -->
      <div class="session" in:fade={{ duration: MOTION_PAGE, easing: EASE }}>
        <p class="session-line">
          {#if running}<span class="live" class:paused={!launch.pulseLive} aria-hidden="true"></span>{/if}
          <span class="tnum">
            {running ? 'Playing' : crashed ? 'Ran' : 'Played'} for {elapsed(launch.elapsedSeconds)}
          </span>
          {#if running && pulse && !launch.pulseLive}
            <span class="session-note">· paused, in a menu or in the background</span>
          {/if}
        </p>
        {#if pulse}
          <SessionFigures {pulse} live={running} />
          <FrameTimeline
            {pulse}
            height={36}
            caption={running ? 'Frame time this session · newest on the right' : 'Frame time over the session'}
          />
        {:else if running}
          <!-- The figures come from Fathom's client in the game. A vanilla
               installation has none, and says so rather than promising them. -->
          <p class="session-note quiet">
            {profile?.kind === 'vanilla'
              ? 'This installation runs without the Fathom client, so there are no live figures.'
              : 'Figures appear once you are in a world. The game measures them itself; menus are left out.'}
          </p>
        {/if}
      </div>
    {:else}
    <div class="installation" aria-label="Installation summary">
      <div>
        <span>Client</span>
        <strong>{profile?.kindLabel ?? 'Fathom'}</strong>
      </div>
      <div>
        <span>Preset</span>
        <strong>{profile?.preset ?? '—'}</strong>
      </div>
      <div>
        <span>Modules</span>
        <strong>{profile ? profile.enabledMods : '—'} active</strong>
      </div>
    </div>
    {/if}

  </div>
</section>

<style>
  .home-stage {
    position: relative;
    width: 100%;
    height: 100%;
  }

  /* Inside the shell's content area, which starts after the 76px rail
     column: 28px in puts it where it has always been, 104px from the edge. */
  .launch-composition {
    position: absolute;
    bottom: 40px;
    left: 28px;
    width: min(440px, calc(100vw - 160px));
    padding: 24px;
    border-radius: var(--g-r-panel);
    /* Lit from above by the scene's accent (glow.css). */
    background: var(--glow-sheen), var(--g-mat-standard);
    box-shadow:
      inset 0 0 0 var(--metric-rim) var(--g-mat-standard-rim),
      var(--g-elevation);
    -webkit-backdrop-filter: blur(var(--g-mat-standard-blur)) saturate(1.12);
    backdrop-filter: blur(var(--g-mat-standard-blur)) saturate(1.12);
  }

  /* With a session to show, the panel widens to give five figures room. The
     width eases, so the change reads as the same panel making room. */
  .launch-composition {
    transition: width var(--g-motion-panel) var(--g-ease);
  }

  .launch-composition.wide {
    width: min(540px, calc(100vw - 160px));
  }

  .heading {
    margin-bottom: 18px;
  }

  .eyebrow {
    display: block;
    margin-bottom: 6px;
    color: var(--g-text-secondary);
    font: var(--g-type-caption-weight) var(--g-type-caption-size) / var(--g-type-caption-line) var(--g-type-caption-family);
    letter-spacing: var(--g-type-caption-tracking);
  }

  h1 {
    margin: 0;
    color: var(--g-text-primary);
    font: var(--g-type-title-weight) var(--g-type-title-size) / var(--g-type-title-line) var(--g-type-title-family);
    letter-spacing: var(--g-type-title-tracking);
    text-shadow: var(--backdrop-text-shadow-glass);
  }

  .build {
    margin: 4px 0 0;
    color: var(--g-text-secondary);
    font: var(--g-type-small-weight) var(--g-type-small-size) / var(--g-type-small-line) var(--g-type-small-family);
    text-shadow: var(--backdrop-text-shadow-glass);
  }

  .build .sep {
    margin: 0 6px;
    color: var(--g-text-tertiary);
  }

  .actions {
    display: flex;
    align-items: flex-start;
    gap: 12px;
  }

  .primary-wrap {
    position: relative;
    width: 168px;
  }

  .primary {
    position: relative;
    overflow: hidden;
    isolation: isolate;
    width: 168px;
    height: var(--metric-control-primary);
    border-radius: var(--metric-radius-control);
    background: var(--g-mat-standard);
    color: var(--g-text-disabled);
    font: var(--g-type-primary-weight) var(--g-type-primary-size) / var(--g-type-primary-line) var(--g-type-primary-family);
    box-shadow: inset 0 0 0 var(--metric-rim) var(--g-mat-standard-rim);
    transition:
      color var(--g-motion-fast) var(--g-ease),
      background-color var(--g-motion-fast) var(--g-ease),
      box-shadow var(--g-motion-fast) var(--g-ease),
      transform var(--motion-press) var(--ease-standard);
  }

  /* The same press every control in the launcher has: a denser fill and a
     slight compression. Play had the fill and not the give. */
  .primary:active:not(:disabled) {
    transform: scale(var(--press-scale));
  }

  .primary:disabled {
    cursor: default;
  }

  /* The one thing to press gets the one halo: the accent spilling out
     beneath it, round because it follows the capsule (glow.css). */
  .primary.ready {
    background: var(--g-action);
    color: var(--g-action-text);
    box-shadow: var(--glow-halo);
  }

  .primary.ready:hover {
    background: var(--g-action-hover);
    box-shadow: var(--glow-halo-strong);
  }

  .primary.ready:active {
    background: var(--g-action-pressed);
  }

  .primary.pending {
    color: var(--g-text-primary);
  }

  .primary.pending:hover {
    background: var(--g-mat-dense);
    box-shadow: inset 0 0 0 var(--metric-rim) var(--g-rim-selected);
  }

  .primary.pending:active {
    background: var(--g-mat-dense);
    box-shadow: inset 0 0 0 var(--metric-rim) var(--g-rim-hover);
  }

  .primary.busy {
    color: var(--g-text-secondary);
    cursor: progress;
  }

  /* Working: a soft round pool of the accent drifting across the capsule and
     back, so a launch that is thinking still looks alive. */
  .primary.busy::before {
    content: '';
    position: absolute;
    top: 50%;
    left: 0;
    width: 120px;
    height: 120px;
    margin: -60px 0 0 -60px;
    border-radius: 50%;
    background: radial-gradient(
      circle closest-side,
      var(--glow-halo-colour),
      transparent
    );
    pointer-events: none;
    z-index: -1;
    animation: pool 2.4s ease-in-out infinite alternate;
  }

  .primary.running::before {
    content: none;
  }

  @keyframes pool {
    from {
      transform: translateX(0);
    }
    to {
      transform: translateX(168px);
    }
  }

  @media (prefers-reduced-motion: reduce) {
    .primary.busy::before {
      animation: none;
      transform: translateX(84px);
    }
  }

  .installation {
    display: grid;
    grid-template-columns: repeat(3, 1fr);
    margin-top: 14px;
    padding-top: 14px;
    border-top: var(--hairline) solid var(--g-hairline);
  }

  .installation div {
    min-width: 0;
    padding: 0 12px;
    border-left: var(--hairline) solid var(--g-hairline);
  }

  .installation div:first-child {
    padding-left: 0;
    border-left: 0;
  }

  .installation span,
  .installation strong {
    display: block;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .installation span {
    margin-bottom: 3px;
    color: var(--g-text-tertiary);
    font: var(--g-type-caption-weight) var(--g-type-caption-size) / var(--g-type-caption-line) var(--g-type-caption-family);
  }

  .installation strong {
    color: var(--g-text-primary);
    font: var(--g-type-ui-weight) var(--g-type-ui-size) / var(--g-type-ui-line) var(--g-type-ui-family);
    font-variant-numeric: tabular-nums;
  }

  .primary:focus-visible {
    outline: var(--focus-ring-width) solid var(--color-focus);
    outline-offset: 3px;
  }

  .progress {
    height: 10px;
    padding: 6px 16px 0;
  }

  .result {
    display: flex;
    flex-direction: column;
    gap: var(--g-s-2);
    margin-top: var(--g-s-3);
  }

  .result-line {
    display: flex;
    align-items: center;
    gap: var(--g-s-2);
    min-height: 28px;
    margin: 0;
    color: var(--g-text-secondary);
    font: var(--g-type-small-weight) var(--g-type-small-size) / var(--g-type-small-line) var(--g-type-small-family);
  }

  .result-line.quiet {
    margin-top: var(--g-s-2);
    color: var(--g-text-tertiary);
  }

  .result.failed .result-line {
    align-items: flex-start;
    color: var(--g-text-primary);
  }

  .result .mark {
    display: grid;
    flex-shrink: 0;
    place-items: center;
    height: var(--g-type-small-line);
    color: var(--color-danger-text);
  }

  .result .text {
    flex: 1;
    min-width: 0;
  }

  .evidence {
    display: block;
    margin-top: 2px;
    color: var(--g-text-secondary);
  }

  .result-line .ui-capsule {
    flex-shrink: 0;
    margin-right: -12px;
  }

  /* The actions sit on their own line, so a long failure never squeezes them
     and four of them never run off the panel. */
  .result-actions {
    display: flex;
    flex-wrap: wrap;
    gap: var(--g-s-2);
  }

  /* -- the launch's steps -------------------------------------------------- */
  .steps {
    display: flex;
    gap: var(--g-s-3);
    margin: 6px 0 0;
    padding: 0 16px;
    list-style: none;
    color: var(--g-text-disabled);
    font: var(--g-type-caption-weight) var(--g-type-caption-size) / var(--g-type-caption-line) var(--g-type-caption-family);
    letter-spacing: var(--g-type-caption-tracking);
  }

  .steps li {
    transition: color var(--g-motion-fast) var(--g-ease);
  }

  .steps li.done {
    color: var(--g-text-tertiary);
  }

  .steps li.current {
    color: var(--g-text-primary);
  }

  /* -- the session ----------------------------------------------------------- */
  .session {
    display: flex;
    flex-direction: column;
    gap: var(--g-s-3);
    margin-top: 14px;
    padding-top: 14px;
    border-top: var(--hairline) solid var(--g-hairline);
  }

  .session-line {
    display: flex;
    align-items: center;
    gap: var(--g-s-2);
    margin: 0;
    color: var(--g-text-primary);
    font: var(--g-type-ui-weight) var(--g-type-ui-size) / var(--g-type-ui-line) var(--g-type-ui-family);
  }

  .session-note {
    color: var(--g-text-tertiary);
    font: var(--g-type-small-weight) var(--g-type-small-size) / var(--g-type-small-line) var(--g-type-small-family);
  }

  .session-note.quiet {
    margin: 0;
  }

  /* The running-now dot: the one live mark, in the telemetry blue. It dims
     while the game has nothing new to report rather than pretending to be
     live. It does not pulse: a session lasts an hour, and nothing on this
     page should move for that long. */
  .live {
    flex-shrink: 0;
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: var(--color-signal);
    transition: opacity var(--g-motion-fast) var(--g-ease);
  }

  .live.paused {
    opacity: 0.35;
  }

  @media (max-width: 760px) {
    .launch-composition {
      left: 20px;
      width: min(420px, calc(100vw - 128px));
    }
  }
</style>

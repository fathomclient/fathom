<script lang="ts">
  import { fade, fly } from 'svelte/transition';
  import { CircleAlert } from '@lucide/svelte';
  import { NO_LAUNCH, type HomeView, type LaunchProgress } from '../lib/api';
  import GraphiteProfileSwitcher from '../lib/graphite/GraphiteProfileSwitcher.svelte';
  import Progress from '../lib/ui/Progress.svelte';
  import { EASE, MOTION_EXIT, MOTION_PAGE, MOTION_HOVER } from '../lib/motion';

  export let view: HomeView;
  export let onAction: (command: string) => void;
  export let launch: LaunchProgress = NO_LAUNCH;
  export let onCancel: () => void = () => {};
  export let onDismiss: () => void = () => {};
  export let onProfileChanged: () => void = () => {};
  export let epoch = 0;

  $: profile = view.profile;
  $: action = view.action;
  $: busy = launch.active;
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
  $: label = busy ? 'Launching' : action.ready ? 'Play' : sentence(action.label);
  $: disabled = busy || !action.command;
  /* A failed launch is the one settled state worth a status mark; a cancelled
     or finished one just reports what happened. */
  $: failed = launch.phase === 'failed';
</script>

<section class="home-stage">
  <div class="launch-composition" in:fly={{ y: 8, duration: MOTION_PAGE, easing: EASE }}>
    <div class="heading">
      <span class="eyebrow">CURRENT INSTALLATION</span>
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
          class:ready={action.ready && !busy}
          class:pending={!action.ready && !busy && !!action.command}
          class:busy
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
      </div>

      <GraphiteProfileSwitcher {epoch} onChanged={onProfileChanged} />
    </div>

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

    {#if settled && launch.detail}
      <div class="result" class:failed in:fade={{ duration: MOTION_HOVER }} out:fade={{ duration: MOTION_EXIT }}>
        {#if failed}<span class="mark"><CircleAlert size={14} /></span>{/if}
        <span class="text">{launch.detail}</span>
        <button class="ui-capsule small ghost" on:click={onDismiss}>Dismiss</button>
      </div>
    {:else if busy && launch.detail}
      <div class="result" in:fade={{ duration: MOTION_HOVER }} out:fade={{ duration: MOTION_EXIT }}>
        <span class="text">{launch.detail}</span>
        {#if launch.cancellable}
          <button class="ui-capsule small ghost" on:click={onCancel}>Stop</button>
        {/if}
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
    background: var(--g-mat-standard);
    box-shadow:
      inset 0 0 0 1px var(--g-mat-standard-rim),
      var(--g-elevation);
    -webkit-backdrop-filter: blur(var(--g-mat-standard-blur)) saturate(1.12);
    backdrop-filter: blur(var(--g-mat-standard-blur)) saturate(1.12);
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
    width: 168px;
    height: 44px;
    border-radius: 22px;
    background: var(--g-mat-standard);
    color: var(--g-text-disabled);
    font: var(--g-type-primary-weight) var(--g-type-primary-size) / var(--g-type-primary-line) var(--g-type-primary-family);
    box-shadow: inset 0 0 0 1px var(--g-mat-standard-rim);
    transition:
      color var(--g-motion-fast) var(--g-ease),
      background-color var(--g-motion-fast) var(--g-ease),
      box-shadow var(--g-motion-fast) var(--g-ease);
  }

  .primary:disabled {
    cursor: default;
  }

  .primary.ready {
    background: var(--g-action);
    color: var(--g-action-text);
    box-shadow: none;
  }

  .primary.ready:hover {
    background: var(--g-action-hover);
  }

  .primary.ready:active {
    background: var(--g-action-pressed);
  }

  .primary.pending {
    color: var(--g-text-primary);
  }

  .primary.pending:hover {
    background: var(--g-mat-dense);
    box-shadow: inset 0 0 0 1px var(--g-rim-selected);
  }

  .primary.pending:active {
    background: var(--g-mat-dense);
    box-shadow: inset 0 0 0 1px var(--g-rim-hover);
  }

  .primary.busy {
    color: var(--g-text-secondary);
    cursor: progress;
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
    align-items: center;
    gap: var(--g-s-2);
    min-height: 28px;
    margin-top: var(--g-s-2);
    color: var(--g-text-secondary);
    font: var(--g-type-small-weight) var(--g-type-small-size) / var(--g-type-small-line) var(--g-type-small-family);
  }

  .result.failed {
    color: var(--g-text-primary);
  }

  .result .mark {
    display: grid;
    place-items: center;
    color: var(--color-danger-text);
  }

  .result .text {
    flex: 1;
    min-width: 0;
  }

  .result .ui-capsule {
    flex-shrink: 0;
    margin-right: -12px;
  }

  @media (max-width: 760px) {
    .launch-composition {
      left: 20px;
      width: min(420px, calc(100vw - 128px));
    }
  }
</style>

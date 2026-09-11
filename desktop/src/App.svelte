<!--
  The shell.

  ┌──────────────────────────────────────────┐
  │ TOP BAR      wordmark · route │ version  │
  ├─────┬────────────────────────────────────┤
  │ NAV │           MAIN CONTENT             │
  ├─────┴────────────────────────────────────┤
  │ BOTTOM BAR   state │ profile │ controls  │
  └──────────────────────────────────────────┘

  All three bars are pure black. That is the composition: the chrome FRAMES the
  gameplay rather than floating on it, and the centre is the only place the
  video shows through.

  THE RAIL EXPANDS AS AN OVERLAY, NEVER AS LAYOUT.

  Hovering the navigation floats a labelled tray out over the content. The grid
  column stays 56px wide the whole time, so nothing reflows because a pointer
  crossed the edge of the window — a nav that resizes the page it is navigating
  makes every screen jump when you reach for it.
-->
<script lang="ts">
  import { onMount } from 'svelte';
  import { fade, fly } from 'svelte/transition';
  import {
    Image as ImageIcon,
    ChevronDown,
  } from '@lucide/svelte';

  import './lib/theme.css';
  import Background from './lib/Background.svelte';
  import Home from './routes/Home.svelte';
  import Profiles from './routes/Profiles.svelte';
  import FathomProfiles from './routes/FathomProfiles.svelte';
  import Mods from './routes/Mods.svelte';
  import Performance from './routes/Performance.svelte';
  import Privacy from './routes/Privacy.svelte';
  import Recovery from './routes/Recovery.svelte';
  import Settings_ from './routes/Settings.svelte';
  import Logs from './routes/Logs.svelte';
  import AddOptiFine from './routes/AddOptiFine.svelte';
  import AccountDrawer from './lib/ui/AccountDrawer.svelte';
  import ProfileSwitcher from './lib/ui/ProfileSwitcher.svelte';
  import BackgroundPicker from './lib/ui/BackgroundPicker.svelte';
  import FathomIcon from './lib/ui/FathomIcon.svelte';
  import { FATHOM_ICONS } from './lib/icons.generated';
  import { EASE, FAST, NORMAL } from './lib/motion';
  import {
    background as loadBackground,
    cancelLaunch,
    cancelSignIn,
    homeView,
    launchProgress,
    play,
    setBackground,
    settingsView,
    shellStatus,
    signIn,
    signInDevelopment,
    signInProgress,
    signOut,
    NO_AUTH,
    NO_LAUNCH,
    type AuthProgress,
    type HomeView,
    type LaunchProgress,
    type ShellStatus,
  } from './lib/api';

  /* One icon family throughout (Lucide), and one table. Screens reference a
     route, never an icon file, so swapping in Fathom artwork later means
     editing this and nothing else. */
  const ROUTES = [
    { id: 'home', label: 'Home', icon: FATHOM_ICONS.home },
    { id: 'profiles', label: 'Profiles', icon: FATHOM_ICONS.profiles },
    { id: 'installations', label: 'Installations', icon: FATHOM_ICONS.package },
    { id: 'mods', label: 'Mods', icon: FATHOM_ICONS.mods },
    { id: 'performance', label: 'Performance', icon: FATHOM_ICONS.performance },
    { id: 'privacy', label: 'Privacy', icon: FATHOM_ICONS.privacy },
    { id: 'recovery', label: 'Recovery', icon: FATHOM_ICONS.recovery },
  ];
  const UTILITY = [
    { id: 'logs', label: 'Logs', icon: FATHOM_ICONS.logs },
    { id: 'settings', label: 'Settings', icon: FATHOM_ICONS.settings },
  ];

  export const BACKGROUNDS = [
    { id: 'static', label: 'Midnight' },
    { id: 'bg_1', label: 'Cherry Grove' },
    { id: 'bg_2', label: 'Snowfall' },
    { id: 'bg_3', label: 'Midnight Coast' },
    { id: 'bg_4', label: 'Savanna' },
    { id: 'bg_5rain', label: 'Rainforest Storm' },
    { id: 'bg_5noraintickfrozen', label: 'Rainforest Twilight' },
    { id: 'bg_5noraintickunfrozen', label: 'Rainforest Drift' },
    { id: 'bg_6midnight', label: 'Midnight Metropolis' },
    { id: 'bg_6clear', label: 'Metropolis' },
  ];

  let route = 'home';
  let settingsCategory = 'general';
  let home: HomeView | null = null;
  let status: ShellStatus | null = null;
  let choice = 'bg_1';
  let pickerOpen = false;
  let accountOpen = false;
  let railOpen = false;
  /** The launch in flight, if any. `NO_LAUNCH` the rest of the time. */
  let launch: LaunchProgress = NO_LAUNCH;
  let poll: ReturnType<typeof setInterval> | null = null;
  /** Set while Minecraft runs, so the video stops competing with the game. */
  let gameRunning = false;
  let windowVisible = true;
  let canSignIn = false;
  let developmentAvailable = false;
  let credentialStore = '';
  /** The sign-in in flight, if any. */
  let auth: AuthProgress = NO_AUTH;
  let authPoll: ReturnType<typeof setInterval> | null = null;

  $: playing = windowVisible && !gameRunning;
  $: gameRunning = launch.phase === 'running';

  onMount(() => {
    const onVisibility = () => (windowVisible = document.visibilityState === 'visible');
    document.addEventListener('visibilitychange', onVisibility);
    return () => document.removeEventListener('visibilitychange', onVisibility);
  });

  /* The in-game Hub asking for the launcher's own diagnostics. Rust has already
     raised the window by the time this arrives; all that is left is to be on
     the right page when the user looks at it. */
  onMount(() => {
    let stop: (() => void) | null = null;
    void (async () => {
      try {
        const { listen } = await import('@tauri-apps/api/event');
        stop = await listen('fathom://show-diagnostics', () => {
          route = 'logs';
          railOpen = false;
        });
      } catch {
        /* Not in the app shell. There is no game to ask, either. */
      }
    })();
    return () => stop?.();
  });

  onMount(() => {
    void (async () => {
      [home, status, choice] = await Promise.all([homeView(), shellStatus(), loadBackground()]);
      const settings = await settingsView();
      canSignIn = settings.account.canSignIn;
      developmentAvailable = settings.account.developmentAvailable;
      credentialStore = settings.account.credentialStore;

      /* The stored token is tried on a Rust thread as the app starts, so by the
         time this runs it may already be under way. Adopting it here is what
         stops the drawer saying "not signed in" over a restore in progress. */
      auth = await signInProgress();
      if (auth.active) watchAuth();

      /* A launch outlives the window's script: it runs on a Rust thread, and a
         reload during development — or a second window opening — must find it
         rather than show an idle Play button over a running download. */
      launch = await launchProgress();
      if (launch.active) watch();
    })();
    return () => {
      stopWatching();
      stopWatchingAuth();
    };
  });

  /* -- the launch ---------------------------------------------------------
     Polled at four frames a second while one is live and not at all otherwise.
     See `api::launch` on the Rust side for why this is a poll and not an event
     channel. */
  const POLL = 250;

  function stopWatching() {
    if (poll !== null) {
      clearInterval(poll);
      poll = null;
    }
  }

  function watch() {
    stopWatching();
    poll = setInterval(() => {
      void (async () => {
        launch = await launchProgress();
        if (!launch.active) {
          stopWatching();
          /* The install state and the selected profile may both have changed,
             so the launch composition is rebuilt rather than patched. */
          await refreshShell();
        }
      })();
    }, POLL);
  }

  async function startLaunch() {
    if (launch.active) return;
    try {
      launch = await play();
      watch();
    } catch (cause) {
      launch = {
        ...NO_LAUNCH,
        phase: 'failed',
        headline: 'Launch failed',
        detail: String(cause),
        failure: String(cause),
      };
    }
  }

  async function stopLaunch() {
    launch = await cancelLaunch();
  }

  /* -- signing in ---------------------------------------------------------
     The same poll, over the other supervisor. A device-code sign-in waits on a
     person, so it is slower than a launch and polled less often. */
  const AUTH_POLL = 700;

  function stopWatchingAuth() {
    if (authPoll !== null) {
      clearInterval(authPoll);
      authPoll = null;
    }
  }

  function watchAuth() {
    stopWatchingAuth();
    authPoll = setInterval(() => {
      void (async () => {
        auth = await signInProgress();
        if (!auth.active) {
          stopWatchingAuth();
          await refreshShell();
        }
      })();
    }, AUTH_POLL);
  }

  async function attempt(request: () => Promise<AuthProgress>) {
    try {
      auth = await request();
      if (auth.active) watchAuth();
      else await refreshShell();
    } catch (cause) {
      auth = {
        ...NO_AUTH,
        phase: 'failed',
        headline: 'Sign-in failed',
        detail: String(cause),
        failure: String(cause),
      };
    }
  }

  /** Put the control back to Play after the user has read how it ended. */
  function dismissLaunch() {
    launch = NO_LAUNCH;
  }

  async function chooseBackground(id: string) {
    pickerOpen = false;
    if (id === choice) return;
    choice = id;
    await setBackground(id);
  }

  /* Bumped on every shell refresh, so anything holding its own copy of the
     profile list knows to re-read it. */
  let epoch = 0;

  async function refreshShell() {
    [home, status] = await Promise.all([homeView(), shellStatus()]);
    epoch += 1;
  }

  function go(next: string, category?: string) {
    if (category) settingsCategory = category;
    route = next;
    railOpen = false;
  }

  function onAction(command: string) {
    if (command === 'play') {
      void startLaunch();
      return;
    }
    if ([...ROUTES, ...UTILITY].some((r) => r.id === command)) {
      route = command;
      return;
    }
    if (command === 'sign-in' || command === 'account') accountOpen = true;
    else if (command === 'java') go('recovery');
    else route = 'recovery';
  }

  $: activeLabel =
    route === 'add-optifine'
      ? 'Add OptiFine'
      : ([...ROUTES, ...UTILITY].find((r) => r.id === route)?.label ?? 'Home');
  $: backgroundLabel = BACKGROUNDS.find((b) => b.id === choice)?.label ?? 'Background';
  $: ready = home?.action.ready ?? false;
  /* One line, from whichever of the two knows more. While a launch is under way
     it is the launch; otherwise it is whatever the launch control is saying.
     Previously this was hard-coded to "Sign in required", which was wrong for
     every blocker that is not sign-in. */
  $: stateLine =
    launch.phase === 'idle' ? (home?.action.detail ?? '') : launch.headline || launch.detail;
</script>

<svelte:window
  on:click={() => {
    pickerOpen = false;
  }}
/>

<Background {choice} {playing} />

<div class="shell">
  <!-- TOP BAR -->
  <header class="topbar">
    <div class="brand">
      <!-- The mark, inline rather than fetched. It is five rectangles; an
           <img> would cost a request to say the same thing, and the file it
           fetched would be this. The 24-unit viewBox is the hand-drawn 24px
           grid from `assets/brand/small/mark-24.svg`, so every bar lands on a
           whole pixel at 1x. Decorative: the wordmark beside it already names
           the product to a screen reader. -->
      <svg class="mark" viewBox="0 0 24 24" width="24" height="24" aria-hidden="true">
        <rect x="3" y="3" width="18" height="4" />
        <rect x="3" y="7" width="4" height="14" />
        <rect x="7" y="17" width="8" height="4" />
        <rect x="17" y="7" width="4" height="8" />
        <rect class="plane" x="10" y="10" width="5" height="5" />
      </svg>
      <span class="wordmark">FATHOM</span>
    </div>
    <span class="crumb">{activeLabel}</span>

    <div class="spacer"></div>

    {#if home?.profile}
      <span class="version tnum">{home.profile.version}</span>
    {/if}

    <button class="account" on:click|stopPropagation={() => (accountOpen = true)}>
      <span class="avatar" class:signed={home?.account.signedIn}>
        {home?.account.name?.[0]?.toUpperCase() ?? '?'}
      </span>
      <span>{home?.account.name ?? 'Sign in'}</span>
      <ChevronDown size={13} strokeWidth={2.2} />
    </button>
  </header>

  <!-- LEFT RAIL -->
  <!-- svelte-ignore a11y-no-noninteractive-element-interactions -->
  <nav
    class="rail"
    on:mouseenter={() => (railOpen = true)}
    on:mouseleave={() => (railOpen = false)}
  >
    <div class="rail-inner" class:open={railOpen}>
      <div class="rail-group">
        {#each ROUTES as item}
          <button
            class="nav"
            class:active={route === item.id}
            title={railOpen ? '' : item.label}
            aria-label={item.label}
            on:click={() => go(item.id)}
          >
            <span class="marker"></span>
            <span class="glyph"><FathomIcon source={item.icon} size={19} /></span>
            <span class="nav-label">{item.label}</span>
          </button>
        {/each}
      </div>
      <div class="rail-group">
        {#each UTILITY as item}
          <button
            class="nav"
            class:active={route === item.id}
            title={railOpen ? '' : item.label}
            aria-label={item.label}
            on:click={() => go(item.id)}
          >
            <span class="marker"></span>
            <span class="glyph"><FathomIcon source={item.icon} size={19} /></span>
            <span class="nav-label">{item.label}</span>
          </button>
        {/each}
      </div>
    </div>
  </nav>

  <!-- MAIN -->
  <main class="content">
    {#key route}
      <div class="page" in:fade={{ duration: FAST, easing: EASE }}>
        {#if route === 'home' && home}
          <Home
            view={home}
            {onAction}
            {launch}
            onCancel={() => void stopLaunch()}
            onDismiss={dismissLaunch}
            onProfileChanged={refreshShell}
            {epoch}
          />
        {:else if route === 'profiles'}
          <FathomProfiles />
        {:else if route === 'installations'}
          <Profiles onChanged={refreshShell} onAddOptiFine={() => go('add-optifine')} />
        {:else if route === 'mods'}
          <Mods onChanged={refreshShell} onAddOptiFine={() => go('add-optifine')} />
        {:else if route === 'add-optifine'}
          <AddOptiFine onChanged={refreshShell} />
        {:else if route === 'performance'}
          <Performance onChanged={refreshShell} />
        {:else if route === 'privacy'}
          <Privacy />
        {:else if route === 'recovery'}
          <Recovery />
        {:else if route === 'logs'}
          <Logs />
        {:else if route === 'settings'}
          <Settings_
            bind:category={settingsCategory}
            {choice}
            backgrounds={BACKGROUNDS}
            onBackground={chooseBackground}
            onNavigate={go}
          />
        {/if}
      </div>
    {/key}
  </main>

  <!-- BOTTOM BAR -->
  <footer class="bottombar">
    <div class="zone left">
      <span class="dot" class:ready={ready && launch.phase === 'idle'}></span>
      <span class="state">{stateLine}</span>
      <span class="sep">·</span>
      <span class="muted tnum">Fathom {status?.version ?? ''}</span>
      <span class="muted">{status?.offline ? 'Offline' : 'Online'}</span>
    </div>

    <button class="zone centre" on:click={() => go('installations')} title="Change installation">
      <span class="tnum">{status?.summary ?? ''}</span>
    </button>

    <div class="zone right">
      <!-- The switcher, not a link to Profiles. Picking which setup to launch
           is the second most common thing anybody does here, and it used to be
           possible on exactly one screen. -->
      <ProfileSwitcher {epoch} onChanged={refreshShell} />

      <div class="picker-anchor">
        <button
          class="bar-button"
          class:active={pickerOpen}
          on:click|stopPropagation={() => (pickerOpen = !pickerOpen)}
        >
          <ImageIcon size={13} strokeWidth={2} />
          <span>{backgroundLabel}</span>
        </button>

        {#if pickerOpen}
          <div
            class="picker glass-strong"
            transition:fly={{ y: 6, duration: NORMAL, easing: EASE }}
            on:click|stopPropagation
            on:keydown={(event) => event.key === 'Escape' && (pickerOpen = false)}
            role="menu"
            tabindex="-1"
          >
            <span class="picker-title">Background</span>
            <BackgroundPicker
              options={BACKGROUNDS}
              value={choice}
              onChoose={chooseBackground}
            />
          </div>
        {/if}
      </div>

      <button class="bar-button" on:click={() => go('logs')}>
        <FathomIcon source={FATHOM_ICONS.logs} size={13} />
        <span>Logs</span>
      </button>
    </div>
  </footer>
</div>

{#if accountOpen && home}
  <AccountDrawer
    account={home.account}
    note={home.action.developerNote}
    {canSignIn}
    {developmentAvailable}
    {credentialStore}
    {auth}
    onSignIn={() => void attempt(signIn)}
    onDevelopment={() => void attempt(signInDevelopment)}
    onCancelSignIn={() => void attempt(cancelSignIn)}
    onSignOut={() => void attempt(signOut)}
    onClose={() => (accountOpen = false)}
  />
{/if}

<style>
  .shell {
    position: relative;
    display: grid;
    grid-template-columns: var(--rail-width) 1fr;
    grid-template-rows: var(--topbar-height) 1fr var(--bottombar-height);
    grid-template-areas:
      'top top'
      'rail main'
      'bottom bottom';
    height: 100%;
  }

  /* -- top bar ----------------------------------------------------------- */
  .topbar {
    grid-area: top;
    display: flex;
    align-items: center;
    gap: var(--s-3);
    padding: 0 var(--s-3);
    background: var(--chrome);
    border-bottom: 1px solid var(--chrome-line);
  }

  .brand {
    display: flex;
    align-items: center;
    gap: var(--s-2);
  }

  /* The frame takes the bar's own ink and only the suspended plane is
     coloured, which is the whole construction of the mark: one accent, and the
     gap between the two planes doing the work. No tile behind it -- the top
     bar is already the black the app icon puts under it. */
  .mark {
    display: block;
    width: 24px;
    height: 24px;
    color: var(--text);
    fill: currentColor;
  }

  .mark .plane {
    fill: var(--accent);
  }

  .wordmark {
    font-size: var(--t-body);
    font-weight: var(--w-bold);
    letter-spacing: 0.16em;
  }

  .crumb {
    font-size: var(--t-caption);
    font-weight: var(--w-medium);
    color: var(--text-faint);
    padding-left: var(--s-3);
    border-left: 1px solid var(--chrome-line);
  }

  .spacer {
    flex: 1;
  }

  .version {
    font-size: var(--t-caption);
    color: var(--text-faint);
  }

  .account {
    display: flex;
    align-items: center;
    gap: var(--s-2);
    height: var(--h-sm);
    padding: 0 var(--s-2) 0 var(--s-1);
    border-radius: var(--r-pill);
    font-size: var(--t-caption);
    font-weight: var(--w-medium);
    color: var(--text-muted);
    transition:
      background var(--m-fast) var(--ease),
      color var(--m-fast) var(--ease);
  }

  .account:hover {
    background: var(--fill-hover);
    color: var(--text);
  }

  .avatar {
    display: grid;
    place-items: center;
    width: 20px;
    height: 20px;
    border-radius: var(--r-pill);
    background: var(--fill-active);
    font-size: 10px;
    font-weight: var(--w-semibold);
    color: var(--text);
  }

  .avatar.signed {
    background: linear-gradient(160deg, var(--accent-strong), var(--accent-deep));
    color: #fff;
  }

  /* -- rail -------------------------------------------------------------- */
  .rail {
    grid-area: rail;
    position: relative;
    background: var(--chrome);
    border-right: 1px solid var(--chrome-line);
    z-index: 30;
  }

  /* The tray. Absolutely positioned inside a 56px column, so widening it
     covers the content instead of moving it. */
  .rail-inner {
    position: absolute;
    inset: 0 auto 0 0;
    width: var(--rail-width);
    display: flex;
    flex-direction: column;
    justify-content: space-between;
    padding: var(--s-2) var(--s-2) var(--s-2) 0;
    overflow: hidden;
    background: var(--chrome);
    border-right: 1px solid transparent;
    transition:
      width var(--m-normal) var(--ease),
      box-shadow var(--m-normal) var(--ease),
      border-color var(--m-normal) var(--ease);
  }

  .rail-inner.open {
    width: var(--rail-tray-width);
    border-right-color: var(--chrome-line);
    box-shadow: 12px 0 34px rgba(0, 0, 0, 0.55);
  }

  .rail-group {
    display: flex;
    flex-direction: column;
    gap: 3px;
  }

  .nav {
    position: relative;
    display: flex;
    align-items: center;
    gap: var(--s-3);
    height: 40px;
    /* The glyph stays at the same x whether the tray is open or closed, so the
       icons do not slide sideways when the label appears. */
    padding-left: 10px;
    margin-left: var(--s-2);
    border-radius: var(--r-md);
    color: var(--text-faint);
    white-space: nowrap;
    transition:
      color var(--m-fast) var(--ease),
      background var(--m-fast) var(--ease);
  }

  .nav:hover {
    color: var(--text);
    background: var(--fill-hover);
  }

  .nav.active {
    color: var(--text);
    background: var(--fill-active);
  }

  .glyph {
    display: grid;
    place-items: center;
    width: 20px;
    flex-shrink: 0;
  }

  .nav-label {
    font-size: var(--t-body-lg);
    font-weight: var(--w-medium);
    opacity: 0;
    transform: translateX(-4px);
    transition:
      opacity var(--m-fast) var(--ease),
      transform var(--m-normal) var(--ease);
  }

  .rail-inner.open .nav-label {
    opacity: 1;
    transform: none;
  }

  /* The selection mark sits on the window's edge, the way a real rail does. */
  .marker {
    position: absolute;
    left: -10px;
    width: 3px;
    height: 0;
    border-radius: var(--r-pill);
    background: var(--accent-strong);
    box-shadow: 0 0 10px var(--accent-glow);
    transition: height var(--m-normal) var(--ease);
  }

  .nav.active .marker {
    height: 22px;
  }

  /* -- content ----------------------------------------------------------- */
  .content {
    grid-area: main;
    position: relative;
    overflow: hidden;
  }

  .page {
    position: absolute;
    inset: 0;
  }

  /* -- bottom bar -------------------------------------------------------- */
  .bottombar {
    grid-area: bottom;
    display: grid;
    grid-template-columns: 1fr auto 1fr;
    align-items: center;
    gap: var(--s-3);
    padding: 0 var(--s-3);
    background: var(--chrome);
    border-top: 1px solid var(--chrome-line);
    font-size: var(--t-meta);
  }

  .zone {
    display: flex;
    align-items: center;
    gap: var(--s-2);
  }

  .zone.right {
    justify-content: flex-end;
  }

  .centre {
    justify-self: center;
    padding: 0 var(--s-2);
    height: var(--h-sm);
    border-radius: var(--r-xs);
    font-size: var(--t-caption);
    color: var(--text-muted);
    transition:
      background var(--m-fast) var(--ease),
      color var(--m-fast) var(--ease);
  }

  .centre:hover {
    background: var(--fill-hover);
    color: var(--text);
  }

  .dot {
    width: 7px;
    height: 7px;
    border-radius: var(--r-pill);
    background: var(--warn);
    box-shadow: 0 0 8px currentColor;
  }

  .dot.ready {
    background: var(--good);
  }

  .state {
    font-weight: var(--w-medium);
    color: var(--text-muted);
  }

  .muted {
    color: var(--text-disabled);
  }

  .sep {
    color: var(--text-disabled);
  }

  .bar-button {
    display: flex;
    align-items: center;
    gap: var(--s-1);
    height: var(--h-sm);
    padding: 0 var(--s-2);
    border-radius: var(--r-xs);
    font-size: var(--t-meta);
    color: var(--text-faint);
    transition:
      background var(--m-fast) var(--ease),
      color var(--m-fast) var(--ease);
  }

  .bar-button:hover,
  .bar-button.active {
    background: var(--fill-hover);
    color: var(--text);
  }

  /* -- background popover ------------------------------------------------ */
  .picker-anchor {
    position: relative;
  }

  .picker {
    position: absolute;
    bottom: calc(100% + var(--s-2));
    right: 0;
    width: 300px;
    padding: var(--s-3);
    display: flex;
    flex-direction: column;
    gap: var(--s-2);
    z-index: 20;
  }

  .picker-title {
    font-size: var(--t-meta);
    font-weight: var(--w-semibold);
    letter-spacing: var(--track-caps);
    text-transform: uppercase;
    color: var(--text-faint);
  }
</style>

<!--
  The shell.

  ┌──────────────────────────────────────────────┐
  │ FATHOM  Home              1.8.9  (account) ─□× │   header row: type + glass
  │ ╭──╮                                          │
  │ │⌂ │            MAIN CONTENT                  │   the photograph continues
  │ │▦ │                                          │   behind everything
  │ ╰──╯                                          │
  │ ● state · Fathom 0.1.0   (install)  (switch) │   status line: type + glass
  └──────────────────────────────────────────────┘

  ONE PHOTOGRAPHIC ENVIRONMENT. There are no bars. The photograph fills the
  window, and the header row, the navigation and the status line are pieces of
  glass and legible type laid onto it -- so the window reads as one scene with
  an interface in it, not as a frame around a picture.

  THE RAIL EXPANDS AS AN OVERLAY, NEVER AS LAYOUT.

  Hovering the navigation widens its capsules to show labels over the content.
  The grid column keeps its width the whole time, so nothing reflows because a
  pointer crossed the edge of the window.
-->
<script lang="ts">
  import { ICON } from './lib/metrics.generated';
  import { onMount } from 'svelte';
  import { fade, fly } from 'svelte/transition';
  import { Image as ImageIcon, ChevronDown, User } from '@lucide/svelte';

  import './lib/theme.css';
  import GraphiteBackdrop from './lib/graphite/GraphiteBackdrop.svelte';
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
  import LoadingScreen from './lib/ui/LoadingScreen.svelte';
  import FathomIcon from './lib/ui/FathomIcon.svelte';
  import WindowControls from './lib/ui/WindowControls.svelte';
  import GraphiteWordmark from './lib/graphite/GraphiteWordmark.svelte';
  import { FATHOM_ICONS } from './lib/icons.generated';
  import { BACKGROUNDS, DEFAULT_BACKGROUND, PICKER_AVAILABLE } from './lib/backgrounds';
  import { EASE, EASE_EXIT, MOTION_EXIT, MOTION_PAGE, MOTION_SURFACE } from './lib/motion';
  import { tooltip } from './lib/tooltip';
  import {
    background as loadBackground,
    accountsView,
    cancelLaunch,
    cancelSignIn,
    homeView,
    launchProgress,
    play,
    setBackground,
    settingsView,
    shellStatus,
    signIn,
    reopenSignInPage,
    signInDevelopment,
    signInProgress,
    switchAccount,
    forgetAccount,
    windowShow,
    windowMinimize,
    windowToggleMaximize,
    windowToggleFullscreen,
    windowClose,
    NO_AUTH,
    NO_LAUNCH,
    type AuthProgress,
    type HomeView,
    type AccountSummary,
    type LaunchProgress,
    type ShellStatus,
  } from './lib/api';

  /* One icon family throughout (Lucide geometry, Fathom ids), and one table.
     Screens reference a route, never an icon file. */
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


  let route = 'home';
  let settingsCategory = 'general';
  /* Development only: `?route=mods` opens a page directly, so the screenshot
     pass can photograph every screen without clicking through the shell. */
  const devQuery = import.meta.env.DEV ? new URLSearchParams(window.location.search) : null;
  route = devQuery?.get('route') ?? route;
  settingsCategory = devQuery?.get('category') ?? settingsCategory;
  let home: HomeView | null = null;
  let accounts: AccountSummary[] = [];
  let status: ShellStatus | null = null;
  let choice = DEFAULT_BACKGROUND;
  let pickerOpen = false;
  let accountOpen = devQuery?.get("sheet") === "account";
  let railOpen = false;
  /** The launch in flight, if any. `NO_LAUNCH` the rest of the time. */
  let launch: LaunchProgress = NO_LAUNCH;
  let poll: ReturnType<typeof setInterval> | null = null;
  let canSignIn = false;
  let developmentAvailable = false;
  let credentialStore = '';
  /** The sign-in in flight, if any. */
  let auth: AuthProgress = NO_AUTH;
  let authPoll: ReturnType<typeof setInterval> | null = null;

  /* The in-game Sign In button. Rust has raised the window and started (or
     found) the sign-in; open the drawer on it and follow its progress. */
  onMount(() => {
    let stop: (() => void) | null = null;
    void (async () => {
      try {
        const { listen } = await import('@tauri-apps/api/event');
        stop = await listen('fathom://show-account', () => {
          accountOpen = true;
          void (async () => {
            auth = await signInProgress();
            if (auth.active) watchAuth();
            else await refreshShell();
          })();
        });
      } catch {
        /* Not in the app shell. */
      }
    })();
    return () => stop?.();
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

  /* -- start-up -------------------------------------------------------------
     The window is already showing the loading screen (`main.ts` shows it as
     soon as the static splash has painted). Everything Home needs is asked
     for AT ONCE: these used to be four calls, then settings, then the sign-in,
     then the launch, one round trip after another, with the window hidden
     until the last answered. `pending` names what is still outstanding, most
     important first, for the loading screen's line. */
  let loading = true;
  let pending: string[] = [];

  function tracked<T>(label: string, request: Promise<T>): Promise<T> {
    pending = [...pending, label];
    const settle = () => (pending = pending.filter((item) => item !== label));
    request.then(settle, settle);
    return request;
  }

  onMount(() => {
    void (async () => {
      try {
        const [
          homeResult, statusResult, choiceResult, accountsResult, settings, authResult, launchResult,
        ] = await Promise.all([
          tracked('Checking Java and your installation', homeView()),
          tracked('Reading the launcher', shellStatus()),
          tracked('Loading your background', loadBackground()),
          tracked('Loading accounts', accountsView()),
          tracked('Loading settings', settingsView()),
          signInProgress(),
          launchProgress(),
        ]);
        home = homeResult;
        status = statusResult;
        choice = choiceResult;
        accounts = accountsResult;
        canSignIn = settings.account.canSignIn;
        developmentAvailable = settings.account.developmentAvailable;
        credentialStore = settings.account.credentialStore;

        /* The stored token is tried on a Rust thread as the app starts, so it
           may already be under way. Adopting it here is what stops the drawer
           saying "not signed in" over a restore in progress. The restore does
           not hold up the loading screen: the shell draws the account as
           restoring, and Rust gives up on it after 20 seconds. */
        auth = authResult;
        if (auth.active) watchAuth();

        /* A launch outlives the window's script: it runs on a Rust thread, and a
           reload during development — or a second window opening — must find it
           rather than show an idle Play button over a running download. */
        launch = launchResult;
        if (launch.active) watch();
      } finally {
        /* `finally`: an unexpected data error must not leave the launcher
           behind a loading screen forever. */
        loading = false;
        pending = [];
        /* Harmless if `main.ts` already did it; covers a window it could not. */
        void windowShow();
      }
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
    [home, status, accounts] = await Promise.all([homeView(), shellStatus(), accountsView()]);
    epoch += 1;
  }

  function go(next: string, category?: string) {
    if (category) settingsCategory = category;
    route = next;
    railOpen = false;
  }

  function onAction(command: string) {
    /* `play` owns the complete prepare -> verify -> repair -> launch pipeline.
       Accept the older stage names too so a WebView that was already open when
       the backend updated cannot turn its primary button into a dead end. */
    if (['play', 'install', 'verify', 'repair'].includes(command)) {
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

  /**
   * F11 toggles fullscreen, from any page.
   *
   * On `svelte:window` rather than a per-page binding so it works wherever the
   * user is, which is what a window-level shortcut means. Two things it
   * deliberately does not do:
   *
   * - It does not fire while a text field has focus and the key would mean
   *   something there. F11 means nothing in an input, but the guard is the same
   *   one any window shortcut needs and costs nothing to write correctly.
   * - It does not swallow the key when a modifier is held, so Shift+F11 and the
   *   like stay available to the browser engine and to anything added later.
   */
  function handleGlobalKey(event: KeyboardEvent) {
    if (event.key !== 'F11' || event.ctrlKey || event.altKey || event.shiftKey || event.metaKey) {
      return;
    }
    const target = event.target as HTMLElement | null;
    if (target?.isContentEditable) return;
    const tag = target?.tagName;
    if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return;

    // The WebView has its own F11 handling; ours is the one that should win.
    event.preventDefault();
    void windowToggleFullscreen();
  }
</script>

<svelte:window
  on:click={() => {
    pickerOpen = false;
  }}
  on:keydown={handleGlobalKey}
/>

<!-- One persistent player across every route: navigation never restarts the
     approved recording or replaces it with a page-specific image. -->
<GraphiteBackdrop {choice} />

<LoadingScreen visible={loading} {pending} />

<!-- One photographic environment. Nothing below paints a bar: the header row,
     the navigation and the status line are pieces of glass and type laid onto
     the photograph, which continues behind all of them. -->
<!-- Home is the same shell: the same header, account and rail in the same
     places, so nothing jumps when you leave it. It only drops the crumb (it is
     the root) and the status line (its launch panel already says all of it). -->
<div class="shell" class:home={route === 'home'}>
  <!-- The header row: brand, where you are, the account, and the window's own
       controls. The native strip is disabled; this whole row is the drag
       region, and only its interactive pieces opt out of it. -->
  <header
    class="header"
    data-tauri-drag-region
    role="toolbar"
    tabindex="-1"
    aria-label="Window"
    on:dblclick={() => void windowToggleMaximize()}
  >
    <div class="brand" data-tauri-drag-region>
      <!-- The same simple tracked FATHOM wordmark used by Home and Minecraft. -->
      <span class="brand-mark" data-tauri-drag-region>
        <GraphiteWordmark height={13} />
      </span>
      {#if route !== 'home'}
        <span class="crumb legible" data-tauri-drag-region>{activeLabel}</span>
      {/if}
    </div>

    <div class="spacer" data-tauri-drag-region></div>

    {#if home?.profile}
      <span class="version tnum legible" data-tauri-drag-region>{home.profile.version}</span>
    {/if}

    <button
      class="account ui-capsule material-clear material-interactive"
      aria-haspopup="dialog"
      aria-expanded={accountOpen}
      on:click|stopPropagation={() => (accountOpen = true)}
    >
      <!-- A remembered account is drawn while its session comes back, subdued
           rather than absent. Restoring takes several seconds, and showing
           "Sign in" for them reads as though the launcher forgot the account it
           is in the middle of restoring. `signed` still marks only a live
           session, so nothing here claims to be signed in. -->
      <span
        class="avatar"
        class:signed={home?.account.signedIn}
        class:restoring={home?.account.remembered}
      >
        {#if home?.account.avatar}
          <img class="face" src={home.account.avatar} alt="" draggable="false" />
        {:else if home?.account.name}
          {home.account.name[0].toUpperCase()}
        {:else}
          <User size={ICON.inline} />
        {/if}
      </span>
      <!-- Never cut: a Minecraft name is at most sixteen characters. -->
      <!-- A session that did not come back says so here too, not only in the
           drawer: the name alone would read as signed in. -->
      <span class="account-name" class:restoring-name={home?.account.remembered}>
        {home?.account.needsSignIn ? 'Sign in again' : (home?.account.name ?? 'Sign in')}
      </span>
      <ChevronDown size={ICON.inline} />
    </button>

    <div class="window-controls">
      <WindowControls
        onMinimize={() => void windowMinimize()}
        onMaximize={() => void windowToggleMaximize()}
        onClose={() => void windowClose()}
      />
    </div>
  </header>

  <!-- NAVIGATION. Two floating capsules of standard glass: the pages, and the two
       utilities at the foot. Hovering widens them to show labels as an
       overlay, never as layout, so the page never reflows because a pointer
       crossed the edge of the window. The current page is the denser pill,
       not a coloured bar. -->
  <!-- svelte-ignore a11y-no-noninteractive-element-interactions -->
  <nav
    class="rail"
    aria-label="Pages"
    on:mouseenter={() => (railOpen = true)}
    on:mouseleave={() => (railOpen = false)}
  >
    <div class="rail-group top material-standard" class:open={railOpen}>
      {#each ROUTES as item}
        <button
          class="nav"
          class:active={route === item.id}
          aria-current={route === item.id ? 'page' : undefined}
          aria-label={item.label}
          use:tooltip={railOpen ? '' : item.label}
          on:click={() => go(item.id)}
        >
          <span class="glyph"><FathomIcon name={item.icon} size={ICON.nav} /></span>
          <span class="nav-label">{item.label}</span>
        </button>
      {/each}
    </div>
    <div class="rail-group bottom material-standard" class:open={railOpen}>
      {#each UTILITY as item}
        <button
          class="nav"
          class:active={route === item.id}
          aria-current={route === item.id ? 'page' : undefined}
          aria-label={item.label}
          use:tooltip={railOpen ? '' : item.label}
          on:click={() => go(item.id)}
        >
          <span class="glyph"><FathomIcon name={item.icon} size={ICON.nav} /></span>
          <span class="nav-label">{item.label}</span>
        </button>
      {/each}
    </div>
  </nav>

  <!-- MAIN -->
  <main class="content">
    {#key route}
      <div class="page" in:fade={{ duration: MOTION_PAGE, easing: EASE }}>
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

  <!-- THE STATUS LINE. Type on the photograph and a few capsules, not a bar. -->
  {#if route !== 'home'}
  <footer class="status">
    <div class="zone left legible">
      <span class="dot" class:ready={ready && launch.phase === 'idle'} aria-hidden="true"></span>
      <span class="state">{stateLine}</span>
      <span class="sep" aria-hidden="true">·</span>
      <span class="meta tnum">Fathom {status?.version ?? ''}</span>
      <span class="meta">{status?.offline ? 'Offline' : 'Online'}</span>
    </div>

    <button
      class="zone centre ui-capsule small material-clear material-interactive"
      use:tooltip={'Change installation'}
      on:click={() => go('installations')}
    >
      <span class="tnum summary">{status?.summary ?? ''}</span>
    </button>

    <div class="zone right">
      <!-- The switcher, not a link to Profiles. Picking which setup to launch
           is the second most common thing anybody does here, and it used to be
           possible on exactly one screen. -->
      <ProfileSwitcher {epoch} onChanged={refreshShell} />

      <!-- Only offered when there is a choice to make. With one approved
           background a picker would be a control that does nothing. -->
      {#if PICKER_AVAILABLE}
        <div class="picker-anchor">
          <button
            class="ui-capsule small material-clear material-interactive"
            aria-haspopup="menu"
            aria-expanded={pickerOpen}
            on:click|stopPropagation={() => (pickerOpen = !pickerOpen)}
          >
            <ImageIcon size={ICON.inline} />
            <span>{backgroundLabel}</span>
          </button>

          {#if pickerOpen}
            <div
              class="picker ui-popover"
              in:fly={{ y: 6, duration: MOTION_SURFACE, easing: EASE }}
              out:fade={{ duration: MOTION_EXIT, easing: EASE_EXIT }}
              on:click|stopPropagation
              on:keydown={(event) => event.key === 'Escape' && (pickerOpen = false)}
              role="menu"
              tabindex="-1"
            >
              <span class="picker-title">Background</span>
              <!-- The list is taller than the window has room for, so it scrolls
                   and the title stays put. -->
              <div class="picker-scroll">
                <BackgroundPicker
                  options={BACKGROUNDS}
                  value={choice}
                  onChoose={chooseBackground}
                />
              </div>
            </div>
          {/if}
        </div>
      {/if}

      <button class="ui-capsule small material-clear material-interactive" on:click={() => go('logs')}>
        <FathomIcon name="logs" size={ICON.inline} />
        <span>Logs</span>
      </button>
    </div>
  </footer>
  {/if}
</div>

{#if accountOpen && home}
  <AccountDrawer
    account={home.account}
    {accounts}
    note={home.action.developerNote}
    {canSignIn}
    {developmentAvailable}
    {credentialStore}
    {auth}
    onSignIn={() => void attempt(signIn)}
    onDevelopment={() => void attempt(signInDevelopment)}
    onCancelSignIn={() => void attempt(cancelSignIn)}
    onReopenPage={() => void attempt(reopenSignInPage)}
    onSwitch={(id) => void attempt(() => switchAccount(id))}
    onForget={(id) => void attempt(() => forgetAccount(id))}
    onClose={() => (accountOpen = false)}
  />
{/if}

<style>
  .shell {
    position: relative;
    display: grid;
    grid-template-columns: var(--layout-shell-rail) minmax(0, 1fr);
    grid-template-rows: var(--layout-shell-header) minmax(0, 1fr) var(--layout-shell-status);
    grid-template-areas:
      'top top'
      'rail main'
      'bottom bottom';
    height: 100%;
  }

  /* Home has no status line: its page runs to the bottom of the window. The
     rail keeps its own row, so its lower group sits exactly where it does on
     every other page. */
  .shell.home {
    grid-template-areas:
      'top top'
      'rail main'
      '. main';
  }

  /* -- header row -------------------------------------------------------- */
  .header {
    grid-area: top;
    display: flex;
    align-items: center;
    gap: var(--space-3);
    min-width: 0;
    padding: 0 var(--space-3) 0 var(--space-5);
    user-select: none;
  }

  .brand {
    display: flex;
    align-items: center;
    gap: var(--space-3);
    min-width: 0;
  }

  /* A tight dark edge keeps the light letters defined where the sky behind
     them is brightest. Darker than the photograph, never a glow. */
  .brand-mark {
    display: flex;
    filter: var(--backdrop-mark-shadow);
  }

  .brand-mark :global(svg) {
    pointer-events: none;
  }

  .crumb {
    padding-left: var(--space-3);
    border-left: var(--hairline) solid var(--control-hairline-strong);
    font-size: var(--type-secondary-size);
    font-weight: var(--type-button-weight);
    color: var(--color-text-secondary);
    white-space: nowrap;
  }

  .spacer {
    flex: 1;
    align-self: stretch;
  }

  .version {
    font-size: var(--type-caption-size);
    color: var(--color-text-secondary);
  }

  .account {
    gap: var(--space-2);
    padding: 0 var(--space-3) 0 5px;
  }

  .account-name {
    font-size: var(--type-secondary-size);
  }

  .avatar {
    display: grid;
    place-items: center;
    width: 24px;
    height: 24px;
    flex-shrink: 0;
    overflow: hidden;
    border-radius: var(--radius-pill);
    background-color: var(--control-fill-selected);
    font-size: var(--type-caption-size);
    font-weight: var(--type-heading-weight);
    color: var(--color-text);
  }

  /* ONE rim, over the face (an inset shadow on the element itself would be
     painted beneath the image). It used to be drawn twice -- on the element
     and again here -- which doubled its weight whenever there was no face. */
  .avatar {
    position: relative;
  }

  .avatar::after {
    content: '';
    position: absolute;
    inset: 0;
    border-radius: inherit;
    box-shadow: var(--control-rim);
    pointer-events: none;
  }

  .avatar.signed::after {
    box-shadow: var(--control-rim-strong);
  }

  /* The player's head, cut from their skin: 8x8 art that Rust has already
     enlarged to 64x64 with hard edges. Displayed at 24 CSS pixels, so it is
     DOWNSCALED, and smoothly: nearest-neighbour at 125% or 150% display scaling
     lands on 3.75 or 4.5 device pixels per block, alternating 3- and 4-pixel
     columns that read as a blurry, uneven face. */
  .face {
    width: 100%;
    height: 100%;
    image-rendering: auto;
    object-fit: cover;
  }

  /* Restoring: present but not yet usable. A slow pulse rather than a spinner,
     because this resolves on its own in a few seconds and a spinner would ask
     to be watched. */
  .avatar.restoring,
  .restoring-name {
    animation: account-restoring 1.6s var(--ease-standard) infinite;
  }

  @keyframes account-restoring {
    0%,
    100% {
      opacity: 0.5;
    }
    50% {
      opacity: 0.8;
    }
  }

  @media (prefers-reduced-motion: reduce) {
    .avatar.restoring,
    .restoring-name {
      animation: none;
      opacity: 0.6;
    }
  }

  .window-controls {
    display: flex;
    align-items: center;
    gap: 2px;
    margin-left: var(--space-1);
  }

  /* -- navigation ------------------------------------------------------------ */
  .rail {
    grid-area: rail;
    position: relative;
    z-index: 30;
  }

  /* Each group is a capsule of light glass sized to its contents, anchored to
     the top or the foot of the column. Widening it is an overlay: the column
     itself never changes width. */
  /* The rail's inset is derived, not chosen: whatever centres a 36 control in
     the 48 rail (6), which is also what makes the item's 12 radius concentric
     with the rail's 18. */
  .rail-group {
    --surface-radius: var(--metric-radius-panel);
    --surface-inset: calc((var(--layout-rail-width) - var(--metric-control)) / 2);
    position: absolute;
    left: var(--space-3);
    width: var(--layout-rail-width);
    display: flex;
    flex-direction: column;
    gap: var(--space-1);
    padding: var(--surface-inset);
    overflow: hidden;
    transition:
      width var(--motion-surface) var(--ease-standard),
      box-shadow var(--motion-surface) var(--ease-standard);
  }

  .rail-group.top {
    top: var(--space-1);
  }

  .rail-group.bottom {
    bottom: var(--space-1);
  }

  .rail-group.open {
    width: var(--layout-rail-tray);
    box-shadow: var(--mat-standard-rim), var(--shadow-md);
  }

  .nav {
    position: relative;
    display: flex;
    align-items: center;
    gap: var(--space-3);
    height: var(--metric-control);
    /* The glyph stays at the same x whether the capsule is open or closed, so
       the icons do not slide sideways when the labels appear: centred in the
       closed 36 square. */
    padding-left: calc((var(--metric-control) - var(--metric-icon-nav)) / 2);
    border-radius: var(--metric-radius-control);
    color: var(--color-text-secondary);
    white-space: nowrap;
    transition:
      color var(--motion-hover) var(--ease-standard),
      background-color var(--motion-hover) var(--ease-standard),
      box-shadow var(--motion-hover) var(--ease-standard);
  }

  .nav:hover {
    color: var(--color-text);
    background-color: var(--control-fill);
  }

  /* The current page is the denser, brighter-rimmed pill. */
  .nav.active {
    color: var(--color-text);
    background-color: var(--control-fill-selected);
    box-shadow: var(--control-rim-strong);
  }

  .nav:focus-visible {
    outline: var(--focus-ring-width) solid var(--color-focus);
    outline-offset: -2px;
  }

  .glyph {
    display: grid;
    place-items: center;
    width: var(--metric-icon-nav);
    flex-shrink: 0;
  }

  .nav-label {
    font-size: var(--type-body-size);
    font-weight: var(--type-button-weight);
    opacity: 0;
    transform: translateX(-4px);
    transition:
      opacity var(--motion-hover) var(--ease-standard),
      transform var(--motion-state) var(--ease-standard);
  }

  .rail-group.open .nav-label {
    opacity: 1;
    transform: none;
  }

  /* -- content ------------------------------------------------------------- */
  .content {
    grid-area: main;
    position: relative;
    overflow: hidden;
  }

  .page {
    position: absolute;
    inset: 0;
  }

  /* -- status line --------------------------------------------------------- */
  .status {
    grid-area: bottom;
    display: grid;
    grid-template-columns: minmax(0, 1fr) auto minmax(0, 1fr);
    align-items: center;
    gap: var(--space-3);
    padding: 0 var(--space-4) 0 var(--space-5);
    font-size: var(--type-caption-size);
  }

  .zone {
    display: flex;
    align-items: center;
    min-width: 0;
  }

  /* Not on the centre zone: it is a capsule itself, and its own gap is the
     small capsule's, as for every other capsule in this bar. */
  .zone.left,
  .zone.right {
    gap: var(--space-2);
  }

  .zone.right {
    justify-content: flex-end;
  }

  .zone.centre {
    justify-self: center;
    max-width: 100%;
  }

  .summary {
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .dot {
    width: 7px;
    height: 7px;
    flex-shrink: 0;
    border-radius: var(--radius-pill);
    background-color: var(--color-warning-text);
  }

  .dot.ready {
    background-color: var(--color-success-text);
  }

  .state {
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font-weight: var(--type-button-weight);
    color: var(--color-text);
  }

  .meta,
  .sep {
    flex-shrink: 0;
    color: var(--color-text-secondary);
    white-space: nowrap;
  }

  /* -- background popover (shown only when there is more than one) --------- */
  .picker-anchor {
    position: relative;
  }

  .picker {
    position: absolute;
    bottom: calc(100% + var(--space-2));
    right: 0;
    z-index: 20;
    width: 300px;
    padding: var(--space-3);
    gap: var(--space-2);
    /* Never taller than the window it opens into. */
    max-height: calc(100vh - var(--layout-shell-status) - var(--space-6));
  }

  .picker-scroll {
    overflow-y: auto;
    overscroll-behavior: contain;
    margin: 0 calc(var(--space-1) * -1);
    padding: 2px var(--space-1);
  }

  .picker-title {
    font-size: var(--type-label-size);
    font-weight: var(--type-heading-weight);
    color: var(--color-text-secondary);
  }
</style>

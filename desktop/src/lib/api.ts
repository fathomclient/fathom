/**
 * The typed view of everything Rust will tell us.
 *
 * These mirror the `Serialize` structs in `crates/launcher-desktop/src/api/`.
 * The frontend never constructs launcher state, only renders it: there is no
 * type here for a credential, a path, or anything that would let this side make
 * a decision the core should be making.
 *
 * Note what is absent from every mutating call below: a return type of `void`
 * with an optimistic local update. Toggles return the whole refreshed view, so
 * the page cannot show a switch as on after the core declined to turn it on.
 */
import { invoke } from '@tauri-apps/api/core';
import { DEFAULT_BACKGROUND } from './backgrounds';

export interface ProfileSummary {
  id: string;
  name: string;
  /** True when the name would only repeat the version; the UI drops it. */
  nameIsRedundant: boolean;
  version: string;
  loader: string;
  loaderAvailable: boolean;
  enabledMods: number;
  preset: string;
  /** What the profile is, which the hero says before what it carries. */
  kind: ProfileKindId;
  kindLabel: string;
}

export interface LaunchAction {
  label: string;
  detail: string;
  command: string | null;
  ready: boolean;
  /** Implementation detail, kept out of the main composition. */
  developerNote: string | null;
}

export interface AccountSummary {
  name: string | null;
  signedIn: boolean;
  development: boolean;
  /** The Minecraft UUID, dashed. Public. */
  uuid: string | null;
  /** The player's head as a `data:image/png` URL, or null to show the initial. */
  avatar: string | null;
  /**
   * Remembered, with its session being restored right now. Never true at the
   * same time as `signedIn`: the name and face are who is coming back, not who
   * is here.
   */
  remembered: boolean;
  /**
   * A known account whose session did not come back -- the restore failed or
   * took longer than 20 seconds -- and needs signing in again.
   */
  needsSignIn: boolean;
}

export interface HomeView {
  profile: ProfileSummary | null;
  action: LaunchAction;
  account: AccountSummary;
}

export interface ShellStatus {
  version: string;
  offline: boolean;
  summary: string;
}

/* -- profiles ------------------------------------------------------------ */

export interface ProfileRow {
  /** Opaque. Round-tripped to name a profile, never displayed. */
  id: string;
  name: string;
  version: string;
  loader: string;
  loaderAvailable: boolean;
  preset: string;
  modCount: number;
  memoryMb: number;
  /** What it was set up as, which is not the same as which loader it has. */
  kind: ProfileKindId;
  kindLabel: string;
  /** The parent's name, when this profile derives from one. Never an id. */
  parent: string | null;
  selected: boolean;
  health: 'healthy' | 'attention' | 'unknown';
  lastPlayed: string;
}

export interface LoaderOption {
  id: string;
  label: string;
  available: boolean;
}

export interface IdentityRow {
  label: string;
  value: string;
  /** True when nothing is set, so the page can render it quietly. */
  unset: boolean;
}

export interface ComponentState {
  id: string;
  name: string;
  summary: string;
  state: 'installed' | 'missing' | 'unavailable' | 'optional';
  detail: string;
}

export interface ServerRow {
  name: string;
  address: string;
}

export interface ProfileDetail {
  id: string;
  name: string;
  version: string;
  loader: string;
  memoryMb: number;
  extraArgs: string;
  keepLauncherOpen: boolean;
  kind: ProfileKindId;
  kindLabel: string;
  java: string;
  javaLabel: string;
  javaPath: string | null;
  preset: string;
  identity: IdentityRow[];
  components: ComponentState[];
  servers: ServerRow[];
  parent: string | null;
  /** Fields a derived profile may not change, because it inherits them. */
  inherited: string[];
  loaders: LoaderOption[];
}

/* -- mods ---------------------------------------------------------------- */

export interface ModRow {
  id: string;
  name: string;
  summary: string;
  enabled: boolean;
  canToggle: boolean;
  origin: string;
  version: string;
  size: string;
  warning: string | null;
  missing: boolean;
  /** Which filter chip this belongs to: 'fathom' | 'managed' | 'yours'. */
  group: string;
  license: string;
  /** The repository project, when it came from one. */
  slug: string | null;
  updatable: boolean;
  /** `builtin` (Fathom's client), `component` (another Fathom jar), `external`. */
  kind: 'builtin' | 'component' | 'external';
  /** "Built into Fathom", "From Modrinth", "Your file", ... */
  sourceLabel: string;
  compatibility: { verdict: Verdict | 'unknown'; detail: string } | null;
  /** Problems in the current set that are about this mod. */
  issues: string[];
  /** How many earlier builds can be put back. */
  previous: number;
}

/** What each filter would show, so a chip can say so before it is pressed. */
export interface ModCounts {
  all: number;
  enabled: number;
  fathom: number;
  managed: number;
  yours: number;
  issues: number;
}

/**
 * One first-party component, fully described.
 *
 * Everything the licensing research asks a component to record: what it is,
 * which version, what it works with, where it came from, its hash, its licence,
 * its dependencies and conflicts, and whether Fathom manages it or the user
 * supplied it.
 */
export interface ComponentRow {
  id: string;
  name: string;
  summary: string;
  tier: string;
  state: string;
  blocker: string | null;
  installable: boolean;
  version: string | null;
  fingerprint: string | null;
  license: string;
  minecraft: string;
  loaders: string;
  source: string;
  dependencies: string[];
  conflicts: string[];
  page: string | null;
}

export interface BrowseRow {
  project: string;
  slug: string;
  title: string;
  summary: string;
  downloads: string;
  license: string;
  installed: boolean;
}

export interface BrowseView {
  rows: BrowseRow[];
  total: number;
  offset: number;
  query: string;
  more: boolean;
}

export interface UpdateRow {
  id: string;
  name: string;
  from: string;
  to: string;
  project: string;
}

export interface UpdatesView {
  rows: UpdateRow[];
  blocked: string | null;
  /** How many of this profile's mods could be checked at all. */
  checkable: number;
  total: number;
}

export interface ModProblem {
  message: string;
  severity: string;
  blocking: boolean;
}

export interface LoaderNotice {
  message: string;
  action: string;
  target: string;
  warning: string;
}

export interface ModsView {
  profileName: string;
  rows: ModRow[];
  problems: ModProblem[];
  summary: string;
  /** Whether the loader reads a mods directory at all. Vanilla does not. */
  loaderSupportsMods: boolean;
  /** Present instead of a page of disabled controls, when mods cannot load. */
  loaderNotice: LoaderNotice | null;
  /** Whether Fathom can launch this loader today. */
  loaderLaunchable: boolean;
  loader: string;
  counts: ModCounts;
  /** Why browsing is unavailable, when it is. Null means it works. */
  browseBlocked: string | null;
  components: ComponentRow[];
  /** The Minecraft version this profile runs. */
  minecraft: string;
  /** Minecraft is running for this profile: mod changes apply on its next start. */
  gameRunning: boolean;
}

/* -- performance --------------------------------------------------------- */

export interface PresetOption {
  id: string;
  label: string;
  description: string;
  selected: boolean;
}

export interface OptimizationRow {
  id: string;
  name: string;
  summary: string;
  detail: string;
  enabled: boolean;
  running: boolean;
  status: string;
  readiness: string;
  category: string;
  tone: 'safe' | 'optional' | 'experimental';
  evidence: string | null;
  /** Whether flipping this would change anything. */
  canToggle: boolean;
  /** Whether the launcher is where this is controlled at all. */
  control: 'switch' | 'automatic' | 'inGame';
}

/** Work the registry describes and nothing implements yet. Never switchable. */
export interface PlannedRow {
  id: string;
  name: string;
  summary: string;
  readiness: string;
}

export interface OptimizationGroup {
  id: string;
  label: string;
  rows: OptimizationRow[];
  summary: string;
}

export interface ModuleStatus {
  installed: boolean;
  headline: string;
  detail: string;
  version: string;
}

/** One number with a name, from a measured run. */
export interface Measurement {
  label: string;
  value: string;
}

/**
 * The most recent benchmark this profile recorded.
 *
 * Never a comparison and never a percentage: one run has no baseline, and a
 * number without one would be the "+42% FPS" claim the research forbids.
 */
export interface BenchmarkSummary {
  headline: string;
  label: string;
  frames: Measurement[];
  memory: Measurement[];
  gc: Measurement[];
  caveats: string[];
  /** True when the frame log wrapped, so percentiles cover only the end. */
  partial: boolean;
}

export interface PerformanceView {
  profileName: string;
  presets: PresetOption[];
  groups: OptimizationGroup[];
  module: ModuleStatus;
  /** Planned work, by name. Derived from the registry, never a hand-kept number. */
  planned: PlannedRow[];
  summary: string;
  /** Null until somebody runs a benchmark in game. */
  benchmark: BenchmarkSummary | null;
  benchmarkCount: number;
  /**
   * Set only when this profile's own options.txt is holding the frame rate
   * down. Null is the normal state and the page shows nothing for it.
   */
  videoLimits: VideoLimits | null;
  /** OptiFine's Render Regions, for a profile that runs OptiFine; null otherwise. */
  renderRegions: RenderRegions | null;
  /** The most recent (or running) session, as the game measured it; null before one. */
  lastSession: SessionPulse | null;
}

/** OptiFine's Render Regions: reported and offered, never changed unasked. */
export interface RenderRegions {
  on: boolean;
  /** Render Regions needs VBOs; with them off OptiFine ignores the setting. */
  vboOff: boolean;
  summary: string;
}

/** A profile capped by its own saved video settings. */
export interface VideoLimits {
  /** The saved maxFps. 260 is Minecraft's "Unlimited" and is not a cap. */
  maxFps: number | null;
  vsync: boolean | null;
  /** One line naming what is limiting this profile. */
  summary: string;
}

/* -- settings ------------------------------------------------------------ */

export interface AccountRow {
  name: string;
  signedIn: boolean;
  development: boolean;
  detail: string;
  credentialStore: string;
  canSignIn: boolean;
  /** False in every release build. See `AccountRow::development_available`. */
  developmentAvailable: boolean;
}

export interface ToggleRow {
  id: string;
  label: string;
  detail: string;
  value: boolean;
  section: string;
  overriddenBy: string | null;
}

export interface JavaRow {
  found: boolean;
  headline: string;
  detail: string;
}

export interface AboutRow {
  label: string;
  value: string;
}

export interface ChoiceOption {
  id: string;
  label: string;
  detail: string;
}

/**
 * A setting with more than two states.
 *
 * Deliberately not a `ToggleRow` with an options array: a switch and a
 * three-way choice are different controls set by different commands, and one
 * shared type would put a branch in every consumer of either.
 */
export interface ChoiceRow {
  id: string;
  label: string;
  detail: string;
  section: string;
  value: string;
  options: ChoiceOption[];
}

export interface SettingsView {
  account: AccountRow;
  toggles: ToggleRow[];
  choices: ChoiceRow[];
  java: JavaRow;
  about: AboutRow[];
}

/* -- privacy ------------------------------------------------------------- */

export interface Guarantee {
  label: string;
  detail: string;
}

export interface DestinationRow {
  host: string;
  reason: string;
  allowed: boolean;
}

export interface ActivityRow {
  host: string;
  purpose: string;
  tone: 'ok' | 'denied' | 'failed';
  outcome: string;
}

export interface PrivacyView {
  guarantees: Guarantee[];
  offline: boolean;
  summary: string;
  destinations: DestinationRow[];
  activity: ActivityRow[];
}

/* -- setting a profile up ------------------------------------------------ */

/** `vanilla` | `fathom` | `custom`. Mirrors `ProfileKind`. */
export type ProfileKindId = string;

export interface VersionOption {
  id: string;
  label: string;
}

export interface KindOption {
  id: ProfileKindId;
  label: string;
  summary: string;
  /** What this choice installs, already worded and status-marked by Rust. */
  includes: string[];
  recommended: boolean;
  unavailable: string | null;
}

export interface JavaOption {
  id: string;
  label: string;
  detail: string;
}

export interface ComponentOption {
  id: string;
  name: string;
  summary: string;
  tier: 'core' | 'recommended' | 'optional';
  /** False for anything the launcher cannot install itself. */
  installable: boolean;
  blocker: string | null;
  /** Where the user gets it, when they have to. */
  whereToGet: string | null;
  selected: boolean;
}

/**
 * The answers, as the setup drawer has them.
 *
 * `memoryMb: null` is Automatic. It is an option rather than a magic number so
 * that a wrong reading cannot silently produce a 0 MB heap.
 */
export interface SetupDraft {
  name: string;
  version: string;
  kind: ProfileKindId;
  loader: string | null;
  components: string[];
  memoryMb: number | null;
  java: string;
  javaPath: string | null;
  parent: string | null;
}

export interface SetupOptions {
  versions: VersionOption[];
  kinds: KindOption[];
  loaders: LoaderOption[];
  java: JavaOption[];
  recommendedMemoryMb: number;
  minMemoryMb: number;
  maxMemoryMb: number;
  defaultDraft: SetupDraft;
}

export interface CreatedProfile {
  id: string;
  name: string;
  summary: string;
  /** Components this build could not supply, each with its reason. */
  unavailable: string[];
}

/** What came back from importing a jar the user chose. */
export interface ImportedMod {
  cancelled: boolean;
  name: string;
  version: string | null;
  minecraft: string | null;
  userSupplied: boolean;
  fingerprint: string;
}

/* -- logs ---------------------------------------------------------------- */

export interface LogLine {
  text: string;
  level: 'error' | 'warn' | 'info' | 'debug';
}

export interface LogSource {
  id: string;
  label: string;
  /** Set when there is nothing to show, saying which kind of nothing. */
  note: string | null;
  lines: LogLine[];
}

/* -- signing in ---------------------------------------------------------- */

/**
 * Where a sign-in has got to.
 *
 * Mirrors `api::auth::AuthPhase`. `restoring` is the start-up attempt with a
 * stored token; the rest is the device-code flow.
 */
export type AuthPhase =
  | 'idle'
  | 'restoring'
  | 'starting'
  | 'waiting'
  | 'verifying'
  | 'signedIn'
  | 'failed'
  | 'cancelled';

export interface AuthProgress {
  phase: AuthPhase;
  headline: string;
  detail: string;
  /**
   * The short code to type on Microsoft's page. Present only while `waiting`.
   * A device code, not a credential: it is useless without the account holder
   * typing it themselves, which is why showing it is the point.
   */
  userCode: string | null;
  /** The page to type it on. Opened in the default browser for you, and shown too. */
  verificationUri: string | null;
  /** Whether Fathom opened the page in the browser. */
  browserOpened: boolean;
  /** Whether Fathom copied the device code before opening the page. */
  codeCopied: boolean;
  active: boolean;
  failure: string | null;
}

/* -- launching ----------------------------------------------------------- */

/**
 * Where a launch has got to.
 *
 * Mirrors `api::launch::Phase`. `idle` is the resting state; the five middle
 * values are the ones that keep the screen polling; the last three are how a
 * launch settles.
 */
export type LaunchPhase =
  | 'idle'
  | 'preparing'
  | 'downloading'
  | 'unpacking'
  | 'starting'
  | 'running'
  | 'finished'
  | 'failed'
  | 'cancelled';

export interface LaunchProgress {
  phase: LaunchPhase;
  headline: string;
  detail: string;
  /** 0-1, or null when the work has no measurable length. Never faked. */
  fraction: number | null;
  active: boolean;
  cancellable: boolean;
  failure: string | null;
  /** The failure may be about mods: offer recovery and a launch without them. */
  recovery?: boolean;
  /** This is a launch without external mods. */
  safe?: boolean;
  /** Seconds since the game started; set while it runs and after it ends. */
  elapsedSeconds?: number | null;
  /** The game's own reading of the session, once its module has written one. */
  pulse?: SessionPulse | null;
  /** Whether `pulse` is recent. False in a menu or with the game in the background. */
  pulseLive?: boolean;
  /** 0 check, 1 download, 2 start, 3 confirm the Fathom client, 4 running. */
  step?: number;
}

/**
 * The game's reading of a session (`launcher_core::session_pulse`). Measured in
 * a world with the window focused; menus and loading screens are not in it.
 */
export interface SessionPulse {
  updatedAt: number;
  secondsOfPlay: number;
  frames: number;
  currentFps: number;
  averageFps: number;
  p95Ms: number;
  p99Ms: number;
  worstMs: number;
  /** Frames of 50 ms or longer. */
  hitches: number;
  heapUsedMb: number;
  heapMaxMb: number;
  modules: number;
  bucketSeconds: number;
  /** Frames per second per bucket, oldest first. */
  timelineFps: number[];
  /** The slowest frame in each bucket, in milliseconds. */
  timelineWorstMs: number[];
  marks: { bucket: number; kind: 'world' | 'settings' }[];
}

export interface ConsoleView {
  profile: string;
  state: string;
  running: boolean;
  lines: LogLine[];
  note: string | null;
}

/* -- recovery ------------------------------------------------------------ */

export interface RecoveryAction {
  id: string;
  label: string;
  detail: string;
  verb: string;
  tone: 'normal' | 'caution';
  unavailable: string | null;
}

export interface RecoveryView {
  actions: RecoveryAction[];
  summary: string;
  healthy: boolean;
}

export interface ActionOutcome {
  ok: boolean;
  message: string;
}

/**
 * Whether we are running inside Tauri at all.
 *
 * `npm run dev` in a plain browser is the fastest way to iterate on layout, and
 * it has no Rust behind it. Rather than crash, the callers below fall back to a
 * fixture so the composition can be worked on without a full rebuild.
 *
 * The fixtures are deliberately *awkward* data — a profile with no mods, a
 * blocked launch, an unavailable loader — because a fixture full of happy
 * results produces a design that only looks right when nothing is wrong.
 */
const inTauri = typeof window !== 'undefined' && '__TAURI_INTERNALS__' in window;

const FIXTURE_HOME: HomeView = {
  profile: {
    id: 'minecraft-1-8-9',
    name: 'Minecraft 1.8.9',
    nameIsRedundant: true,
    version: '1.8.9',
    loader: 'Vanilla',
    loaderAvailable: true,
    enabledMods: 0,
    preset: 'Balanced',
    kind: 'fathom',
    kindLabel: 'Fathom',
  },
  action: {
    label: 'SIGN IN REQUIRED',
    detail: 'A Microsoft account is needed to play',
    command: 'sign-in',
    ready: false,
    developerNote:
      'This build has no Microsoft application id, so sign-in is disabled. Set FATHOM_AZURE_CLIENT_ID to use your own registration.',
  },
  account: {
    name: null,
    signedIn: false,
    development: false,
    uuid: null,
    avatar: null,
    remembered: false,
    needsSignIn: false,
  },
};

// Visual-QA-only happy path. The ordinary browser fixture stays deliberately
// awkward; `?proof=home` lets screenshot automation verify the designed Play
// hierarchy without weakening the signed-out state.
const FIXTURE_HOME_PROOF: HomeView = {
  ...FIXTURE_HOME,
  profile: {
    ...FIXTURE_HOME.profile!,
    loader: 'Legacy Fabric',
    enabledMods: 11,
    preset: 'Balanced',
  },
  action: {
    label: 'PLAY',
    detail: 'Ready to launch',
    command: 'play',
    ready: true,
    developerNote: null,
  },
  account: {
    name: 'Player',
    signedIn: true,
    development: false,
    uuid: null,
    avatar: null,
    remembered: true,
    needsSignIn: false,
  },
};

// Visual-QA only: `?proof=signin-again` -- a remembered account whose session
// restore timed out, so the drawer and top bar can be photographed saying so.
const FIXTURE_HOME_SIGNIN_AGAIN: HomeView = {
  ...FIXTURE_HOME,
  account: {
    name: 'Player',
    signedIn: false,
    development: false,
    uuid: null,
    avatar: null,
    remembered: false,
    needsSignIn: true,
  },
};

const PROOF = typeof window !== 'undefined'
  ? new URLSearchParams(window.location.search).get('proof')
  : null;

const FIXTURE_STATUS: ShellStatus = {
  version: '0.1.0',
  offline: false,
  summary: 'Minecraft 1.8.9 · Vanilla · 0 mods',
};

const FIXTURE_PROFILES: ProfileRow[] = [
  {
    id: 'minecraft-1-8-9',
    name: 'Minecraft 1.8.9',
    version: '1.8.9',
    loader: 'Vanilla',
    loaderAvailable: true,
    preset: 'Balanced',
    modCount: 0,
    memoryMb: 2048,
    selected: true,
    kind: 'fathom',
    kindLabel: 'Fathom',
    parent: null,
    health: 'healthy',
    lastPlayed: 'Never',
  },
];

const FIXTURE_MODS: ModsView = {
  profileName: 'Minecraft 1.8.9',
  rows: [],
  problems: [],
  summary: '0 mods · 0 on',
  counts: { all: 0, enabled: 0, fathom: 0, managed: 0, yours: 0, issues: 0 },
  browseBlocked: 'Mod repositories are turned off in Privacy settings.',
  components: [],
  minecraft: '1.8.9',
  gameRunning: false,
  loaderSupportsMods: false,
  loaderNotice: {
    message: 'Mods need a loader.',
    action: 'Switch to Legacy Fabric',
    target: 'legacy-fabric',
    warning: 'Changing the loader takes effect next launch.',
  },
  loaderLaunchable: true,
  loader: 'Vanilla',
};

const FIXTURE_PERFORMANCE: PerformanceView = {
  profileName: 'Minecraft 1.8.9',
  presets: [
    {
      id: 'balanced',
      label: 'Balanced',
      description: 'Everything we have benchmarked ourselves.',
      selected: true,
    },
    { id: 'competitive', label: 'Competitive', description: 'Adds unmeasured work.', selected: false },
    { id: 'maximum-fps', label: 'Maximum FPS', description: 'Everything, including experimental.', selected: false },
    { id: 'custom', label: 'Custom', description: 'Your own selection.', selected: false },
  ],
  groups: [],
  module: {
    installed: false,
    headline: 'Optimization module not added',
    detail: 'Java settings still apply.',
    version: '0.2.0',
  },
  planned: [],
  summary: '0 active',
  benchmark: null,
  benchmarkCount: 0,
  videoLimits: null,
  renderRegions: null,
  lastSession: null,
};

/* Visual QA only: `?session=preparing|downloading|starting|running|ended|crashed`
   puts the launch in that state in the browser, with a synthetic reading, so
   each state can be looked at without a game. Never used in the app. */
const SESSION_PROOF = typeof window !== 'undefined'
  ? new URLSearchParams(window.location.search).get('session')
  : null;

function fixturePulse(buckets: number): SessionPulse {
  const fps = Array.from({ length: buckets }, (_, index) => {
    const base = index < buckets * 0.55 ? 212 : 248;
    return Math.round((base + Math.sin(index * 1.7) * 9 + Math.sin(index * 0.37) * 6) * 10) / 10;
  });
  const worst = fps.map((rate, index) =>
    Math.round((1000 / rate) * (index % 13 === 7 ? 6.2 : 1.6 + (Math.sin(index * 2.3) + 1) * 0.35) * 10) / 10,
  );
  return {
    updatedAt: Date.now(),
    secondsOfPlay: buckets * 10,
    frames: Math.round(buckets * 10 * 231),
    currentFps: fps[fps.length - 1] ?? 0,
    averageFps: 231.4,
    p95Ms: 5.21,
    p99Ms: 7.84,
    worstMs: 41.6,
    hitches: 0,
    heapUsedMb: 1210,
    heapMaxMb: 4096,
    modules: 9,
    bucketSeconds: 10,
    timelineFps: fps,
    timelineWorstMs: worst,
    marks: [
      { bucket: 2, kind: 'world' },
      { bucket: Math.floor(buckets * 0.55), kind: 'settings' },
    ],
  };
}

function fixtureLaunch(): LaunchProgress {
  const base = { ...NO_LAUNCH };
  switch (SESSION_PROOF) {
    case 'preparing':
      return { ...base, phase: 'preparing', headline: 'Checking', detail: 'Verifying what is on disk', active: true, cancellable: true, step: 0 };
    case 'downloading':
      return { ...base, phase: 'downloading', headline: 'Downloading', detail: '412 of 771 · 84 MB', fraction: 0.534, active: true, cancellable: true, step: 1 };
    case 'starting':
      return { ...base, phase: 'starting', headline: 'Starting', detail: 'Confirming the Fathom client', active: true, step: 3 };
    case 'running':
      return { ...base, phase: 'running', headline: 'Minecraft is running', detail: 'Running for 42 minutes', active: true, step: 4, elapsedSeconds: 2534, pulse: fixturePulse(60), pulseLive: true };
    case 'ended':
      return { ...base, phase: 'finished', headline: 'Session ended', detail: 'Minecraft exited normally.', elapsedSeconds: 4380, pulse: fixturePulse(90) };
    case 'crashed':
      return { ...base, phase: 'finished', headline: 'Minecraft stopped', detail: 'Minecraft crashed (exit code -1).', failure: 'Minecraft crashed (exit code -1).', recovery: true, elapsedSeconds: 312, pulse: fixturePulse(14) };
    default:
      return NO_LAUNCH;
  }
}

const FIXTURE_SETTINGS: SettingsView = {
  account: {
    name: 'Not signed in',
    signedIn: false,
    development: false,
    detail: 'A Microsoft account is needed to play online.',
    credentialStore: 'Windows Credential Manager',
    canSignIn: false,
    developmentAvailable: false,
  },
  toggles: [],
  choices: [],
  java: { found: false, headline: 'No Java found', detail: 'Minecraft 1.8.9 needs Java 8.' },
  about: [],
};

const FIXTURE_PRIVACY: PrivacyView = {
  guarantees: [],
  offline: false,
  summary: 'No network requests this session',
  destinations: [],
  activity: [],
};

const FIXTURE_RECOVERY: RecoveryView = {
  actions: [],
  summary: 'Java is present and no problems were found',
  healthy: true,
};

export async function homeView(): Promise<HomeView> {
  if (!inTauri) {
    // Development only: `?slow=2000` holds Home's data back, so the loading
    // screen can be seen and photographed outside the app shell.
    if (import.meta.env.DEV && typeof window !== 'undefined') {
      const slow = Number(new URLSearchParams(window.location.search).get('slow') ?? 0);
      if (slow > 0) await new Promise((resolve) => setTimeout(resolve, slow));
    }
    if (PROOF === 'home') return FIXTURE_HOME_PROOF;
    if (PROOF === 'signin-again') return FIXTURE_HOME_SIGNIN_AGAIN;
    return FIXTURE_HOME;
  }
  return invoke<HomeView>('home_view');
}

export async function shellStatus(): Promise<ShellStatus> {
  if (!inTauri) return FIXTURE_STATUS;
  return invoke<ShellStatus>('shell_status');
}

export async function background(): Promise<string> {
  if (!inTauri) return DEFAULT_BACKGROUND;
  return invoke<string>('background');
}

export async function setBackground(choice: string): Promise<void> {
  if (!inTauri) return;
  await invoke('set_background', { choice });
}

/**
 * The launcher's own appearance switches: the glass accessibility modes and the
 * letter case. Stored beside the background in `appearance.json`; Windows'
 * "Transparency effects" setting applies on top of the glass ones through the
 * media query, whatever they say.
 */
export interface AppearanceModes {
  reduceTransparency: boolean;
  reduceBlur: boolean;
  /** Off (the default) sets the interface in lowercase; on shows the copy as written. */
  standardCapitalization: boolean;
}

export const DEFAULT_APPEARANCE_MODES: AppearanceModes = {
  reduceTransparency: false,
  reduceBlur: false,
  standardCapitalization: false,
};

export async function appearanceModes(): Promise<AppearanceModes> {
  if (!inTauri) return DEFAULT_APPEARANCE_MODES;
  return invoke<AppearanceModes>('appearance_modes');
}

export async function setAppearanceModes(modes: AppearanceModes): Promise<void> {
  if (!inTauri) return;
  await invoke('set_appearance_modes', { modes });
}

/* -- profiles ------------------------------------------------------------ */

export async function profilesView(): Promise<ProfileRow[]> {
  if (!inTauri) return FIXTURE_PROFILES;
  return invoke<ProfileRow[]>('profiles_view');
}

export async function profileDetail(id: string): Promise<ProfileDetail> {
  if (!inTauri) {
    return {
      id,
      name: 'Minecraft 1.8.9',
      version: '1.8.9',
      loader: 'vanilla',
      memoryMb: 2048,
      extraArgs: '',
      keepLauncherOpen: true,
      kind: 'vanilla',
      kindLabel: 'Vanilla',
      java: 'automatic',
      javaLabel: 'Automatic',
      javaPath: null,
      preset: 'Custom',
      identity: [],
      components: [],
      servers: [],
      parent: null,
      inherited: [],
      loaders: [
        { id: 'vanilla', label: 'Vanilla', available: true },
        { id: 'legacy-fabric', label: 'Legacy Fabric', available: false },
        { id: 'forge', label: 'Forge', available: true },
      ],
    };
  }
  return invoke<ProfileDetail>('profile_detail', { id });
}

/**
 * A profile's artwork as a `data:` URI, or null.
 *
 * See `docs/profile-art.md`. Returns null rather than throwing when there is
 * none, because "no artwork" is the normal case and not an error.
 */
export async function profileArt(id: string): Promise<string | null> {
  if (!inTauri) return null;
  return invoke<string | null>('profile_art', { id });
}

export async function selectProfile(id: string): Promise<void> {
  if (!inTauri) return;
  await invoke('select_profile', { id });
}

export async function createProfile(name: string): Promise<void> {
  if (!inTauri) return;
  await invoke('create_profile', { name });
}

export async function renameProfile(id: string, name: string): Promise<void> {
  if (!inTauri) return;
  await invoke('rename_profile', { id, name });
}

export async function duplicateProfile(id: string): Promise<void> {
  if (!inTauri) return;
  await invoke('duplicate_profile', { id });
}

export async function deleteProfile(id: string): Promise<void> {
  if (!inTauri) return;
  await invoke('delete_profile', { id });
}

export interface ProfileEdit {
  id: string;
  name: string;
  memoryMb: number;
  extraArgs: string;
  keepLauncherOpen: boolean;
  loader: string;
  /** Absent leaves the profile's existing policy alone. */
  java?: string;
}

export async function saveProfile(edit: ProfileEdit): Promise<void> {
  if (!inTauri) return;
  await invoke('save_profile', { edit });
}

/* -- mods ---------------------------------------------------------------- */

export async function modsView(profile?: string): Promise<ModsView> {
  if (!inTauri) return FIXTURE_MODS;
  return invoke<ModsView>('mods_view', { profile: profile ?? null });
}

/**
 * Search the mod repository.
 *
 * Reaches the network, and only ever because somebody typed in a search box:
 * Rust mints a per-action grant from this call and refuses without one.
 */
export async function browseMods(query: string, offset = 0, profile?: string): Promise<BrowseView> {
  if (!inTauri) {
    return { rows: [], total: 0, offset: 0, query, more: false };
  }
  return invoke<BrowseView>('browse_mods', { profile: profile ?? null, query, offset });
}

/** Install a mod from the browse results, by the repository's project id. */
export async function installMod(project: string, profile?: string): Promise<ModsView> {
  if (!inTauri) return FIXTURE_MODS;
  return invoke<ModsView>('install_mod', { profile: profile ?? null, project });
}

/** Install a first-party component the user asked for, such as Fabric API. */
export async function installComponent(component: string, profile?: string): Promise<ModsView> {
  if (!inTauri) return FIXTURE_MODS;
  return invoke<ModsView>('install_component', { profile: profile ?? null, component });
}

/** Which managed mods have a newer build. One request per updatable mod. */
export async function modUpdates(profile?: string): Promise<UpdatesView> {
  if (!inTauri) {
    return { rows: [], blocked: 'Not running in the app.', checkable: 0, total: 0 };
  }
  return invoke<UpdatesView>('mod_updates', { profile: profile ?? null });
}

/**
 * Open a mod's page on the repository.
 *
 * A slug, never a URL: Rust builds the address from a host it spells out and a
 * slug it checks, so this side cannot name a destination.
 */
export async function openProjectPage(slug: string): Promise<void> {
  if (!inTauri) return;
  return invoke<void>('open_project_page', { slug });
}

export async function setModEnabled(
  id: string,
  enabled: boolean,
  profile?: string,
): Promise<ModsView> {
  if (!inTauri) return FIXTURE_MODS;
  return invoke<ModsView>('set_mod_enabled', { id, enabled, profile: profile ?? null });
}

export async function removeMod(id: string, profile?: string): Promise<ModsView> {
  if (!inTauri) return FIXTURE_MODS;
  return invoke<ModsView>('remove_mod', { id, profile: profile ?? null });
}

export async function addMod(profile?: string): Promise<ModsView> {
  if (!inTauri) return FIXTURE_MODS;
  return invoke<ModsView>('add_mod', { profile: profile ?? null });
}

/* -- performance --------------------------------------------------------- */

export async function performanceView(profile?: string): Promise<PerformanceView> {
  if (!inTauri) {
    return SESSION_PROOF ? { ...FIXTURE_PERFORMANCE, lastSession: fixturePulse(90) } : FIXTURE_PERFORMANCE;
  }
  return invoke<PerformanceView>('performance_view', { profile: profile ?? null });
}

export async function setPreset(preset: string, profile?: string): Promise<PerformanceView> {
  if (!inTauri) return FIXTURE_PERFORMANCE;
  return invoke<PerformanceView>('set_preset', { preset, profile: profile ?? null });
}

export async function unlockFrameRate(profile?: string): Promise<PerformanceView> {
  if (!inTauri) return FIXTURE_PERFORMANCE;
  return invoke<PerformanceView>('unlock_frame_rate', { profile: profile ?? null });
}

export async function setRenderRegions(on: boolean, profile?: string): Promise<PerformanceView> {
  if (!inTauri) return FIXTURE_PERFORMANCE;
  return invoke<PerformanceView>('set_render_regions', { on, profile: profile ?? null });
}

export async function setOptimization(
  id: string,
  enabled: boolean,
  profile?: string,
): Promise<PerformanceView> {
  if (!inTauri) return FIXTURE_PERFORMANCE;
  return invoke<PerformanceView>('set_optimization', { id, enabled, profile: profile ?? null });
}

/* -- settings, privacy, recovery ----------------------------------------- */

export async function settingsView(): Promise<SettingsView> {
  if (!inTauri) return FIXTURE_SETTINGS;
  return invoke<SettingsView>('settings_view');
}

export async function setSetting(id: string, value: boolean): Promise<SettingsView> {
  if (!inTauri) return FIXTURE_SETTINGS;
  return invoke<SettingsView>('set_setting', { id, value });
}

export async function setChoice(id: string, value: string): Promise<SettingsView> {
  if (!inTauri) return FIXTURE_SETTINGS;
  return invoke<SettingsView>('set_choice', { id, value });
}

export async function privacyView(): Promise<PrivacyView> {
  if (!inTauri) return FIXTURE_PRIVACY;
  return invoke<PrivacyView>('privacy_view');
}

export async function recoveryView(): Promise<RecoveryView> {
  if (!inTauri) return FIXTURE_RECOVERY;
  return invoke<RecoveryView>('recovery_view');
}

export async function logsView(): Promise<LogSource[]> {
  if (!inTauri) {
    return [
      { id: 'launcher', label: 'Launcher', note: 'Not running in the app.', lines: [] },
      { id: 'game', label: 'Game', note: 'Not running in the app.', lines: [] },
    ];
  }
  return invoke<LogSource[]>('logs_view');
}

export async function runRecovery(id: string): Promise<ActionOutcome> {
  if (!inTauri) return { ok: true, message: 'Nothing to do outside the app.' };
  return invoke<ActionOutcome>('run_recovery', { id });
}

/* -- launching ----------------------------------------------------------- */

/**
 * The resting progress, used outside the app and as the initial value.
 *
 * Exported because more than one component needs "no launch is happening" and
 * a second literal would be a second thing to keep in step with `Phase::Idle`.
 */
export const NO_LAUNCH: LaunchProgress = {
  phase: 'idle',
  headline: '',
  detail: '',
  fraction: null,
  active: false,
  cancellable: false,
  failure: null,
};

/** Start a launch. Returns as soon as the work is on its way, not when it ends. */
/**
 * Start the selected profile. `withoutExternalMods` starts it with Fathom's
 * own components only, for this launch: nothing is deleted or changed.
 */
export async function play(withoutExternalMods = false): Promise<LaunchProgress> {
  if (!inTauri) return NO_LAUNCH;
  return invoke<LaunchProgress>('play', { withoutExternalMods });
}

/** Ask the launch in flight to stop. Only does anything before the game starts. */
export async function cancelLaunch(): Promise<LaunchProgress> {
  if (!inTauri) return NO_LAUNCH;
  return invoke<LaunchProgress>('cancel_launch');
}

export async function launchProgress(): Promise<LaunchProgress> {
  if (!inTauri) return fixtureLaunch();
  return invoke<LaunchProgress>('launch_progress');
}

export async function consoleView(): Promise<ConsoleView> {
  if (!inTauri) {
    return {
      profile: '',
      state: 'No session',
      running: false,
      lines: [],
      note: 'Not running in the app.',
    };
  }
  return invoke<ConsoleView>('console_view');
}

export async function closeConsole(): Promise<void> {
  if (!inTauri) return;
  await invoke('close_console');
}

/**
 * Which window this bundle is rendering in.
 *
 * The game console is the same frontend under a second label rather than a
 * second bundle: one build, one set of styles, one place to fix a bug. Reading
 * the label is how it knows which of the two to draw.
 *
 * Read once at module load. Tauri sets it before any script runs, and a window
 * does not change its label.
 */
export const windowLabel: string = inTauri
  ? ((window as unknown as {
      __TAURI_INTERNALS__: { metadata?: { currentWindow?: { label?: string } } };
    }).__TAURI_INTERNALS__.metadata?.currentWindow?.label ?? 'main')
  : 'main';

/* -- signing in ---------------------------------------------------------- */

/** Rust's `AuthProgress::restore_timed_out` sentence, for the browser fixture. */
const RESTORE_TIMED_OUT =
  'Restoring your saved session is taking too long. Sign in again to continue.';

export const NO_AUTH: AuthProgress = {
  phase: 'idle',
  headline: '',
  detail: '',
  userCode: null,
  verificationUri: null,
  browserOpened: false,
  codeCopied: false,
  active: false,
  failure: null,
};

export async function signIn(): Promise<AuthProgress> {
  if (!inTauri) return NO_AUTH;
  return invoke<AuthProgress>('sign_in');
}

/**
 * Sign in with the development identity.
 *
 * Refused unless the binary was built with `dev-auth` and run with the
 * environment variable set. It owns nothing and cannot join an online-mode
 * server; every view that names it is flagged `development`.
 */
export async function signInDevelopment(): Promise<AuthProgress> {
  if (!inTauri) return NO_AUTH;
  return invoke<AuthProgress>('sign_in_development');
}

export async function cancelSignIn(): Promise<AuthProgress> {
  if (!inTauri) return NO_AUTH;
  return invoke<AuthProgress>('cancel_sign_in');
}

/** Open Microsoft's sign-in page again for the code on screen. */
export async function reopenSignInPage(): Promise<AuthProgress> {
  if (!inTauri) return NO_AUTH;
  return invoke<AuthProgress>('reopen_sign_in_page');
}

export async function signInProgress(): Promise<AuthProgress> {
  if (!inTauri) {
    return PROOF === 'signin-again'
      ? {
          ...NO_AUTH,
          phase: 'failed',
          headline: 'Sign in again',
          detail: RESTORE_TIMED_OUT,
          failure: RESTORE_TIMED_OUT,
        }
      : NO_AUTH;
  }
  return invoke<AuthProgress>('sign_in_progress');
}

export async function signOut(): Promise<AuthProgress> {
  if (!inTauri) return NO_AUTH;
  return invoke<AuthProgress>('sign_out');
}

export async function accountsView(): Promise<AccountSummary[]> {
  if (!inTauri) return [];
  return invoke<AccountSummary[]>('accounts_view');
}

export async function switchAccount(id: string): Promise<AuthProgress> {
  if (!inTauri) return NO_AUTH;
  return invoke<AuthProgress>('switch_account', { id });
}

export async function forgetAccount(id: string): Promise<AuthProgress> {
  if (!inTauri) return NO_AUTH;
  return invoke<AuthProgress>('forget_account', { id });
}

export async function windowShow(): Promise<void> {
  if (inTauri) await invoke('window_show');
}

export async function windowMinimize(): Promise<void> {
  if (inTauri) await invoke('window_minimize');
}

export async function windowToggleMaximize(): Promise<void> {
  if (inTauri) await invoke('window_toggle_maximize');
}

/** Toggle the launcher between windowed and fullscreen. Returns the new state. */
export async function windowToggleFullscreen(): Promise<boolean> {
  if (!inTauri) return false;
  return invoke<boolean>('window_toggle_fullscreen');
}

export async function windowClose(): Promise<void> {
  if (inTauri) await invoke('window_close');
}

/* -- setting a profile up ------------------------------------------------ */

const FIXTURE_SETUP: SetupOptions = {
  versions: [{ id: '1.8.9', label: 'Minecraft 1.8.9' }],
  kinds: [
    {
      id: 'vanilla',
      label: 'Vanilla',
      summary: 'Minecraft as it ships. No mods.',
      includes: ['Minecraft only'],
      recommended: false,
      unavailable: null,
    },
    {
      id: 'fathom',
      label: 'Fathom',
      summary: "Fathom's loader, modules and performance stack.",
      includes: ['Legacy Fabric', 'Fathom Performance'],
      recommended: true,
      unavailable: null,
    },
    {
      id: 'custom',
      label: 'Custom',
      summary: 'Choose the loader and components yourself.',
      includes: ['Your choice of loader and components'],
      recommended: false,
      unavailable: null,
    },
  ],
  loaders: [],
  java: [{ id: 'automatic', label: 'Automatic', detail: 'Uses the best runtime available.' }],
  recommendedMemoryMb: 2048,
  minMemoryMb: 512,
  maxMemoryMb: 16384,
  defaultDraft: {
    name: 'Fathom 1.8.9',
    version: '1.8.9',
    kind: 'fathom',
    loader: null,
    components: [],
    memoryMb: null,
    java: 'automatic',
    javaPath: null,
    parent: null,
  },
};

export async function setupOptions(): Promise<SetupOptions> {
  if (!inTauri) return FIXTURE_SETUP;
  return invoke<SetupOptions>('setup_options');
}

export async function setupComponents(
  loader: string,
  selected: string[],
): Promise<ComponentOption[]> {
  if (!inTauri) return [];
  return invoke<ComponentOption[]>('setup_components', { loader, selected });
}

/**
 * What is wrong with the answers so far.
 *
 * Asked of Rust rather than checked here: the creation path runs the same
 * validation, and a second copy of the rules in TypeScript is a second copy
 * that can disagree.
 */
export async function setupProblems(draft: SetupDraft): Promise<string[]> {
  if (!inTauri) return [];
  return invoke<string[]>('setup_problems', { draft });
}

export async function createProfileFromSetup(draft: SetupDraft): Promise<CreatedProfile> {
  if (!inTauri) return { id: 'x', name: draft.name, summary: '', unavailable: [] };
  return invoke<CreatedProfile>('create_profile_from_setup', { draft });
}

export async function deriveProfile(parent: string, name: string): Promise<void> {
  if (!inTauri) return;
  await invoke('derive_profile', { parent, name });
}

/** Import a jar the user picks. Rust opens the picker; no path crosses over. */
export async function importMod(profile?: string): Promise<ImportedMod> {
  if (!inTauri) {
    return {
      cancelled: true,
      name: '',
      version: null,
      minecraft: null,
      userSupplied: false,
      fingerprint: '',
    };
  }
  return invoke<ImportedMod>('import_mod', { profile });
}

/**
 * Import a jar the user dropped onto the window.
 *
 * The path comes from the OS drop event, not from this side knowing anything
 * about the disk, and Rust checks it as though it were hostile either way.
 */
export async function importDroppedMod(path: string, profile?: string): Promise<ImportedMod> {
  if (!inTauri) {
    return {
      cancelled: true,
      name: '',
      version: null,
      minecraft: null,
      userSupplied: false,
      fingerprint: '',
    };
  }
  return invoke<ImportedMod>('import_dropped_mod', { path, profile });
}

/**
 * A jar the user supplied, and where it could go.
 *
 * The Add OptiFine flow's first half. Nothing has been added to a profile yet:
 * the file has been verified, hashed, copied into the local store and
 * identified, and `profiles` says which of the user's profiles it suits.
 */
export type SuppliedJar = {
  chosen: boolean;
  name: string;
  description: string;
  version: string | null;
  minecraft: string | null;
  fileName: string;
  hash: string;
  size: number;
  entries: number;
  source: string;
  recognised: boolean;
  isOptifine: boolean;
  /** True when the jar was identified from its bytecode, not its filename. */
  verified: boolean;
  /** Set when this jar cannot run without another component. */
  bridge: SuppliedBridge | null;
  profiles: SuppliedProfileFit[];
};

/** A component the supplied jar depends on. */
export type SuppliedBridge = {
  /** Component id, for installComponent(). */
  id: string;
  name: string;
  summary: string;
  /** One sentence explaining why it is needed. */
  reason: string;
};

export type SuppliedProfileFit = {
  id: string;
  name: string;
  compatible: boolean;
  alreadyPresent: boolean;
  note: string;
  /** This profile would still be missing the bridge the jar needs. */
  needsBridge: boolean;
};

export type SuppliedAdded = {
  profileName: string;
  message: string;
  /** What Fathom will stand down for it, already worded. */
  standdowns: string[];
};

const NO_JAR: SuppliedJar = {
  chosen: false,
  name: '',
  description: '',
  version: null,
  minecraft: null,
  fileName: '',
  hash: '',
  size: 0,
  entries: 0,
  source: '',
  recognised: false,
  isOptifine: false,
  verified: false,
  bridge: null,
  profiles: [],
};

/** Choose a jar. Rust opens the picker, so no path crosses over. */
export async function supplyJar(): Promise<SuppliedJar> {
  if (!inTauri) return NO_JAR;
  return invoke<SuppliedJar>('supply_jar');
}

/**
 * Accept a jar the user dropped on the window.
 *
 * The path comes from the OS drop event rather than from this side knowing
 * anything about the disk, and Rust checks it as though it were hostile either
 * way — an existing file, a `.jar` extension, and then the bytes themselves.
 */
export async function supplyDroppedJar(path: string): Promise<SuppliedJar> {
  if (!inTauri) return NO_JAR;
  return invoke<SuppliedJar>('supply_dropped_jar', { path });
}

/** What was supplied last, so leaving the screen and coming back keeps it. */
export async function suppliedJar(): Promise<SuppliedJar> {
  if (!inTauri) return NO_JAR;
  return invoke<SuppliedJar>('supplied_jar');
}

/** Add the supplied jar to a profile. */
export async function addSuppliedJar(profile: string): Promise<SuppliedAdded> {
  if (!inTauri) return { profileName: '', message: '', standdowns: [] };
  return invoke<SuppliedAdded>('add_supplied_jar', { profile });
}

/** Open one of the pages Rust knows about, by id. Never a URL from here. */
export async function openPage(id: string): Promise<void> {
  if (!inTauri) return;
  await invoke('open_page', { id });
}

export async function setLoader(loader: string, profile?: string): Promise<void> {
  if (!inTauri) return;
  await invoke('set_loader', { id: profile, loader });
}

/* -- Fathom profiles ------------------------------------------------------ */

/*
 * Loadouts: modules, settings, HUD and keys. Not installations: a Minecraft
 * version, loader or Java runtime is never part of one. The launcher and the
 * running game read and write the same files (docs/fathom-profiles.md), so
 * this list is exactly what the in-game Profiles page shows.
 *
 * Choosing one here sets the profile the next game opens. A game that is
 * already running switches in game, where it is immediate.
 */

export interface FathomProfileSummary {
  id: string;
  name: string;
  modulesOn: number;
  hudWidgets: number;
  updatedAt: number;
  live: boolean;
}

export interface FathomProfilesView {
  profiles: FathomProfileSummary[];
  /** The profile the next game opens. */
  live: string;
  /** Documents that would not load, with the reason. */
  problems: string[];
}

const FIXTURE_FATHOM_PROFILES: FathomProfilesView = {
  profiles: [
    { id: 'bedwars', name: 'Bedwars', modulesOn: 3, hudWidgets: 1, updatedAt: 0, live: true },
    { id: 'default', name: 'Default', modulesOn: 0, hudWidgets: 0, updatedAt: 0, live: false },
    { id: 'survival', name: 'Survival', modulesOn: 4, hudWidgets: 1, updatedAt: 0, live: false },
  ],
  live: 'bedwars',
  problems: [],
};

export async function fathomProfilesView(): Promise<FathomProfilesView> {
  if (!inTauri) return FIXTURE_FATHOM_PROFILES;
  return invoke<FathomProfilesView>('fathom_profiles_view');
}

export async function switchFathomProfile(id: string): Promise<FathomProfilesView> {
  if (!inTauri) return FIXTURE_FATHOM_PROFILES;
  return invoke<FathomProfilesView>('fathom_profile_switch', { id });
}

export async function createFathomProfile(name: string): Promise<FathomProfilesView> {
  if (!inTauri) return FIXTURE_FATHOM_PROFILES;
  return invoke<FathomProfilesView>('fathom_profile_create', { name });
}

export async function renameFathomProfile(id: string, name: string): Promise<FathomProfilesView> {
  if (!inTauri) return FIXTURE_FATHOM_PROFILES;
  return invoke<FathomProfilesView>('fathom_profile_rename', { id, name });
}

export async function duplicateFathomProfile(id: string): Promise<FathomProfilesView> {
  if (!inTauri) return FIXTURE_FATHOM_PROFILES;
  return invoke<FathomProfilesView>('fathom_profile_duplicate', { id });
}

export async function deleteFathomProfile(id: string): Promise<FathomProfilesView> {
  if (!inTauri) return FIXTURE_FATHOM_PROFILES;
  return invoke<FathomProfilesView>('fathom_profile_delete', { id });
}

/** The profile as a JSON document, for copying to somebody else. */
export async function exportFathomProfile(id: string): Promise<string> {
  if (!inTauri) return '{}';
  return invoke<string>('fathom_profile_export', { id });
}

/** A document from elsewhere, under a free id. Never overwrites a profile. */
export async function importFathomProfile(json: string): Promise<FathomProfilesView> {
  if (!inTauri) return FIXTURE_FATHOM_PROFILES;
  return invoke<FathomProfilesView>('fathom_profile_import', { json });
}

/* -- the mod browser ----------------------------------------------------------
   Mirrors `crates/launcher-desktop/src/api/browse.rs`. Every call that can
   reach the network runs on a Rust worker, outside the launcher's lock, so a
   slow index never freezes the window. There are no fixtures here: outside
   the app these calls fail, and the page says the browser needs Fathom. */

export type CatalogProvider = 'modrinth' | 'curseforge';
export type Verdict = 'compatible' | 'unverified' | 'incompatible';
export type Breadth = 'tagged' | 'plausible' | 'any-loader';
export type CatalogSort = 'relevance' | 'downloads' | 'updated' | 'newest';
export type ReleaseChannel = 'release' | 'beta' | 'alpha';

export interface ProjectRef {
  provider: CatalogProvider;
  id: string;
}

export interface CatalogProviderView {
  id: CatalogProvider;
  label: string;
  ready: boolean;
  note: string | null;
}

export interface BrowseContext {
  profileId: string;
  profileName: string;
  minecraft: string;
  loader: string;
  loadsMods: boolean;
  blocked: string | null;
  providers: CatalogProviderView[];
}

export interface CatalogCategory {
  id: string;
  label: string;
}

export type CatalogInstallState =
  | { state: 'not-installed' }
  | { state: 'installed'; version: string | null; fileName: string }
  | {
      state: 'update-available';
      installed: string | null;
      latest: string;
      versionId: string;
      fileName: string;
    }
  | { state: 'installing' }
  | { state: 'updating' }
  | { state: 'removal-pending' }
  | { state: 'failed'; reason: string };

export interface CatalogEntry {
  key: string;
  reference: ProjectRef;
  providerLabel: string;
  slug: string;
  name: string;
  summary: string;
  authors: string[];
  icon: string | null;
  downloads: number;
  categories: string[];
  updated: string | null;
  install: CatalogInstallState;
  installedId: string | null;
  /** A definite "no" from the project's own facets. Absent means unchecked. */
  ruledOut: string | null;
  possibleDuplicateOf: string | null;
  alsoOn: string[];
}

export interface ProviderOutcome {
  id: CatalogProvider;
  label: string;
  configured: boolean;
  problem: string | null;
  retryable: boolean;
  results: number;
}

export interface CatalogSearchView {
  entries: CatalogEntry[];
  providers: ProviderOutcome[];
  offset: number;
  nextOffset: number;
  hasMore: boolean;
  superseded: boolean;
}

export interface BestFile {
  versionId: string;
  number: string;
  channel: ReleaseChannel;
  published: string | null;
}

export interface ProjectVerdict {
  verdict: Verdict;
  headline: string;
  /** The same reason in one short line, for a card. */
  short: string;
  best: BestFile | null;
  builds: number;
}

export interface VerdictView {
  key: string;
  verdict: ProjectVerdict | null;
  problem: string | null;
}

export interface Finding {
  code: string;
  verdict: Verdict;
  detail: string;
}

export interface CatalogDependency {
  kind: 'required' | 'optional' | 'incompatible' | 'embedded';
  key: string | null;
  name: string | null;
  installed: boolean;
}

export interface CatalogVersion {
  versionId: string;
  number: string;
  name: string;
  channel: ReleaseChannel;
  published: string | null;
  loaders: string[];
  gameVersions: string;
  fileName: string;
  size: number | null;
  verdict: Verdict;
  findings: Finding[];
  dependencies: CatalogDependency[];
  downloadable: boolean;
  installed: boolean;
}

export interface CatalogDetail {
  entry: CatalogEntry;
  description: string | null;
  descriptionFormat: 'markdown' | 'html';
  gallery: { url: string; title: string | null }[];
  license: string;
  hasSource: boolean;
  hasIssues: boolean;
  projectUrl: string;
  verdict: ProjectVerdict;
  versions: CatalogVersion[];
  withheld: boolean;
}

export interface InstallArgs {
  reference: ProjectRef;
  versionId?: string | null;
  optional?: ProjectRef[];
  acceptUnverified?: boolean;
  acceptDuplicates?: boolean;
  /** A profile entry id: makes this an update to the newest build. */
  replacing?: string | null;
  /** With `replacing`: fetch the same build again, because its file is damaged or gone. */
  repair?: boolean;
}

export interface PlannedView {
  key: string;
  name: string;
  number: string;
  fileName: string;
  size: number | null;
  requiredBy: string | null;
  optional: boolean;
  possibleDuplicate: string | null;
}

export interface PlanView {
  root: PlannedView;
  dependencies: PlannedView[];
  satisfied: string[];
  disabledDependencies: string[];
  optional: {
    reference: ProjectRef;
    name: string | null;
    suggestedBy: string;
    alreadyInstalled: boolean;
  }[];
  blockers: string[];
  unverified: { name: string; number: string; reasons: string[] }[];
  needsDuplicateConfirmation: boolean;
  isUpdate: boolean;
  downloadBytes: number;
}

export interface InstallOutcome {
  ok: boolean;
  message: string;
  cancelled: boolean;
}

export interface InstallEvent {
  key: string;
  progress: {
    phase: 'checking' | 'downloading' | 'recording' | 'done';
    completed: number;
    total: number;
    current: string;
  } | null;
  finished: InstallOutcome | null;
}

const NEEDS_APP = 'The mod browser needs the Fathom app.';

function catalogCall<T>(command: string, args: Record<string, unknown> = {}): Promise<T> {
  if (!inTauri) return Promise.reject(new Error(NEEDS_APP));
  return invoke<T>(command, args);
}

export function catalogContext(profile?: string): Promise<BrowseContext> {
  return catalogCall('catalog_context', { profile: profile ?? null });
}

export function catalogCategories(): Promise<CatalogCategory[]> {
  return catalogCall('catalog_categories');
}

export function catalogSearch(
  args: {
    text: string;
    category: string | null;
    sort: CatalogSort;
    offset: number;
    breadth: Breadth;
  },
  profile?: string,
): Promise<CatalogSearchView> {
  return catalogCall('catalog_search', { profile: profile ?? null, args });
}

export function catalogVerdicts(projects: ProjectRef[], profile?: string): Promise<VerdictView[]> {
  return catalogCall('catalog_verdicts', { profile: profile ?? null, projects });
}

/** An icon or screenshot, as a data URL the Rust side fetched and checked. */
export function catalogImage(provider: CatalogProvider, url: string): Promise<string> {
  return catalogCall('catalog_image', { provider, url });
}

export function catalogDetail(reference: ProjectRef, profile?: string): Promise<CatalogDetail> {
  return catalogCall('catalog_detail', { profile: profile ?? null, reference });
}

export function catalogPlan(args: InstallArgs, profile?: string): Promise<PlanView> {
  return catalogCall('catalog_plan', { profile: profile ?? null, args });
}

export function catalogInstall(args: InstallArgs, profile?: string): Promise<InstallOutcome> {
  return catalogCall('catalog_install', { profile: profile ?? null, args });
}

export function catalogCancelInstall(reference: ProjectRef): Promise<boolean> {
  return catalogCall('catalog_cancel_install', { reference });
}

/** Open a project's page, source or tracker, or a link from its description. */
export function catalogOpenLink(
  reference: ProjectRef,
  which: 'page' | 'source' | 'issues' | 'description',
  url?: string,
): Promise<void> {
  return catalogCall('catalog_open_link', { reference, which, url: url ?? null });
}

export function catalogUninstall(id: string, profile?: string): Promise<string> {
  return catalogCall('catalog_uninstall', { profile: profile ?? null, id });
}

/** Progress for installs started from the browser. Returns the unsubscribe. */
export async function onCatalogInstall(
  handler: (event: InstallEvent) => void,
): Promise<() => void> {
  if (!inTauri) return () => {};
  const { listen } = await import('@tauri-apps/api/event');
  return listen<InstallEvent>('fathom://mod-install', (event) => handler(event.payload));
}


/* -- installed mods and recovery ---------------------------------------------
   Mirrors `crates/launcher-desktop/src/api/installed.rs`. */

export interface Requirement {
  id: string;
  name: string;
  installed: boolean;
  enabled: boolean;
}

export interface EarlierBuild {
  sha1: string;
  version: string | null;
  fileName: string;
  replacedAt: number;
  available: boolean;
}

export interface InstalledDetail {
  id: string;
  name: string;
  summary: string;
  kind: 'builtin' | 'component' | 'external';
  sourceLabel: string;
  enabled: boolean;
  version: string | null;
  fileName: string;
  fingerprint: string;
  license: string | null;
  minecraft: string[];
  loaders: string[];
  compatibility: { verdict: Verdict | 'unknown'; detail: string } | null;
  requires: Requirement[];
  requiredBy: string[];
  issues: string[];
  earlier: EarlierBuild[];
  missing: boolean;
  damaged: boolean;
  repairable: boolean;
  updatable: boolean;
  provider: CatalogProvider | null;
  project: string | null;
  disabledReason: string | null;
  gameRunning: boolean;
}

export type ChangeKind =
  | { kind: 'added' }
  | { kind: 'removed' }
  | { kind: 'updated'; from: string | null; to: string | null }
  | { kind: 'enabled' }
  | { kind: 'disabled' };

export interface ModChange {
  id: string;
  name: string;
  source: 'user-supplied' | 'first-party' | 'catalogue';
  change: ChangeKind;
  affectsLaunch: boolean;
}

export interface ModRecoveryView {
  profileName: string;
  lastLaunch: {
    at: number;
    outcome: { state: 'started' } | { state: 'worked' } | { state: 'failed'; message: string };
    safe: boolean;
  } | null;
  lastGoodAt: number | null;
  changes: ModChange[];
  missing: { id: string; name: string; repairable: boolean }[];
  damaged: { id: string; name: string; repairable: boolean }[];
  problems: string[];
  evidence: { source: string; line: string; names: string[] }[];
  namedByLogs: string[];
  canRestore: boolean;
  externalEnabled: number;
  gameRunning: boolean;
}

export interface RestoreReport {
  switchedOff: string[];
  reverted: string[];
  switchedOn: string[];
  reAdded: string[];
  unavailable: string[];
}

export interface UpdatesCheck {
  updates: { id: string; name: string; installed: string | null; latest: string; reference: ProjectRef }[];
  checkable: number;
  total: number;
}

export function installedModDetail(id: string, profile?: string): Promise<InstalledDetail> {
  return catalogCall('installed_mod_detail', { profile: profile ?? null, id });
}

export function revealModFile(id: string, profile?: string): Promise<void> {
  return catalogCall('reveal_mod_file', { profile: profile ?? null, id });
}

export function restoreModBuild(id: string, sha1: string, profile?: string): Promise<string> {
  return catalogCall('restore_mod_build', { profile: profile ?? null, id, sha1 });
}

export function modRecovery(profile?: string): Promise<ModRecoveryView> {
  return catalogCall('mod_recovery', { profile: profile ?? null });
}

export function restoreLastGoodMods(profile?: string): Promise<RestoreReport> {
  return catalogCall('restore_last_good_mods', { profile: profile ?? null });
}

export function checkModUpdates(profile?: string): Promise<UpdatesCheck> {
  return catalogCall('check_mod_updates', { profile: profile ?? null });
}

/** Look up hand-added jars by their exact bytes. Returns how many were recognised. */
export function identifyMods(profile?: string): Promise<number> {
  return catalogCall('identify_mods', { profile: profile ?? null });
}

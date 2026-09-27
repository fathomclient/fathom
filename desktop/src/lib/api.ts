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
  summary: string;
  /** Null until somebody runs a benchmark in game. */
  benchmark: BenchmarkSummary | null;
  benchmarkCount: number;
  /**
   * Set only when this profile's own options.txt is holding the frame rate
   * down. Null is the normal state and the page shows nothing for it.
   */
  videoLimits: VideoLimits | null;
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
  },
};

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
  summary: '3 of 20 running',
  benchmark: null,
  benchmarkCount: 0,
  videoLimits: null,
};

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
    const proof = typeof window !== 'undefined'
      && new URLSearchParams(window.location.search).get('proof') === 'home';
    return proof ? FIXTURE_HOME_PROOF : FIXTURE_HOME;
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
 * The launcher's own accessibility switches for the glass. Stored beside the
 * background in `appearance.json`; Windows' "Transparency effects" setting
 * applies on top of these through the media query, whatever they say.
 */
export interface AppearanceModes {
  reduceTransparency: boolean;
  reduceBlur: boolean;
}

export const DEFAULT_APPEARANCE_MODES: AppearanceModes = {
  reduceTransparency: false,
  reduceBlur: false,
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
        { id: 'forge', label: 'Forge', available: false },
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
  if (!inTauri) return FIXTURE_PERFORMANCE;
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
export async function play(): Promise<LaunchProgress> {
  if (!inTauri) return NO_LAUNCH;
  return invoke<LaunchProgress>('play');
}

/** Ask the launch in flight to stop. Only does anything before the game starts. */
export async function cancelLaunch(): Promise<LaunchProgress> {
  if (!inTauri) return NO_LAUNCH;
  return invoke<LaunchProgress>('cancel_launch');
}

export async function launchProgress(): Promise<LaunchProgress> {
  if (!inTauri) return NO_LAUNCH;
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
  if (!inTauri) return NO_AUTH;
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

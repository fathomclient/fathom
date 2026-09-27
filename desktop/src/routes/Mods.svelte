<!--
  Mods.

  Four tabs over one list, because there are four questions and only the first
  has an answer before the user asks for anything from the network:

    Installed  What will this profile load?
    Browse     What else could it load?
    Updates    What is out of date?
    Issues     What is wrong?

  WHAT CHANGED FROM THE PREVIOUS VERSION OF THIS PAGE

  It used to say, at length, that it was not a store — no browse, no search, no
  install button — because the launcher could not lawfully or safely fetch
  anything. That was true and is no longer the whole truth: Modrinth grants
  third-party applications an API licence, publishes a hash with every file, and
  indexes real 1.8.9 mods.

  What has not changed is the rule underneath. Nothing is offered that cannot be
  delivered. Browse only lists builds that match this profile's version and
  loader. Nothing is fetched without the user asking. And OptiFine still gets a
  drop target and a link to its author, never a download button, because its
  licence has not changed either.

  The tab strip only appears when browsing is possible. A row of tabs where
  three of them explain why they are empty is worse than one honest list.
-->
<script lang="ts">
  import { onMount } from 'svelte';
  import { fly, fade } from 'svelte/transition';
  import {
    FolderOpen,
    Search,
    Puzzle,
    Download,
    Check,
    RefreshCw,
    ExternalLink,
    ChevronRight,
  } from '@lucide/svelte';

  import Page from '../lib/ui/Page.svelte';
  import Section from '../lib/ui/Section.svelte';
  import Switch from '../lib/ui/Switch.svelte';
  import Button from '../lib/ui/Button.svelte';
  import Segmented from '../lib/ui/Segmented.svelte';
  import Notice from '../lib/ui/Notice.svelte';
  import ModDetail from './mods/ModDetail.svelte';
  import { tooltip } from '../lib/tooltip';
  import { EASE, MOTION_HOVER, MOTION_PAGE, MOTION_STAGGER } from '../lib/motion';
  import {
    browseMods,
    importDroppedMod,
    importMod,
    installComponent,
    installMod,
    modUpdates,
    modsView,
    openPage,
    openProjectPage,
    removeMod,
    setLoader,
    setModEnabled,
    type BrowseView,
    type ImportedMod,
    type ModsView,
    type UpdatesView,
  } from '../lib/api';

  /** Told when something here changed what the rest of the shell shows. */
  export let onChanged: () => void = () => {};
  /** Open the full Add OptiFine screen. Set by the shell. */
  export let onAddOptiFine: () => void = () => {};

  let view: ModsView | null = null;
  let busy = false;
  let error = '';
  let query = '';
  /** What the last import turned out to be. Cleared when it stops being news. */
  let imported: ImportedMod | null = null;
  /** True while a file is over the window, so the page can say "let go". */
  let dropping = false;

  type Tab = 'installed' | 'browse' | 'updates' | 'issues';
  let tab: Tab = 'installed';
  /** Which kind of row the Installed tab is showing. */
  let filter: 'all' | 'enabled' | 'fathom' | 'managed' | 'yours' = 'all';

  let browse: BrowseView | null = null;
  let browseQuery = '';
  let browsing = false;
  let updates: UpdatesView | null = null;
  let checking = false;
  /** A one-line confirmation of the last thing that worked. */
  let note = '';

  onMount(() => {
    void (async () => {
      view = await modsView();
    })();

    /* Dropping a jar onto the window is the fastest path from "downloaded
       OptiFine" to "it is in my profile", and it is the one the licensing
       research asked for by name. The event is the OS telling us the user
       pointed at a file; Rust still checks it. */
    let stop: (() => void) | null = null;
    void (async () => {
      try {
        const { getCurrentWebview } = await import('@tauri-apps/api/webview');
        stop = await getCurrentWebview().onDragDropEvent((event) => {
          if (event.payload.type === 'over') {
            dropping = true;
          } else if (event.payload.type === 'drop') {
            dropping = false;
            void acceptDrop(event.payload.paths);
          } else {
            dropping = false;
          }
        });
      } catch {
        /* No drag-drop in a plain browser, and none needed: Add mod works.
           Swallowed rather than surfaced, because a missing convenience is not
           something to put an error on the page about. */
      }
    })();
    return () => stop?.();
  });

  async function acceptDrop(paths: string[]) {
    const jar = paths.find((path) => path.toLowerCase().endsWith('.jar'));
    if (!jar || busy) return;
    busy = true;
    error = '';
    try {
      const result = await importDroppedMod(jar);
      if (!result.cancelled) {
        imported = result;
        view = await modsView();
        onChanged();
        setTimeout(() => (imported = null), 6000);
      }
    } catch (cause) {
      error = String(cause);
    } finally {
      busy = false;
    }
  }

  /* Import goes through the picker on the Rust side and comes back saying
     what the file was, so the page can confirm "OptiFine HD U M5" rather than
     echoing a filename. A cancelled picker is a normal outcome, not an error. */
  async function addOne() {
    if (busy) return;
    busy = true;
    error = '';
    try {
      const result = await importMod();
      if (!result.cancelled) {
        imported = result;
        view = await modsView();
        onChanged();
        setTimeout(() => (imported = null), 6000);
      }
    } catch (cause) {
      error = String(cause);
    } finally {
      busy = false;
    }
  }

  async function switchLoader(target: string) {
    if (busy) return;
    busy = true;
    error = '';
    try {
      await setLoader(target);
      view = await modsView();
      // The loader is on the bottom bar and the Home composition too, and a
      // status line still saying "Vanilla" after the switch is a stale fact.
      onChanged();
    } catch (cause) {
      error = String(cause);
    } finally {
      busy = false;
    }
  }

  async function run(action: () => Promise<ModsView>) {
    if (busy) return;
    busy = true;
    error = '';
    try {
      view = await action();
      // A mod removed from its own page has nothing left to show: go back to
      // the library rather than keep a selection that could match a later one.
      if (selected?.kind === 'mod' && !view.rows.some((row) => row.id === selected?.id)) {
        selected = null;
      }
    } catch (cause) {
      error = String(cause);
    } finally {
      busy = false;
    }
  }

  /* Searching reaches the network, so it happens when asked rather than on
     every keystroke: a debounced search box would send a request per pause,
     and each of those is a third party learning what somebody is looking for. */
  async function search() {
    if (browsing) return;
    browsing = true;
    error = '';
    try {
      browse = await browseMods(browseQuery, 0);
    } catch (cause) {
      error = String(cause);
      browse = null;
    } finally {
      browsing = false;
    }
  }

  async function more() {
    if (browsing || !browse?.more) return;
    browsing = true;
    try {
      const next = await browseMods(browse.query, browse.offset + browse.rows.length);
      browse = { ...next, rows: [...browse.rows, ...next.rows] };
    } catch (cause) {
      error = String(cause);
    } finally {
      browsing = false;
    }
  }

  async function install(project: string, title: string) {
    if (busy) return;
    busy = true;
    error = '';
    try {
      view = await installMod(project);
      note = `Installed ${title}.`;
      // The browse list carries an "installed" flag per row, and it is now
      // stale for the row just pressed.
      if (browse) browse = await browseMods(browse.query, 0);
      onChanged();
      setTimeout(() => (note = ''), 6000);
    } catch (cause) {
      error = String(cause);
    } finally {
      busy = false;
    }
  }

  async function addComponent(id: string, name: string) {
    if (busy) return;
    busy = true;
    error = '';
    try {
      view = await installComponent(id);
      note = `Installed ${name}.`;
      onChanged();
      setTimeout(() => (note = ''), 6000);
    } catch (cause) {
      error = String(cause);
    } finally {
      busy = false;
    }
  }

  async function checkUpdates() {
    if (checking) return;
    checking = true;
    error = '';
    try {
      updates = await modUpdates();
    } catch (cause) {
      error = String(cause);
    } finally {
      checking = false;
    }
  }

  function show(next: Tab) {
    tab = next;
    if (next === 'browse' && browse === null && !view?.browseBlocked) void search();
    if (next === 'updates' && updates === null && !view?.browseBlocked) void checkUpdates();
  }

  /* A plain function rather than a reactive statement: `filter` and `query`
     both feed it, and a `$:` variable read from a helper is the thing Svelte
     does not invalidate. */
  function visible(current: ModsView | null, chip: string, text: string) {
    const rows = (current?.rows ?? []).filter((row) => {
      if (chip === 'enabled') return row.enabled;
      if (chip === 'all') return true;
      return row.group === chip;
    });
    const needle = text.trim().toLowerCase();
    if (!needle) return rows;
    return rows.filter((row) =>
      `${row.name} ${row.summary} ${row.origin} ${row.license}`.toLowerCase().includes(needle),
    );
  }

  $: rows = visible(view, filter, query);
  /* Four is the point where scanning stops being instant. Below that a search
     field is a control that costs more than it saves. */
  $: searchable = (view?.rows.length ?? 0) >= 4;
  $: chips = view
    ? ([
        ['all', 'All', view.counts.all],
        ['enabled', 'On', view.counts.enabled],
        ['fathom', 'Fathom', view.counts.fathom],
        ['managed', 'Managed', view.counts.managed],
        ['yours', 'Yours', view.counts.yours],
      ] as const).filter(([id, , count]) => id === 'all' || count > 0)
    : [];
  /* The tab strip earns its place only when there is more than one answer.
     Under a loader that reads no mods, or with repositories switched off,
     three of the four tabs would exist to explain why they are empty. */
  $: tabbed = !!view && !view.loaderNotice && !view.browseBlocked;
  $: tabs = [
    { id: 'installed', label: 'Installed' },
    { id: 'browse', label: 'Browse' },
    { id: 'updates', label: updates?.rows.length ? `Updates (${updates.rows.length})` : 'Updates' },
    { id: 'issues', label: view?.counts.issues ? `Issues (${view.counts.issues})` : 'Issues' },
  ];

  /* -- one mod, as a page ----------------------------------------------------
     Configuring a mod opens its own page in place of the library, not a
     drawer. The page reads the row live from `view`, so a toggle or an install
     made there is reflected there; a mod removed from its own page has nothing
     left to show, and the library comes back. */
  type Selection = { kind: 'mod' | 'component'; id: string };
  let selected: Selection | null = null;

  function open(kind: Selection['kind'], id: string) {
    selected = { kind, id };
  }

  function close() {
    selected = null;
  }

  $: selectedMod =
    selected?.kind === 'mod' ? (view?.rows.find((row) => row.id === selected?.id) ?? null) : null;
  $: selectedComponent =
    selected?.kind === 'component'
      ? (view?.components.find((component) => component.id === selected?.id) ?? null)
      : null;

  /** The library's mark for a mod: its initial. */
  function initial(name: string): string {
    return (name.trim()[0] ?? '?').toUpperCase();
  }
</script>

{#if view}
  {#if selectedMod || selectedComponent}
    <!-- ------------------------------------------------- one mod, as a page -->
    {#key selected?.id}
      <ModDetail
        mod={selectedMod}
        component={selectedComponent}
        profileName={view.profileName}
        {busy}
        onBack={close}
        onToggle={(value) => selectedMod && run(() => setModEnabled(selectedMod.id, value))}
        onRemove={() => selectedMod && run(() => removeMod(selectedMod.id))}
        onInstall={() =>
          selectedComponent && void addComponent(selectedComponent.id, selectedComponent.name)}
        onOpenProject={(slug) => void openProjectPage(slug)}
        onOpenPage={(page) => void openPage(page)}
      />
    {/key}
  {:else}
    <Page title="Mods" summary="{view.profileName} · {view.summary}" wide>
      <svelte:fragment slot="actions">
        {#if view.loaderSupportsMods}
          <Button variant="primary" disabled={busy} onClick={addOne}>
            <FolderOpen size={14} strokeWidth={2.2} />
            Add JAR
          </Button>
        {/if}
      </svelte:fragment>

      {#if error}
        <div transition:fade={{ duration: MOTION_HOVER }}>
          <Notice tone="danger">{error}</Notice>
        </div>
      {/if}

      {#if imported}
        <div transition:fade={{ duration: MOTION_HOVER }}>
          <Notice tone="success">
            Added {imported.name}{imported.version ? ` ${imported.version}` : ''}
            {#if imported.userSupplied}
              <span class="aside">· Your file · {imported.fingerprint}</span>
            {/if}
          </Notice>
        </div>
      {/if}

      {#if note}
        <div transition:fade={{ duration: MOTION_HOVER }}>
          <Notice tone="success">{note}</Notice>
        </div>
      {/if}

      <!-- Two different problems, and telling somebody the wrong one sends them
           to fix something that is not broken. A loader that reads no mods
           directory gets one sentence and a button that fixes it, rather than a
           page of controls that cannot do anything. -->
      {#if view.loaderNotice}
        <Section material>
          <div class="loader-block" in:fade={{ duration: MOTION_PAGE, easing: EASE }}>
            <Puzzle size={22} strokeWidth={1.6} />
            <span class="loader-message">{view.loaderNotice.message}</span>
            <span class="loader-warning">{view.loaderNotice.warning}</span>
            <Button
              variant="primary"
              disabled={busy}
              onClick={() => void switchLoader(view!.loaderNotice!.target)}
            >
              {view.loaderNotice.action}
            </Button>
          </div>
        </Section>
      {:else if !view.loaderLaunchable}
        <Notice tone="warning">{view.loader} is not launchable yet.</Notice>
      {/if}

      {#if !view.loaderNotice}
        {#if tabbed}
          <div class="views" in:fade={{ duration: MOTION_HOVER }}>
            <Segmented options={tabs} value={tab} onChange={(id) => show(id as Tab)} />
          </div>
        {/if}

        <!-- ---------------------------------------------------- installed -->
        {#if tab === 'installed'}
          {#if view.problems.length && !tabbed}
            {#each view.problems as problem}
              <Notice tone={problem.blocking ? 'danger' : 'warning'}>{problem.message}</Notice>
            {/each}
          {/if}

          {#if view.components.length}
            <Section label="Fathom components" summary="Installed and kept current by Fathom" material>
              {#each view.components as component (component.id)}
                {@const off = component.state === 'Missing' || component.state === 'Not added'}
                <div class="item" class:off>
                  <span class="monogram" aria-hidden="true">{initial(component.name)}</span>
                  <div class="text">
                    <button class="name" on:click={() => open('component', component.id)}>
                      {component.name}
                    </button>
                    {#if component.summary}<p class="summary">{component.summary}</p>{/if}
                    <p class="meta tnum">
                      <span class:flag={component.state === 'Missing'}>{component.state}</span>
                      {#if component.version}<span> · {component.version}</span>{/if}
                    </p>
                  </div>
                  <div class="controls">
                    {#if component.installable}
                      <Button
                        disabled={busy}
                        onClick={() => void addComponent(component.id, component.name)}
                      >
                        <Download size={13} strokeWidth={2} />
                        Install
                      </Button>
                    {/if}
                    <button
                      class="ui-icon-button small"
                      aria-label="Open {component.name}"
                      use:tooltip={'Details'}
                      on:click={() => open('component', component.id)}
                    >
                      <ChevronRight size={16} strokeWidth={2.2} />
                    </button>
                  </div>
                </div>
              {/each}
            </Section>
          {/if}

          {#if view.rows.length}
            <section class="library">
              <!-- Filtering lives with the list it filters. Hidden until the list
                   is long enough to need it. -->
              {#if searchable || chips.length > 2}
                <div class="toolbar">
                  {#if searchable}
                    <label class="search">
                      <Search size={15} strokeWidth={2} />
                      <input
                        class="ui-field"
                        bind:value={query}
                        placeholder="Filter mods"
                        spellcheck="false"
                        aria-label="Filter mods"
                      />
                    </label>
                  {/if}
                  {#if chips.length > 2}
                    <div class="chips" role="group" aria-label="Show">
                      {#each chips as [id, label, count]}
                        <button
                          class="ui-capsule small"
                          class:selected={filter === id}
                          aria-pressed={filter === id}
                          on:click={() => (filter = id)}
                        >
                          {label}<span class="count tnum">{count}</span>
                        </button>
                      {/each}
                    </div>
                  {/if}
                </div>
              {/if}

              <Section label="Installed" summary={query ? `${rows.length} shown` : `${view.counts.enabled} of ${view.counts.all} on`} material>
                {#each rows as row, index (row.id)}
                  <div
                    class="item"
                    class:off={!row.enabled}
                    in:fly={{ y: 6, duration: MOTION_PAGE, delay: Math.min(index, 12) * MOTION_STAGGER, easing: EASE }}
                  >
                    <span class="monogram" aria-hidden="true">{initial(row.name)}</span>
                    <div class="text">
                      <button class="name" on:click={() => open('mod', row.id)}>{row.name}</button>
                      {#if row.summary}<p class="summary">{row.summary}</p>{/if}
                      <p class="meta tnum">{[row.version, row.origin, row.size].filter(Boolean).join(' · ')}</p>
                      {#if row.missing}<p class="flag">File missing</p>{/if}
                      {#if row.warning}<p class="flag">{row.warning}</p>{/if}
                    </div>
                    <div class="controls">
                      <Switch
                        checked={row.enabled}
                        disabled={busy || !row.canToggle}
                        label={row.name}
                        onChange={(value) => run(() => setModEnabled(row.id, value))}
                      />
                      <button
                        class="ui-icon-button small"
                        aria-label="Configure {row.name}"
                        use:tooltip={'Configure'}
                        on:click={() => open('mod', row.id)}
                      >
                        <ChevronRight size={16} strokeWidth={2.2} />
                      </button>
                    </div>
                  </div>
                {/each}

                {#if rows.length === 0}
                  <p class="none">{query ? 'No mod matches that.' : 'Nothing in this group.'}</p>
                {/if}
              </Section>
            </section>
          {:else}
            <div class="empty" in:fade={{ duration: MOTION_PAGE, easing: EASE }}>
              <span class="empty-title">No mods yet</span>
              <span class="empty-detail">Drop a mod JAR onto the window, or add one.</span>
            </div>
          {/if}
        {/if}

        <!-- ------------------------------------------------------- browse -->
        {#if tab === 'browse'}
          <section class="library">
            <div class="toolbar">
              <label class="search wide">
                <Search size={15} strokeWidth={2} />
                <input
                  class="ui-field"
                  bind:value={browseQuery}
                  placeholder="Search Modrinth"
                  spellcheck="false"
                  aria-label="Search Modrinth"
                  on:keydown={(event) => event.key === 'Enter' && void search()}
                />
              </label>
              <Button variant="primary" disabled={browsing} onClick={search}>
                {browsing ? 'Searching…' : 'Search'}
              </Button>
            </div>

            <Section label="Modrinth" summary="{view.loader} · Minecraft {view.minecraft}" material>
              {#if browse}
                {#each browse.rows as hit, index (hit.project)}
                  <div
                    class="item"
                    in:fly={{ y: 6, duration: MOTION_PAGE, delay: Math.min(index, 12) * MOTION_STAGGER, easing: EASE }}
                  >
                    <span class="monogram" aria-hidden="true">{initial(hit.title)}</span>
                    <div class="text">
                      <span class="name static">{hit.title}</span>
                      {#if hit.summary}<p class="summary">{hit.summary}</p>{/if}
                      <p class="meta tnum">{[hit.downloads, hit.license].filter(Boolean).join(' · ')}</p>
                    </div>
                    <div class="controls">
                      {#if hit.installed}
                        <span class="installed"><Check size={14} strokeWidth={2.4} /> Installed</span>
                      {:else}
                        <Button disabled={busy} onClick={() => void install(hit.project, hit.title)}>
                          <Download size={13} strokeWidth={2} />
                          Install
                        </Button>
                      {/if}
                      <button
                        class="ui-icon-button small"
                        aria-label="Open {hit.title} on Modrinth"
                        use:tooltip={'Open its page'}
                        on:click={() => void openProjectPage(hit.slug)}
                      >
                        <ExternalLink size={15} strokeWidth={2} />
                      </button>
                    </div>
                  </div>
                {/each}

                {#if browse.rows.length === 0}
                  <p class="none">Nothing on Modrinth matches that for this version and loader.</p>
                {/if}
              {:else if !browsing}
                <p class="none">Search for a mod, or press Search to see what is available.</p>
              {:else}
                <p class="none">Searching…</p>
              {/if}
            </Section>

            {#if browse?.more}
              <div class="more">
                <Button disabled={browsing} onClick={more}>
                  {browsing ? 'Loading…' : `Show more (${browse.total} found)`}
                </Button>
              </div>
            {/if}
          </section>
        {/if}

        <!-- ------------------------------------------------------ updates -->
        {#if tab === 'updates'}
          <section class="library">
            <Section
              label="Updates"
              summary={updates ? `${updates.checkable} of ${updates.total} can be checked` : ''}
              material
            >
              {#if updates?.blocked}
                <p class="none">{updates.blocked}</p>
              {:else if updates}
                {#each updates.rows as update (update.id)}
                  <div class="item">
                    <span class="monogram" aria-hidden="true">{initial(update.name)}</span>
                    <div class="text">
                      <span class="name static">{update.name}</span>
                      <p class="meta tnum">{update.from} → {update.to}</p>
                    </div>
                    <div class="controls">
                      <Button disabled={busy} onClick={() => void install(update.project, update.name)}>
                        <RefreshCw size={13} strokeWidth={2} />
                        Update
                      </Button>
                    </div>
                  </div>
                {/each}
                {#if updates.rows.length === 0}
                  <p class="none">
                    Everything Fathom can check is up to date.
                    {#if updates.total > updates.checkable}
                      {updates.total - updates.checkable} of your mods are files you added, which
                      Fathom has nothing to check against.
                    {/if}
                  </p>
                {/if}
              {:else}
                <p class="none">Checking…</p>
              {/if}
            </Section>
            <div class="more">
              <Button disabled={checking} onClick={checkUpdates}>
                {checking ? 'Checking…' : 'Check again'}
              </Button>
            </div>
          </section>
        {/if}

        <!-- ------------------------------------------------------- issues -->
        {#if tab === 'issues'}
          <section class="library">
            {#each view.problems as problem}
              <Notice tone={problem.blocking ? 'danger' : 'warning'}>
                {problem.message}
                <span class="aside">· {problem.severity}</span>
              </Notice>
            {/each}
            {#if view.problems.length === 0}
              <p class="none open">Nothing wrong with this set.</p>
            {/if}
          </section>
        {/if}

        <!-- The OptiFine question, answered before it is asked. It is the mod
             most people look for and the one this launcher cannot fetch, so the
             page says why rather than leaving somebody to search. -->
        {#if tab === 'installed'}
          <Section label="OptiFine" material>
            <div class="optifine">
              <p>
                OptiFine can't be bundled by Fathom. Download it from the author, then drop the
                JAR here.
              </p>
              <div class="drop" class:active={dropping}>
                <FolderOpen size={18} strokeWidth={1.8} />
                <span>{dropping ? 'Let go to add it' : 'Drop OptiFine here'}</span>
              </div>

              <div class="optifine-actions">
                <Button onClick={() => void openPage('optifine')}>
                  <Download size={13} strokeWidth={2} />
                  Get OptiFine
                </Button>
                <!-- The full flow, for somebody who has the file but not a
                     decision about which profile it goes in. The drop target
                     above stays: it is the one-step path for people already
                     looking at this profile's mods. -->
                <Button onClick={onAddOptiFine}>Add OptiFine…</Button>
                <Button disabled={busy} onClick={addOne}>
                  <FolderOpen size={13} strokeWidth={2} />
                  Add my JAR
                </Button>
              </div>
              <p class="quiet">
                Stays on this machine. Fathom switches off any of its own optimizations that
                would clash.
              </p>
            </div>
          </Section>
        {/if}
      {/if}
    </Page>
  {/if}
{/if}

<style>
  /* -- the page's own controls ------------------------------------------------ */
  .views {
    display: flex;
  }

  .library {
    display: flex;
    flex-direction: column;
    gap: var(--space-3);
  }

  .toolbar {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: var(--space-3);
  }

  .search {
    position: relative;
    display: flex;
    align-items: center;
    flex: 0 1 280px;
    min-width: 200px;
    color: var(--color-text-tertiary);
  }

  .search.wide {
    flex: 1 1 320px;
  }

  .search :global(svg) {
    position: absolute;
    left: var(--space-3);
    pointer-events: none;
  }

  .search input {
    padding-left: var(--space-10);
  }

  .search:focus-within {
    color: var(--color-text-secondary);
  }

  .chips {
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-1);
  }

  .count {
    margin-left: 6px;
    color: var(--color-text-tertiary);
  }

  /* -- a library row -----------------------------------------------------------
     A mark, the name and what it is, and on the right the one control that
     matters plus the way into its page. No pill per row: a mod that is on is
     the one with full ivory type and a lit mark; one that is off recedes. */
  .item {
    display: grid;
    grid-template-columns: auto minmax(0, 1fr) auto;
    align-items: center;
    gap: var(--space-4);
    padding: var(--space-3) var(--space-4);
    border-radius: max(var(--radius-inner-min), calc(var(--surface-radius, var(--radius-lg)) - 2px));
    box-shadow: 0 -1px 0 var(--control-hairline);
    transition: background-color var(--motion-hover) var(--ease-standard);
  }

  .item:first-child {
    box-shadow: none;
  }

  .item:hover {
    background-color: var(--control-fill-faint);
  }

  .monogram {
    display: grid;
    place-items: center;
    width: var(--control-height-lg);
    height: var(--control-height-lg);
    border-radius: var(--radius-md);
    background-color: var(--control-fill-selected);
    box-shadow: var(--control-rim);
    font-size: var(--type-heading-size);
    font-weight: var(--type-heading-weight);
    color: var(--color-text);
    transition:
      background-color var(--motion-state) var(--ease-standard),
      color var(--motion-state) var(--ease-standard);
  }

  .text {
    display: flex;
    flex-direction: column;
    gap: 2px;
    min-width: 0;
  }

  .name {
    align-self: flex-start;
    max-width: 100%;
    padding: 0;
    font-size: var(--type-body-size);
    font-weight: var(--type-heading-weight);
    letter-spacing: var(--type-heading-tracking);
    line-height: 1.35;
    color: var(--color-text);
    text-align: left;
    overflow-wrap: anywhere;
    transition: color var(--motion-hover) var(--ease-standard);
  }

  button.name:hover {
    text-decoration: underline;
    text-decoration-color: var(--control-hairline-strong);
    text-underline-offset: 3px;
  }

  button.name:focus-visible {
    outline: 2px solid var(--color-focus);
    outline-offset: 2px;
    border-radius: var(--radius-xs);
  }

  .summary {
    margin: 0;
    font-size: var(--type-secondary-size);
    line-height: var(--type-secondary-leading);
    color: var(--color-text-secondary);
    overflow-wrap: anywhere;
  }

  .meta {
    margin: 0;
    font-size: var(--type-caption-size);
    color: var(--color-text-tertiary);
  }

  .flag {
    margin: 2px 0 0;
    font-size: var(--type-caption-size);
    color: var(--color-warning-text);
  }

  .meta .flag {
    margin: 0;
  }

  /* Off: the row recedes; the switch stays fully readable. */
  .item.off .monogram {
    background-color: var(--control-fill-faint);
    color: var(--color-text-tertiary);
  }

  .item.off .name {
    color: var(--color-text-secondary);
    font-weight: var(--type-button-weight);
  }

  .item.off .summary {
    color: var(--color-text-tertiary);
  }

  .controls {
    display: flex;
    align-items: center;
    gap: var(--space-2);
  }

  .installed {
    display: inline-flex;
    align-items: center;
    gap: var(--space-1);
    font-size: var(--type-secondary-size);
    color: var(--color-text-secondary);
  }

  .aside {
    color: inherit;
    opacity: 0.8;
  }

  .more {
    display: flex;
    justify-content: center;
  }

  .none {
    margin: 0;
    padding: var(--space-4);
    font-size: var(--type-secondary-size);
    line-height: var(--type-secondary-leading);
    color: var(--color-text-secondary);
  }

  .none.open {
    padding: var(--space-2) var(--space-1);
  }

  /* -- empty ------------------------------------------------------------------- */
  .empty {
    display: flex;
    flex-direction: column;
    gap: var(--space-1);
    padding: var(--space-2) var(--space-1);
  }

  .empty-title {
    font-size: var(--type-heading-size);
    font-weight: var(--type-heading-weight);
    color: var(--color-text);
  }

  .empty-detail {
    font-size: var(--type-secondary-size);
    color: var(--color-text-secondary);
  }

  /* -- the loader's own way out ---------------------------------------------- */
  .loader-block {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: var(--space-2);
    padding: var(--space-8) var(--space-6);
    color: var(--color-text-secondary);
    text-align: center;
  }

  .loader-message {
    font-size: var(--type-heading-size);
    font-weight: var(--type-heading-weight);
    color: var(--color-text);
  }

  .loader-warning {
    max-width: 52ch;
    margin-bottom: var(--space-2);
    font-size: var(--type-secondary-size);
  }

  /* -- OptiFine ---------------------------------------------------------------- */
  .optifine {
    display: flex;
    flex-direction: column;
    gap: var(--space-4);
    padding: var(--space-4);
  }

  .optifine p {
    margin: 0;
    font-size: var(--type-body-size);
    line-height: var(--type-body-leading);
    color: var(--color-text-secondary);
  }

  /* A real target, not a decoration: the window accepts the drop anywhere, and
     this says where to aim. A sunken channel with a dashed rim; while a file is
     over the window it fills with light. */
  .drop {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: var(--space-2);
    padding: var(--space-6) var(--space-4);
    border-radius: var(--radius-md);
    background-color: var(--control-fill-sunken);
    outline: 1.5px dashed var(--control-hairline-strong);
    outline-offset: -1.5px;
    font-size: var(--type-body-size);
    color: var(--color-text-secondary);
    transition:
      background-color var(--motion-state) var(--ease-standard),
      color var(--motion-state) var(--ease-standard);
  }

  .drop.active {
    background-color: var(--control-fill-selected);
    color: var(--color-text);
  }

  .optifine-actions {
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-2);
  }

  .optifine .quiet {
    font-size: var(--type-caption-size);
    color: var(--color-text-tertiary);
  }
</style>

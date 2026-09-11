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
    Trash2,
    TriangleAlert,
    Search,
    Puzzle,
    Download,
    Check,
    RefreshCw,
    ExternalLink,
    Package,
  } from '@lucide/svelte';

  import Page from '../lib/ui/Page.svelte';
  import Section from '../lib/ui/Section.svelte';
  import Row from '../lib/ui/Row.svelte';
  import Switch from '../lib/ui/Switch.svelte';
  import Button from '../lib/ui/Button.svelte';
  import { EASE, FAST, NORMAL, STAGGER } from '../lib/motion';
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
</script>

{#if view}
  <Page title="Mods" summary="{view.profileName} · {view.summary}">
    <svelte:fragment slot="actions">
      {#if searchable && tab === 'installed'}
        <div class="search" transition:fade={{ duration: FAST }}>
          <Search size={13} strokeWidth={2} />
          <input bind:value={query} placeholder="Filter" spellcheck="false" />
        </div>
      {/if}
      {#if view.loaderSupportsMods}
        <Button variant="primary" disabled={busy} onClick={addOne}>
          <FolderOpen size={14} strokeWidth={2.2} />
          Add JAR
        </Button>
      {/if}
    </svelte:fragment>

    {#if error}
      <div class="notice bad" transition:fade={{ duration: FAST }}>
        <TriangleAlert size={15} strokeWidth={2} />
        <span>{error}</span>
      </div>
    {/if}

    {#if imported}
      <div class="notice good" transition:fade={{ duration: FAST }}>
        <Check size={15} strokeWidth={2.4} />
        <span>
          Added {imported.name}{imported.version ? ` ${imported.version}` : ''}
        </span>
        {#if imported.userSupplied}
          <span class="quiet">Your file · {imported.fingerprint}</span>
        {/if}
      </div>
    {/if}

    {#if note}
      <div class="notice good" transition:fade={{ duration: FAST }}>
        <Check size={15} strokeWidth={2.4} />
        <span>{note}</span>
      </div>
    {/if}

    <!-- Two different problems, and telling somebody the wrong one sends them
         to fix something that is not broken. A loader that reads no mods
         directory gets one sentence and a button that fixes it, rather than a
         page of controls that cannot do anything. -->
    {#if view.loaderNotice}
      <div class="loader-block" in:fade={{ duration: NORMAL, easing: EASE }}>
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
    {:else if !view.loaderLaunchable}
      <div class="notice" transition:fade={{ duration: FAST }}>
        <TriangleAlert size={15} strokeWidth={2} />
        <span>{view.loader} is not launchable yet.</span>
      </div>
    {/if}

    {#if !view.loaderNotice}
      {#if tabbed}
        <div class="tabs" in:fade={{ duration: FAST }}>
          {#each [['installed', 'Installed', view.counts.all], ['browse', 'Browse', null], ['updates', 'Updates', updates?.rows.length ?? null], ['issues', 'Issues', view.counts.issues]] as [id, label, count]}
            <button class="tab" class:on={tab === id} on:click={() => show(id as Tab)}>
              {label}{#if count}<span class="count">{count}</span>{/if}
            </button>
          {/each}
        </div>
      {/if}

      <!-- ------------------------------------------------------ installed -->
      {#if tab === 'installed'}
        {#if view.problems.length && !tabbed}
          <Section label="Problems" material>
            {#each view.problems as problem}
              <Row
                title={problem.message}
                badge={problem.severity}
                tone={problem.blocking ? 'bad' : 'warn'}
                compact
              />
            {/each}
          </Section>
        {/if}

        {#if view.components.length}
          <Section
            label="Fathom components"
            summary="Installed and kept current by Fathom"
          >
            {#each view.components as component (component.id)}
              <Row
                title={component.name}
                detail={component.summary}
                badge={component.state}
                tone={component.state === 'Missing'
                  ? 'warn'
                  : component.state === 'Not added'
                    ? 'neutral'
                    : 'good'}
                dimmed={component.state === 'Missing' || component.state === 'Not added'}
                expandable
              >
                <span slot="icon"><Package size={15} strokeWidth={1.9} /></span>

                <svelte:fragment slot="control">
                  {#if component.installable}
                    <Button
                      disabled={busy}
                      onClick={() => void addComponent(component.id, component.name)}
                    >
                      <Download size={13} strokeWidth={2} />
                      Install
                    </Button>
                  {/if}
                </svelte:fragment>

                <svelte:fragment slot="details">
                  <dl>
                    <div><dt>Role</dt><dd>{component.tier}</dd></div>
                    <div><dt>Version</dt><dd class="tnum">{component.version ?? '—'}</dd></div>
                    <div><dt>Licence</dt><dd>{component.license}</dd></div>
                    <div><dt>Minecraft</dt><dd>{component.minecraft}</dd></div>
                    <div><dt>Loaders</dt><dd>{component.loaders}</dd></div>
                    <div><dt>Source</dt><dd>{component.source}</dd></div>
                    {#if component.fingerprint}
                      <div><dt>File</dt><dd class="tnum">{component.fingerprint}</dd></div>
                    {/if}
                    {#if component.dependencies.length}
                      <div><dt>Needs</dt><dd>{component.dependencies.join(', ')}</dd></div>
                    {/if}
                    {#if component.conflicts.length}
                      <div><dt>Clashes</dt><dd>{component.conflicts.join(', ')}</dd></div>
                    {/if}
                    {#if component.blocker}
                      <div><dt>Why not</dt><dd>{component.blocker}</dd></div>
                    {/if}
                  </dl>
                </svelte:fragment>
              </Row>
            {/each}
          </Section>
        {/if}

        {#if view.rows.length}
          <Section label="Installed" summary={query ? `${rows.length} shown` : ''}>
            {#if chips.length > 2}
              <div class="chips">
                {#each chips as [id, label, count]}
                  <button class="chip" class:on={filter === id} on:click={() => (filter = id)}>
                    {label} <span class="count">{count}</span>
                  </button>
                {/each}
              </div>
            {/if}

            {#each rows as row, index (row.id)}
              <div in:fly={{ y: 6, duration: NORMAL, delay: index * STAGGER, easing: EASE }}>
                <Row
                  title={row.name}
                  detail={row.summary || `${row.origin} · ${row.version} · ${row.size}`}
                  badge={row.missing ? 'File missing' : row.origin}
                  tone={row.missing ? 'bad' : 'neutral'}
                  warning={row.warning}
                  dimmed={!row.enabled}
                  expandable
                >
                  <span slot="icon"><Puzzle size={15} strokeWidth={1.9} /></span>

                  <svelte:fragment slot="control">
                    <Switch
                      checked={row.enabled}
                      disabled={busy || !row.canToggle}
                      label={row.name}
                      onChange={(value) => run(() => setModEnabled(row.id, value))}
                    />
                    <button
                      class="remove"
                      title="Remove"
                      disabled={busy}
                      on:click={() => run(() => removeMod(row.id))}
                    >
                      <Trash2 size={13} strokeWidth={2} />
                    </button>
                  </svelte:fragment>

                  <svelte:fragment slot="details">
                    <dl>
                      <div><dt>Version</dt><dd class="tnum">{row.version}</dd></div>
                      <div><dt>Source</dt><dd>{row.origin}</dd></div>
                      <div><dt>Licence</dt><dd>{row.license}</dd></div>
                      <div><dt>Size</dt><dd class="tnum">{row.size}</dd></div>
                      <div>
                        <dt>Updates</dt>
                        <dd>
                          {row.updatable
                            ? 'Fathom can check this for updates.'
                            : 'Your own file. Fathom has nothing to check it against.'}
                        </dd>
                      </div>
                    </dl>
                  </svelte:fragment>
                </Row>
              </div>
            {/each}

            {#if rows.length === 0}
              <p class="none">{query ? 'No match.' : 'Nothing in this group.'}</p>
            {/if}
          </Section>
        {:else}
          <!-- Compact and intentional. One line, one action, no rectangle. -->
          <div class="empty" in:fade={{ duration: NORMAL, easing: EASE }}>
            <span>No mods yet.</span>
            <span class="empty-detail">Drop a mod JAR here.</span>
          </div>
        {/if}
      {/if}

      <!-- --------------------------------------------------------- browse -->
      {#if tab === 'browse'}
        <Section
          label="Browse"
          summary="{view.loader} · Minecraft {view.minecraft}"
        >
          <div class="browse-bar">
            <div class="search wide">
              <Search size={13} strokeWidth={2} />
              <!-- svelte-ignore a11y-autofocus -->
              <input
                bind:value={browseQuery}
                placeholder="Search Modrinth"
                spellcheck="false"
                on:keydown={(event) => event.key === 'Enter' && void search()}
              />
            </div>
            <Button variant="primary" disabled={browsing} onClick={search}>
              {browsing ? 'Searching…' : 'Search'}
            </Button>
          </div>

          {#if browse}
            {#each browse.rows as hit, index (hit.project)}
              <div in:fly={{ y: 6, duration: NORMAL, delay: index * STAGGER, easing: EASE }}>
                <Row
                  title={hit.title}
                  detail={hit.summary}
                  badge={hit.installed ? 'Installed' : hit.license}
                  tone={hit.installed ? 'good' : 'neutral'}
                >
                  <span slot="icon"><Puzzle size={15} strokeWidth={1.9} /></span>
                  <svelte:fragment slot="control">
                    <button
                      class="link"
                      title="Open its page"
                      on:click={() => void openProjectPage(hit.slug)}
                    >
                      <ExternalLink size={13} strokeWidth={2} />
                    </button>
                    {#if !hit.installed}
                      <Button
                        disabled={busy}
                        onClick={() => void install(hit.project, hit.title)}
                      >
                        <Download size={13} strokeWidth={2} />
                        Install
                      </Button>
                    {/if}
                  </svelte:fragment>
                </Row>
              </div>
            {/each}

            {#if browse.rows.length === 0}
              <p class="none">Nothing on Modrinth matches that for this version and loader.</p>
            {:else if browse.more}
              <div class="more">
                <Button disabled={browsing} onClick={more}>
                  {browsing ? 'Loading…' : `Show more (${browse.total} found)`}
                </Button>
              </div>
            {/if}
          {:else if !browsing}
            <p class="none">Search for a mod, or press Search to see what is available.</p>
          {/if}
        </Section>
      {/if}

      <!-- -------------------------------------------------------- updates -->
      {#if tab === 'updates'}
        <Section label="Updates" summary={updates ? `${updates.checkable} of ${updates.total} can be checked` : ''}>
          {#if updates?.blocked}
            <p class="none">{updates.blocked}</p>
          {:else if updates}
            {#each updates.rows as update (update.id)}
              <Row
                title={update.name}
                detail="{update.from} → {update.to}"
                badge="Update"
                tone="good"
              >
                <span slot="icon"><RefreshCw size={15} strokeWidth={1.9} /></span>
                <svelte:fragment slot="control">
                  <Button
                    disabled={busy}
                    onClick={() => void install(update.project, update.name)}
                  >
                    <Download size={13} strokeWidth={2} />
                    Update
                  </Button>
                </svelte:fragment>
              </Row>
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
          {/if}
          <div class="more">
            <Button disabled={checking} onClick={checkUpdates}>
              {checking ? 'Checking…' : 'Check again'}
            </Button>
          </div>
        </Section>
      {/if}

      <!-- --------------------------------------------------------- issues -->
      {#if tab === 'issues'}
        <Section label="Issues" summary={`${view.problems.length} found`}>
          {#each view.problems as problem}
            <Row
              title={problem.message}
              badge={problem.severity}
              tone={problem.blocking ? 'bad' : 'warn'}
              compact
            />
          {/each}
          {#if view.problems.length === 0}
            <p class="none">Nothing wrong with this set.</p>
          {/if}
        </Section>
      {/if}

      <!-- The OptiFine question, answered before it is asked. It is the mod
           most people look for and the one this launcher cannot fetch, so the
           page says why rather than leaving somebody to search. -->
      {#if tab === 'installed'}
        <Section label="OptiFine">
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
              <Button variant="primary" disabled={busy} onClick={addOne}>
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

<style>
  /* Tabs are text with a rule under the active one. A pill row here would
     compete with the segmented controls used for settings, which mean
     "choose one value"; these mean "look at a different list". */
  .tabs {
    display: flex;
    gap: var(--s-1);
    border-bottom: 1px solid var(--chrome-line);
    margin-bottom: var(--s-2);
  }

  .tab {
    position: relative;
    padding: 8px 12px;
    font-size: var(--t-body);
    color: var(--text-faint);
    background: none;
    border: none;
    cursor: pointer;
    transition: color var(--m-fast) var(--ease);
  }

  .tab:hover {
    color: var(--text-muted);
  }

  .tab.on {
    color: var(--text);
  }

  .tab.on::after {
    content: '';
    position: absolute;
    left: 10px;
    right: 10px;
    bottom: -1px;
    height: 2px;
    border-radius: 2px;
    background: var(--accent);
  }

  .tab .count,
  .chip .count {
    margin-left: 5px;
    font-size: var(--t-meta);
    color: var(--text-disabled);
  }

  .chips {
    display: flex;
    flex-wrap: wrap;
    gap: var(--s-1);
    padding: var(--s-2) 0;
  }

  .chip {
    padding: 3px 9px;
    border-radius: 999px;
    border: 1px solid var(--chrome-line-strong);
    background: none;
    font-size: var(--t-caption);
    color: var(--text-faint);
    cursor: pointer;
    transition:
      color var(--m-fast) var(--ease),
      border-color var(--m-fast) var(--ease);
  }

  .chip.on {
    color: var(--text);
    border-color: var(--accent);
  }

  .browse-bar {
    display: flex;
    gap: var(--s-2);
    align-items: center;
    padding: var(--s-3) 0;
  }

  .more {
    display: flex;
    justify-content: center;
    padding: var(--s-3) 0;
  }

  .link {
    display: grid;
    place-items: center;
    width: 27px;
    height: 27px;
    border-radius: var(--r-xs);
    color: var(--text-disabled);
    transition:
      color var(--m-fast) var(--ease),
      background var(--m-fast) var(--ease);
  }

  .link:hover {
    color: var(--text);
    background: var(--fill-hover);
  }

  /* A real target, not a decoration: the window accepts the drop anywhere, and
     this says where to aim. */
  .drop {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: var(--s-2);
    padding: var(--s-6) var(--s-4);
    border-radius: var(--r-md);
    border: 1px dashed var(--chrome-line-strong);
    background: var(--fill-sunken);
    font-size: var(--t-body);
    color: var(--text-faint);
    transition:
      border-color var(--m-normal) var(--ease),
      background var(--m-normal) var(--ease),
      color var(--m-normal) var(--ease);
  }

  .drop.active {
    border-color: var(--accent);
    color: var(--text);
    background: rgba(224, 80, 92, 0.12);
  }

  /* The way out of a loader that reads no mods. One sentence, one consequence,
     one button -- instead of a page of controls that cannot do anything. */
  .loader-block {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: var(--s-2);
    padding: var(--s-8) var(--s-5);
    color: var(--text-faint);
    text-align: center;
  }

  .loader-message {
    font-size: var(--t-row);
    font-weight: var(--w-medium);
    color: var(--text);
  }

  .loader-warning {
    font-size: var(--t-caption);
    margin-bottom: var(--s-2);
  }

  .notice.good {
    color: var(--good);
  }

  .quiet {
    font-size: var(--t-caption);
    color: var(--text-faint);
  }

  .optifine {
    display: flex;
    flex-direction: column;
    gap: var(--s-3);
    padding: var(--s-4) 0 var(--s-2);
  }

  .optifine p {
    margin: 0;
    font-size: var(--t-body);
    color: var(--text-muted);
    line-height: 1.5;
  }

  .optifine-actions {
    display: flex;
    gap: var(--s-2);
  }

  .search {
    display: flex;
    align-items: center;
    gap: var(--s-2);
    height: 30px;
    padding: 0 10px;
    border-radius: var(--r-sm);
    background: var(--fill-sunken);
    border: 1px solid var(--chrome-line-strong);
    color: var(--text-faint);
    transition: border-color var(--m-fast) var(--ease);
  }

  .search:focus-within {
    border-color: var(--accent);
    color: var(--text-muted);
  }

  .search input {
    width: 130px;
    background: none;
    border: none;
    font-size: var(--t-body);
    color: var(--text);
  }

  .search.wide {
    flex: 1;
  }

  .search.wide input {
    width: 100%;
  }

  .search input::placeholder {
    color: var(--text-disabled);
  }

  .remove {
    display: grid;
    place-items: center;
    width: 27px;
    height: 27px;
    border-radius: var(--r-xs);
    color: var(--text-disabled);
    transition:
      color var(--m-fast) var(--ease),
      background var(--m-fast) var(--ease);
  }

  .remove:hover:not(:disabled) {
    color: var(--bad);
    background: rgba(232, 114, 114, 0.15);
  }

  .remove:disabled {
    opacity: 0.4;
    cursor: default;
  }

  .empty {
    display: flex;
    flex-direction: column;
    gap: var(--s-1);
    padding: 4px 2px 8px;
    font-size: var(--t-body-lg);
    color: var(--text-muted);
  }

  .empty-detail {
    font-size: var(--t-body);
    color: var(--text-faint);
  }

  .none {
    margin: 0;
    padding: 14px;
    font-size: var(--t-body);
    color: var(--text-faint);
    line-height: 1.5;
  }

  .notice {
    display: flex;
    align-items: center;
    gap: var(--s-3);
    padding: 10px 13px;
    border-radius: var(--r-md);
    font-size: var(--t-body);
    /* Glass, so a warning over footage stays readable without a solid slab. */
    background: rgba(224, 174, 87, 0.1);
    backdrop-filter: blur(var(--glass-blur));
    -webkit-backdrop-filter: blur(var(--glass-blur));
    border: 1px solid rgba(224, 174, 87, 0.28);
    box-shadow: var(--glass-sheen);
    color: var(--warn);
  }

  .notice.bad {
    background: rgba(232, 114, 114, 0.11);
    border-color: rgba(232, 114, 114, 0.3);
    color: var(--bad);
  }

  dl {
    display: flex;
    flex-direction: column;
    gap: var(--s-1);
    margin: 0;
  }

  dl div {
    display: flex;
    gap: var(--s-3);
  }

  dt {
    flex-shrink: 0;
    width: 80px;
    color: var(--text-faint);
    font-size: var(--t-meta);
    text-transform: uppercase;
    letter-spacing: var(--track-wide);
    padding-top: var(--s-1);
  }

  dd {
    margin: 0;
    flex: 1;
  }
</style>

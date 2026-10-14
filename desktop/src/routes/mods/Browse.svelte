<!--
  The Browse tab: find mods for this profile, see whether they will load, and
  install them.

  HOW A RESULT BECOMES TRUSTWORTHY
  A search asks the index for projects whose *tags* mention Minecraft 1.8.9 and
  a plausible loader. Tags are project-wide, so a hit is only a candidate: each
  card starts as "Checking" and the compatibility resolver in Rust then reads
  the project's actual builds and answers Compatible, Unverified or
  Incompatible with its reason. Checks run a few cards at a time, in the order
  the cards appear, paced by Rust so the index is not hammered.

  WHAT KEEPS IT RESPONSIVE
  Typing is debounced; a new search cancels the one in flight (in Rust, down to
  the HTTP request) and a late answer for an old query is dropped. Results load
  a page at a time as the list scrolls. Every request runs on a Rust worker,
  never on the window's thread.
-->
<script lang="ts">
  import { onDestroy, onMount, tick } from 'svelte';
  import { fly, fade } from 'svelte/transition';
  import { Download, RefreshCw, Check, SearchX, Globe, LoaderCircle } from '@lucide/svelte';
  import { ICON } from '../../lib/metrics.generated';
  import { EASE, MOTION_HOVER, MOTION_PAGE, MOTION_STAGGER } from '../../lib/motion';
  import Section from '../../lib/ui/Section.svelte';
  import Button from '../../lib/ui/Button.svelte';
  import Notice from '../../lib/ui/Notice.svelte';
  import SearchField from '../../lib/ui/SearchField.svelte';
  import Segmented from '../../lib/ui/Segmented.svelte';
  import Select from '../../lib/ui/Select.svelte';
  import EmptyState from '../../lib/ui/EmptyState.svelte';
  import RemoteImage from '../../lib/ui/RemoteImage.svelte';
  import VerdictBadge from './VerdictBadge.svelte';
  import { formatCount, formatWhen } from './format';
  import { keep, restore, type BrowseState } from './browseState';
  import {
    catalogCategories,
    catalogContext,
    catalogSearch,
    catalogVerdicts,
    setSetting,
    type BrowseContext,
    type CatalogCategory,
    type CatalogEntry,
    type Verdict,
  } from '../../lib/api';

  /** Bumped by the parent after anything was installed or removed. */
  export let revision = 0;
  export let busy = false;
  /** Keys of projects with an install running, for their cards. */
  export let running: Set<string> = new Set();
  export let onOpen: (entry: CatalogEntry) => void;
  /** `quick` when the card was verified compatible: install in one click. */
  export let onInstall: (entry: CatalogEntry, quick: boolean) => void;
  export let onUpdate: (entry: CatalogEntry) => void;

  let context: BrowseContext | null = null;
  let categories: CatalogCategory[] = [];
  let state: BrowseState | null = null;
  let loading = false;
  let loadingMore = false;
  let problem = '';
  let contextProblem = '';
  let generation = 0;
  let debounce: ReturnType<typeof setTimeout> | null = null;
  let sentinel: HTMLElement;
  let root: HTMLElement;
  let observer: IntersectionObserver | null = null;
  /** Pages loaded in a row that added nothing visible; stops runaway paging. */
  let emptyPages = 0;
  let enabling = false;

  const DEBOUNCE_MS = 300;
  const CHECK_BATCH = 6;

  const SORTS = [
    { id: 'relevance', label: 'Relevance' },
    { id: 'downloads', label: 'Downloads' },
    { id: 'updated', label: 'Updated' },
  ];

  const BREADTHS = [
    { id: 'tagged', label: 'Verified Legacy Fabric' },
    { id: 'plausible', label: 'Include unverified' },
    { id: 'any-loader', label: 'All 1.8.9 mods' },
  ];

  function scroller(): HTMLElement | null {
    return root?.closest('.scroll') ?? null;
  }

  onMount(() => {
    void (async () => {
      try {
        context = await catalogContext();
      } catch (cause) {
        contextProblem = message(cause);
        return;
      }
      state = restore(context.profileId);
      if (!context.blocked) {
        void loadCategories();
        if (state.loaded) {
          await tick();
          const scroll = scroller();
          if (scroll) scroll.scrollTop = state.scrollTop;
          void verifyVisible();
        } else {
          void search();
        }
      }
    })();
  });

  // The sentinel exists only once there is a list to page; watch it then.
  $: if (sentinel && !observer) watchSentinel();

  onDestroy(() => {
    if (debounce) clearTimeout(debounce);
    observer?.disconnect();
    generation += 1;
    if (state) {
      state.scrollTop = scroller()?.scrollTop ?? 0;
      keep(state);
    }
  });

  function message(cause: unknown): string {
    return String(cause instanceof Error ? cause.message : cause);
  }

  async function loadCategories() {
    try {
      categories = await catalogCategories();
    } catch {
      // The filter is a convenience; without it, search still works.
      categories = [];
    }
  }

  function watchSentinel() {
    if (typeof IntersectionObserver === 'undefined' || !sentinel) return;
    observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting) && emptyPages < 4) void more();
      },
      { rootMargin: '400px' },
    );
    observer.observe(sentinel);
  }

  // -- searching ---------------------------------------------------------------

  async function search() {
    if (!state || !context) return;
    if (debounce) clearTimeout(debounce);
    const mine = ++generation;
    loading = true;
    problem = '';
    emptyPages = 0;
    state.entries = [];
    state.verdicts = {};
    state.loaded = false;
    state = state;
    try {
      const page = await catalogSearch({
        text: state.text,
        category: state.category || null,
        sort: state.sort,
        offset: 0,
        breadth: state.breadth,
      });
      if (mine !== generation || page.superseded) return;
      state.entries = page.entries;
      state.providers = page.providers;
      state.nextOffset = page.nextOffset;
      state.hasMore = page.hasMore;
      state.loaded = true;
      state = state;
      const scroll = scroller();
      if (scroll && scroll.scrollTop > 200) scroll.scrollTop = 0;
      void verifyVisible();
    } catch (cause) {
      if (mine === generation) problem = message(cause);
    } finally {
      if (mine === generation) loading = false;
    }
  }

  async function more() {
    if (!state || loading || loadingMore || !state.hasMore || !state.loaded) return;
    const mine = generation;
    loadingMore = true;
    const before = shown(state.entries).length;
    try {
      const page = await catalogSearch({
        text: state.text,
        category: state.category || null,
        sort: state.sort,
        offset: state.nextOffset,
        breadth: state.breadth,
      });
      if (mine !== generation || page.superseded) return;
      const known = new Set(state.entries.map((entry) => entry.key));
      state.entries = [...state.entries, ...page.entries.filter((entry) => !known.has(entry.key))];
      state.nextOffset = page.nextOffset;
      state.hasMore = page.hasMore;
      state = state;
      void verifyVisible();
    } catch (cause) {
      if (mine === generation) problem = message(cause);
    } finally {
      loadingMore = false;
      if (state && shown(state.entries).length === before) emptyPages += 1;
      else emptyPages = 0;
    }
  }

  /** The search box: wait for typing to settle. Filters apply at once. */
  function typed(value: string) {
    if (!state) return;
    state.text = value;
    if (debounce) clearTimeout(debounce);
    debounce = setTimeout(() => void search(), DEBOUNCE_MS);
  }

  function setFilter(change: Partial<Pick<BrowseState, 'category' | 'sort' | 'breadth'>>) {
    if (!state) return;
    Object.assign(state, change);
    void search();
  }

  // -- verdicts ----------------------------------------------------------------

  let verifying = false;

  async function verifyVisible() {
    if (verifying || !state) return;
    verifying = true;
    const mine = generation;
    try {
      for (;;) {
        if (!state || mine !== generation) break;
        const pending = state.entries
          .filter((entry) => !entry.ruledOut && !state!.verdicts[entry.key])
          .slice(0, CHECK_BATCH);
        if (pending.length === 0) break;
        let answers;
        try {
          answers = await catalogVerdicts(pending.map((entry) => entry.reference));
        } catch (cause) {
          // Mark this batch as not checked rather than spinning forever.
          for (const entry of pending) {
            state.verdicts[entry.key] = { key: entry.key, verdict: null, problem: message(cause) };
          }
          state = state;
          break;
        }
        if (mine !== generation) break;
        for (const answer of answers) state.verdicts[answer.key] = answer;
        // A project the check skipped (cancelled mid-batch) is retried next round;
        // one it could not answer at all is recorded so it is not retried forever.
        for (const entry of pending) {
          if (!state.verdicts[entry.key] && answers.length === 0) {
            state.verdicts[entry.key] = { key: entry.key, verdict: null, problem: 'Not checked' };
          }
        }
        state = state;
      }
    } finally {
      verifying = false;
    }
  }

  function verdictOf(entry: CatalogEntry, s: BrowseState): Verdict | 'checking' | 'unknown' {
    if (entry.ruledOut) return 'incompatible';
    const known = s.verdicts[entry.key];
    if (!known) return 'checking';
    return known.verdict?.verdict ?? 'unknown';
  }

  function headlineOf(entry: CatalogEntry, s: BrowseState): string {
    if (entry.ruledOut) return entry.ruledOut;
    const known = s.verdicts[entry.key];
    return known?.verdict?.headline ?? known?.problem ?? 'Reading its builds for this version…';
  }

  function shortOf(entry: CatalogEntry, s: BrowseState): string {
    if (entry.ruledOut) return entry.ruledOut;
    const known = s.verdicts[entry.key];
    return known?.verdict?.short ?? known?.problem ?? '';
  }

  function shown(entries: CatalogEntry[]): CatalogEntry[] {
    if (!state) return [];
    const s = state;
    return entries.filter((entry) => {
      const verdict = verdictOf(entry, s);
      if (s.breadth === 'tagged') return verdict === 'compatible' || verdict === 'checking';
      if (s.breadth === 'plausible') return verdict !== 'incompatible';
      return true;
    });
  }

  // -- after an install --------------------------------------------------------

  let seenRevision = revision;
  $: if (revision !== seenRevision) {
    seenRevision = revision;
    void refreshStates();
  }

  /** Re-read install states for the loaded pages. Served from Rust's cache. */
  async function refreshStates() {
    if (!state?.loaded) return;
    const mine = generation;
    const updated = new Map<string, CatalogEntry>();
    for (let offset = 0; offset < state.nextOffset; ) {
      try {
        const page = await catalogSearch({
          text: state.text,
          category: state.category || null,
          sort: state.sort,
          offset,
          breadth: state.breadth,
        });
        if (mine !== generation || page.superseded) return;
        for (const entry of page.entries) updated.set(entry.key, entry);
        if (page.nextOffset <= offset) break;
        offset = page.nextOffset;
      } catch {
        break;
      }
    }
    if (!state || mine !== generation) return;
    state.entries = state.entries.map((entry) => {
      const fresh = updated.get(entry.key);
      return fresh ? { ...entry, install: fresh.install, installedId: fresh.installedId } : entry;
    });
    // Re-reading the pages cancels checks that were in flight; ask again for
    // any card that was left without an answer.
    for (const [key, known] of Object.entries(state.verdicts)) {
      if (!known.verdict && known.problem === 'Not checked') delete state.verdicts[key];
    }
    state = state;
    void verifyVisible();
  }

  async function allowRepositories() {
    if (enabling) return;
    enabling = true;
    try {
      await setSetting('mod-repositories', true);
      context = await catalogContext();
      if (context && !context.blocked) {
        state = restore(context.profileId);
        void loadCategories();
        void search();
      }
    } catch (cause) {
      contextProblem = message(cause);
    } finally {
      enabling = false;
    }
  }

  $: visible = state ? shown(state.entries) : [];
  $: hidden = state ? state.entries.length - visible.length : 0;
  $: categoryOptions = [{ id: '', label: 'All categories' }, ...categories.map((c) => ({ id: c.id, label: c.label }))];
  $: unconfigured = context?.providers.filter((provider) => !provider.ready) ?? [];
  $: failing = state?.providers.filter((provider) => provider.problem) ?? [];
  $: sources = context?.providers.filter((provider) => provider.ready).map((p) => p.label).join(' and ') ?? '';
</script>

<section class="browse" bind:this={root}>
  {#if contextProblem}
    <Notice tone="danger">{contextProblem}</Notice>
  {:else if context?.blocked}
    <Section material>
      <div in:fade={{ duration: MOTION_PAGE, easing: EASE }}>
        <EmptyState
          icon={Globe}
          title="Mod repositories are off"
          detail="Browsing sends what you search for, and which mods you open, to Modrinth. Nothing about your account or your game is sent. You can turn this off again in Privacy."
        >
          <Button variant="primary" disabled={enabling} onClick={() => void allowRepositories()}>
            {enabling ? 'Turning on…' : 'Allow mod repositories'}
          </Button>
        </EmptyState>
      </div>
    </Section>
  {:else if context && state}
    <div class="toolbar">
      <div class="search">
        <SearchField value={state.text} placeholder="Search {sources || 'Modrinth'}" size="lg" onInput={typed} />
      </div>
      <div class="filters">
        <Segmented options={SORTS} value={state.sort} onChange={(id) => setFilter({ sort: id as BrowseState['sort'] })} />
        <div class="select">
          <Select label="Compatibility" options={BREADTHS} value={state.breadth} onChange={(id) => setFilter({ breadth: id as BrowseState['breadth'] })} />
        </div>
        {#if categories.length}
          <div class="select">
            <Select label="Category" options={categoryOptions} value={state.category} onChange={(id) => setFilter({ category: id })} />
          </div>
        {/if}
      </div>
    </div>

    <p class="context">
      For {context.profileName} · {context.loader} · Minecraft {context.minecraft}
      {#each unconfigured as provider}
        <span class="aside" title={provider.note ?? ''}> · {provider.label} is not set up</span>
      {/each}
    </p>

    {#if problem}
      <div transition:fade={{ duration: MOTION_HOVER }}>
        <Notice tone="danger">
          {problem}
          <button class="inline" on:click={() => void search()}>Try again</button>
        </Notice>
      </div>
    {/if}
    {#each failing as provider}
      <Notice tone="warning">
        {provider.problem}
        {#if provider.retryable}<button class="inline" on:click={() => void search()}>Try again</button>{/if}
      </Notice>
    {/each}

    <Section
      label={state.text.trim() ? `Results for “${state.text.trim()}”` : 'Mods'}
      summary={state.loaded ? `${visible.length} shown${hidden ? ` · ${hidden} hidden by the compatibility filter` : ''}` : ''}
      material
    >
      {#if loading}
        {#each Array(6) as _, index}
          <div class="card skeleton-card" style="animation-delay: {index * 80}ms" aria-hidden="true">
            <div class="skeleton tile"></div>
            <div class="lines">
              <div class="skeleton line w40"></div>
              <div class="skeleton line w90"></div>
              <div class="skeleton line w60"></div>
            </div>
          </div>
        {/each}
      {:else}
        {#each visible as entry, index (entry.key)}
          {@const verdict = verdictOf(entry, state)}
          {@const install = entry.install}
          {@const working = running.has(entry.key) || install.state === 'installing' || install.state === 'updating'}
          <!-- svelte-ignore a11y-click-events-have-key-events a11y-no-static-element-interactions -->
          <div
            class="card"
            class:receded={verdict === 'incompatible'}
            on:click={() => onOpen(entry)}
            in:fly={{ y: 6, duration: MOTION_PAGE, delay: Math.min(index % 24, 10) * MOTION_STAGGER, easing: EASE }}
          >
            <RemoteImage provider={entry.reference.provider} url={entry.icon} alt="" fallback={entry.name[0]?.toUpperCase() ?? '?'} />
            <div class="text">
              <div class="title-line">
                <button class="name" on:click|stopPropagation={() => onOpen(entry)}>{entry.name}</button>
                {#if entry.authors.length}<span class="by">by {entry.authors[0]}</span>{/if}
              </div>
              {#if entry.summary}<p class="summary">{entry.summary}</p>{/if}
              <p class="meta tnum">
                <span><Download size={12} /> {formatCount(entry.downloads)}</span>
                {#if entry.updated}<span>Updated {formatWhen(entry.updated)}</span>{/if}
                {#each entry.categories.slice(0, 3) as category}<span class="tag">{category}</span>{/each}
              </p>
              {#if verdict !== 'compatible' && verdict !== 'checking'}
                <p class="why" class:bad={verdict === 'incompatible'}>{shortOf(entry, state)}</p>
              {/if}
              {#if entry.possibleDuplicateOf}<p class="why">May already be installed: {entry.possibleDuplicateOf}</p>{/if}
            </div>
            <div class="controls">
              <VerdictBadge {verdict} detail={headlineOf(entry, state)} />
              {#if working}
                <span class="state"><span class="spin"><LoaderCircle size={14} /></span> Installing</span>
              {:else if install.state === 'installed'}
                <span class="state"><Check size={ICON.inline} /> Installed</span>
              {:else if install.state === 'update-available'}
                <Button small disabled={busy} onClick={() => onUpdate(entry)}>
                  <RefreshCw size={ICON.inline} />
                  Update
                </Button>
              {:else if verdict === 'compatible'}
                <Button small disabled={busy} onClick={() => onInstall(entry, true)}>
                  <Download size={ICON.inline} />
                  Install
                </Button>
              {:else if verdict === 'unverified'}
                <Button small disabled={busy} onClick={() => onInstall(entry, false)}>Review</Button>
              {/if}
            </div>
          </div>
        {/each}

        {#if loadingMore}
          {#each Array(2) as _}
            <div class="card skeleton-card" aria-hidden="true">
              <div class="skeleton tile"></div>
              <div class="lines"><div class="skeleton line w40"></div><div class="skeleton line w90"></div></div>
            </div>
          {/each}
        {/if}

        {#if state.loaded && visible.length === 0 && !loadingMore}
          <EmptyState
            compact
            icon={SearchX}
            title={state.entries.length ? 'Nothing here passes the filter' : 'No mods found'}
            detail={state.entries.length
              ? `${hidden} results were hidden because they will not load on ${context.loader} ${context.minecraft}. Choose “All 1.8.9 mods” to see them and why.`
              : state.text.trim()
                ? `Nothing for ${context.loader} ${context.minecraft} matches “${state.text.trim()}”. Try fewer words, or widen the compatibility filter.`
                : 'Try another category, or widen the compatibility filter.'}
          />
        {/if}
      {/if}
    </Section>

    <div class="tail" bind:this={sentinel}>
      {#if state.loaded && state.hasMore && emptyPages >= 4}
        <Button onClick={() => { emptyPages = 0; void more(); }}>Load more</Button>
      {:else if state.loaded && !state.hasMore && state.entries.length > 0}
        <span class="end">That is everything {sources || 'Modrinth'} has for this search.</span>
      {/if}
    </div>
  {:else}
    <div class="card skeleton-card" aria-hidden="true">
      <div class="skeleton tile"></div>
      <div class="lines"><div class="skeleton line w40"></div><div class="skeleton line w90"></div></div>
    </div>
  {/if}
</section>

<style>
  .browse {
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
    flex: 1 1 320px;
    min-width: 240px;
  }

  .filters {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: var(--space-2);
  }

  .select {
    min-width: 170px;
  }

  .context {
    margin: 0;
    font-size: var(--type-caption-size);
    color: var(--color-text-tertiary);
  }

  .aside {
    color: var(--color-text-tertiary);
  }

  .inline {
    margin-left: var(--space-2);
    padding: 0;
    color: inherit;
    font-weight: var(--type-button-weight);
    text-decoration: underline;
    text-underline-offset: 3px;
  }

  /* -- a result ---------------------------------------------------------------- */
  .card {
    display: grid;
    grid-template-columns: auto minmax(0, 1fr) auto;
    align-items: start;
    gap: var(--space-4);
    padding: var(--space-3) var(--space-4);
    border-radius: var(--metric-radius-panel);
    box-shadow: 0 calc(-1 * var(--hairline)) 0 var(--control-hairline);
    cursor: pointer;
    transition: background-color var(--motion-hover) var(--ease-standard);
  }

  .card:first-child {
    box-shadow: none;
  }

  .card:hover {
    background-color: var(--control-fill-faint);
  }

  .card.receded {
    opacity: 0.72;
  }

  .text {
    display: flex;
    flex-direction: column;
    gap: 3px;
    min-width: 0;
  }

  .title-line {
    display: flex;
    align-items: baseline;
    flex-wrap: wrap;
    gap: 0 var(--space-2);
  }

  .name {
    padding: 0;
    font-size: var(--type-body-size);
    font-weight: var(--type-heading-weight);
    letter-spacing: var(--type-heading-tracking);
    line-height: 1.35;
    color: var(--color-text);
    text-align: left;
    overflow-wrap: anywhere;
  }

  .name:hover {
    text-decoration: underline;
    text-decoration-color: var(--control-hairline-strong);
    text-underline-offset: 3px;
  }

  .name:focus-visible {
    outline: var(--focus-ring-width) solid var(--color-focus);
    outline-offset: 2px;
    border-radius: var(--radius-xs);
  }

  .by {
    font-size: var(--type-caption-size);
    color: var(--color-text-tertiary);
  }

  .summary {
    margin: 0;
    font-size: var(--type-secondary-size);
    line-height: var(--type-secondary-leading);
    color: var(--color-text-secondary);
    display: -webkit-box;
    -webkit-line-clamp: 2;
    line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden;
  }

  .meta {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: var(--space-1) var(--space-3);
    margin: 2px 0 0;
    font-size: var(--type-caption-size);
    color: var(--color-text-tertiary);
  }

  .meta span {
    display: inline-flex;
    align-items: center;
    gap: 4px;
  }

  .tag {
    padding: 0 7px;
    border-radius: var(--radius-pill);
    background-color: var(--control-fill-faint);
    text-transform: capitalize;
  }

  .why {
    margin: 2px 0 0;
    font-size: var(--type-caption-size);
    line-height: var(--type-secondary-leading);
    color: var(--color-warning-text);
  }

  .why.bad {
    color: var(--color-text-tertiary);
  }

  .controls {
    display: flex;
    flex-direction: column;
    align-items: flex-end;
    gap: var(--space-2);
    min-width: 112px;
  }

  .state {
    display: inline-flex;
    align-items: center;
    gap: var(--space-1);
    font-size: var(--type-secondary-size);
    color: var(--color-text-secondary);
    white-space: nowrap;
  }

  .spin {
    display: inline-flex;
    animation: spin 1.1s linear infinite;
  }

  @keyframes spin {
    to {
      transform: rotate(360deg);
    }
  }

  .tail {
    display: flex;
    justify-content: center;
    min-height: 40px;
    padding: var(--space-2);
  }

  .end {
    font-size: var(--type-caption-size);
    color: var(--color-text-tertiary);
  }

  /* -- loading placeholders: still shapes and a slow breath ---------------------- */
  .skeleton-card {
    cursor: default;
    grid-template-columns: auto minmax(0, 1fr);
  }

  .skeleton-card:hover {
    background-color: transparent;
  }

  .lines {
    display: flex;
    flex-direction: column;
    gap: var(--space-2);
    padding-top: 4px;
  }

  .skeleton {
    border-radius: var(--radius-xs);
    background-color: var(--control-fill-faint);
    animation: breathe 1.6s var(--ease-standard) infinite alternate;
  }

  .skeleton.tile {
    width: var(--metric-tile);
    height: var(--metric-tile);
    border-radius: var(--metric-radius-small);
  }

  .skeleton.line {
    height: 11px;
  }

  .w40 {
    width: 40%;
  }
  .w60 {
    width: 60%;
  }
  .w90 {
    width: 90%;
  }

  @keyframes breathe {
    from {
      opacity: 0.45;
    }
    to {
      opacity: 0.9;
    }
  }

  @media (prefers-reduced-motion: reduce) {
    .skeleton,
    .spin {
      animation: none;
    }
  }
</style>

<!--
  Settings.

  Two panes: categories left, rows right.

  WHAT IS NOT HERE, AND WHY

  - **Account.** It lives in the top bar's drawer. A settings category showing
    the same one row would be two places to sign in.
  - **Privacy.** It is a main destination. What lives here instead is *Network*,
    which is the switches; the Privacy screen is the explanation and the log.

  Removing those two is the point of this structure. A settings tree that
  mirrors the main navigation teaches people that the same thing lives in two
  places, and then they have to guess which one is authoritative.

  Categories come from the data: each switch carries its own (`api/settings.rs`),
  so adding a preference files itself without anybody editing this component.
-->
<script lang="ts">
  import { onMount } from 'svelte';
  import { fade, fly } from 'svelte/transition';
  import {
    Search,
    SlidersHorizontal,
    Gamepad2,
    Palette,
    Wifi,
    HardDrive,
    Wrench,
    Coffee,
    TriangleAlert,
    ScrollText,
    Terminal,
  } from '@lucide/svelte';

  import Section from '../lib/ui/Section.svelte';
  import Row from '../lib/ui/Row.svelte';
  import Switch from '../lib/ui/Switch.svelte';
  import Segmented from '../lib/ui/Segmented.svelte';
  import Button from '../lib/ui/Button.svelte';
  import BackgroundPicker from '../lib/ui/BackgroundPicker.svelte';
  import { EASE, FAST, NORMAL, STAGGER } from '../lib/motion';
  import { WORLD_CREDITS } from '../lib/credits';
  import { kindOf } from '../lib/backgrounds';
  import {
    openPage,
    recoveryView,
    setChoice,
    setSetting,
    settingsView,
    type RecoveryView,
    type SettingsView,
  } from '../lib/api';

  export let category = 'general';
  export let choice = 'bg_5rain';
  export let backgrounds: { id: string; label: string }[] = [];
  export let onBackground: (id: string) => void;
  export let onNavigate: (route: string, category?: string) => void = () => {};

  const CATEGORIES = [
    { id: 'general', label: 'General', icon: SlidersHorizontal },
    { id: 'game', label: 'Game', icon: Gamepad2 },
    { id: 'appearance', label: 'Appearance', icon: Palette },
    { id: 'network', label: 'Network', icon: Wifi },
    { id: 'storage', label: 'Storage', icon: HardDrive },
    { id: 'advanced', label: 'Advanced', icon: Wrench },
  ];

  let view: SettingsView | null = null;
  /** Storage borrows the cache figure Recovery already computes. */
  let recovery: RecoveryView | null = null;
  let busy = false;
  let error = '';
  let query = '';

  onMount(() => {
    void (async () => {
      [view, recovery] = await Promise.all([settingsView(), recoveryView()]);
    })();
  });

  async function toggle(id: string, value: boolean) {
    if (busy) return;
    busy = true;
    error = '';
    try {
      view = await setSetting(id, value);
    } catch (cause) {
      error = String(cause);
    } finally {
      busy = false;
    }
  }

  /* Same shape as `toggle`, and deliberately a second function rather than one
     that takes `boolean | string`: the two go to different commands, and the
     compiler should be the thing that notices if a caller mixes them up. */
  async function choose(id: string, value: string) {
    if (busy) return;
    busy = true;
    error = '';
    try {
      view = await setChoice(id, value);
    } catch (cause) {
      error = String(cause);
    } finally {
      busy = false;
    }
  }

  /* Both take the view rather than closing over it.
     Svelte only invalidates an {#each} whose expression MENTIONS a reactive
     variable. `togglesFor('advanced')` mentioned a function, so the list was
     never rebuilt when `view` was replaced: changing the game console wrote the
     new value to disk and left the old one selected on screen. Passing `view`
     in is what puts it in the expression. */
  function togglesFor(from: SettingsView | null, id: string) {
    return (from?.toggles ?? []).filter((toggle) => toggle.section === id);
  }

  function choicesFor(from: SettingsView | null, id: string) {
    return (from?.choices ?? []).filter((choice) => choice.section === id);
  }

  /* Searching jumps to the category holding the match rather than filtering in
     place: a search that empties five of six categories leaves the reader
     wondering where they went. */
  $: matches =
    query.trim() === ''
      ? []
      : [...(view?.toggles ?? []), ...(view?.choices ?? [])].filter((setting) =>
          `${setting.label} ${setting.detail}`.toLowerCase().includes(query.toLowerCase()),
        );

  $: cacheAction = recovery?.actions.find((action) => action.id === 'clear-cache');
  $: animated = kindOf(choice) === 'video';
  $: activeLabel = CATEGORIES.find((c) => c.id === category)?.label ?? 'Settings';
</script>

{#if view}
  <div class="settings">
    <nav class="sidebar">
      <div class="search">
        <Search size={13} strokeWidth={2} />
        <input bind:value={query} placeholder="Search" spellcheck="false" />
      </div>

      {#if query.trim()}
        <div class="results" transition:fade={{ duration: FAST }}>
          {#if matches.length}
            {#each matches as match}
              <button
                class="result"
                on:click={() => {
                  category = match.section;
                  query = '';
                }}
              >
                <span class="result-label">{match.label}</span>
                <span class="result-cat">
                  {CATEGORIES.find((c) => c.id === match.section)?.label ?? match.section}
                </span>
              </button>
            {/each}
          {:else}
            <p class="no-results">No match.</p>
          {/if}
        </div>
      {:else}
        <div class="categories">
          {#each CATEGORIES as item}
            <button
              class="category"
              class:active={category === item.id}
              on:click={() => (category = item.id)}
            >
              <svelte:component this={item.icon} size={15} strokeWidth={1.9} />
              <span>{item.label}</span>
            </button>
          {/each}
        </div>
      {/if}
    </nav>

    <div class="pane">
      {#key category}
        <div class="pane-inner" in:fly={{ y: 8, duration: NORMAL, easing: EASE }}>
          <h1>{activeLabel}</h1>

          {#if error}
            <div class="notice" transition:fade={{ duration: FAST }}>
              <TriangleAlert size={15} strokeWidth={2} />
              <span>{error}</span>
            </div>
          {/if}

          <!-- APPEARANCE -->
          {#if category === 'appearance'}
            <Section material>
              <Row title="Animated background" detail="Play gameplay footage behind Fathom.">
                <span slot="icon"><Palette size={15} strokeWidth={1.9} /></span>
                <svelte:fragment slot="control">
                  <Switch
                    checked={animated}
                    label="Animated background"
                    onChange={(value) => onBackground(value ? 'bg_5rain' : 'static')}
                  />
                </svelte:fragment>
              </Row>
            </Section>

            <Section label="Background">
              <!-- The same component the bottom bar uses, at a larger size. -->
              <BackgroundPicker
                options={backgrounds}
                value={choice}
                size="large"
                onChoose={onBackground}
              />
            </Section>

            <!-- Their creators allow the footage to be used with credit.
                 The world and the creator are the links, rather than a pair of
                 buttons off to the right saying "World" and "Creator": the name
                 is the thing you would click, and a credit whose subject is not
                 the link makes you read a label to find out where it goes. -->
            <Section label="Background credits" material>
              {#each WORLD_CREDITS as credit}
                <div class="credit-row">
                  <button class="credit-world" on:click={() => void openPage(credit.worldPage)}>
                    {credit.world}
                  </button>
                  <p class="credit-by">
                    A world by <button
                      class="credit-creator"
                      on:click={() => void openPage(credit.creatorPage)}>{credit.creator}</button
                    > on CurseForge
                  </p>
                </div>
              {/each}
            </Section>
          {/if}

          <!-- GAME -->
          {#if category === 'game'}
            <Section material>
              <Row title={view.java.headline} detail={view.java.detail}>
                <span slot="icon" class="java" class:found={view.java.found}>
                  {#if view.java.found}
                    <Coffee size={15} strokeWidth={1.9} />
                  {:else}
                    <TriangleAlert size={15} strokeWidth={1.9} />
                  {/if}
                </span>
                <svelte:fragment slot="control">
                  <Button onClick={() => onNavigate('recovery')}>Find Java</Button>
                </svelte:fragment>
              </Row>

              {#each choicesFor(view, 'game') as row}
                <Row
                  title={row.label}
                  detail={row.options.find((option) => option.id === row.value)?.detail ??
                    row.detail}
                >
                  <span slot="icon"><Coffee size={15} strokeWidth={1.9} /></span>
                  <svelte:fragment slot="control">
                    <Segmented
                      options={row.options}
                      value={row.value}
                      disabled={busy}
                      onChange={(id) => choose(row.id, id)}
                    />
                  </svelte:fragment>
                </Row>
              {/each}

              {#each togglesFor(view, 'game') as row}
                <Row
                  title={row.label}
                  detail={row.detail}
                  badge={row.overriddenBy ?? ''}
                  dimmed={Boolean(row.overriddenBy)}
                >
                  <svelte:fragment slot="control">
                    <Switch
                      checked={row.value}
                      disabled={busy || Boolean(row.overriddenBy)}
                      label={row.label}
                      onChange={(value) => toggle(row.id, value)}
                    />
                  </svelte:fragment>
                </Row>
              {/each}
            </Section>
            <p class="aside">Memory, Java arguments and loader belong to a profile.</p>
          {/if}

          <!-- STORAGE -->
          {#if category === 'storage'}
            <Section material>
              <Row title="Cache" detail={cacheAction?.detail ?? 'Downloaded metadata.'}>
                <span slot="icon"><HardDrive size={15} strokeWidth={1.9} /></span>
                <svelte:fragment slot="control">
                  <Button
                    disabled={Boolean(cacheAction?.unavailable)}
                    title={cacheAction?.unavailable ?? ''}
                    onClick={() => onNavigate('recovery')}
                  >
                    Manage
                  </Button>
                </svelte:fragment>
              </Row>
            </Section>
          {/if}

          <!-- ADVANCED -->
          {#if category === 'advanced'}
            <Section material>
              <Row title="Logs" detail="Launcher and game output.">
                <span slot="icon"><ScrollText size={15} strokeWidth={1.9} /></span>
                <svelte:fragment slot="control">
                  <Button onClick={() => onNavigate('logs')}>Open</Button>
                </svelte:fragment>
              </Row>

              {#each choicesFor(view, 'advanced') as row}
                <Row
                  title={row.label}
                  detail={row.options.find((option) => option.id === row.value)?.detail ??
                    row.detail}
                >
                  <span slot="icon"><Terminal size={15} strokeWidth={1.9} /></span>
                  <svelte:fragment slot="control">
                    <Segmented
                      options={row.options}
                      value={row.value}
                      disabled={busy}
                      onChange={(id) => choose(row.id, id)}
                    />
                  </svelte:fragment>
                </Row>
              {/each}

              {#each togglesFor(view, 'advanced') as row}
                <Row title={row.label} detail={row.detail}>
                  <svelte:fragment slot="control">
                    <Switch
                      checked={row.value}
                      disabled={busy}
                      label={row.label}
                      onChange={(value) => toggle(row.id, value)}
                    />
                  </svelte:fragment>
                </Row>
              {/each}
            </Section>
          {/if}

          <!-- GENERAL and NETWORK are switch lists. -->
          {#if category === 'general' || category === 'network'}
            <Section material>
              {#each togglesFor(view, category) as row, index}
                <div in:fly={{ y: 6, duration: NORMAL, delay: index * STAGGER, easing: EASE }}>
                  <Row
                    title={row.label}
                    detail={row.detail}
                    badge={row.overriddenBy ?? ''}
                    dimmed={Boolean(row.overriddenBy)}
                  >
                    <svelte:fragment slot="control">
                      <Switch
                        checked={row.value}
                        disabled={busy || Boolean(row.overriddenBy)}
                        label={row.label}
                        onChange={(value) => toggle(row.id, value)}
                      />
                    </svelte:fragment>
                  </Row>
                </div>
              {/each}
            </Section>
          {/if}

          {#if category === 'general'}
            <Section label="About" material>
              {#each view.about as row}
                <Row title={row.label} compact>
                  <span slot="control" class="value tnum">{row.value}</span>
                </Row>
              {/each}
            </Section>
          {/if}
        </div>
      {/key}
    </div>
  </div>
{/if}

<style>
  /* Row's geometry, by hand: these are rows in the same glass as every other
     list, but their content is a sentence with links in it rather than a title,
     a detail and a control. */
  .credit-row {
    padding: 12px 14px;
    border-radius: var(--r-md);
    box-shadow: 0 -1px 0 var(--chrome-line);
    transition: background var(--m-fast) var(--ease);
  }

  .credit-row:first-child {
    box-shadow: none;
  }

  .credit-row:hover {
    background: var(--fill);
  }

  .credit-world {
    display: block;
    padding: 0;
    font-size: var(--t-row);
    font-weight: var(--w-semibold);
    letter-spacing: var(--track-snug);
    color: var(--text);
    text-align: left;
    text-decoration: underline;
    text-decoration-color: rgba(255, 255, 255, 0.26);
    text-underline-offset: 3px;
    transition:
      color var(--m-fast) var(--ease),
      text-decoration-color var(--m-fast) var(--ease);
  }

  .credit-by {
    margin: 2px 0 0;
    font-size: var(--t-body);
    color: var(--text-muted);
  }

  .credit-creator {
    display: inline;
    padding: 0;
    font: inherit;
    color: var(--text-muted);
    text-decoration: underline;
    text-decoration-color: rgba(255, 255, 255, 0.26);
    text-underline-offset: 3px;
    transition:
      color var(--m-fast) var(--ease),
      text-decoration-color var(--m-fast) var(--ease);
  }

  .credit-world:hover,
  .credit-creator:hover {
    color: var(--text);
    text-decoration-color: var(--accent);
  }

  .settings {
    display: grid;
    grid-template-columns: 200px 1fr;
    height: 100%;
  }

  .sidebar {
    display: flex;
    flex-direction: column;
    gap: var(--s-3);
    padding: var(--page-pad-y) var(--s-3);
    border-right: 1px solid var(--chrome-line);
    /* Darkened rather than glass: this pane sits against the chrome and reads
       as part of the frame, not as something floating on the footage. */
    background: rgba(0, 0, 0, 0.42);
    backdrop-filter: blur(var(--glass-blur));
    -webkit-backdrop-filter: blur(var(--glass-blur));
    overflow-y: auto;
  }

  .search {
    display: flex;
    align-items: center;
    gap: var(--s-2);
    height: var(--h-md);
    padding: 0 var(--s-3);
    border-radius: var(--r-sm);
    background: var(--fill-sunken);
    border: 1px solid var(--chrome-line-strong);
    color: var(--text-faint);
    flex-shrink: 0;
    transition: border-color var(--m-fast) var(--ease);
  }

  .search:focus-within {
    border-color: var(--accent);
  }

  .search input {
    width: 100%;
    min-width: 0;
    background: none;
    border: none;
    font-size: var(--t-body);
    color: var(--text);
  }

  .search input::placeholder {
    color: var(--text-disabled);
  }

  .categories,
  .results {
    display: flex;
    flex-direction: column;
    gap: var(--s-1);
  }

  .category {
    display: flex;
    align-items: center;
    gap: var(--s-3);
    padding: var(--s-2) var(--s-3);
    border-radius: var(--r-sm);
    font-size: var(--t-body-lg);
    font-weight: var(--w-medium);
    color: var(--text-muted);
    text-align: left;
    transition:
      background var(--m-fast) var(--ease),
      color var(--m-fast) var(--ease);
  }

  .category:hover {
    background: var(--fill-hover);
    color: var(--text);
  }

  .category.active {
    background: var(--fill-active);
    color: var(--text);
    font-weight: var(--w-semibold);
  }

  .result {
    display: flex;
    flex-direction: column;
    gap: 1px;
    padding: var(--s-2) var(--s-3);
    border-radius: var(--r-sm);
    text-align: left;
    transition: background var(--m-fast) var(--ease);
  }

  .result:hover {
    background: var(--fill-hover);
  }

  .result-label {
    font-size: var(--t-body);
    color: var(--text);
  }

  .result-cat {
    font-size: var(--t-meta);
    color: var(--text-faint);
  }

  .no-results {
    margin: 0;
    padding: var(--s-2) var(--s-3);
    font-size: var(--t-body);
    color: var(--text-faint);
  }

  .pane {
    overflow-y: auto;
    padding: var(--page-pad-y) var(--page-pad-x) var(--s-10);
    /* The same readability substrate `Page` uses, because this pane is its own
       scroller and does not go through that component. */
    background: linear-gradient(
      180deg,
      rgba(5, 6, 10, 0.66) 0%,
      rgba(5, 6, 10, 0.58) 45%,
      rgba(5, 6, 10, 0.4) 100%
    );
  }

  .pane-inner {
    max-width: 640px;
    display: flex;
    flex-direction: column;
    gap: var(--page-gap);
  }

  h1 {
    margin: 0;
    font-size: var(--t-page);
    font-weight: var(--w-bold);
    letter-spacing: var(--track-tight);
    line-height: 1.15;
    text-shadow: 0 1px 14px rgba(0, 0, 0, 0.9);
  }

  .java {
    color: var(--warn);
  }
  .java.found {
    color: var(--good);
  }

  .value {
    font-size: var(--t-body);
    color: var(--text-muted);
  }

  .aside {
    margin: 0;
    font-size: var(--t-body);
    color: var(--text-faint);
  }

  .notice {
    display: flex;
    align-items: center;
    gap: var(--s-2);
    padding: var(--s-2) var(--s-3);
    border-radius: var(--r-md);
    font-size: var(--t-body);
    background: rgba(232, 114, 114, 0.11);
    border: 1px solid rgba(232, 114, 114, 0.3);
    color: var(--bad);
  }
</style>

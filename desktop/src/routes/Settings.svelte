<!--
  Settings.

  A page like every other: the same frame, the same veil, the same title line.
  Inside it, the categories on the left in one small pane, and the chosen
  category's rows on the right in sections. Both are standard glass: light
  glass was tried for the categories and measured about 2.3:1 for their text
  where the pane crosses the bright sky, which is not readable.

  The category list used to be a darkened, blurred column pinned to the window
  edge -- a settings utility embedded in the launcher. Now it is a small pane
  that sits in the page's column and scrolls with it until it reaches the top,
  and the photograph carries on around it.

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
    SlidersHorizontal,
    Gamepad2,
    Palette,
    Wifi,
    HardDrive,
    Wrench,
  } from '@lucide/svelte';

  import Page from '../lib/ui/Page.svelte';
  import Section from '../lib/ui/Section.svelte';
  import Row from '../lib/ui/Row.svelte';
  import Switch from '../lib/ui/Switch.svelte';
  import Segmented from '../lib/ui/Segmented.svelte';
  import Button from '../lib/ui/Button.svelte';
  import Notice from '../lib/ui/Notice.svelte';
  import BackgroundPicker from '../lib/ui/BackgroundPicker.svelte';
  import SearchField from '../lib/ui/SearchField.svelte';
  import EmptyState from '../lib/ui/EmptyState.svelte';
  import { EASE, MOTION_HOVER, MOTION_PAGE } from '../lib/motion';
  import { WORLD_CREDITS } from '../lib/credits';
  import {
    BACKGROUNDS,
    DEFAULT_BACKGROUND,
    PICKER_AVAILABLE,
    type BackgroundOption,
  } from '../lib/backgrounds';
  import { changeAppearanceModes, restoreAppearanceModes } from '../lib/appearance';
  import {
    openPage,
    recoveryView,
    setChoice,
    setSetting,
    settingsView,
    DEFAULT_APPEARANCE_MODES,
    type AppearanceModes,
    type RecoveryView,
    type SettingsView,
  } from '../lib/api';

  export let category = 'general';
  export let choice = DEFAULT_BACKGROUND;
  export let backgrounds: BackgroundOption[] = [];
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
    void (async () => {
      modes = await restoreAppearanceModes();
    })();
  });

  /* The glass accessibility switches. Applied before they are saved, so the
     window answers the click even if the write is slow. */
  let modes: AppearanceModes = DEFAULT_APPEARANCE_MODES;

  async function changeModes(next: AppearanceModes) {
    modes = next;
    try {
      await changeAppearanceModes(next);
    } catch (cause) {
      error = String(cause);
    }
  }

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
  $: activeLabel = CATEGORIES.find((c) => c.id === category)?.label ?? 'Settings';
</script>

<Page title="Settings">
  {#if view}
    <div class="frame">
    <div class="settings">
      <!-- THE CATEGORIES. One pane of standard glass; everything else on the page
           is either the photograph or a section of rows. -->
      <nav class="nav material-standard" aria-label="Settings categories">
        <div class="search">
          <SearchField bind:value={query} placeholder="Search" label="Search settings" />
        </div>

        {#if query.trim()}
          <div class="list" transition:fade={{ duration: MOTION_HOVER }}>
            {#if matches.length}
              {#each matches as match}
                <button
                  class="item result"
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
              <p class="no-results">No setting matches “{query.trim()}”.</p>
            {/if}
          </div>
        {:else}
          <div class="list">
            {#each CATEGORIES as item}
              <button
                class="item"
                class:active={category === item.id}
                aria-current={category === item.id ? 'page' : undefined}
                on:click={() => (category = item.id)}
              >
                <svelte:component this={item.icon} size={16} />
                <span>{item.label}</span>
              </button>
            {/each}
          </div>
        {/if}
      </nav>

      <div class="pane">
        {#key category}
          <div class="pane-inner" in:fly={{ y: 8, duration: MOTION_PAGE, easing: EASE }}>
            <h2 class="on-footage">{activeLabel}</h2>

            {#if error}
              <div transition:fade={{ duration: MOTION_HOVER }}>
                <Notice tone="danger" onDismiss={() => (error = '')}>{error}</Notice>
              </div>
            {/if}

            <!-- APPEARANCE -->
            {#if category === 'appearance'}
              <!-- Accessibility for the glass itself. Windows' own "Transparency
                   effects" switch also reduces transparency, whatever these say. -->
              <Section label="Glass" material>
                <Row title="Reduce transparency" detail="Panels become nearly opaque. Windows' own transparency setting also applies.">
                  <svelte:fragment slot="control">
                    <Switch
                      checked={modes.reduceTransparency}
                      label="Reduce transparency"
                      onChange={(value) => void changeModes({ ...modes, reduceTransparency: value })}
                    />
                  </svelte:fragment>
                </Row>
                <Row title="Reduce blur" detail="Nothing is blurred behind panels; they get denser to stay readable.">
                  <svelte:fragment slot="control">
                    <Switch
                      checked={modes.reduceBlur}
                      label="Reduce blur"
                      onChange={(value) => void changeModes({ ...modes, reduceBlur: value })}
                    />
                  </svelte:fragment>
                </Row>
              </Section>

              <!-- Only when there is a choice to make: with one approved
                   background a picker would be a control that does nothing. The
                   same component the bottom bar uses, at a larger size. -->
              {#if PICKER_AVAILABLE}
                <Section label="Background">
                  <BackgroundPicker
                    options={backgrounds}
                    value={choice}
                    size="large"
                    onChoose={onBackground}
                  />
                </Section>
              {/if}

              <!-- The world and the creator are the links, rather than a pair
                   of buttons off to the right saying "World" and "Creator": the
                   name is the thing you would click. -->
              <Section label="Credits" material>
                <!-- The launcher's photograph. Plain text: the WebView can only
                     open the pages `pages.rs` allows, and this credit does not
                     need a new outbound host to be complete. -->
                {#each BACKGROUNDS as option}
                  <div class="credit">
                    <p class="credit-title">{option.label}</p>
                    <p class="credit-by">{option.credit}</p>
                  </div>
                {/each}
                {#each WORLD_CREDITS as credit}
                  <div class="credit">
                    <button class="credit-title link" on:click={() => void openPage(credit.worldPage)}>
                      {credit.world}
                    </button>
                    <p class="credit-by">
                      A world by <button
                        class="link"
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
                <Row
                  title={view.java.headline}
                  detail={view.java.detail}
                  badge={view.java.found ? '' : 'Needed to play'}
                  tone="warn"
                >
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
              <p class="aside on-footage">
                Memory, Java arguments and the loader are set per installation.
                <button class="link" on:click={() => onNavigate('installations')}>Installations</button>
              </p>
            {/if}

            <!-- STORAGE -->
            {#if category === 'storage'}
              <Section material>
                <Row title="Cache" detail={cacheAction?.detail ?? 'Downloaded metadata.'}>
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
              {@const rows = togglesFor(view, category)}
              {#if rows.length}
                <Section material>
                  {#each rows as row}
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
              {:else if category === 'network' || !view.about.length}
                <Section material>
                  <EmptyState
                    compact
                    icon={category === 'network' ? Wifi : SlidersHorizontal}
                    title="Nothing to set here yet"
                    detail="Preferences for this category appear here when there are some."
                  />
                </Section>
              {/if}
            {/if}

            {#if category === 'general' && view.about.length}
              <Section label="About" material>
                {#each view.about as row}
                  <Row title={row.label} compact>
                    <span slot="control" class="value mono">{row.value}</span>
                  </Row>
                {/each}
              </Section>
            {/if}
          </div>
        {/key}
      </div>
    </div>
    </div>
  {/if}
</Page>

<style>
  /* Categories beside the rows while there is room for both; above them, as a
     wrapping row, when there is not. */
  /* The frame is the container: an element cannot answer a container query
     about itself, so the query below used to never apply. */
  .frame {
    container-type: inline-size;
  }

  .settings {
    display: grid;
    grid-template-columns: minmax(168px, 200px) minmax(0, 1fr);
    gap: var(--space-6);
    align-items: start;
  }

  @container (max-width: 620px) {
    .settings {
      grid-template-columns: minmax(0, 1fr);
    }

    .nav {
      position: static;
    }

    .nav .list {
      flex-direction: row;
      flex-wrap: wrap;
    }
  }

  /* -- the categories ------------------------------------------------------ */
  .nav {
    --surface-radius: var(--radius-lg);
    position: sticky;
    top: 0;
    display: flex;
    flex-direction: column;
    gap: var(--space-2);
    padding: var(--space-2);
    border-radius: var(--radius-lg);
    /* This pane sits over the sun, the brightest part of the photograph, and
       at plain standard density its type measured about 4.2:1. A local
       deepening through the material's own veil layer, rather than a denser
       material or a darker colour for the whole page. */
    --mat-veil: var(--control-deepen);
  }

  /* Concentric with the pane around it. */
  .search {
    --search-radius: max(var(--radius-inner-min), calc(var(--radius-lg) - var(--space-2)));
  }

  .list {
    display: flex;
    flex-direction: column;
    gap: 2px;
  }

  /* Rest: secondary type on the pane. Hover: a thin fill. Chosen: a denser
     fill, a brighter rim and full-strength type -- no marker, no hue. */
  .item {
    display: flex;
    align-items: center;
    gap: var(--space-3);
    min-height: var(--control-height-lg);
    padding: var(--space-2) var(--space-3);
    border-radius: max(var(--radius-inner-min), calc(var(--radius-lg) - var(--space-2)));
    font-size: var(--type-body-size);
    font-weight: var(--type-button-weight);
    color: var(--color-text-secondary);
    text-align: left;
    transition:
      background-color var(--motion-hover) var(--ease-standard),
      color var(--motion-hover) var(--ease-standard);
  }

  .item:hover {
    background-color: var(--control-fill);
    color: var(--color-text);
  }

  .item:focus-visible {
    outline: var(--focus-ring-width) solid var(--color-focus);
    outline-offset: 2px;
  }

  .item.active {
    background-color: var(--control-fill-selected);
    box-shadow: var(--control-rim-strong);
    color: var(--color-text);
    font-weight: var(--type-heading-weight);
  }

  .result {
    flex-direction: column;
    align-items: flex-start;
    gap: 1px;
  }

  .result-label {
    color: var(--color-text);
    overflow-wrap: anywhere;
  }

  .result-cat {
    font-size: var(--type-caption-size);
    font-weight: var(--type-body-weight);
    color: var(--color-text-secondary);
  }

  .no-results {
    margin: 0;
    padding: var(--space-2) var(--space-3);
    font-size: var(--type-secondary-size);
    line-height: var(--type-secondary-leading);
    color: var(--color-text-secondary);
    overflow-wrap: anywhere;
  }

  /* -- the rows ------------------------------------------------------------ */
  .pane {
    min-width: 0;
  }

  .pane-inner {
    display: flex;
    flex-direction: column;
    gap: var(--layout-section-gap);
  }

  h2 {
    margin: 0;
    padding: 0 var(--space-1);
    font-size: var(--type-heading-size);
    font-weight: var(--type-heading-weight);
    letter-spacing: var(--type-heading-tracking);
    line-height: var(--type-heading-leading);
    color: var(--color-text);
    /* Level with the first category in the pane beside it. */
    min-height: var(--control-height-md);
    display: flex;
    align-items: center;
    margin-top: var(--space-2);
  }

  .value {
    font-size: var(--type-caption-size);
    color: var(--color-text-secondary);
  }

  .aside {
    margin: 0;
    padding: 0 var(--space-1);
    font-size: var(--type-secondary-size);
    line-height: var(--type-secondary-leading);
    color: var(--color-text-secondary);
  }

  /* A link inside running text: the text colour, underlined with a hairline
     that firms up on hover. */
  .link {
    display: inline;
    padding: 0;
    font: inherit;
    color: var(--color-text);
    text-decoration: underline;
    text-decoration-color: var(--control-hairline-strong);
    text-underline-offset: 3px;
    transition: text-decoration-color var(--motion-hover) var(--ease-standard);
  }

  .link:hover {
    text-decoration-color: currentColor;
  }

  .link:focus-visible {
    outline: var(--focus-ring-width) solid var(--color-focus);
    outline-offset: 2px;
    border-radius: var(--radius-xs);
  }

  /* -- credits: Row's geometry, because their content is a sentence with links
     in it rather than a title, a detail and a control. */
  .credit {
    padding: var(--space-3) var(--space-4);
    box-shadow: 0 calc(-1 * var(--hairline)) 0 var(--control-hairline);
  }

  .credit:first-child {
    box-shadow: none;
  }

  .credit-title {
    display: block;
    margin: 0;
    font-size: var(--type-body-size);
    font-weight: var(--type-heading-weight);
    letter-spacing: var(--type-heading-tracking);
    color: var(--color-text);
    text-align: left;
  }

  .credit-by {
    margin: 3px 0 0;
    font-size: var(--type-secondary-size);
    line-height: var(--type-secondary-leading);
    color: var(--color-text-secondary);
    overflow-wrap: anywhere;
  }
</style>

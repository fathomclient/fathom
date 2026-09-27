<!--
  Profiles.

  Fathom loadouts: which modules are on, their settings, the HUD and the keys.
  The same collection the in-game Profiles page shows — both read and write the
  same files (docs/fathom-profiles.md) — so a profile made here is in the game's
  picker the next time it opens its hub, and the reverse.

  Not installations. A Minecraft version, a loader or a Java runtime is never
  part of a profile; those are on Installations.

  "Use" sets the profile the next game opens. A game that is already running
  switches in game, where it is immediate, and this page says so rather than
  pretending otherwise.

  THE ONE IN USE

  It is said by placement, not by decoration: it has a pane of its own at the
  top, a larger mark and name, and a slightly denser fill. The rest are a plain
  list under it, each with a Use button. No coloured edge, no "ACTIVE" pill --
  the page's structure already answers "which one?".
-->
<script lang="ts">
  import { onMount } from 'svelte';
  import { fly, fade } from 'svelte/transition';
  import { Plus, Copy, Pencil, Trash2, Download, Upload } from '@lucide/svelte';

  import Page from '../lib/ui/Page.svelte';
  import Section from '../lib/ui/Section.svelte';
  import Button from '../lib/ui/Button.svelte';
  import Notice from '../lib/ui/Notice.svelte';
  import { tooltip } from '../lib/tooltip';
  import { EASE, MOTION_HOVER, MOTION_PAGE, MOTION_STAGGER } from '../lib/motion';
  import {
    createFathomProfile,
    deleteFathomProfile,
    duplicateFathomProfile,
    exportFathomProfile,
    fathomProfilesView,
    importFathomProfile,
    renameFathomProfile,
    switchFathomProfile,
    type FathomProfileSummary,
    type FathomProfilesView,
  } from '../lib/api';

  let view: FathomProfilesView = { profiles: [], live: '', problems: [] };
  let loaded = false;
  let busy = false;
  let error = '';
  let note = '';
  let renaming: string | null = null;
  let renameText = '';
  let confirmingDelete: string | null = null;
  let importing = false;
  let importText = '';

  onMount(() => {
    void run(fathomProfilesView).then(() => (loaded = true));
  });

  /** Every command returns the whole view, so the page never shows what the store refused. */
  async function run(action: () => Promise<FathomProfilesView>, done = '') {
    if (busy) return;
    busy = true;
    error = '';
    try {
      view = await action();
      note = done;
    } catch (failure) {
      error = String(failure);
    } finally {
      busy = false;
    }
  }

  function describe(profile: FathomProfileSummary): string {
    const parts: string[] = [];
    if (profile.modulesOn > 0) {
      parts.push(`${profile.modulesOn} ${profile.modulesOn === 1 ? 'module on' : 'modules on'}`);
    }
    if (profile.hudWidgets > 0) {
      parts.push(`${profile.hudWidgets} ${profile.hudWidgets === 1 ? 'widget placed' : 'widgets placed'}`);
    }
    return parts.length ? parts.join(' · ') : 'Module defaults';
  }

  function startRename(profile: FathomProfileSummary) {
    confirmingDelete = null;
    renaming = profile.id;
    renameText = profile.name;
  }

  async function commitRename() {
    const id = renaming;
    if (!id) return;
    const name = renameText.trim();
    if (!name) {
      error = 'A profile needs a name.';
      return;
    }
    renaming = null;
    await run(() => renameFathomProfile(id, name));
  }

  async function copyExport(profile: FathomProfileSummary) {
    try {
      const json = await exportFathomProfile(profile.id);
      await navigator.clipboard.writeText(json);
      note = `${profile.name} copied.`;
      error = '';
    } catch (failure) {
      error = String(failure);
    }
  }

  async function commitImport() {
    const json = importText.trim();
    if (!json) return;
    await run(() => importFathomProfile(json), 'Imported.');
    if (!error) {
      importing = false;
      importText = '';
    }
  }

  function initial(name: string): string {
    return name.trim()[0]?.toUpperCase() ?? '?';
  }

  $: current = view.profiles.find((profile) => profile.live) ?? null;
  $: others = view.profiles.filter((profile) => !profile.live);
  $: lastOne = view.profiles.length <= 1;
</script>

<Page
  title="Profiles"
  summary="{view.profiles.length} {view.profiles.length === 1 ? 'profile' : 'profiles'}"
>
  <svelte:fragment slot="actions">
    <Button
      disabled={busy}
      onClick={() => (importing = !importing)}
    >
      <Upload size={14} />
      Import
    </Button>
    <Button variant="primary" disabled={busy} onClick={() => void run(() => createFathomProfile('New profile'))}>
      <Plus size={14} />
      New profile
    </Button>
  </svelte:fragment>

  {#if error}
    <div transition:fade={{ duration: MOTION_HOVER }}>
      <Notice tone="danger" onDismiss={() => (error = '')}>{error}</Notice>
    </div>
  {:else if note}
    <div transition:fade={{ duration: MOTION_HOVER }}>
      <Notice onDismiss={() => (note = '')}>{note}</Notice>
    </div>
  {/if}

  {#each view.problems as problem}
    <Notice tone="warning">{problem}</Notice>
  {/each}

  {#if importing}
    <div transition:fly={{ y: -6, duration: MOTION_PAGE, easing: EASE }}>
      <Section label="Import a profile" material>
        <div class="import">
          <textarea
            class="ui-field"
            bind:value={importText}
            rows="5"
            placeholder="Paste a profile copied from Fathom"
            spellcheck="false"
            aria-label="Profile text"
          ></textarea>
          <div class="import-actions">
            <Button onClick={() => (importing = false)}>Cancel</Button>
            <Button variant="primary" disabled={busy || !importText.trim()} onClick={() => void commitImport()}>
              Import
            </Button>
          </div>
        </div>
      </Section>
    </div>
  {/if}

  {#if current}
    <Section label="In use" summary="Opens with the next game" material>
      {@const profile = current}
      <div class="ui-item current lead-item">
        <span class="ui-mark big" aria-hidden="true">{initial(profile.name)}</span>
        <div class="text">
          {#if renaming === profile.id}
            <!-- svelte-ignore a11y-autofocus -->
            <input
              class="ui-field rename"
              bind:value={renameText}
              maxlength="64"
              autofocus
              aria-label="Profile name"
              on:keydown={(event) => {
                if (event.key === 'Enter') void commitRename();
                if (event.key === 'Escape') renaming = null;
              }}
              on:blur={() => void commitRename()}
            />
          {:else}
            <span class="ui-item-title big">{profile.name}</span>
          {/if}
          <span class="ui-item-meta">{describe(profile)}</span>
          <span class="hint">A game that is already running switches in game.</span>
        </div>
        {#if confirmingDelete === profile.id}
          <div class="ui-item-controls confirm" transition:fade={{ duration: MOTION_HOVER }}>
            <span>Delete {profile.name}?</span>
            <Button onClick={() => (confirmingDelete = null)}>Keep</Button>
            <Button
              variant="caution"
              onClick={() => {
                confirmingDelete = null;
                void run(() => deleteFathomProfile(profile.id), `${profile.name} deleted.`);
              }}
            >
              Delete
            </Button>
          </div>
        {:else}
          <div class="ui-item-controls">
            <button class="ui-icon-button small" aria-label="Rename {profile.name}" use:tooltip={'Rename'} on:click={() => startRename(profile)}>
              <Pencil size={14} />
            </button>
            <button class="ui-icon-button small" aria-label="Duplicate {profile.name}" use:tooltip={'Duplicate'} disabled={busy} on:click={() => void run(() => duplicateFathomProfile(profile.id))}>
              <Copy size={14} />
            </button>
            <button class="ui-icon-button small" aria-label="Copy {profile.name} as text" use:tooltip={'Copy as text'} on:click={() => void copyExport(profile)}>
              <Download size={14} />
            </button>
            <button
              class="ui-icon-button small danger"
              aria-label="Delete {profile.name}"
              use:tooltip={lastOne ? 'The last profile stays' : 'Delete'}
              disabled={lastOne}
              on:click={() => (confirmingDelete = profile.id)}
            >
              <Trash2 size={14} />
            </button>
          </div>
        {/if}
      </div>
    </Section>
  {/if}

  {#if others.length}
    <Section label={current ? 'Other profiles' : 'Profiles'} material>
      {#each others as profile, index (profile.id)}
        <div class="ui-item" in:fly={{ y: 8, duration: MOTION_PAGE, delay: index * MOTION_STAGGER, easing: EASE }}>
          <span class="ui-mark quiet" aria-hidden="true">{initial(profile.name)}</span>
          <div class="text">
            {#if renaming === profile.id}
              <!-- svelte-ignore a11y-autofocus -->
              <input
                class="ui-field rename"
                bind:value={renameText}
                maxlength="64"
                autofocus
                aria-label="Profile name"
                on:keydown={(event) => {
                  if (event.key === 'Enter') void commitRename();
                  if (event.key === 'Escape') renaming = null;
                }}
                on:blur={() => void commitRename()}
              />
            {:else}
              <span class="ui-item-title">{profile.name}</span>
            {/if}
            <span class="ui-item-meta">{describe(profile)}</span>
          </div>

          {#if confirmingDelete === profile.id}
            <div class="ui-item-controls confirm" transition:fade={{ duration: MOTION_HOVER }}>
              <span>Delete {profile.name}?</span>
              <Button onClick={() => (confirmingDelete = null)}>Keep</Button>
              <Button
                variant="caution"
                onClick={() => {
                  confirmingDelete = null;
                  void run(() => deleteFathomProfile(profile.id), `${profile.name} deleted.`);
                }}
              >
                Delete
              </Button>
            </div>
          {:else}
            <div class="ui-item-controls">
              <button class="ui-icon-button small" aria-label="Rename {profile.name}" use:tooltip={'Rename'} on:click={() => startRename(profile)}>
                <Pencil size={14} />
              </button>
              <button class="ui-icon-button small" aria-label="Duplicate {profile.name}" use:tooltip={'Duplicate'} disabled={busy} on:click={() => void run(() => duplicateFathomProfile(profile.id))}>
                <Copy size={14} />
              </button>
              <button class="ui-icon-button small" aria-label="Copy {profile.name} as text" use:tooltip={'Copy as text'} on:click={() => void copyExport(profile)}>
                <Download size={14} />
              </button>
              <button
                class="ui-icon-button small danger"
                aria-label="Delete {profile.name}"
                use:tooltip={lastOne ? 'The last profile stays' : 'Delete'}
                disabled={lastOne}
                on:click={() => (confirmingDelete = profile.id)}
              >
                <Trash2 size={14} />
              </button>
              <span class="use">
                <Button
                  disabled={busy}
                  onClick={() => void run(() => switchFathomProfile(profile.id), `${profile.name} will open next.`)}
                >
                  Use
                </Button>
              </span>
            </div>
          {/if}
        </div>
      {/each}
    </Section>
  {:else if current}
    <p class="empty on-footage">
      One profile so far. Duplicate it to try a different set of modules without losing this one.
    </p>
  {/if}

  {#if loaded && !view.profiles.length}
    <div class="blank">
      <p class="blank-title on-footage">No profiles yet</p>
      <p class="empty on-footage">
        A profile is a set of modules, their settings, the HUD and your keys. The game makes one the
        first time it opens, or you can start one here.
      </p>
    </div>
  {/if}
</Page>

<style>
  .import {
    display: flex;
    flex-direction: column;
    gap: var(--space-3);
    padding: var(--space-3);
  }

  .import textarea {
    font-size: var(--type-secondary-size);
  }

  .import-actions {
    display: flex;
    justify-content: flex-end;
    gap: var(--space-2);
  }

  .text {
    min-width: 0;
  }

  /* The one in use: a larger mark and name. Its pane crosses the brightest
     part of the photograph, and its meta line is set at full strength both for
     emphasis and to keep it clear of 4.5:1 there. */
  .lead-item {
    padding-block: var(--space-4);
  }

  .lead-item .ui-item-meta {
    color: var(--color-text);
  }

  .ui-mark.big {
    width: 52px;
    height: 52px;
    border-radius: var(--radius-lg);
    font-size: var(--type-title-size);
    box-shadow: var(--control-rim-strong);
  }

  .ui-item-title.big {
    font-size: var(--type-heading-size);
  }

  /* The others recede slightly until they are chosen. */
  .ui-mark.quiet {
    background-color: var(--control-fill);
    color: var(--color-text-secondary);
  }

  .hint {
    display: block;
    margin-top: var(--space-1);
    font-size: var(--type-caption-size);
    line-height: var(--type-caption-leading);
    color: var(--color-text-secondary);
  }

  .use {
    display: contents;
  }

  .use :global(button) {
    margin-left: var(--space-2);
  }

  .rename {
    min-height: var(--control-height-md);
    max-width: 360px;
  }

  .confirm {
    gap: var(--space-2);
  }

  .confirm span {
    font-size: var(--type-secondary-size);
    color: var(--color-text-secondary);
    margin-right: var(--space-1);
  }

  .empty {
    margin: 0;
    padding: 0 var(--space-1);
    max-width: 60ch;
    font-size: var(--type-secondary-size);
    line-height: var(--type-secondary-leading);
    color: var(--color-text-secondary);
  }

  .blank {
    display: flex;
    flex-direction: column;
    gap: var(--space-2);
    padding: var(--space-8) var(--space-1);
  }

  .blank-title {
    margin: 0;
    font-size: var(--type-heading-size);
    font-weight: var(--type-heading-weight);
    color: var(--color-text);
  }
</style>

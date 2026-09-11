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
-->
<script lang="ts">
  import { onMount } from 'svelte';
  import { fly, fade } from 'svelte/transition';
  import { Plus, Copy, Pencil, Trash2, Check, Download, Upload } from '@lucide/svelte';

  import Page from '../lib/ui/Page.svelte';
  import Button from '../lib/ui/Button.svelte';
  import { EASE, FAST, NORMAL, STAGGER } from '../lib/motion';
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
  let busy = false;
  let error = '';
  let note = '';
  let renaming: string | null = null;
  let renameText = '';
  let confirmingDelete: string | null = null;
  let importing = false;
  let importText = '';

  onMount(() => {
    void run(fathomProfilesView);
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
</script>

<Page
  title="Profiles"
  summary="{view.profiles.length} {view.profiles.length === 1 ? 'profile' : 'profiles'}"
  wide
>
  <svelte:fragment slot="actions">
    <Button variant="normal" disabled={busy} onClick={() => (importing = !importing)}>
      <Upload size={14} strokeWidth={2.2} />
      Import
    </Button>
    <Button variant="primary" disabled={busy} onClick={() => void run(() => createFathomProfile('New profile'))}>
      <Plus size={14} strokeWidth={2.4} />
      New profile
    </Button>
  </svelte:fragment>

  <p class="lede">Used by the next game. A running game switches in game.</p>

  {#if error}
    <p class="error" transition:fade={{ duration: FAST }}>{error}</p>
  {:else if note}
    <p class="note" transition:fade={{ duration: FAST }}>{note}</p>
  {/if}

  {#each view.problems as problem}
    <p class="problem">{problem}</p>
  {/each}

  {#if importing}
    <div class="import glass" transition:fly={{ y: -6, duration: NORMAL, easing: EASE }}>
      <textarea bind:value={importText} rows="5" placeholder="Paste a profile" spellcheck="false"></textarea>
      <div class="import-actions">
        <Button variant="normal" onClick={() => (importing = false)}>Cancel</Button>
        <Button variant="primary" disabled={busy || !importText.trim()} onClick={() => void commitImport()}>
          Import
        </Button>
      </div>
    </div>
  {/if}

  <ul class="list">
    {#each view.profiles as profile, index (profile.id)}
      <li
        class="row"
        class:live={profile.live}
        in:fly={{ y: 10, duration: NORMAL, delay: index * STAGGER, easing: EASE }}
      >
        <span class="tick">
          {#if profile.live}<Check size={14} strokeWidth={2.8} />{/if}
        </span>

        <span class="text">
          {#if renaming === profile.id}
            <!-- svelte-ignore a11y-autofocus -->
            <input
              class="rename"
              bind:value={renameText}
              maxlength="64"
              autofocus
              on:keydown={(event) => {
                if (event.key === 'Enter') void commitRename();
                if (event.key === 'Escape') renaming = null;
              }}
              on:blur={() => void commitRename()}
            />
          {:else}
            <span class="name">{profile.name}</span>
          {/if}
          <span class="facts">{describe(profile)}</span>
        </span>

        {#if profile.live}
          <span class="badge">Next game</span>
        {:else}
          <button
            class="use"
            disabled={busy}
            on:click={() => void run(() => switchFathomProfile(profile.id), `${profile.name} will open next.`)}
          >
            Use
          </button>
        {/if}

        <span class="tools">
          <button title="Rename" on:click={() => startRename(profile)}>
            <Pencil size={13} strokeWidth={2} />
          </button>
          <button title="Duplicate" on:click={() => void run(() => duplicateFathomProfile(profile.id))}>
            <Copy size={13} strokeWidth={2} />
          </button>
          <button title="Copy as text" on:click={() => void copyExport(profile)}>
            <Download size={13} strokeWidth={2} />
          </button>
          <button
            class="danger"
            title={view.profiles.length > 1 ? 'Delete' : 'The last profile stays'}
            disabled={view.profiles.length <= 1}
            on:click={() => (confirmingDelete = profile.id)}
          >
            <Trash2 size={13} strokeWidth={2} />
          </button>
        </span>

        {#if confirmingDelete === profile.id}
          <span class="confirm" transition:fade={{ duration: FAST }}>
            <span>Delete {profile.name}?</span>
            <Button variant="normal" onClick={() => (confirmingDelete = null)}>Keep</Button>
            <Button
              variant="caution"
              onClick={() => {
                confirmingDelete = null;
                void run(() => deleteFathomProfile(profile.id), `${profile.name} deleted.`);
              }}
            >
              Delete
            </Button>
          </span>
        {/if}
      </li>
    {/each}
  </ul>
</Page>

<style>
  .lede {
    margin: 0 0 var(--s-4);
    font-size: var(--t-caption);
    color: var(--text-faint);
  }

  .error,
  .note,
  .problem {
    margin: 0 0 var(--s-3);
    font-size: var(--t-caption);
  }

  .error {
    color: var(--bad);
  }

  .note {
    color: var(--good);
  }

  .problem {
    color: var(--warn);
  }

  .import {
    display: flex;
    flex-direction: column;
    gap: var(--s-2);
    padding: var(--s-3);
    margin-bottom: var(--s-4);
    border-radius: var(--r-md);
  }

  .import textarea {
    width: 100%;
    resize: vertical;
    padding: var(--s-2);
    border-radius: var(--r-sm);
    border: 1px solid var(--edge);
    background: var(--fill-sunken);
    color: var(--text);
    font: inherit;
    font-size: var(--t-caption);
  }

  .import-actions {
    display: flex;
    justify-content: flex-end;
    gap: var(--s-2);
  }

  .list {
    list-style: none;
    margin: 0;
    padding: 0;
    display: flex;
    flex-direction: column;
    gap: var(--s-2);
    max-width: 720px;
  }

  .row {
    position: relative;
    display: flex;
    align-items: center;
    gap: var(--s-3);
    min-height: 56px;
    padding: 0 var(--s-3);
    border-radius: var(--r-md);
    border: 1px solid var(--edge);
    background: var(--fill);
    transition:
      background var(--m-fast) var(--ease),
      border-color var(--m-fast) var(--ease);
  }

  .row:hover {
    background: var(--fill-hover);
  }

  .row.live {
    border-color: color-mix(in srgb, var(--accent) 45%, transparent);
  }

  .tick {
    display: grid;
    place-items: center;
    width: 18px;
    color: var(--accent-strong);
  }

  .text {
    display: flex;
    flex-direction: column;
    gap: 2px;
    min-width: 0;
    flex: 1;
  }

  .name {
    font-size: var(--t-body);
    font-weight: var(--w-medium);
    color: var(--text);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .facts {
    font-size: var(--t-caption);
    color: var(--text-faint);
  }

  .rename {
    height: var(--h-sm);
    padding: 0 var(--s-2);
    border-radius: var(--r-sm);
    border: 1px solid var(--accent);
    background: var(--fill-sunken);
    color: var(--text);
    font: inherit;
    font-size: var(--t-body);
  }

  .badge {
    padding: 2px var(--s-2);
    border-radius: var(--r-pill);
    font-size: var(--t-meta);
    font-weight: var(--w-medium);
    color: var(--good);
    background: color-mix(in srgb, var(--good) 14%, transparent);
  }

  .use {
    height: var(--h-sm);
    padding: 0 var(--s-3);
    border-radius: var(--r-sm);
    font-size: var(--t-caption);
    font-weight: var(--w-medium);
    color: var(--text-muted);
    border: 1px solid var(--edge);
    transition:
      color var(--m-fast) var(--ease),
      background var(--m-fast) var(--ease);
  }

  .use:hover:not(:disabled) {
    color: var(--text);
    background: var(--fill-hover);
  }

  .tools {
    display: flex;
    gap: 2px;
    opacity: 0;
    transition: opacity var(--m-fast) var(--ease);
  }

  .row:hover .tools,
  .row:focus-within .tools {
    opacity: 1;
  }

  .tools button {
    display: grid;
    place-items: center;
    width: 28px;
    height: 28px;
    border-radius: var(--r-sm);
    color: var(--text-faint);
  }

  .tools button:hover:not(:disabled) {
    color: var(--text);
    background: var(--fill-hover);
  }

  .tools button.danger:hover:not(:disabled) {
    color: var(--bad);
  }

  .tools button:disabled {
    opacity: 0.35;
  }

  .confirm {
    position: absolute;
    inset: 0;
    display: flex;
    align-items: center;
    justify-content: flex-end;
    gap: var(--s-2);
    padding: 0 var(--s-3);
    border-radius: var(--r-md);
    background: var(--overlay);
    font-size: var(--t-caption);
    color: var(--text-muted);
  }

  .confirm span {
    margin-right: auto;
  }
</style>

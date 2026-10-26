<!--
  Installations.

  How Minecraft starts: version, loader, Java, memory and mods. These used to be
  called Profiles, which is the word the game uses for loadouts; they are a
  different thing and now a different page (docs/fathom-profiles.md).

  A list, not a card grid: most people have one to four of these, and a grid of
  four tiles is mostly empty space with rectangles in it. Each row leads with
  imagery: the user's own artwork where they have supplied it
  (`docs/profile-art.md`), and otherwise a field of colour derived from the
  name. Artwork is shown as it is -- rounded, unframed, nothing printed on it.

  The colour field is decoration, not data. It never claims to be a screenshot;
  it is there so that four installations are four distinguishable objects.
  Deriving it from the name means the same installation is the same colour
  every time, and taking it from the palette keeps it inside the photograph's
  light.

  What a row does NOT show: the profile id, the game directory, or any path.
  Those are filenames.

  The installation in use sits in its own pane at the top, larger. Editing
  opens the installation on its own page (InstallationEditor), which is also
  where OptiFine is added and where it says whether it is on.
-->
<script lang="ts">
  import { ICON } from '../lib/metrics.generated';
  import { onMount } from 'svelte';
  import { fly, fade } from 'svelte/transition';
  import { Plus, Copy, Pencil, Trash2, GitBranch, Package } from '@lucide/svelte';

  import Page from '../lib/ui/Page.svelte';
  import Section from '../lib/ui/Section.svelte';
  import Button from '../lib/ui/Button.svelte';
  import Notice from '../lib/ui/Notice.svelte';
  import ProfileSetup from '../lib/ui/ProfileSetup.svelte';
  import InstallationEditor from './InstallationEditor.svelte';
  import EmptyState from '../lib/ui/EmptyState.svelte';
  import { tooltip } from '../lib/tooltip';
  import { EASE, MOTION_HOVER, MOTION_PAGE, MOTION_STAGGER } from '../lib/motion';
  import {
    deleteProfile,
    deriveProfile,
    duplicateProfile,
    profileArt,
    profilesView,
    selectProfile,
    type ProfileRow,
  } from '../lib/api';

  export let onChanged: () => void = () => {};

  let rows: ProfileRow[] = [];
  let loaded = false;
  let art: Record<string, string | null> = {};
  let busy = false;
  /** The installation open on its own page, if any. */
  let editing: string | null = null;
  let settingUp = false;
  let confirmingDelete: string | null = null;
  let error = '';

  async function refresh() {
    rows = await profilesView();
    loaded = true;
    // Artwork is optional and per-profile, so it is fetched alongside rather
    // than folded into the row: a profile with no art must not delay the ones
    // that have it, and a failure here must not take the page down.
    await Promise.all(
      rows.map(async (row) => {
        if (row.id in art) return;
        art[row.id] = await profileArt(row.id).catch(() => null);
      }),
    );
    art = art;
  }

  onMount(() => {
    void refresh();
  });

  async function run(action: () => Promise<unknown>) {
    if (busy) return;
    busy = true;
    error = '';
    try {
      await action();
      await refresh();
      onChanged();
    } catch (cause) {
      error = String(cause);
    } finally {
      busy = false;
    }
  }

  function openEditor(id: string) {
    editing = id;
  }

  /* Pairs from the palette, so a generated field is always a colour the
     photograph could contain. */
  const FIELDS = [
    ['--palette-bronze-500', '--palette-umber-800'],
    ['--palette-olive-500', '--palette-umber-900'],
    ['--palette-terracotta-500', '--palette-umber-800'],
    ['--palette-sage-500', '--palette-olive-700'],
    ['--palette-amber-500', '--palette-bronze-600'],
    ['--palette-sand-500', '--palette-umber-700'],
  ];

  /**
   * A stable colour field for a profile with no artwork.
   *
   * A cheap string hash, not a cryptographic one: the requirement is only that
   * the same name always yields the same field and that similar names do not
   * collide too obviously.
   */
  function field(name: string): string {
    let h = 0;
    for (let i = 0; i < name.length; i += 1) {
      h = (h * 31 + name.charCodeAt(i)) % 3600;
    }
    const [from, to] = FIELDS[h % FIELDS.length];
    const angle = 120 + (Math.floor(h / FIELDS.length) % 90);
    return `background-image:linear-gradient(${angle}deg, var(${from}), var(${to}))`;
  }

  function cover(row: ProfileRow): string {
    return art[row.id] ? `background-image:url(${art[row.id]})` : field(row.name);
  }

  /** "Last played Never" reads as a malfunction; say it the way a person would. */
  function played(when: string): string {
    return when === 'Never' ? 'Not played yet' : `Last played ${when}`;
  }

  $: current = rows.find((row) => row.selected) ?? null;
  $: others = rows.filter((row) => !row.selected);
</script>

{#if editing}
  <InstallationEditor
    id={editing}
    onBack={() => (editing = null)}
    onChanged={() => void refresh().then(onChanged)}
  />
{:else}
<Page title="Installations" summary="{rows.length} {rows.length === 1 ? 'installation' : 'installations'}">
  <svelte:fragment slot="actions">
    <Button variant="primary" disabled={busy} onClick={() => (settingUp = true)}>
      <Plus size={ICON.inline} />
      New installation
    </Button>
  </svelte:fragment>

  {#if error}
    <div transition:fade={{ duration: MOTION_HOVER }}>
      <Notice tone="danger" onDismiss={() => (error = '')}>{error}</Notice>
    </div>
  {/if}

  {#if current}
    {@const row = current}
    <!-- The one Play launches. -->
    <Section label="In use" summary="Play launches this" material>
      <div class="ui-item current lead-item">
        <span class="cover big" class:art={Boolean(art[row.id])} style={cover(row)} aria-hidden="true">
          {#if !art[row.id]}<Package size={ICON.tile} />{/if}
        </span>
        <div class="text">
          <span class="ui-item-title big">{row.name}</span>
          <span class="ui-item-meta">
            {row.kindLabel} · <span class:attention={!row.loaderAvailable}>{row.loader}</span> ·
            {row.modCount} {row.modCount === 1 ? 'mod' : 'mods'}
          </span>
          <span class="facts tnum">
            Minecraft {row.version} · {row.preset} · {played(row.lastPlayed)}
          </span>
          {#if row.parent}
            <span class="facts derived"><GitBranch size={ICON.caption} /> Based on {row.parent}</span>
          {/if}
        </div>
        {#if confirmingDelete === row.id}
          <div class="ui-item-controls confirm" transition:fade={{ duration: MOTION_HOVER }}>
            <span>Delete this installation?</span>
            <Button onClick={() => (confirmingDelete = null)}>Keep</Button>
            <Button
              variant="caution"
              onClick={() => {
                confirmingDelete = null;
                void run(() => deleteProfile(row.id));
              }}
            >
              Delete
            </Button>
          </div>
        {:else}
          <div class="ui-item-controls">
            <button class="ui-icon-button small" aria-label="Edit {row.name}" use:tooltip={'Edit'} on:click={() => openEditor(row.id)}>
              <Pencil size={ICON.inline} />
            </button>
            <!-- A variant, not a copy. It shares the parent's client and
                 overrides only its own look and extra mods, so it stays
                 current when the parent's loader moves. -->
            <button
              class="ui-icon-button small"
              aria-label="Make a variant of {row.name}"
              use:tooltip={'Make a variant'}
              disabled={busy}
              on:click={() => run(() => deriveProfile(row.id, `${row.name} variant`))}
            >
              <GitBranch size={ICON.inline} />
            </button>
            <button class="ui-icon-button small" aria-label="Duplicate {row.name}" use:tooltip={'Duplicate'} disabled={busy} on:click={() => run(() => duplicateProfile(row.id))}>
              <Copy size={ICON.inline} />
            </button>
            <button class="ui-icon-button small danger" aria-label="Delete {row.name}" use:tooltip={'Delete'} on:click={() => (confirmingDelete = row.id)}>
              <Trash2 size={ICON.inline} />
            </button>
          </div>
        {/if}
      </div>
    </Section>
  {/if}

  {#if others.length}
    <Section label={current ? 'Other installations' : 'Installations'} material>
      {#each others as row, index (row.id)}
        <div class="ui-item" in:fly={{ y: 8, duration: MOTION_PAGE, delay: index * MOTION_STAGGER, easing: EASE }}>
          <span class="cover" class:art={Boolean(art[row.id])} style={cover(row)} aria-hidden="true">
            {#if !art[row.id]}<Package size={ICON.tile} />{/if}
          </span>
          <div class="text">
            <span class="ui-item-title">{row.name}</span>
            <span class="ui-item-meta">
              {row.kindLabel} · <span class:attention={!row.loaderAvailable}>{row.loader}</span> ·
              {row.modCount} {row.modCount === 1 ? 'mod' : 'mods'}
            </span>
            <span class="facts tnum">Minecraft {row.version} · {played(row.lastPlayed)}</span>
            {#if row.parent}
              <span class="facts derived"><GitBranch size={ICON.caption} /> Based on {row.parent}</span>
            {/if}
          </div>

          {#if confirmingDelete === row.id}
            <div class="ui-item-controls confirm" transition:fade={{ duration: MOTION_HOVER }}>
              <span>Delete this installation?</span>
              <Button onClick={() => (confirmingDelete = null)}>Keep</Button>
              <Button
                variant="caution"
                onClick={() => {
                  confirmingDelete = null;
                  void run(() => deleteProfile(row.id));
                }}
              >
                Delete
              </Button>
            </div>
          {:else}
            <div class="ui-item-controls">
              <button class="ui-icon-button small" aria-label="Edit {row.name}" use:tooltip={'Edit'} on:click={() => openEditor(row.id)}>
                <Pencil size={ICON.inline} />
              </button>
              <button
                class="ui-icon-button small"
                aria-label="Make a variant of {row.name}"
                use:tooltip={'Make a variant'}
                disabled={busy}
                on:click={() => run(() => deriveProfile(row.id, `${row.name} variant`))}
              >
                <GitBranch size={ICON.inline} />
              </button>
              <button class="ui-icon-button small" aria-label="Duplicate {row.name}" use:tooltip={'Duplicate'} disabled={busy} on:click={() => run(() => duplicateProfile(row.id))}>
                <Copy size={ICON.inline} />
              </button>
              <button class="ui-icon-button small danger" aria-label="Delete {row.name}" use:tooltip={'Delete'} on:click={() => (confirmingDelete = row.id)}>
                <Trash2 size={ICON.inline} />
              </button>
              <span class="use">
                <Button disabled={busy} onClick={() => run(() => selectProfile(row.id))}>Use</Button>
              </span>
            </div>
          {/if}
        </div>
      {/each}
    </Section>
  {/if}

  {#if loaded && !rows.length}
    <Section material>
      <EmptyState
        icon={Package}
        title="No installations"
        detail="An installation is how Minecraft starts: its version, loader, Java and memory. Make one to play."
      >
        <Button variant="primary" onClick={() => (settingUp = true)}>New installation</Button>
      </EmptyState>
    </Section>
  {/if}
</Page>
{/if}

{#if settingUp}
  <ProfileSetup
    onClose={() => (settingUp = false)}
    onCreated={(id, openIt) => {
      void refresh().then(onChanged);
      if (openIt) editing = id;
    }}
  />
{/if}

<style>
  .text {
    display: flex;
    flex-direction: column;
    min-width: 0;
  }

  /* -- the artwork ------------------------------------------------------------
     16:9, rounded, unframed. A generated field gets the control rim so it reads
     as a surface; real artwork gets nothing on top of it. */
  /* A generated field carries the installation glyph, so it reads as a cover
     rather than as an image that failed to load. */
  .cover {
    display: grid;
    place-items: center;
    width: 88px;
    color: var(--color-text-secondary);
    aspect-ratio: 16 / 9;
    border-radius: var(--radius-md);
    background-size: cover;
    background-position: center;
    box-shadow: var(--control-rim);
  }

  .cover.art {
    box-shadow: none;
  }

  .cover.big {
    width: 144px;
    border-radius: var(--radius-lg);
  }

  /* The one in use: its pane crosses the brightest part of the photograph, and
     its meta line is set at full strength both for emphasis and to keep it
     clear of 4.5:1 there. */
  .lead-item {
    padding-block: var(--space-4);
  }

  .lead-item .ui-item-meta {
    color: var(--color-text);
  }

  .ui-item-title.big {
    font-size: var(--type-heading-size);
  }

  .facts {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: var(--space-1);
    margin-top: 2px;
    font-size: var(--type-caption-size);
    line-height: var(--type-caption-leading);
    color: var(--color-text-secondary);
  }

  .attention {
    color: var(--color-warning-text);
  }

  .use {
    display: contents;
  }

  .use :global(button) {
    margin-left: var(--space-2);
  }

  .confirm {
    gap: var(--space-2);
  }

  .confirm span {
    font-size: var(--type-secondary-size);
    color: var(--color-text-secondary);
    margin-right: var(--space-1);
  }
</style>

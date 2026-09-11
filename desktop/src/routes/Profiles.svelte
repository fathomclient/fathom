<!--
  Installations.

  How Minecraft starts: version, loader, Java, memory and mods. These used to be
  called Profiles, which is the word the game uses for loadouts; they are a
  different thing and now a different page (docs/fathom-profiles.md).

  A visual selector, not a card grid. Each tile leads with imagery: the user's
  own artwork where they have supplied it (`docs/profile-art.md`), and otherwise
  a deterministic gradient derived from the profile's name.

  The gradient is decoration, not data. It never claims to be a screenshot — it
  is a coloured field so that six profiles are six distinguishable objects
  rather than six identical grey rectangles. Deriving it from the name means the
  same profile is the same colour every time, which is what makes it useful for
  recognition at all.

  What the tile does NOT show: the profile id, the game directory, or any path.
  Those are filenames. Putting them on the card is most of why the previous
  version read as a form over a database.

  Editing opens a drawer. Dumping the whole form into the page was the other
  half of that problem.
-->
<script lang="ts">
  import { onMount } from 'svelte';
  import { fly, fade } from 'svelte/transition';
  import { Plus, Copy, Pencil, Trash2, Check, GitBranch } from '@lucide/svelte';

  import Page from '../lib/ui/Page.svelte';
  import Button from '../lib/ui/Button.svelte';
  import ProfileSetup from '../lib/ui/ProfileSetup.svelte';
  import { EASE, FAST, NORMAL, STAGGER } from '../lib/motion';
  import {
    deleteProfile,
    deriveProfile,
    duplicateProfile,
    profileArt,
    profileDetail,
    profilesView,
    saveProfile,
    selectProfile,
    type ProfileDetail,
    type ProfileRow,
  } from '../lib/api';

  export let onChanged: () => void = () => {};
  /** Leave for the full Add OptiFine screen. Set by the shell. */
  export let onAddOptiFine: () => void = () => {};

  let rows: ProfileRow[] = [];
  let art: Record<string, string | null> = {};
  let busy = false;
  let editing: ProfileDetail | null = null;
  let settingUp = false;
  let confirmingDelete: string | null = null;
  let error = '';

  async function refresh() {
    rows = await profilesView();
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

  async function openEditor(id: string) {
    editing = await profileDetail(id);
  }

  async function save() {
    if (!editing) return;
    const detail = editing;
    await run(async () => {
      await saveProfile({
        id: detail.id,
        name: detail.name,
        memoryMb: detail.memoryMb,
        extraArgs: detail.extraArgs,
        keepLauncherOpen: detail.keepLauncherOpen,
        loader: detail.loader,
        java: detail.java,
      });
      editing = null;
    });
  }

  /**
   * A stable hue for a profile with no artwork.
   *
   * A cheap string hash, not a cryptographic one: the requirement is only that
   * the same name always yields the same colour and that similar names do not
   * collide too obviously.
   */
  function hue(name: string): number {
    let h = 0;
    for (let i = 0; i < name.length; i += 1) {
      h = (h * 31 + name.charCodeAt(i)) % 360;
    }
    return h;
  }
</script>

<Page title="Installations" summary="{rows.length} {rows.length === 1 ? 'installation' : 'installations'}" wide>
  <svelte:fragment slot="actions">
    <Button variant="primary" disabled={busy} onClick={() => (settingUp = true)}>
      <Plus size={14} strokeWidth={2.4} />
      New installation
    </Button>
  </svelte:fragment>

  {#if error}
    <p class="error" transition:fade={{ duration: FAST }}>{error}</p>
  {/if}

  <div class="grid">
    {#each rows as row, index (row.id)}
      <div
        class="tile"
        class:selected={row.selected}
        in:fly={{ y: 12, duration: NORMAL, delay: index * STAGGER, easing: EASE }}
      >
        <button class="face" on:click={() => run(() => selectProfile(row.id))}>
          <span
            class="art"
            style={art[row.id]
              ? `background-image:url(${art[row.id]})`
              : `background-image:linear-gradient(150deg,
                   hsl(${hue(row.name)} 42% 26%),
                   hsl(${(hue(row.name) + 40) % 360} 38% 12%))`}
          >
            {#if row.selected}
              <span class="tick" transition:fade={{ duration: FAST }}>
                <Check size={13} strokeWidth={3} />
              </span>
            {/if}
            <span class="version tnum">{row.version}</span>
          </span>

          <span class="body">
            <span class="name">{row.name}</span>
            <span class="facts">
              <!-- The type first: what a profile *is* answers more questions
                   than which loader it happens to carry. -->
              <span class="kind {row.kind}">{row.kindLabel}</span>
              <span class:attention={!row.loaderAvailable}>{row.loader}</span>
              <span class="dot">·</span>
              <span>{row.modCount} {row.modCount === 1 ? 'mod' : 'mods'}</span>
            </span>
            {#if row.parent}
              <span class="derived">
                <GitBranch size={11} strokeWidth={2} />
                Based on {row.parent}
              </span>
            {/if}
            <span class="foot">
              <span class="preset">{row.preset}</span>
              <span class="played tnum">{row.lastPlayed}</span>
            </span>
          </span>
        </button>

        <!-- Controls appear on hover. Three icon buttons on every tile at rest
             would turn a wall of six into eighteen affordances. -->
        <div class="tools">
          <button title="Edit" on:click|stopPropagation={() => openEditor(row.id)}>
            <Pencil size={13} strokeWidth={2} />
          </button>
          <!-- A variant, not a copy. It shares the parent's client and
               overrides only its own look and extra mods, so it stays current
               when the parent's loader moves. -->
          <button
            title="Make a variant"
            on:click|stopPropagation={() => run(() => deriveProfile(row.id, `${row.name} variant`))}
          >
            <GitBranch size={13} strokeWidth={2} />
          </button>
          <button
            title="Duplicate"
            on:click|stopPropagation={() => run(() => duplicateProfile(row.id))}
          >
            <Copy size={13} strokeWidth={2} />
          </button>
          <button
            class="danger"
            title="Delete"
            on:click|stopPropagation={() => (confirmingDelete = row.id)}
          >
            <Trash2 size={13} strokeWidth={2} />
          </button>
        </div>

        {#if confirmingDelete === row.id}
          <!-- Confirmed in place, on the thing being deleted, rather than in a
               modal that covers it up. -->
          <div class="confirm" transition:fade={{ duration: FAST, easing: EASE }}>
            <span>Delete this installation?</span>
            <div class="confirm-actions">
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
          </div>
        {/if}
      </div>
    {/each}
  </div>
</Page>

{#if settingUp}
  <ProfileSetup
    onClose={() => (settingUp = false)}
    onCreated={() => void refresh().then(onChanged)}
    onAddOptiFine={() => {
      settingUp = false;
      onAddOptiFine();
    }}
  />
{/if}

<!-- EDITOR -->
{#if editing}
  <div
    class="scrim"
    role="button"
    tabindex="-1"
    aria-label="Close"
    on:click={() => (editing = null)}
    on:keydown={(event) => event.key === 'Escape' && (editing = null)}
    transition:fade={{ duration: FAST }}
  ></div>
  <aside class="drawer glass-strong" transition:fly={{ x: 340, duration: NORMAL, easing: EASE }}>
    <h2>Edit installation</h2>

    <div class="what">
      <span class="kind {editing.kind}">{editing.kindLabel}</span>
      <span class="what-facts">Minecraft {editing.version} · {editing.preset}</span>
    </div>

    {#if editing.parent}
      <!-- A derived profile's technical half comes from its parent, so the
           fields that would be overwritten at launch are removed rather than
           shown greyed. -->
      <p class="inherited">
        Based on {editing.parent}. Version, loader, memory and Java come from it.
      </p>
    {/if}

    <label>
      <span>Name</span>
      <input bind:value={editing.name} spellcheck="false" />
    </label>

    {#if !editing.parent}
      <label>
        <span>Loader</span>
        <select bind:value={editing.loader} disabled={editing.kind === 'fathom'}>
          {#each editing.loaders as loader}
            <!-- Unavailable loaders are shown and marked, not hidden. Hiding
                 them makes the product look smaller than it is and gives
                 somebody looking for Forge nothing to conclude. -->
            <option value={loader.id} disabled={!loader.available}>
              {loader.label}{loader.available ? '' : ' — not ready'}
            </option>
          {/each}
        </select>
      </label>
      {#if editing.kind === 'fathom'}
        <p class="hint">A Fathom installation's loader comes with the type.</p>
      {/if}

      <label>
        <span>Java runtime</span>
        <select bind:value={editing.java}>
          <option value="automatic">Automatic</option>
          <option value="managed">Fathom managed</option>
          <option value="system">System</option>
          {#if editing.javaPath}
            <option value="custom">Custom — {editing.javaPath}</option>
          {/if}
        </select>
      </label>
    {/if}

    {#if !editing.parent}
      <label>
        <span>Memory</span>
        <div class="memory">
          <input type="range" min="512" max="8192" step="256" bind:value={editing.memoryMb} />
          <span class="value tnum">{(editing.memoryMb / 1024).toFixed(1)} GB</span>
        </div>
      </label>

      <label>
        <span>Extra Java arguments</span>
        <input bind:value={editing.extraArgs} spellcheck="false" placeholder="None" />
      </label>
    {/if}

    {#if editing.components.length}
      <div class="block">
        <span class="block-label">Components</span>
        {#each editing.components as component}
          <div class="component">
            <span class="component-name">{component.name}</span>
            <span class="component-state {component.state}">{component.detail}</span>
          </div>
        {/each}
      </div>
    {/if}

    <!-- Everything a profile can carry, whether or not this one does. Rows that
         are unset say so, rather than being omitted: the point is to show what
         a profile *is*, not only what has been filled in. -->
    <div class="block">
      <span class="block-label">Look</span>
      {#each editing.identity as row}
        <div class="component">
          <span class="component-name">{row.label}</span>
          <span class="component-state" class:unset={row.unset}>{row.value}</span>
        </div>
      {/each}
    </div>

    {#if editing.servers.length}
      <div class="block">
        <span class="block-label">Servers</span>
        {#each editing.servers as server}
          <div class="component">
            <span class="component-name">{server.name}</span>
            <span class="component-state">{server.address}</span>
          </div>
        {/each}
      </div>
    {/if}

    <label class="inline">
      <input type="checkbox" bind:checked={editing.keepLauncherOpen} />
      <span>Keep Fathom open while playing</span>
    </label>

    <div class="drawer-actions">
      <Button onClick={() => (editing = null)}>Cancel</Button>
      <Button variant="primary" disabled={busy} onClick={save}>Save</Button>
    </div>
  </aside>
{/if}

<style>

  .what {
    display: flex;
    align-items: center;
    gap: var(--s-2);
    margin-bottom: var(--s-1);
  }

  .what-facts {
    font-size: var(--t-caption);
    color: var(--text-faint);
  }

  .inherited,
  .hint {
    margin: 0;
    font-size: var(--t-caption);
    color: var(--text-faint);
    line-height: 1.5;
  }

  .block {
    display: flex;
    flex-direction: column;
    gap: 3px;
    padding-top: var(--s-2);
    border-top: 1px solid var(--chrome-line);
  }

  .block-label {
    font-size: var(--t-meta);
    font-weight: var(--w-semibold);
    letter-spacing: var(--track-caps);
    text-transform: uppercase;
    color: var(--text-label);
    margin-bottom: 2px;
  }

  .component {
    display: flex;
    align-items: baseline;
    gap: var(--s-3);
    font-size: var(--t-caption);
  }

  .component-name {
    color: var(--text-muted);
  }

  .component-state {
    margin-left: auto;
    color: var(--text-faint);
    text-align: right;
  }

  .component-state.installed {
    color: var(--good);
  }

  .component-state.unavailable,
  .component-state.unset {
    color: var(--text-disabled);
  }

  .component-state.missing {
    color: var(--warn);
  }

  /* The profile type, said before the loader: what a profile *is* answers more
     questions than which loader it happens to carry. */
  .kind {
    padding: 1px 6px;
    border-radius: var(--r-pill);
    font-size: var(--t-meta);
    font-weight: var(--w-medium);
    color: var(--text-muted);
    background: var(--fill-hover);
  }

  .kind.fathom {
    color: #ffb9bf;
    background: rgba(224, 80, 92, 0.18);
  }

  .derived {
    display: flex;
    align-items: center;
    gap: 4px;
    margin-top: 3px;
    font-size: var(--t-meta);
    color: var(--text-faint);
  }
  .grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(212px, 1fr));
    gap: var(--s-4);
  }

  .tile {
    position: relative;
    border-radius: var(--r-lg);
    overflow: hidden;
    background: var(--glass-fill);
    backdrop-filter: blur(var(--glass-blur)) saturate(1.2);
    -webkit-backdrop-filter: blur(var(--glass-blur)) saturate(1.2);
    border: 1px solid var(--glass-edge);
    box-shadow:
      var(--glass-sheen),
      var(--glass-shadow);
    transition:
      border-color var(--m-normal) var(--ease),
      transform var(--m-fast) var(--ease),
      box-shadow var(--m-normal) var(--ease);
  }

  .tile:hover {
    transform: translateY(-2px);
    border-color: var(--glass-edge-strong);
    box-shadow:
      var(--glass-sheen-strong),
      var(--glass-shadow-strong);
  }

  /* A restrained accent edge and a soft glow. Not a filled red card: the
     selected profile should be identifiable, not shouting. */
  .tile.selected {
    border-color: rgba(224, 80, 92, 0.65);
    box-shadow:
      inset 0 1px 0 rgba(255, 255, 255, 0.12),
      0 0 0 1px rgba(224, 80, 92, 0.2),
      0 16px 40px rgba(0, 0, 0, 0.45),
      0 0 34px var(--accent-glow);
  }

  .face {
    display: block;
    width: 100%;
    text-align: left;
  }

  .art {
    position: relative;
    display: block;
    height: 96px;
    background-size: cover;
    background-position: center;
    border-bottom: 1px solid var(--chrome-line);
  }

  .tick {
    position: absolute;
    top: 8px;
    right: 8px;
    display: grid;
    place-items: center;
    width: 21px;
    height: 21px;
    border-radius: var(--r-pill);
    background: var(--accent);
    color: #fff;
    box-shadow:
      inset 0 1px 0 rgba(255, 255, 255, 0.3),
      0 2px 8px rgba(0, 0, 0, 0.5);
  }

  .version {
    position: absolute;
    left: 8px;
    bottom: 8px;
    padding: 2px 7px;
    border-radius: var(--r-pill);
    background: rgba(0, 0, 0, 0.55);
    backdrop-filter: blur(8px);
    font-size: var(--t-meta);
    font-weight: var(--w-semibold);
    color: #fff;
  }

  .body {
    display: block;
    padding: 11px 13px 12px;
  }

  .name {
    display: block;
    font-size: var(--t-row);
    font-weight: var(--w-semibold);
    letter-spacing: var(--track-snug);
    color: var(--text);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .facts {
    display: flex;
    align-items: center;
    gap: var(--s-2);
    margin-top: var(--s-1);
    font-size: var(--t-caption);
    color: var(--text-muted);
  }

  .facts .attention {
    color: var(--warn);
  }

  .dot {
    color: var(--text-disabled);
  }

  .foot {
    display: flex;
    align-items: center;
    gap: var(--s-2);
    margin-top: var(--s-3);
  }

  .preset {
    padding: 2px 8px;
    border-radius: var(--r-pill);
    background: var(--fill);
    font-size: var(--t-meta);
    color: var(--text-muted);
  }

  .played {
    margin-left: auto;
    font-size: var(--t-meta);
    color: var(--text-faint);
  }

  .tools {
    position: absolute;
    top: 8px;
    left: 8px;
    display: flex;
    gap: var(--s-1);
    opacity: 0;
    transform: translateY(-4px);
    transition:
      opacity var(--m-fast) var(--ease),
      transform var(--m-fast) var(--ease);
  }

  .tile:hover .tools {
    opacity: 1;
    transform: none;
  }

  .tools button {
    display: grid;
    place-items: center;
    width: 25px;
    height: 25px;
    border-radius: var(--r-xs);
    color: rgba(255, 255, 255, 0.75);
    background: rgba(0, 0, 0, 0.55);
    backdrop-filter: blur(8px);
    transition:
      color var(--m-fast) var(--ease),
      background var(--m-fast) var(--ease);
  }

  .tools button:hover {
    color: #fff;
    background: rgba(255, 255, 255, 0.2);
  }

  .tools button.danger:hover {
    color: #fff;
    background: rgba(232, 114, 114, 0.7);
  }

  .confirm {
    position: absolute;
    inset: 0;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: var(--s-3);
    background: rgba(6, 7, 10, 0.93);
    backdrop-filter: blur(10px);
    font-size: var(--t-body);
  }

  .confirm-actions {
    display: flex;
    gap: var(--s-2);
  }

  .error {
    margin: 0;
    padding: 10px 13px;
    border-radius: var(--r-md);
    background: rgba(232, 114, 114, 0.12);
    border: 1px solid rgba(232, 114, 114, 0.3);
    color: var(--bad);
    font-size: var(--t-body);
  }

  /* -- editor ------------------------------------------------------------ */
  .scrim {
    position: fixed;
    inset: 0;
    background: rgba(0, 0, 0, 0.5);
    z-index: 30;
  }

  .drawer {
    position: fixed;
    top: var(--topbar-height);
    right: 0;
    bottom: var(--bottombar-height);
    width: 350px;
    display: flex;
    flex-direction: column;
    gap: var(--s-4);
    padding: 22px;
    border-radius: 0;
    border-right: none;
    border-top: none;
    border-bottom: none;
    overflow-y: auto;
    z-index: 31;
  }

  h2 {
    margin: 0;
    font-size: var(--t-section);
    font-weight: var(--w-semibold);
    letter-spacing: var(--track-snug);
  }

  label {
    display: flex;
    flex-direction: column;
    gap: var(--s-2);
  }

  label > span {
    font-size: var(--t-meta);
    font-weight: var(--w-semibold);
    letter-spacing: var(--track-caps);
    text-transform: uppercase;
    color: var(--text-faint);
  }

  label.inline {
    flex-direction: row;
    align-items: center;
    gap: var(--s-2);
  }

  label.inline span {
    font-size: var(--t-body);
    text-transform: none;
    letter-spacing: 0;
    font-weight: var(--w-normal);
    color: var(--text-muted);
  }

  input:not([type]),
  select {
    height: 32px;
    padding: 0 10px;
    border-radius: var(--r-sm);
    background: var(--fill-sunken);
    border: 1px solid var(--chrome-line-strong);
    font-size: var(--t-body);
    transition: border-color var(--m-fast) var(--ease);
  }

  input:not([type]):focus,
  select:focus {
    border-color: var(--accent);
  }

  .memory {
    display: flex;
    align-items: center;
    gap: var(--s-3);
  }

  input[type='range'] {
    flex: 1;
    accent-color: var(--accent);
  }

  .value {
    width: 54px;
    text-align: right;
    font-size: var(--t-body);
    color: var(--text-muted);
  }

  .drawer-actions {
    display: flex;
    justify-content: flex-end;
    gap: var(--s-2);
    margin-top: auto;
    padding-top: var(--s-3);
  }
</style>

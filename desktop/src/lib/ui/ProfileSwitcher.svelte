<!--
  Switching profile without going to Profiles.

  Picking which setup to launch is the second most common thing anybody does in
  this launcher, and it was only possible on one screen. So this is the same
  control in two places: a compact popover in the bottom bar, and a wider one
  under the hero on Home.

  It is a switcher, not a manager. No rename, no delete, no edit — those live on
  Profiles, where there is room to confirm them. A menu that can delete a
  profile is a menu you have to read carefully; this one you can use without
  looking.

  CHANGING PROFILE IS NOT A LIVE OPERATION.

  A different profile can mean a different Minecraft version and a different
  loader, so the change applies to the next launch. The row says so once, at the
  bottom, rather than on every entry.
-->
<script lang="ts">
  import { onMount } from 'svelte';
  import { fly } from 'svelte/transition';
  import { Check, ChevronDown, Layers } from '@lucide/svelte';

  import { EASE, NORMAL } from '../motion';
  import { profilesView, selectProfile, type ProfileRow } from '../api';

  /** `bar` is the compact bottom-bar trigger; `hero` is the one on Home. */
  export let size: 'bar' | 'hero' = 'bar';
  export let onChanged: () => void = () => {};
  /** Where the popover grows from, so it never opens off-screen. */
  export let align: 'up' | 'down' = 'up';
  /**
   * Bumped by the shell whenever something changed the profile list.
   *
   * The switcher loads on mount and when it opens, which leaves it stale after
   * a profile is created or selected somewhere else -- the trigger kept naming
   * the previous profile while the rest of the shell had moved on.
   */
  export let epoch = 0;

  let rows: ProfileRow[] = [];
  let open = false;
  let busy = false;

  onMount(() => {
    void load();
  });

  async function load() {
    rows = await profilesView();
  }

  async function choose(id: string) {
    open = false;
    if (busy) return;
    busy = true;
    try {
      await selectProfile(id);
      await load();
      onChanged();
    } finally {
      busy = false;
    }
  }

  async function toggle() {
    open = !open;
    if (open) await load();
  }

  $: if (epoch >= 0) void load();

  $: current = rows.find((row) => row.selected);
  $: label = current?.name ?? 'No installation';
</script>

<svelte:window on:click={() => (open = false)} />

<div class="anchor {size}">
  <button
    class="trigger {size}"
    class:active={open}
    title="Switch installation"
    on:click|stopPropagation={() => void toggle()}
  >
    <Layers size={size === 'hero' ? 14 : 13} strokeWidth={2} />
    <span class="label">{label}</span>
    <ChevronDown size={12} strokeWidth={2.2} />
  </button>

  {#if open}
    <div
      class="menu glass-strong {align}"
      transition:fly={{ y: align === 'up' ? 6 : -6, duration: NORMAL, easing: EASE }}
      on:click|stopPropagation
      on:keydown={(event) => event.key === 'Escape' && (open = false)}
      role="menu"
      tabindex="-1"
    >
      {#each rows as row}
        <button class="entry" class:selected={row.selected} on:click={() => void choose(row.id)}>
          <span class="tick">
            {#if row.selected}<Check size={13} strokeWidth={2.8} />{/if}
          </span>
          <span class="text">
            <span class="name">{row.name}</span>
            <span class="facts">
              {row.kindLabel} · {row.version} · {row.loader}
            </span>
          </span>
        </button>
      {/each}

      {#if rows.length === 0}
        <p class="none">No installations yet.</p>
      {:else}
        <p class="note">Applies next launch.</p>
      {/if}
    </div>
  {/if}
</div>

<style>
  .anchor {
    position: relative;
  }

  .trigger {
    display: flex;
    align-items: center;
    gap: var(--s-2);
    color: var(--text-muted);
    transition:
      color var(--m-fast) var(--ease),
      background var(--m-fast) var(--ease);
  }

  .trigger.bar {
    height: var(--h-sm);
    padding: 0 var(--s-2);
    border-radius: var(--r-sm);
    font-size: var(--t-caption);
  }

  .trigger.hero {
    height: var(--h-md);
    padding: 0 var(--s-3);
    border-radius: var(--r-pill);
    font-size: var(--t-body);
    color: #cfd4dd;
    background: rgba(12, 14, 19, 0.55);
    backdrop-filter: blur(var(--glass-blur));
    -webkit-backdrop-filter: blur(var(--glass-blur));
    border: 1px solid var(--glass-edge);
  }

  .trigger:hover,
  .trigger.active {
    color: var(--text);
    background: var(--fill-hover);
  }

  .trigger.hero:hover,
  .trigger.hero.active {
    background: rgba(12, 14, 19, 0.75);
  }

  .label {
    /* Content-sized, with a cap that only bites when the name genuinely has
       nowhere to go. The cap used to be a flat 180px, which ellipsised ordinary
       profile names on a wide window with empty space beside them -- an
       arbitrary pixel count standing in for "is there room". `ch` ties it to the
       text instead, and `min-width: 0` lets the flex parent shrink it further
       when the bar really is tight, so the ellipsis appears only then. */
    min-width: 0;
    max-width: 28ch;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font-weight: var(--w-medium);
  }

  .menu {
    position: absolute;
    z-index: 30;
    min-width: 260px;
    max-height: 320px;
    overflow-y: auto;
    padding: var(--s-2);
    border-radius: var(--r-md);
  }

  .menu.up {
    bottom: calc(100% + 8px);
    right: 0;
  }

  .menu.down {
    top: calc(100% + 8px);
    left: 50%;
    transform: translateX(-50%);
  }

  .entry {
    display: flex;
    align-items: center;
    gap: var(--s-2);
    width: 100%;
    padding: var(--s-2);
    border-radius: var(--r-sm);
    text-align: left;
    transition: background var(--m-fast) var(--ease);
  }

  .entry:hover {
    background: var(--fill-hover);
  }

  .tick {
    display: grid;
    place-items: center;
    width: 14px;
    color: var(--accent);
  }

  .text {
    display: flex;
    flex-direction: column;
    min-width: 0;
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
    font-size: var(--t-meta);
    color: var(--text-faint);
  }

  .note,
  .none {
    margin: var(--s-2) 0 2px;
    padding: 0 var(--s-2);
    font-size: var(--t-meta);
    color: var(--text-disabled);
  }
</style>

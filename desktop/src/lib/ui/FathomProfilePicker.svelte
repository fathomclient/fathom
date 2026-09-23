<!--
  Which Fathom Profile the next game opens, without going to Profiles.

  Beside the installation switcher on Home, and deliberately a different
  control: an installation is how Minecraft starts, a profile is the loadout
  inside it. The two used to share a word, which is how the launcher ended up
  with "Profiles" meaning versions while the game's "Profiles" meant settings.

  Choosing here sets the profile the next game opens. It cannot reach into a
  game that is already running; that switches in game.
-->
<script lang="ts">
  import { onMount } from 'svelte';
  import { fly } from 'svelte/transition';
  import { Check, ChevronDown, SlidersHorizontal } from '@lucide/svelte';

  import { EASE, NORMAL } from '../motion';
  import { fathomProfilesView, switchFathomProfile, type FathomProfilesView } from '../api';

  export let align: 'up' | 'down' = 'down';

  let view: FathomProfilesView = { profiles: [], live: '', problems: [] };
  let open = false;
  let busy = false;

  onMount(() => {
    void load();
  });

  async function load() {
    try {
      view = await fathomProfilesView();
    } catch {
      // A collection that cannot be read leaves the picker empty rather than
      // taking Home down with it; the Profiles page shows the reason.
    }
  }

  async function choose(id: string) {
    open = false;
    if (busy || id === view.live) return;
    busy = true;
    try {
      view = await switchFathomProfile(id);
    } finally {
      busy = false;
    }
  }

  async function toggle() {
    open = !open;
    if (open) await load();
  }

  $: current = view.profiles.find((profile) => profile.id === view.live);
</script>

<svelte:window on:click={() => (open = false)} />

<div class="anchor">
  <button
    class="trigger"
    class:active={open}
    title="Switch profile"
    on:click|stopPropagation={() => void toggle()}
  >
    <SlidersHorizontal size={14} strokeWidth={2} />
    <span class="prefix">Profile:</span>
    <span class="label">{current?.name ?? 'Default'}</span>
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
      {#each view.profiles as profile (profile.id)}
        <button class="entry" class:selected={profile.id === view.live} on:click={() => void choose(profile.id)}>
          <span class="tick">
            {#if profile.id === view.live}<Check size={13} strokeWidth={2.8} />{/if}
          </span>
          <span class="name">{profile.name}</span>
        </button>
      {/each}
      <p class="note">Used by the next game.</p>
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
    height: var(--h-md);
    padding: 0 var(--s-3);
    border-radius: var(--r-pill);
    font-size: var(--t-body);
    color: #cfd4dd;
    background: rgba(12, 14, 19, 0.55);
    backdrop-filter: blur(var(--glass-blur));
    -webkit-backdrop-filter: blur(var(--glass-blur));
    border: 1px solid var(--glass-edge);
    transition:
      color var(--m-fast) var(--ease),
      background var(--m-fast) var(--ease);
  }

  .trigger:hover,
  .trigger.active {
    color: var(--text);
    background: rgba(12, 14, 19, 0.75);
  }

  .prefix {
    color: var(--text-faint);
  }

  .label {
    /* Content-sized, with a cap that only bites when the name genuinely has
       nowhere to go. The cap used to be a flat 160px, which ellipsised ordinary
       profile names on a wide window with empty space beside them -- an
       arbitrary pixel count standing in for "is there room". `ch` ties it to the
       text instead, and `min-width: 0` lets the flex parent shrink it further
       when the bar really is tight, so the ellipsis appears only then. */
    min-width: 0;
    max-width: 26ch;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font-weight: var(--w-medium);
  }

  .menu {
    position: absolute;
    z-index: 30;
    min-width: 220px;
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
    color: var(--text-muted);
    font-size: var(--t-body);
  }

  .entry:hover,
  .entry.selected {
    color: var(--text);
    background: var(--fill-hover);
  }

  .tick {
    display: grid;
    place-items: center;
    width: 16px;
    color: var(--accent-strong);
  }

  .name {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .note {
    margin: var(--s-2) var(--s-2) 0;
    font-size: var(--t-meta);
    color: var(--text-faint);
  }
</style>

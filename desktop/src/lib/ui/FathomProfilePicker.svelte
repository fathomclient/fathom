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

  import { EASE, MOTION_SURFACE } from '../motion';
  import { tooltip } from '../tooltip';
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
    class="trigger ui-capsule material-clear material-interactive"
    class:active={open}
    aria-haspopup="menu"
    aria-expanded={open}
    use:tooltip={open ? '' : 'Switch profile'}
    on:click|stopPropagation={() => void toggle()}
  >
    <SlidersHorizontal size={15} strokeWidth={2} />
    <span class="prefix">Profile</span>
    <span class="label">{current?.name ?? 'Default'}</span>
    <span class="chevron" class:open><ChevronDown size={13} strokeWidth={2.2} /></span>
  </button>

  {#if open}
    <div
      class="menu ui-popover {align}"
      transition:fly={{ y: align === 'up' ? 6 : -6, duration: MOTION_SURFACE, easing: EASE }}
      on:click|stopPropagation
      on:keydown={(event) => event.key === 'Escape' && (open = false)}
      role="menu"
      tabindex="-1"
    >
      {#each view.profiles as profile (profile.id)}
        <button
          class="entry ui-popover-item"
          class:selected={profile.id === view.live}
          role="menuitemradio"
          aria-checked={profile.id === view.live}
          on:click={() => void choose(profile.id)}
        >
          <span class="tick">
            {#if profile.id === view.live}<Check size={13} strokeWidth={2.8} />{/if}
          </span>
          <span class="name">{profile.name}</span>
        </button>
      {/each}
      <p class="ui-popover-note">Used by the next game.</p>
    </div>
  {/if}
</div>

<style>
  .anchor {
    position: relative;
  }

  .trigger {
    height: var(--control-height-lg);
    padding: 0 var(--space-4);
    font-size: var(--type-body-size);
    color: var(--color-text);
  }

  .trigger.active {
    --mat-veil: var(--control-fill);
  }

  .prefix {
    color: var(--color-text-secondary);
    font-weight: var(--type-body-weight);
  }

  .label {
    /* Content-sized; the cap only bites when the name has nowhere to go. */
    min-width: 0;
    max-width: 30ch;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .chevron {
    display: grid;
    place-items: center;
    color: var(--color-text-secondary);
    transition: transform var(--motion-state) var(--ease-standard);
  }

  .chevron.open {
    transform: rotate(180deg);
  }

  .menu {
    position: absolute;
    z-index: 30;
    min-width: 240px;
    max-width: min(380px, 90vw);
    max-height: 360px;
    overflow-y: auto;
  }

  .menu.up {
    bottom: calc(100% + var(--space-2));
    right: 0;
  }

  .menu.down {
    top: calc(100% + var(--space-2));
    left: 50%;
    transform: translateX(-50%);
  }

  .tick {
    display: grid;
    place-items: center;
    width: 16px;
    flex-shrink: 0;
    color: var(--color-text);
  }

  .name {
    min-width: 0;
    overflow-wrap: anywhere;
  }
</style>

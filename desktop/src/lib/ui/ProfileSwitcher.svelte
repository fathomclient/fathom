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
  import { ICON } from '../metrics.generated';
  import { onMount } from 'svelte';
  import { fade, fly } from 'svelte/transition';
  import { Check, ChevronDown, Layers } from '@lucide/svelte';

  import { EASE, MOTION_SURFACE, EASE_EXIT, MOTION_EXIT } from '../motion';
  import { tooltip } from '../tooltip';
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
  <!-- On the photograph in both places it appears, so it is clear glass: the
       capsule brings its own blur and rim rather than a flat fill. -->
  <button
    class="trigger ui-capsule material-clear material-interactive {size}"
    class:small={size === 'bar'}
    class:large={size === 'hero'}
    class:active={open}
    aria-haspopup="menu"
    aria-expanded={open}
    use:tooltip={open ? '' : 'Switch installation'}
    on:click|stopPropagation={() => void toggle()}
  >
    <Layers size={ICON.inline} />
    <span class="label">{label}</span>
    <span class="chevron" class:open><ChevronDown size={ICON.inline} /></span>
  </button>

  {#if open}
    <div
      class="menu ui-popover {align}"
      in:fly={{ y: align === 'up' ? 6 : -6, duration: MOTION_SURFACE, easing: EASE }}
      out:fade={{ duration: MOTION_EXIT, easing: EASE_EXIT }}
      on:click|stopPropagation
      on:keydown={(event) => event.key === 'Escape' && (open = false)}
      role="menu"
      tabindex="-1"
    >
      {#each rows as row}
        <button
          class="entry ui-popover-item"
          class:selected={row.selected}
          role="menuitemradio"
          aria-checked={row.selected}
          on:click={() => void choose(row.id)}
        >
          <span class="tick">
            {#if row.selected}<Check size={ICON.inline} />{/if}
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
        <p class="ui-popover-note">No installations yet.</p>
      {:else}
        <p class="ui-popover-note">Applies next launch.</p>
      {/if}
    </div>
  {/if}
</div>

<style>
  .anchor {
    position: relative;
  }

  .trigger {
    color: var(--color-text);
  }

  .trigger.active {
    --mat-veil: var(--control-fill);
  }

  .label {
    /* Content-sized, with a cap that only bites when the name genuinely has
       nowhere to go: `ch` ties it to the text rather than to an arbitrary pixel
       count, and `min-width: 0` lets the flex parent shrink it further only
       when the space really is tight. */
    min-width: 0;
    max-width: 32ch;
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
    min-width: 280px;
    max-width: min(420px, 90vw);
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

  .text {
    display: flex;
    flex-direction: column;
    gap: 2px;
    min-width: 0;
  }

  /* Names wrap inside the menu rather than being cut: the menu is where you
     go to read them. */
  .name {
    font-size: var(--type-body-size);
    font-weight: var(--type-button-weight);
    color: var(--color-text);
    overflow-wrap: anywhere;
  }

  .facts {
    font-size: var(--type-caption-size);
    color: var(--color-text-tertiary);
  }
</style>

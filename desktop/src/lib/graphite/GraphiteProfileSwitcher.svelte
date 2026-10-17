<script lang="ts">
  import { ICON } from '../metrics.generated';
  import { onMount } from 'svelte';
  import { fade, fly } from 'svelte/transition';
  import { EASE, EASE_EXIT, MOTION_EXIT, MOTION_SURFACE } from '../motion';
  import { tooltip } from '../tooltip';
  import EmptyState from '../ui/EmptyState.svelte';
  import { Check, ChevronsUpDown, Layers } from '@lucide/svelte';
  import { profilesView, selectProfile, type ProfileRow } from '../api';

  export let epoch = 0;
  export let onChanged: () => void = () => {};

  let rows: ProfileRow[] = [];
  let open = false;
  let busy = false;

  onMount(() => void load());
  $: if (epoch >= 0) void load();

  async function load() {
    rows = await profilesView();
  }

  async function choose(id: string) {
    if (busy) return;
    open = false;
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
</script>

<svelte:window on:click={() => (open = false)} />

<div class="anchor">
  <button
    class="trigger"
    class:active={open}
    aria-label="Switch profile"
    use:tooltip={open ? '' : 'Switch installation'}
    aria-haspopup="menu"
    aria-expanded={open}
    on:click|stopPropagation={() => void toggle()}
  >
    <ChevronsUpDown size={ICON.inline} />
  </button>

  {#if open}
    <div
      class="menu"
      role="menu"
      tabindex="-1"
      in:fly={{ x: -6, duration: MOTION_SURFACE, easing: EASE }}
      out:fade={{ duration: MOTION_EXIT, easing: EASE_EXIT }}
      on:click|stopPropagation
      on:keydown={(event) => event.key === 'Escape' && (open = false)}
    >
      {#each rows.slice(0, 6) as row}
        <button
          class="row"
          class:selected={row.selected}
          role="menuitemradio"
          aria-checked={row.selected}
          on:click={() => void choose(row.id)}
        >
          <span class="copy">
            <span class="name">{row.name}</span>
            <span class="version">{row.version} · {row.loader}</span>
          </span>
          <span class="check">{#if row.selected}<Check size={ICON.inline} />{/if}</span>
        </button>
      {/each}
      {#if rows.length === 0}
        <EmptyState compact icon={Layers} title="No installations" detail="Make one on the Installations page." />
      {/if}
    </div>
  {/if}
</div>

<style>
  .anchor {
    position: relative;
  }

  .trigger {
    display: grid;
    place-items: center;
    /* The primary action's height and the control radius: it sits in the same
       row as Play, so it is the same family, not a circle beside a rectangle. */
    width: var(--metric-control-primary);
    height: var(--metric-control-primary);
    border-radius: var(--metric-radius-control);
    color: var(--g-text-primary);
    background: var(--g-mat-light);
    box-shadow: inset 0 0 0 var(--metric-rim) var(--g-mat-light-rim);
    -webkit-backdrop-filter: blur(var(--g-mat-light-blur)) saturate(1.12);
    backdrop-filter: blur(var(--g-mat-light-blur)) saturate(1.12);
    transition:
      background-color var(--g-motion-fast) var(--g-ease),
      box-shadow var(--g-motion-fast) var(--g-ease);
  }

  .trigger:hover {
    background: var(--g-mat-standard);
    box-shadow: inset 0 0 0 var(--metric-rim) var(--g-rim-hover);
  }

  .trigger.active {
    background: var(--g-mat-standard);
    box-shadow: inset 0 0 0 var(--metric-rim) var(--g-rim-selected);
  }

  .trigger:focus-visible,
  .row:focus-visible {
    outline: var(--focus-ring-width) solid var(--color-focus);
    outline-offset: 2px;
  }

  /* Beside the button, over the scene, bottom-aligned with it. It used to open
     upwards over the card's own heading, and a dense tint nested inside the
     card's glass blurs only the card -- so the installation name showed
     through the list. Nearly opaque for the same reason. */
  .menu {
    position: absolute;
    left: calc(100% + var(--space-2));
    bottom: 0;
    z-index: 80;
    width: 280px;
    max-height: 216px;
    overflow-y: auto;
    padding: var(--space-2);
    border-radius: var(--metric-radius-panel);
    background: rgba(12, 16, 20, 0.92);
    box-shadow:
      inset 0 0 0 var(--metric-rim) var(--g-mat-dense-rim),
      var(--g-elevation);
    -webkit-backdrop-filter: blur(var(--g-mat-dense-blur)) saturate(1.1);
    backdrop-filter: blur(var(--g-mat-dense-blur)) saturate(1.1);
  }

  .row {
    display: flex;
    align-items: center;
    width: 100%;
    min-height: var(--metric-control);
    padding: var(--space-1) var(--space-2);
    /* An item inside a panel: the small radius (the panel's 18 less its 8). */
    border-radius: var(--metric-radius-small);
    text-align: left;
    color: var(--g-text-primary);
  }

  .row:hover {
    background: var(--g-state-hover);
  }

  .row.selected {
    background: var(--g-state-selected);
    box-shadow: inset 0 0 0 var(--metric-rim) var(--g-rim-selected);
  }

  .copy {
    display: flex;
    min-width: 0;
    flex: 1;
    flex-direction: column;
  }

  .name {
    overflow: hidden;
    font: var(--g-type-ui-weight) var(--g-type-ui-size) / var(--g-type-ui-line) var(--g-type-ui-family);
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .version {
    overflow: hidden;
    font: var(--g-type-data-s-weight) var(--g-type-data-s-size) / var(--g-type-data-s-line) var(--g-type-data-s-family);
    color: var(--g-text-secondary);
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .check {
    display: grid;
    place-items: center;
    width: 24px;
  }


</style>

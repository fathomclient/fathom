<!--
  A choice from a short list: Fathom's dropdown.

  Replaces the native `<select>`, whose open list is drawn by Windows -- a
  white system menu over dense glass. The closed control is the shared field
  (`.ui-field`) with a chevron; the open list is the shared popover
  (`.ui-popover`): dense glass, compact rows, a check on the chosen one.

  Keyboard: Enter, Space or Down opens it; the arrows move; Enter chooses;
  Escape or Tab closes without choosing. Unavailable options stay in the list,
  marked, and cannot be chosen.
-->
<script lang="ts">
  import { tick } from 'svelte';
  import { fade, fly } from 'svelte/transition';
  import { Check, ChevronDown } from '@lucide/svelte';
  import { EASE, EASE_EXIT, MOTION_EXIT, MOTION_SURFACE } from '../motion';

  export let options: { id: string; label: string; disabled?: boolean; note?: string }[] = [];
  export let value: string;
  export let disabled = false;
  export let label = '';
  export let onChange: ((id: string) => void) | null = null;

  let open = false;
  let active = -1;
  let trigger: HTMLButtonElement;
  let list: HTMLDivElement;
  const listId = `select-${Math.random().toString(36).slice(2, 9)}`;

  $: current = options.find((option) => option.id === value);

  async function show() {
    if (disabled || open) return;
    open = true;
    active = Math.max(0, options.findIndex((option) => option.id === value));
    await tick();
    list?.focus();
  }

  function hide(refocus = true) {
    open = false;
    if (refocus) trigger?.focus();
  }

  function choose(index: number) {
    const option = options[index];
    if (!option || option.disabled) return;
    value = option.id;
    onChange?.(option.id);
    hide();
  }

  function step(direction: 1 | -1) {
    if (!options.length) return;
    let next = active;
    for (let i = 0; i < options.length; i += 1) {
      next = (next + direction + options.length) % options.length;
      if (!options[next].disabled) break;
    }
    active = next;
  }

  function onTriggerKey(event: KeyboardEvent) {
    if (['Enter', ' ', 'ArrowDown', 'ArrowUp'].includes(event.key)) {
      event.preventDefault();
      void show();
    }
  }

  function onListKey(event: KeyboardEvent) {
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      step(1);
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      step(-1);
    } else if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      choose(active);
    } else if (event.key === 'Escape') {
      event.preventDefault();
      event.stopPropagation();
      hide();
    } else if (event.key === 'Tab') {
      hide(false);
    }
  }
</script>

<svelte:window on:click={() => open && hide(false)} />

<div class="select">
  <button
    bind:this={trigger}
    type="button"
    class="ui-field trigger"
    {disabled}
    aria-label={label || undefined}
    aria-haspopup="listbox"
    aria-expanded={open}
    aria-controls={listId}
    on:click|stopPropagation={() => (open ? hide() : void show())}
    on:keydown={onTriggerKey}
  >
    <span class="value">{current?.label ?? ''}</span>
    <span class="chevron" class:open><ChevronDown size={16} /></span>
  </button>

  {#if open}
    <div
      bind:this={list}
      id={listId}
      class="ui-popover list"
      role="listbox"
      tabindex="-1"
      aria-activedescendant={active >= 0 ? `${listId}-${active}` : undefined}
      in:fly={{ y: -6, duration: MOTION_SURFACE, easing: EASE }}
      out:fade={{ duration: MOTION_EXIT, easing: EASE_EXIT }}
      on:click|stopPropagation
      on:keydown={onListKey}
    >
      {#each options as option, index}
        <button
          id="{listId}-{index}"
          type="button"
          class="ui-popover-item"
          class:selected={option.id === value}
          class:active={index === active}
          role="option"
          aria-selected={option.id === value}
          tabindex="-1"
          disabled={option.disabled}
          on:mouseenter={() => (active = index)}
          on:click={() => choose(index)}
        >
          <span class="label">
            {option.label}
            {#if option.note}<span class="note">{option.note}</span>{/if}
          </span>
          {#if option.id === value}<Check size={16} />{/if}
        </button>
      {/each}
    </div>
  {/if}
</div>

<style>
  .select {
    position: relative;
    width: 100%;
  }

  .trigger {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    text-align: left;
    cursor: pointer;
  }

  .trigger:disabled {
    cursor: default;
  }

  .value {
    flex: 1;
    min-width: 0;
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

  .list {
    position: absolute;
    top: calc(100% + var(--space-1));
    left: 0;
    right: 0;
    z-index: 60;
    max-height: 280px;
    overflow-y: auto;
  }

  .list:focus {
    outline: none;
  }

  .ui-popover-item {
    justify-content: space-between;
    font-size: var(--type-secondary-size);
  }

  /* The keyboard's position in the list: the hover fill, so pointer and keys
     look the same. */
  .ui-popover-item.active:not(:disabled) {
    color: var(--color-text);
    background-color: var(--control-fill);
  }

  .label {
    min-width: 0;
    overflow-wrap: anywhere;
  }

  .note {
    margin-left: var(--space-2);
    font-size: var(--type-caption-size);
    color: var(--color-text-tertiary);
  }
</style>

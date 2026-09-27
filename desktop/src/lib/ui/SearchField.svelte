<!--
  A search or filter field.

  The shared field (`.ui-field`: transparent sunken glass, a 1px rim that
  brightens on focus, the signal ring for keyboard focus) with the search glyph
  optically centred in its own 16px slot and a clear button that exists only
  while there is something to clear. Escape clears too.

  Two sizes: `md` (34px, inside a page's header row or a pane) and `lg` (40px,
  the one search a page is about).
-->
<script lang="ts">
  import { Search, X } from '@lucide/svelte';
  import { tooltip } from '../tooltip';

  export let value = '';
  export let placeholder = 'Search';
  export let label = placeholder;
  export let size: 'md' | 'lg' = 'md';
  /** Called on every keystroke and on clear, for callers that do not bind. */
  export let onInput: ((value: string) => void) | null = null;

  let input: HTMLInputElement;

  function set(next: string) {
    value = next;
    onInput?.(next);
  }

  function clear() {
    set('');
    input?.focus();
  }
</script>

<div class="search {size}">
  <span class="glyph" aria-hidden="true"><Search size={16} /></span>
  <input
    bind:this={input}
    class="ui-field"
    type="text"
    {value}
    {placeholder}
    aria-label={label}
    spellcheck="false"
    autocomplete="off"
    on:input={(event) => set(event.currentTarget.value)}
    on:keydown={(event) => {
      if (event.key === 'Escape' && value) {
        event.stopPropagation();
        clear();
      }
    }}
  />
  {#if value}
    <button class="clear" aria-label="Clear search" use:tooltip={'Clear'} on:click={clear}>
      <X size={14} />
    </button>
  {/if}
</div>

<style>
  .search {
    position: relative;
    display: flex;
    align-items: center;
    min-width: 0;
  }

  .glyph {
    position: absolute;
    left: 12px;
    display: grid;
    place-items: center;
    color: var(--color-text-tertiary);
    pointer-events: none;
    transition: color var(--motion-hover) var(--ease-standard);
  }

  .search:focus-within .glyph {
    color: var(--color-text-secondary);
  }

  input {
    padding-left: 36px;
    padding-right: 36px;
  }

  /* `--search-radius` lets a field inside a rounded pane stay concentric
     with it. */
  .md input {
    min-height: var(--control-height-md);
    border-radius: var(--search-radius, var(--radius-md));
    font-size: var(--type-secondary-size);
  }

  .lg input {
    min-height: var(--control-height-lg);
    border-radius: var(--search-radius, var(--radius-md));
  }

  .clear {
    position: absolute;
    right: 6px;
    display: grid;
    place-items: center;
    width: 24px;
    height: 24px;
    border-radius: var(--radius-pill);
    color: var(--color-text-tertiary);
    transition:
      color var(--motion-hover) var(--ease-standard),
      background-color var(--motion-hover) var(--ease-standard);
  }

  .clear:hover {
    color: var(--color-text);
    background-color: var(--control-fill-hover);
  }

  .clear:active {
    background-color: var(--control-fill-selected);
  }

  .clear:focus-visible {
    outline: var(--focus-ring-width) solid var(--color-focus);
    outline-offset: 1px;
  }
</style>

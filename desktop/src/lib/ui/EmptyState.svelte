<!--
  Nothing to show, said once and quietly.

  A small icon, a short heading, one line of explanation, and optionally one
  action. No illustration and no card: it sits in the space the content would
  have taken, centred in it, so an empty list and a full one have the same
  frame.

  `compact` is for the inside of a pane or a menu, where a whole block would be
  louder than the list it replaces.
-->
<script lang="ts">
  import type { Component } from 'svelte';
  import type { IconProps } from '@lucide/svelte';

  /** A Lucide icon component. Drawn at 20px (16px compact). */
  export let icon: Component<IconProps> | null = null;
  export let title: string;
  export let detail = '';
  export let compact = false;
</script>

<div class="empty" class:compact role="status">
  {#if icon}
    <span class="glyph" aria-hidden="true">
      <svelte:component this={icon} size={compact ? 16 : 20} />
    </span>
  {/if}
  <span class="title">{title}</span>
  {#if detail}<span class="detail">{detail}</span>{/if}
  {#if $$slots.default}
    <div class="action"><slot /></div>
  {/if}
</div>

<style>
  .empty {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: var(--space-1);
    padding: var(--space-10) var(--space-6);
    text-align: center;
  }

  .empty.compact {
    padding: var(--space-5) var(--space-4);
  }

  .glyph {
    display: grid;
    place-items: center;
    width: 40px;
    height: 40px;
    margin-bottom: var(--space-2);
    border-radius: var(--radius-pill);
    background-color: var(--control-fill-faint);
    box-shadow: var(--control-rim);
    color: var(--color-text-secondary);
  }

  .compact .glyph {
    width: 32px;
    height: 32px;
    margin-bottom: var(--space-1);
  }

  .title {
    font-size: var(--type-body-size);
    font-weight: var(--type-heading-weight);
    letter-spacing: var(--type-heading-tracking);
    color: var(--color-text);
  }

  .detail {
    max-width: 44ch;
    font-size: var(--type-secondary-size);
    line-height: var(--type-secondary-leading);
    color: var(--color-text-secondary);
  }

  .action {
    display: flex;
    gap: var(--space-2);
    margin-top: var(--space-3);
  }
</style>

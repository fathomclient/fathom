<!--
  A mod's description, rendered from the safe tree in `lib/richtext.ts`.
-->
<script lang="ts">
  import type { Block } from '../richtext';
  import type { CatalogProvider } from '../api';
  import RichInline from './RichInline.svelte';

  export let blocks: Block[];
  export let provider: CatalogProvider;
  export let onLink: (href: string) => void;
</script>

<div class="rich">
  {#each blocks as block}
    {#if block.kind === 'heading'}
      {#if block.level <= 2}
        <h3><RichInline inlines={block.inlines} {provider} {onLink} /></h3>
      {:else}
        <h4><RichInline inlines={block.inlines} {provider} {onLink} /></h4>
      {/if}
    {:else if block.kind === 'paragraph'}
      <p><RichInline inlines={block.inlines} {provider} {onLink} /></p>
    {:else if block.kind === 'quote'}
      <blockquote><RichInline inlines={block.inlines} {provider} {onLink} /></blockquote>
    {:else if block.kind === 'list'}
      {#if block.ordered}
        <ol>
          {#each block.items as item}<li><RichInline inlines={item} {provider} {onLink} /></li>{/each}
        </ol>
      {:else}
        <ul>
          {#each block.items as item}<li><RichInline inlines={item} {provider} {onLink} /></li>{/each}
        </ul>
      {/if}
    {:else if block.kind === 'code'}
      <pre><code>{block.text}</code></pre>
    {:else if block.kind === 'rule'}
      <hr />
    {/if}
  {/each}
</div>

<style>
  .rich {
    display: flex;
    flex-direction: column;
    gap: var(--space-3);
    min-width: 0;
    font-size: var(--type-body-size);
    line-height: var(--type-body-leading);
    color: var(--color-text-secondary);
    overflow-wrap: anywhere;
  }

  h3,
  h4 {
    margin: var(--space-2) 0 0;
    font-weight: var(--type-heading-weight);
    letter-spacing: var(--type-heading-tracking);
    line-height: var(--type-heading-leading);
    color: var(--color-text);
  }

  h3 {
    font-size: var(--type-heading-size);
  }

  h4 {
    font-size: var(--type-body-size);
  }

  p,
  ul,
  ol,
  blockquote,
  pre {
    margin: 0;
  }

  ul,
  ol {
    padding-left: var(--space-5);
    display: flex;
    flex-direction: column;
    gap: var(--space-1);
  }

  blockquote {
    padding: var(--space-2) var(--space-3);
    border-left: 2px solid var(--control-hairline-strong);
    color: var(--color-text-tertiary);
  }

  pre {
    padding: var(--space-3);
    border-radius: var(--radius-md);
    background-color: var(--control-fill-sunken);
    overflow-x: auto;
    font-size: var(--type-caption-size);
    line-height: 1.5;
  }

  pre code {
    font-family: var(--font-mono, ui-monospace, monospace);
    white-space: pre;
  }

  hr {
    width: 100%;
    height: var(--hairline);
    border: 0;
    background-color: var(--control-hairline);
  }

  .rich :global(strong) {
    color: var(--color-text);
    font-weight: var(--type-heading-weight);
  }
</style>

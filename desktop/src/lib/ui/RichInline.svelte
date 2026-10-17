<!--
  One run of inline description text: emphasis, code, links and images, as
  plain elements and text nodes. Recursive through `strong`, `em` and links.
  See `lib/richtext.ts` for why nothing here is ever HTML.
-->
<script lang="ts">
  import type { Inline } from '../richtext';
  import type { CatalogProvider } from '../api';
  import RemoteImage from './RemoteImage.svelte';
  import Self from './RichInline.svelte';

  export let inlines: Inline[];
  export let provider: CatalogProvider;
  export let onLink: (href: string) => void;
</script>

{#each inlines as inline}
  {#if inline.kind === 'text'}{inline.text}{:else if inline.kind === 'strong'}<strong
      ><Self inlines={inline.children} {provider} {onLink} /></strong
    >{:else if inline.kind === 'em'}<em><Self inlines={inline.children} {provider} {onLink} /></em
    >{:else if inline.kind === 'code'}<code>{inline.text}</code>{:else if inline.kind === 'break'}<br
    />{:else if inline.kind === 'link'}<a
      href={inline.href}
      title={inline.href}
      on:click|preventDefault={() => onLink(inline.href)}
      ><Self inlines={inline.children} {provider} {onLink} /></a
    >{:else if inline.kind === 'image'}<span class="figure"
      ><RemoteImage {provider} url={inline.src} alt={inline.alt} fallback={inline.alt} kind="shot" /></span
    >{/if}
{/each}

<style>
  code {
    padding: 1px 5px;
    border-radius: var(--radius-xs);
    background-color: var(--control-fill-sunken);
    font-family: var(--font-mono, ui-monospace, monospace);
    font-size: 0.92em;
  }

  a {
    color: var(--color-signal);
    text-decoration: underline;
    text-decoration-color: color-mix(in srgb, var(--color-signal) 40%, transparent);
    text-underline-offset: 3px;
    cursor: pointer;
  }

  a:hover {
    text-decoration-color: var(--color-signal);
  }

  a:focus-visible {
    outline: var(--focus-ring-width) solid var(--color-focus);
    outline-offset: 2px;
    border-radius: var(--radius-xs);
  }

  .figure {
    display: block;
    max-width: 100%;
    aspect-ratio: 16 / 9;
    margin: var(--space-2) 0;
  }
</style>

<!--
  Logs.

  Two real files: the launcher's own log, and the most recent Minecraft session.
  Nothing is buffered in the interface and nothing is synthesised — this shows
  the bytes on disk, which are the bytes a user would attach to a bug report.

  Copy and Save hand over what is on screen, filter included, because the
  useful thing to send is usually the twenty lines around a failure rather than
  the whole session.
-->
<script lang="ts">
  import { onMount, tick } from 'svelte';
  import { fade } from 'svelte/transition';
  import { Copy, RefreshCw, Search, ArrowDownToLine, Check, ScrollText } from '@lucide/svelte';

  import Page from '../lib/ui/Page.svelte';
  import Button from '../lib/ui/Button.svelte';
  import Segmented from '../lib/ui/Segmented.svelte';
  import Switch from '../lib/ui/Switch.svelte';
  import SearchField from '../lib/ui/SearchField.svelte';
  import EmptyState from '../lib/ui/EmptyState.svelte';
  import { FAST } from '../lib/motion';
  import { logsView, type LogSource } from '../lib/api';

  let sources: LogSource[] = [];
  let active = 'launcher';
  let query = '';
  let autoScroll = true;
  let copied = false;
  let pane: HTMLDivElement | null = null;

  async function load() {
    sources = await logsView();
    if (autoScroll) {
      await tick();
      if (pane) pane.scrollTop = pane.scrollHeight;
    }
  }

  onMount(() => {
    void load();
    // Polled rather than pushed: the command bridge is request/response, and a
    // two-second refresh on a screen somebody is actively watching is cheaper
    // than an event channel that exists for one page.
    const timer = setInterval(() => void load(), 2000);
    return () => clearInterval(timer);
  });

  $: current = sources.find((source) => source.id === active);
  $: lines = (current?.lines ?? []).filter((line) =>
    query.trim() === '' ? true : line.text.toLowerCase().includes(query.toLowerCase()),
  );

  async function copy() {
    await navigator.clipboard.writeText(lines.map((line) => line.text).join('\n'));
    copied = true;
    setTimeout(() => (copied = false), 1400);
  }
</script>

<Page title="Logs" summary={current?.label ?? ''} wide>
  <svelte:fragment slot="actions">
    <div class="search">
      <SearchField bind:value={query} placeholder="Filter" label="Filter log lines" />
    </div>
    <Button onClick={() => void load()}>
      <RefreshCw size={14} />
      Refresh
    </Button>
    <Button onClick={copy}>
      {#if copied}
        <Check size={14} />
        Copied
      {:else}
        <Copy size={14} />
        Copy
      {/if}
    </Button>
  </svelte:fragment>

  <div class="controls">
    <Segmented
      options={sources.map((source) => ({ id: source.id, label: source.label }))}
      value={active}
      onChange={(id) => (active = id)}
    />
    <div class="follow">
      <ArrowDownToLine size={14} />
      <span>Follow</span>
      <Switch checked={autoScroll} label="Follow" onChange={(value) => (autoScroll = value)} />
    </div>
  </div>

  <div class="pane material-standard" bind:this={pane}>
    {#if current?.note}
      <p class="note" in:fade={{ duration: FAST }}>{current.note}</p>
    {:else if lines.length === 0}
      {#if query.trim()}
        <EmptyState
          compact
          icon={Search}
          title="No matching lines"
          detail="Nothing in this log contains “{query.trim()}”."
        />
      {:else}
        <EmptyState compact icon={ScrollText} title="This log is empty" detail="Lines appear here as they are written." />
      {/if}
    {:else}
      {#each lines as line}
        <div class="line keep-case {line.level}">{line.text}</div>
      {/each}
    {/if}
  </div>
</Page>

<style>
  .controls {
    display: flex;
    align-items: center;
    gap: var(--space-3);
    margin-bottom: var(--space-3);
  }

  .follow {
    display: flex;
    align-items: center;
    gap: var(--s-2);
    margin-left: auto;
    font-size: var(--t-caption);
    color: var(--text-muted);
  }

  .search {
    width: 200px;
  }

  .pane {
    --surface-radius: var(--radius-lg);
    height: calc(100vh - 260px);
    min-height: 260px;
    overflow: auto;
    padding: var(--space-3) var(--space-4);
    /* Geist Mono, because a log is columnar: timestamps and levels only line
       up in a fixed-width face. */
    font-family: var(--type-family-mono);
    font-size: var(--type-caption-size);
    line-height: 1.55;
    /* Selectable, unlike the rest of the product: the whole point is to copy
       lines out of it. */
    user-select: text;
    cursor: text;
  }

  .line {
    white-space: pre-wrap;
    word-break: break-word;
    color: var(--text-muted);
  }

  .line.error {
    color: var(--bad);
  }
  .line.warn {
    color: var(--warn);
  }
  .line.debug {
    color: var(--text-disabled);
  }

  .note {
    margin: 0;
    color: var(--color-text-secondary);
    font-family: var(--type-family);
    font-size: var(--type-secondary-size);
  }

  .pane :global(.empty) {
    font-family: var(--type-family);
  }
</style>

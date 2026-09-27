<!--
  The game console.

  A second window, not a page. It appears when Minecraft does — or when
  Minecraft falls over, depending on the Advanced setting — and it shows one
  thing: what the game is printing, as it prints it.

  It is deliberately not the Logs screen with a window frame around it. Logs is
  a browser: two sources, a filter, a source switcher. This has one source and
  no choices, because somebody looking at it has already lost a session and is
  looking for a stack trace, not for a control.

  It does not own the process. The bytes come from the log file the launch
  pipeline is already writing, which is why closing this window cannot affect
  the game, and why re-opening it shows everything that happened while it was
  shut.
-->
<script lang="ts">
  import { onMount, tick } from 'svelte';
  import { Check, Copy, ArrowDownToLine, X } from '@lucide/svelte';

  import '../lib/theme.css';
  import Button from '../lib/ui/Button.svelte';
  import Switch from '../lib/ui/Switch.svelte';
  import { closeConsole, consoleView, type ConsoleView } from '../lib/api';

  let view: ConsoleView | null = null;
  let follow = true;
  let copied = false;
  let pane: HTMLDivElement | null = null;

  /* Faster than the Logs screen's two seconds. This window exists to be watched
     while something is happening, and a two-second lag between a crash and its
     stack trace appearing reads as the window being broken. */
  const REFRESH = 700;

  async function load() {
    view = await consoleView();
    if (follow) {
      await tick();
      if (pane) pane.scrollTop = pane.scrollHeight;
    }
  }

  onMount(() => {
    void load();
    const timer = setInterval(() => void load(), REFRESH);
    return () => clearInterval(timer);
  });

  async function copy() {
    const text = (view?.lines ?? []).map((line) => line.text).join('\n');
    await navigator.clipboard.writeText(text);
    copied = true;
    setTimeout(() => (copied = false), 1400);
  }
</script>

<div class="console">
  <header>
    <span class="dot" class:live={view?.running}></span>
    <span class="state">{view?.state ?? 'Loading'}</span>
    {#if view?.profile}
      <span class="sep">·</span>
      <span class="profile">{view.profile}</span>
    {/if}

    <div class="spacer"></div>

    <label class="follow">
      <ArrowDownToLine size={14} />
      <span>Follow</span>
      <Switch checked={follow} label="Follow output" onChange={(value) => (follow = value)} />
    </label>

    <Button onClick={copy}>
      {#if copied}
        <Check size={14} />
        Copied
      {:else}
        <Copy size={14} />
        Copy
      {/if}
    </Button>

    <Button onClick={() => void closeConsole()}>
      <X size={14} />
      Close
    </Button>
  </header>

  <div class="pane" bind:this={pane}>
    {#if view?.note}
      <p class="note">{view.note}</p>
    {:else}
      {#each view?.lines ?? [] as line}
        <div class="line {line.level}">{line.text}</div>
      {/each}
    {/if}
  </div>

  <footer>
    <span><span class="mono">{view?.lines.length ?? 0}</span> lines</span>
    <span class="sep">·</span>
    <!-- Said plainly, because somebody about to paste this into a bug report
         deserves to know it has already been through the redactor. -->
    <span>Access tokens are removed before this is shown</span>
  </footer>
</div>

<style>
  .console {
    display: grid;
    grid-template-rows: var(--topbar-height) 1fr var(--bottombar-height);
    height: 100vh;
    /* A plain window, not a scene: the backdrop's own base colour, no
       photograph behind a log. */
    background: var(--backdrop-base);
    font-family: var(--font);
    color: var(--text);
  }

  header {
    display: flex;
    align-items: center;
    gap: var(--s-2);
    padding: 0 var(--s-3);
    border-bottom: var(--hairline) solid var(--chrome-line);
    font-size: var(--t-body);
  }

  .spacer {
    flex: 1;
  }

  .dot {
    width: 7px;
    height: 7px;
    border-radius: var(--r-pill);
    background: var(--text-disabled);
  }

  /* Live: the game is running and writing. Signal colour, no glow. */
  .dot.live {
    background: var(--color-signal);
  }

  .state {
    font-weight: var(--w-medium);
  }

  .profile,
  .sep {
    color: var(--text-faint);
  }

  .follow {
    display: flex;
    align-items: center;
    gap: var(--s-2);
    margin-right: var(--s-2);
    font-size: var(--t-caption);
    color: var(--text-muted);
  }

  .pane {
    overflow: auto;
    padding: var(--s-3);
    /* Fixed width, like the Logs screen: a log is columnar, and timestamps only
       line up in a monospace face. */
    font-family: var(--type-family-mono);
    font-size: var(--t-caption);
    line-height: 1.5;
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
    font-family: var(--font);
    font-size: var(--t-body);
    color: var(--text-faint);
  }

  footer {
    display: flex;
    align-items: center;
    gap: var(--s-2);
    padding: 0 var(--s-3);
    border-top: var(--hairline) solid var(--chrome-line);
    font-size: var(--t-meta);
    color: var(--text-faint);
  }
</style>

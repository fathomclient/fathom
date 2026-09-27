<!--
  Recovery.

  Compact actionable rows, like a professional repair utility: an icon, a short
  label, one line of what pressing it does, and the button. No giant card.

  Somebody arriving here has a problem already, so the result appears IN the row
  rather than in a toast that vanishes before it is read. An action that would
  currently do nothing renders inert with the reason attached — "There is
  nothing cached" — rather than as a live button that produces no visible change
  when pressed.
-->
<script lang="ts">
  import { ICON } from '../lib/metrics.generated';
  import { onMount } from 'svelte';
  import { fly, fade } from 'svelte/transition';
  import {
    FileSearch,
    Coffee,
    Eraser,
    Undo2,
    FileText,
  } from '@lucide/svelte';

  import Page from '../lib/ui/Page.svelte';
  import Section from '../lib/ui/Section.svelte';
  import Row from '../lib/ui/Row.svelte';
  import Button from '../lib/ui/Button.svelte';
  import Notice from '../lib/ui/Notice.svelte';
  import { EASE, FAST, NORMAL, STAGGER } from '../lib/motion';
  import { recoveryView, runRecovery, type ActionOutcome, type RecoveryView } from '../lib/api';

  let view: RecoveryView | null = null;
  /** Which action is running, so only that row says it is working. */
  let running: string | null = null;
  let outcomes: Record<string, ActionOutcome> = {};

  /* Icons are a display concern, so they are matched to the action's id here
     rather than sent from Rust. The core has no business knowing what a
     recovery step looks like. */
  const ICONS: Record<string, typeof FileSearch> = {
    verify: FileSearch,
    java: Coffee,
    'clear-cache': Eraser,
    restore: Undo2,
    diagnostics: FileText,
  };

  onMount(() => {
    void (async () => {
      view = await recoveryView();
    })();
  });

  async function act(id: string) {
    if (running) return;
    running = id;
    try {
      outcomes = { ...outcomes, [id]: await runRecovery(id) };
      // The action may have changed what the other rows should say -- clearing
      // the cache makes "Clear cache" unavailable, finding Java changes the
      // header -- so the page is re-read rather than patched.
      view = await recoveryView();
    } catch (cause) {
      outcomes = { ...outcomes, [id]: { ok: false, message: String(cause) } };
    } finally {
      running = null;
    }
  }
</script>

{#if view}
  <Page title="Recovery" summary={view.healthy ? 'No problems found' : 'Needs attention'}>
    <div in:fade={{ duration: NORMAL, easing: EASE }}>
      <Notice tone={view.healthy ? 'success' : 'warning'}>{view.summary}</Notice>
    </div>

    {#if view.actions.length}
    <Section label="Things you can do" material>
      {#each view.actions as action, index (action.id)}
        <div in:fly={{ y: 6, duration: NORMAL, delay: index * STAGGER, easing: EASE }}>
          <Row
            title={action.label}
            detail={action.detail}
            dimmed={Boolean(action.unavailable)}
            warning={outcomes[action.id] && !outcomes[action.id].ok
              ? outcomes[action.id].message
              : null}
            compact
          >
            <svelte:fragment slot="icon">
              <svelte:component this={ICONS[action.id] ?? FileSearch} size={ICON.inline} />
            </svelte:fragment>

            <svelte:fragment slot="control">
              {#if outcomes[action.id]?.ok}
                <span class="done" transition:fade={{ duration: FAST, easing: EASE }}>
                  {outcomes[action.id].message}
                </span>
              {/if}
              <Button
                variant={action.tone === 'caution' ? 'caution' : 'normal'}
                disabled={Boolean(action.unavailable) || running !== null}
                title={action.unavailable ?? ''}
                onClick={() => act(action.id)}
              >
                {running === action.id ? 'Working' : action.verb}
              </Button>
            </svelte:fragment>
          </Row>
        </div>
      {/each}
    </Section>
    {/if}
  </Page>
{/if}

<style>
  /* What the action did: a small success dot and a neutral sentence. */
  .done {
    display: inline-flex;
    align-items: baseline;
    gap: 6px;
    max-width: 230px;
    font-size: var(--type-secondary-size);
    color: var(--color-text-secondary);
    text-align: right;
  }

  .done::before {
    content: '';
    flex-shrink: 0;
    width: 6px;
    height: 6px;
    border-radius: var(--radius-pill);
    background-color: var(--color-success-text);
    transform: translateY(-1px);
  }
</style>

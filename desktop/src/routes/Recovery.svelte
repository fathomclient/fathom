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
  import { onMount } from 'svelte';
  import { fly, fade } from 'svelte/transition';
  import {
    CircleCheck,
    TriangleAlert,
    LoaderCircle,
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
  import { EASE, FAST, NORMAL, STAGGER } from '../lib/motion';
  import { recoveryView, runRecovery, type ActionOutcome, type RecoveryView } from '../lib/api';

  let view: RecoveryView | null = null;
  /** Which action is running, so only that row shows a spinner. */
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
    <div class="state glass" class:good={view.healthy} in:fade={{ duration: NORMAL, easing: EASE }}>
      <span class="state-icon">
        {#if view.healthy}
          <CircleCheck size={17} strokeWidth={2} />
        {:else}
          <TriangleAlert size={17} strokeWidth={2} />
        {/if}
      </span>
      <span>{view.summary}</span>
    </div>

    <Section label="Things you can do">
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
              <svelte:component this={ICONS[action.id] ?? FileSearch} size={15} strokeWidth={1.9} />
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
                {#if running === action.id}
                  <span class="spin"><LoaderCircle size={13} strokeWidth={2.4} /></span>
                {/if}
                {action.verb}
              </Button>
            </svelte:fragment>
          </Row>
        </div>
      {/each}
    </Section>
  </Page>
{/if}

<style>
  .state {
    display: flex;
    align-items: center;
    gap: var(--s-3);
    padding: 13px 15px;
    font-size: var(--t-body-lg);
    color: var(--warn);
    /* Overrides the neutral glass edge with a tinted one, so the whole surface
       carries the state rather than a coloured dot on a grey panel. */
    border-color: rgba(224, 174, 87, 0.3);
    background: rgba(224, 174, 87, 0.09);
  }

  .state.good {
    color: var(--good);
    border-color: rgba(79, 201, 138, 0.26);
    background: rgba(79, 201, 138, 0.08);
  }

  .state-icon {
    display: grid;
    place-items: center;
  }

  .done {
    font-size: var(--t-body);
    color: var(--good);
    text-align: right;
    max-width: 230px;
  }

  .spin {
    display: grid;
    place-items: center;
    animation: spin 800ms linear infinite;
  }

  @keyframes spin {
    to {
      transform: rotate(360deg);
    }
  }
</style>

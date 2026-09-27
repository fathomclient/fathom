<!--
  Privacy.

  A status board, not an essay.

  The page opens with four facts in a grid — Telemetry / Analytics / Crash
  uploads / Credentials — each a label and a one-word value. That is the whole
  answer for almost everybody, and it is checkable at a glance rather than
  extracted from paragraphs.

  Everything else is collapsed: the guarantees in full, the host list, and the
  session log. The previous version led with five paragraphs of prose, which is
  how a privacy page ends up unread — the reassurance is proportional to how
  little of it there is.

  The host list is the same enumeration the network layer consults before
  opening a socket, so this page cannot describe a policy the product does not
  have. That is the difference between a privacy page and a privacy promise.
-->
<script lang="ts">
  import { onMount } from 'svelte';
  import { fly, fade } from 'svelte/transition';
  import { Check, Radio, WifiOff } from '@lucide/svelte';

  import Page from '../lib/ui/Page.svelte';
  import Section from '../lib/ui/Section.svelte';
  import Row from '../lib/ui/Row.svelte';
  import Switch from '../lib/ui/Switch.svelte';
  import Notice from '../lib/ui/Notice.svelte';
  import EmptyState from '../lib/ui/EmptyState.svelte';
  import { EASE, NORMAL, STAGGER } from '../lib/motion';
  import {
    privacyView,
    setSetting,
    settingsView,
    type PrivacyView,
    type SettingsView,
    type ToggleRow,
  } from '../lib/api';

  export let onChanged: () => void = () => {};

  let view: PrivacyView | null = null;
  let settings: SettingsView | null = null;
  let busy = false;
  let error = '';
  let guaranteesOpen = false;
  let hostsOpen = false;
  let activityOpen = false;

  onMount(() => {
    void (async () => {
      view = await privacyView();
      settings = await settingsView();
    })();
  });

  /* The settings this page is about.

     They are the same rows Settings > Network draws, read from the same place
     and written through the same command -- not a second copy that could
     disagree with it. They are *here* because this is the page that tells you
     what Fathom may contact, and being told that a repository is blocked while
     the switch that blocks it lives on another page is how somebody ends up
     pressing Install and watching nothing happen. */
  $: privacyToggles = (settings?.toggles ?? []).filter(
    (toggle) => toggle.section === 'network',
  );

  async function flip(row: ToggleRow, value: boolean) {
    if (busy) return;
    busy = true;
    error = '';
    try {
      settings = await setSetting(row.id, value);
      /* The host list and the session log are both derived from these, so the
         board above has to be re-read or it would still show the old policy. */
      view = await privacyView();
      onChanged();
    } catch (cause) {
      error = String(cause);
    } finally {
      busy = false;
    }
  }

  /**
   * The headline facts.
   *
   * Every value here is a property of the build, not a setting: there is no
   * telemetry code, no analytics code, and no automatic crash upload, so these
   * are constants rather than state read from anywhere. Credentials is the one
   * that varies, and it comes from the core.
   */
  const FACTS = [
    { label: 'Telemetry', value: 'None' },
    { label: 'Analytics', value: 'None' },
    { label: 'Crash uploads', value: 'Manual' },
    { label: 'Credentials', value: 'OS protected' },
  ];
</script>

{#if view}
  <Page title="Privacy" summary={view.summary}>
    {#if view.offline}
      <div transition:fade={{ duration: NORMAL }}>
        <Notice glyph={WifiOff}>Offline mode is on. Nothing is contacted.</Notice>
      </div>
    {/if}

    <div class="facts material-light">
      {#each FACTS as fact, index}
        <div
          class="fact"
          in:fly={{ y: 8, duration: NORMAL, delay: index * STAGGER, easing: EASE }}
        >
          <span class="fact-label">{fact.label}</span>
          <span class="fact-value">{fact.value}</span>
        </div>
      {/each}
    </div>

    {#if error}
      <Notice tone="danger" onDismiss={() => (error = '')}>{error}</Notice>
    {/if}

    {#if privacyToggles.length}
    <Section label="What Fathom may do" summary="{privacyToggles.length} settings" material>
      {#each privacyToggles as row (row.id)}
        <Row
          title={row.label}
          detail={row.detail}
          badge={row.overriddenBy ?? ''}
          dimmed={Boolean(row.overriddenBy)}
        >
          <svelte:fragment slot="control">
            <Switch
              checked={row.value}
              disabled={busy || Boolean(row.overriddenBy)}
              label={row.label}
              onChange={(value) => void flip(row, value)}
            />
          </svelte:fragment>
        </Row>
      {/each}
    </Section>
    {/if}

    <Section
      label="What Fathom does"
      summary="{view.guarantees.length} guarantees"
      collapsible
      material
      bind:open={guaranteesOpen}
    >
      <ul class="claims">
        {#each view.guarantees as claim}
          <li>
            <span class="tick"><Check size={12} /></span>
            <div>
              <span class="claim">{claim.label}</span>
              <p class="claim-detail">{claim.detail}</p>
            </div>
          </li>
        {/each}
      </ul>
    </Section>

    <Section
      label="Where it can connect"
      summary="{view.destinations.filter((d) => d.allowed).length} of {view.destinations
        .length} allowed"
      collapsible
      material
      bind:open={hostsOpen}
    >
      <table>
        <tbody>
          {#each view.destinations as destination}
            <tr class:denied={!destination.allowed}>
              <td class="host mono">{destination.host}</td>
              <td class="reason">{destination.reason}</td>
              <td class="state">
                <span class="status" class:on={destination.allowed}>
                  {destination.allowed ? 'Allowed' : 'Blocked'}
                </span>
              </td>
            </tr>
          {/each}
        </tbody>
      </table>
    </Section>

    <Section
      label="Network activity"
      summary={view.activity.length ? `${view.activity.length} recorded` : 'Nothing yet'}
      collapsible
      material
      bind:open={activityOpen}
    >
      {#if view.activity.length}
        <table>
          <tbody>
            {#each view.activity as entry}
              <tr>
                <td class="host mono">{entry.host}</td>
                <td class="reason">{entry.purpose}</td>
                <td class="state"><span class="outcome {entry.tone}">{entry.outcome}</span></td>
              </tr>
            {/each}
          </tbody>
        </table>
      {:else}
        <EmptyState
          compact
          icon={Radio}
          title="No network activity"
          detail="Nothing has been contacted this session."
        />
      {/if}
    </Section>
  </Page>
{/if}

<style>
  .facts {
    --surface-radius: var(--radius-lg);
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
    gap: 1px;
    overflow: hidden;
    padding: 2px;
  }

  .fact {
    display: flex;
    flex-direction: column;
    gap: var(--s-1);
    padding: 13px 15px;
    background: var(--control-fill-faint);
    border-radius: max(var(--radius-inner-min), calc(var(--radius-lg) - 2px));
  }

  .fact-label {
    font-size: var(--type-label-size);
    font-weight: var(--type-label-weight);
    letter-spacing: var(--type-label-tracking);
    color: var(--color-text-secondary);
  }

  .fact-value {
    font-size: var(--type-heading-size);
    font-weight: var(--type-heading-weight);
    letter-spacing: var(--type-heading-tracking);
    color: var(--color-text);
  }

  .claims {
    list-style: none;
    margin: 0;
    padding: 0;
  }

  .claims li {
    display: flex;
    align-items: flex-start;
    gap: var(--space-3);
    padding: var(--space-3) var(--space-4);
    box-shadow: 0 calc(-1 * var(--hairline)) 0 var(--control-hairline);
  }

  .claims li:first-child {
    box-shadow: none;
  }

  /* A small check in the success tone, centred on the claim's first line. */
  .tick {
    display: grid;
    place-items: center;
    height: 19px;
    color: var(--color-success-text);
    flex-shrink: 0;
  }

  .claim {
    font-size: var(--type-body-size);
    font-weight: var(--type-heading-weight);
    letter-spacing: var(--type-heading-tracking);
    line-height: 19px;
  }

  .claim-detail {
    margin: 2px 0 0;
    font-size: var(--type-secondary-size);
    line-height: var(--type-secondary-leading);
    color: var(--color-text-secondary);
  }

  table {
    width: 100%;
    border-collapse: collapse;
    font-size: var(--type-secondary-size);
  }

  tr {
    box-shadow: 0 calc(-1 * var(--hairline)) 0 var(--control-hairline);
  }

  tr:first-child {
    box-shadow: none;
  }

  tr.denied {
    opacity: 0.5;
  }

  td {
    padding: 10px var(--space-2);
    vertical-align: top;
  }

  td:first-child {
    padding-left: var(--space-4);
  }

  td:last-child {
    padding-right: var(--space-4);
  }

  .host {
    width: 32%;
    font-size: var(--type-caption-size);
    line-height: 19px;
    color: var(--color-text);
    overflow-wrap: anywhere;
  }

  .reason {
    color: var(--text-muted);
    padding-right: 12px;
  }

  .state {
    width: 92px;
    text-align: right;
    white-space: nowrap;
  }

  /* Status is a small dot and a word: the dot carries the colour, the word
     stays neutral. */
  .status,
  .outcome {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    font-size: var(--type-caption-size);
    line-height: 19px;
    color: var(--color-text-secondary);
  }

  .status::before,
  .outcome::before {
    content: '';
    width: 6px;
    height: 6px;
    border-radius: var(--radius-pill);
    background-color: var(--dot, var(--color-text-disabled));
  }

  .status.on,
  .outcome.ok {
    --dot: var(--color-success-text);
  }

  .outcome.denied {
    --dot: var(--color-warning-text);
  }

  .outcome.failed {
    --dot: var(--color-danger-text);
  }
</style>

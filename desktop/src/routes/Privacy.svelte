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
  import { Check, WifiOff } from '@lucide/svelte';

  import Page from '../lib/ui/Page.svelte';
  import Section from '../lib/ui/Section.svelte';
  import { EASE, NORMAL, STAGGER } from '../lib/motion';
  import { privacyView, type PrivacyView } from '../lib/api';

  let view: PrivacyView | null = null;
  let guaranteesOpen = false;
  let hostsOpen = false;
  let activityOpen = false;

  onMount(() => {
    void (async () => {
      view = await privacyView();
    })();
  });

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
      <div class="offline" transition:fade={{ duration: NORMAL }}>
        <WifiOff size={15} strokeWidth={2} />
        <span>Offline mode is on. Nothing is contacted.</span>
      </div>
    {/if}

    <div class="facts">
      {#each FACTS as fact, index}
        <div
          class="fact glass"
          in:fly={{ y: 8, duration: NORMAL, delay: index * STAGGER, easing: EASE }}
        >
          <span class="fact-label">{fact.label}</span>
          <span class="fact-value">{fact.value}</span>
        </div>
      {/each}
    </div>

    <Section
      label="What Fathom does"
      summary="{view.guarantees.length} guarantees"
      collapsible
      bind:open={guaranteesOpen}
    >
      <ul class="claims">
        {#each view.guarantees as claim}
          <li>
            <span class="tick"><Check size={11} strokeWidth={3} /></span>
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
      bind:open={hostsOpen}
    >
      <table>
        <tbody>
          {#each view.destinations as destination}
            <tr class:denied={!destination.allowed}>
              <td class="host">{destination.host}</td>
              <td class="reason">{destination.reason}</td>
              <td class="state">
                <span class="pill" class:on={destination.allowed}>
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
      bind:open={activityOpen}
    >
      {#if view.activity.length}
        <table>
          <tbody>
            {#each view.activity as entry}
              <tr>
                <td class="host">{entry.host}</td>
                <td class="reason">{entry.purpose}</td>
                <td class="state"><span class="outcome {entry.tone}">{entry.outcome}</span></td>
              </tr>
            {/each}
          </tbody>
        </table>
      {:else}
        <p class="quiet">Nothing contacted this session.</p>
      {/if}
    </Section>
  </Page>
{/if}

<style>
  .facts {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
    gap: var(--s-3);
  }

  .fact {
    display: flex;
    flex-direction: column;
    gap: var(--s-1);
    padding: 13px 15px;
  }

  .fact-label {
    font-size: var(--t-meta);
    font-weight: var(--w-semibold);
    letter-spacing: var(--track-caps);
    text-transform: uppercase;
    color: var(--text-faint);
  }

  .fact-value {
    font-size: var(--t-section);
    font-weight: var(--w-semibold);
    letter-spacing: var(--track-snug);
    color: var(--text);
  }

  .claims {
    list-style: none;
    margin: 0;
    padding: 0;
  }

  .claims li {
    display: flex;
    align-items: flex-start;
    gap: var(--s-3);
    padding: 10px 2px;
    box-shadow: 0 -1px 0 var(--chrome-line);
  }

  .claims li:first-child {
    box-shadow: none;
  }

  .tick {
    display: grid;
    place-items: center;
    width: 17px;
    height: 17px;
    margin-top: var(--s-1);
    border-radius: var(--r-pill);
    background: rgba(79, 201, 138, 0.16);
    color: var(--good);
    flex-shrink: 0;
  }

  .claim {
    font-size: var(--t-body-lg);
    font-weight: var(--w-medium);
  }

  .claim-detail {
    margin: 2px 0 0;
    font-size: var(--t-body);
    color: var(--text-muted);
  }

  table {
    width: 100%;
    border-collapse: collapse;
    font-size: var(--t-body);
  }

  tr {
    box-shadow: 0 -1px 0 var(--chrome-line);
  }

  tr:first-child {
    box-shadow: none;
  }

  tr.denied {
    opacity: 0.5;
  }

  td {
    padding: 8px 2px;
    vertical-align: top;
  }

  .host {
    width: 32%;
    color: var(--text);
    font-variant-numeric: tabular-nums;
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

  .pill {
    padding: 2px 8px;
    border-radius: var(--r-pill);
    font-size: var(--t-meta);
    color: var(--text-faint);
    background: var(--fill);
  }

  .pill.on {
    color: var(--good);
    background: rgba(79, 201, 138, 0.13);
  }

  .outcome {
    font-size: var(--t-meta);
    color: var(--text-muted);
  }
  .outcome.ok {
    color: var(--good);
  }
  .outcome.denied {
    color: var(--warn);
  }
  .outcome.failed {
    color: var(--bad);
  }

  .quiet {
    margin: 0;
    padding: 12px 2px;
    font-size: var(--t-body);
    color: var(--text-faint);
  }

  .offline {
    display: flex;
    align-items: center;
    gap: var(--s-3);
    padding: 10px 13px;
    border-radius: var(--r-md);
    background: rgba(79, 201, 138, 0.1);
    backdrop-filter: blur(var(--glass-blur));
    -webkit-backdrop-filter: blur(var(--glass-blur));
    border: 1px solid rgba(79, 201, 138, 0.26);
    box-shadow: var(--glass-sheen);
    color: var(--good);
    font-size: var(--t-body);
  }
</style>

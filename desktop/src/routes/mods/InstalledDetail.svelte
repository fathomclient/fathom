<!--
  One installed mod, as a page.

  Everything the launcher knows about the jar this profile loads: where it
  came from, whether it fits, what it needs and what needs it, earlier builds
  that can be put back, and whether its file is intact. And the things that
  can be done to it: switch it off, update it, fetch it again, put an earlier
  build back, show the file, remove it.

  Mods are Java code loaded when Minecraft starts. Nothing here takes effect in
  a game that is already running, and the page says so rather than implying a
  change is live.
-->
<script lang="ts">
  import { onMount } from 'svelte';
  import { fade } from 'svelte/transition';
  import { ArrowLeft, FolderOpen, Trash2, RefreshCw, Wrench, RotateCcw, Check, CircleAlert } from '@lucide/svelte';
  import { ICON } from '../../lib/metrics.generated';
  import { MOTION_HOVER } from '../../lib/motion';
  import Page from '../../lib/ui/Page.svelte';
  import Section from '../../lib/ui/Section.svelte';
  import Switch from '../../lib/ui/Switch.svelte';
  import Button from '../../lib/ui/Button.svelte';
  import Notice from '../../lib/ui/Notice.svelte';
  import VerdictBadge from './VerdictBadge.svelte';
  import { formatWhen } from './format';
  import {
    installedModDetail,
    restoreModBuild,
    revealModFile,
    type InstallArgs,
    type InstalledDetail,
  } from '../../lib/api';

  export let id: string;
  export let busy = false;
  /** Bumped by the parent after anything changed, so the page re-reads. */
  export let revision = 0;
  export let onBack: () => void;
  export let onToggle: (enabled: boolean) => void;
  export let onRemove: () => void;
  export let onInstall: (args: InstallArgs, name: string) => void;
  export let onChanged: (note: string) => void;

  let detail: InstalledDetail | null = null;
  let problem = '';

  async function load() {
    try {
      detail = await installedModDetail(id);
      problem = '';
    } catch (cause) {
      problem = String(cause instanceof Error ? cause.message : cause);
    }
  }

  onMount(() => void load());
  let seen = revision;
  $: if (revision !== seen) {
    seen = revision;
    void load();
  }

  async function restore(sha1: string) {
    try {
      onChanged(await restoreModBuild(id, sha1));
      await load();
    } catch (cause) {
      problem = String(cause instanceof Error ? cause.message : cause);
    }
  }

  async function reveal() {
    try {
      await revealModFile(id);
    } catch (cause) {
      problem = String(cause instanceof Error ? cause.message : cause);
    }
  }

  function reference() {
    return detail?.provider && detail.project
      ? { provider: detail.provider, id: detail.project }
      : null;
  }

  function update() {
    const ref = reference();
    if (ref && detail) onInstall({ reference: ref, replacing: detail.id }, detail.name);
  }

  function repair() {
    const ref = reference();
    if (ref && detail) onInstall({ reference: ref, replacing: detail.id, repair: true }, detail.name);
  }

  $: external = detail?.kind === 'external';
  $: title = detail?.name ?? '';
  $: summary = detail ? [detail.sourceLabel, detail.version].filter(Boolean).join(' · ') : '';
</script>

<Page {title} {summary} wide>
  <svelte:fragment slot="lead">
    <button class="ui-capsule small material-clear material-interactive" on:click={onBack}>
      <ArrowLeft size={ICON.inline} />
      Mods
    </button>
  </svelte:fragment>

  <svelte:fragment slot="actions">
    {#if detail}
      <Button onClick={() => void reveal()} disabled={detail.missing}>
        <FolderOpen size={ICON.inline} />
        Show file
      </Button>
      {#if external}
        <Button disabled={busy} onClick={onRemove}>
          <Trash2 size={ICON.inline} />
          Remove
        </Button>
      {/if}
    {/if}
  </svelte:fragment>

  {#if problem}
    <div transition:fade={{ duration: MOTION_HOVER }}><Notice tone="danger">{problem}</Notice></div>
  {/if}

  {#if detail}
    {#if detail.gameRunning}
      <Notice>Minecraft is running. Changes here take effect the next time it starts.</Notice>
    {/if}

    {#if detail.missing || detail.damaged}
      <Notice tone="danger">
        {detail.missing
          ? 'Its file is no longer on this computer, so it will not be loaded.'
          : 'Its file is damaged — it no longer matches the file that was installed — so it will not be loaded.'}
        {#if detail.repairable}
          <span class="inline-action"><Button small onClick={repair}><Wrench size={ICON.inline} /> Fetch it again</Button></span>
        {:else}
          Add the file again with Add JAR.
        {/if}
      </Notice>
    {/if}

    {#if detail.disabledReason}
      <Notice tone="warning">{detail.disabledReason}</Notice>
    {/if}

    <div class="frame">
      <div class="main">
        <Section label="In this profile" material>
          <div class="row">
            <div class="text">
              <span class="label">{detail.enabled ? 'On' : 'Off'}</span>
              <span class="quiet">
                {#if detail.kind === 'builtin'}
                  Fathom's own client. Its modules — HUD, Keystrokes, Zoom and the rest — are switched on and
                  configured in game, from the Hub (Right Shift).
                {:else if detail.kind === 'component'}
                  A component Fathom installs and keeps up to date for this profile.
                {:else}
                  A Java mod. It is loaded when Minecraft starts; switching it takes effect at the next start.
                {/if}
              </span>
            </div>
            <Switch
              checked={detail.enabled}
              disabled={busy || detail.kind === 'builtin'}
              label={detail.name}
              onChange={(value) => onToggle(value)}
            />
          </div>
          {#if detail.compatibility}
            <div class="row">
              <div class="text">
                <span class="label">Compatibility</span>
                <span class="quiet">{detail.compatibility.detail}</span>
              </div>
              <VerdictBadge verdict={detail.compatibility.verdict} detail={detail.compatibility.detail} />
            </div>
          {/if}
          {#if detail.updatable || detail.repairable}
            <div class="row">
              <div class="text">
                <span class="label">Updates</span>
                <span class="quiet">Fathom can check {detail.sourceLabel.replace('From ', '').replace('Your file · identified on ', '')} for a newer build for this profile.</span>
              </div>
              <Button disabled={busy} onClick={update}><RefreshCw size={ICON.inline} /> Check for update</Button>
            </div>
          {/if}
        </Section>

        {#if detail.issues.length}
          <Section label="Problems" material>
            <ul class="list">
              {#each detail.issues as issue}<li class="issue"><CircleAlert size={ICON.inline} /> {issue}</li>{/each}
            </ul>
          </Section>
        {/if}

        <Section label="Dependencies" material>
          <div class="deps">
            <div>
              <p class="label">Needs</p>
              {#if detail.requires.length}
                <ul class="list">
                  {#each detail.requires as requirement}
                    <li>
                      <span>{requirement.name}</span>
                      <span class="state" class:bad={!requirement.installed || !requirement.enabled}>
                        {!requirement.installed ? 'missing' : requirement.enabled ? 'installed' : 'switched off'}
                      </span>
                    </li>
                  {/each}
                </ul>
              {:else}
                <p class="quiet">
                  {external && !detail.provider
                    ? 'Not known: nothing recorded what this file needs.'
                    : 'Nothing.'}
                </p>
              {/if}
            </div>
            <div>
              <p class="label">Needed by</p>
              {#if detail.requiredBy.length}
                <ul class="list">{#each detail.requiredBy as name}<li>{name}</li>{/each}</ul>
              {:else}
                <p class="quiet">Nothing in this profile.</p>
              {/if}
            </div>
          </div>
        </Section>

        {#if detail.earlier.length}
          <Section label="Earlier builds" summary="Kept so a bad update can be undone" material>
            {#each detail.earlier as build (build.sha1)}
              <div class="row">
                <div class="text">
                  <span class="label tnum">{build.version ?? build.fileName}</span>
                  <span class="quiet">
                    Used until {build.replacedAt ? formatWhen(new Date(build.replacedAt * 1000).toISOString()) : 'an update'}
                    {build.available ? '' : ' · its file is gone'}
                  </span>
                </div>
                <Button small disabled={busy || !build.available} onClick={() => void restore(build.sha1)}>
                  <RotateCcw size={ICON.inline} /> Put back
                </Button>
              </div>
            {/each}
          </Section>
        {/if}
      </div>

      <aside class="side">
        <dl class="facts">
          <dt>Version</dt><dd class="tnum">{detail.version ?? '—'}</dd>
          <dt>Source</dt><dd>{detail.sourceLabel}</dd>
          <dt>File</dt><dd class="mono">{detail.fileName}</dd>
          <dt>SHA-1</dt><dd class="mono">{detail.fingerprint}…</dd>
          {#if detail.license}<dt>Licence</dt><dd>{detail.license}</dd>{/if}
          {#if detail.loaders.length}<dt>Built for</dt><dd>{detail.loaders.join(', ')}</dd>{/if}
          {#if detail.minecraft.length}
            <dt>Minecraft</dt>
            <dd>{detail.minecraft.length > 4 ? `${detail.minecraft.length} versions` : detail.minecraft.join(', ')}</dd>
          {/if}
          <dt>File check</dt>
          <dd>
            {#if detail.missing}Missing{:else if detail.damaged}Damaged{:else}<Check size={12} /> Intact{/if}
          </dd>
        </dl>
        {#if detail.summary}<p class="quiet">{detail.summary}</p>{/if}
      </aside>
    </div>
  {/if}
</Page>

<style>
  .frame {
    display: grid;
    grid-template-columns: minmax(0, 1fr) minmax(0, 260px);
    gap: var(--space-6);
    align-items: start;
  }

  @media (max-width: 980px) {
    .frame {
      grid-template-columns: minmax(0, 1fr);
    }
  }

  .main {
    display: flex;
    flex-direction: column;
    gap: var(--space-4);
    min-width: 0;
  }

  .row {
    display: flex;
    align-items: center;
    gap: var(--space-4);
    padding: var(--space-3) var(--space-4);
    box-shadow: 0 calc(-1 * var(--hairline)) 0 var(--control-hairline);
  }

  .row:first-child {
    box-shadow: none;
  }

  .text {
    flex: 1;
    min-width: 0;
    display: flex;
    flex-direction: column;
    gap: 2px;
  }

  .label {
    margin: 0;
    font-size: var(--type-body-size);
    font-weight: var(--type-heading-weight);
    color: var(--color-text);
  }

  .quiet {
    margin: 0;
    font-size: var(--type-secondary-size);
    line-height: var(--type-secondary-leading);
    color: var(--color-text-secondary);
  }

  .deps {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
    gap: var(--space-4);
    padding: var(--space-4);
  }

  .deps .label {
    font-size: var(--type-caption-size);
    color: var(--color-text-tertiary);
    margin-bottom: var(--space-2);
  }

  .list {
    margin: 0;
    padding: 0;
    list-style: none;
    display: flex;
    flex-direction: column;
    gap: var(--space-1);
    font-size: var(--type-secondary-size);
    color: var(--color-text-secondary);
  }

  .list li {
    display: flex;
    justify-content: space-between;
    gap: var(--space-3);
  }

  .issue {
    justify-content: flex-start !important;
    align-items: flex-start;
    padding: var(--space-2) var(--space-4);
    color: var(--color-warning-text);
  }

  .state {
    font-size: var(--type-caption-size);
    color: var(--color-text-tertiary);
  }

  .state.bad {
    color: var(--color-warning-text);
  }

  .side {
    display: flex;
    flex-direction: column;
    gap: var(--space-4);
  }

  .facts {
    display: grid;
    grid-template-columns: auto minmax(0, 1fr);
    gap: var(--space-1) var(--space-4);
    margin: 0;
    font-size: var(--type-secondary-size);
  }

  dt {
    color: var(--color-text-tertiary);
  }

  dd {
    margin: 0;
    color: var(--color-text-secondary);
    overflow-wrap: anywhere;
  }

  .mono {
    font-family: var(--font-mono, ui-monospace, monospace);
    font-size: var(--type-caption-size);
  }

  .inline-action {
    display: inline-flex;
    margin-left: var(--space-2);
    vertical-align: middle;
  }
</style>

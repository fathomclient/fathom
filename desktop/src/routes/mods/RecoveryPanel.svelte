<!--
  After a launch goes wrong: the facts, and the ways back.

  Three kinds of fact, kept apart on purpose, because only one of them is
  evidence about a particular mod:

  - what changed since the last launch that worked (correlation, not cause);
  - what is broken on disk or in the set (certain);
  - what the game's own logs say, quoted, with the mods those lines name.

  Nothing here says "this mod crashed your game" unless a log line names it.
  The ways back never delete anything: a launch without external mods changes
  nothing at all, and restoring the last working set switches mods off rather
  than removing them.
-->
<script lang="ts">
  import { onMount } from 'svelte';
  import { fade } from 'svelte/transition';
  import { Play, RotateCcw, Wrench, ShieldCheck } from '@lucide/svelte';
  import { ICON } from '../../lib/metrics.generated';
  import { MOTION_HOVER } from '../../lib/motion';
  import Section from '../../lib/ui/Section.svelte';
  import Button from '../../lib/ui/Button.svelte';
  import Notice from '../../lib/ui/Notice.svelte';
  import { formatWhen } from './format';
  import {
    modRecovery,
    restoreLastGoodMods,
    type ChangeKind,
    type ModRecoveryView,
    type RestoreReport,
  } from '../../lib/api';

  export let revision = 0;
  export let busy = false;
  export let onSafeLaunch: () => void;
  export let onChanged: (note: string) => void;
  export let onOpenMod: (id: string) => void;

  let view: ModRecoveryView | null = null;
  let problem = '';
  let restored: RestoreReport | null = null;
  let restoring = false;

  async function load() {
    try {
      view = await modRecovery();
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

  async function restore() {
    if (restoring) return;
    restoring = true;
    try {
      restored = await restoreLastGoodMods();
      const count =
        restored.switchedOff.length + restored.reverted.length + restored.switchedOn.length + restored.reAdded.length;
      onChanged(`Restored the last working mod set (${count} ${count === 1 ? 'change' : 'changes'}).`);
      await load();
    } catch (cause) {
      problem = String(cause instanceof Error ? cause.message : cause);
    } finally {
      restoring = false;
    }
  }

  function when(seconds: number | null | undefined): string {
    return seconds ? formatWhen(new Date(seconds * 1000).toISOString()) : '';
  }

  function describe(change: ChangeKind): string {
    switch (change.kind) {
      case 'added':
        return 'added';
      case 'removed':
        return 'removed';
      case 'enabled':
        return 'switched on';
      case 'disabled':
        return 'switched off';
      case 'updated':
        return change.from || change.to ? `updated ${change.from ?? '?'} → ${change.to ?? '?'}` : 'updated';
    }
  }

  $: last = view?.lastLaunch ?? null;
  $: failed = last?.outcome.state === 'failed';
  $: nothingToSay =
    !!view &&
    !failed &&
    view.changes.length === 0 &&
    view.missing.length === 0 &&
    view.damaged.length === 0 &&
    view.problems.length === 0;
</script>

{#if problem}
  <Notice tone="danger">{problem}</Notice>
{/if}

{#if view}
  <Section label="Last launch" material>
    <div class="body">
      {#if !last}
        <p>No launch of {view.profileName} has been recorded yet.</p>
      {:else if last.outcome.state === 'failed'}
        <p class="bad">
          The last launch, {when(last.at)}, did not work{last.safe ? ' (without external mods)' : ''}:
          {last.outcome.message}
        </p>
      {:else if last.outcome.state === 'worked'}
        <p>The last launch, {when(last.at)}, worked{last.safe ? ', without external mods' : ''}.</p>
      {:else}
        <p>The last launch, {when(last.at)}, was not watched to the end (Fathom was closed while it ran).</p>
      {/if}

      {#if failed && last?.safe}
        <p class="quiet">
          It failed even without external mods, so mods are unlikely to be the cause. The log lines below, and the
          Recovery page, are the places to look.
        </p>
      {/if}

      <div class="actions">
        {#if view.externalEnabled > 0}
          <Button disabled={busy || view.gameRunning} onClick={onSafeLaunch}>
            <Play size={ICON.inline} />
            Launch without external mods
          </Button>
        {/if}
        {#if view.canRestore}
          <Button disabled={busy || restoring} onClick={() => void restore()}>
            <RotateCcw size={ICON.inline} />
            {restoring ? 'Restoring…' : 'Restore last working mods'}
          </Button>
        {/if}
      </div>
      {#if view.externalEnabled > 0}
        <p class="quiet">
          Launching without external mods starts the game with Fathom's own components only, this once. Nothing
          is deleted or switched off.
          {#if view.canRestore}
            Restoring switches off mods added since {when(view.lastGoodAt)} and puts updated ones back to the build
            that worked; nothing is deleted, and each can be switched back on.
          {/if}
        </p>
      {/if}
    </div>
  </Section>

  {#if restored}
    <div transition:fade={{ duration: MOTION_HOVER }}>
      <Notice tone="success">
        {#if restored.switchedOff.length}Switched off: {restored.switchedOff.join(', ')}. {/if}
        {#if restored.reverted.length}Put back to the working build: {restored.reverted.join(', ')}. {/if}
        {#if restored.switchedOn.length}Switched back on: {restored.switchedOn.join(', ')}. {/if}
        {#if restored.reAdded.length}Added back: {restored.reAdded.join(', ')}. {/if}
        {#if restored.unavailable.length}Could not put back (file gone): {restored.unavailable.join(', ')}.{/if}
      </Notice>
    </div>
  {/if}

  {#if view.missing.length || view.damaged.length}
    <Section label="Files that will not load" material>
      {#each [...view.missing.map((m) => ({ ...m, why: 'missing' })), ...view.damaged.map((m) => ({ ...m, why: 'damaged' }))] as file (file.id)}
        <div class="row">
          <button class="name" on:click={() => onOpenMod(file.id)}>{file.name}</button>
          <span class="quiet">{file.why === 'missing' ? 'Its file is gone.' : 'Its file is damaged.'}</span>
          {#if file.repairable}
            <Button small disabled={busy} onClick={() => onOpenMod(file.id)}>
              <Wrench size={ICON.inline} /> Repair
            </Button>
          {/if}
        </div>
      {/each}
    </Section>
  {/if}

  {#if view.changes.length || (failed && view.lastGoodAt === null)}
    <Section
      label="Changed since the last launch that worked"
      summary={view.lastGoodAt ? when(view.lastGoodAt) : ''}
      material
    >
      {#if view.lastGoodAt === null}
        <p class="body quiet">
          No launch of this profile has been recorded as working yet, so Fathom cannot say what changed.
        </p>
      {:else}
        {#each view.changes as change (change.id)}
          <div class="row">
            <button class="name" on:click={() => onOpenMod(change.id)}>{change.name}</button>
            <span class="quiet">{describe(change.change)}</span>
            {#if view.namedByLogs.includes(change.id)}<span class="named">named in the log</span>{/if}
          </div>
        {/each}
        <p class="body quiet">
          Changes are what is different, not what is wrong. A mod is only pointed at when the game's own log
          names it.
        </p>
      {/if}
    </Section>
  {/if}

  {#if failed}
    <Section label="What the game's logs say" material>
      {#if view.evidence.length}
        <ul class="evidence">
          {#each view.evidence as item}
            <li>
              <span class="source">{item.source}</span>
              <code>{item.line}</code>
              {#if item.names.length}<span class="named">names {item.names.join(', ')}</span>{/if}
            </li>
          {/each}
        </ul>
      {:else}
        <p class="body quiet">
          The logs of that launch do not contain the lines Fathom looks for (mod loading, mixin and missing-class
          errors), so they do not point at any mod. The full logs are on the Logs page.
        </p>
      {/if}
    </Section>
  {/if}

  {#if nothingToSay}
    <div class="ok"><ShieldCheck size={ICON.inline} /> Nothing in this profile's mods needs attention.</div>
  {/if}
{/if}

<style>
  .body {
    display: flex;
    flex-direction: column;
    gap: var(--space-3);
    padding: var(--space-4);
    margin: 0;
  }

  p {
    margin: 0;
    font-size: var(--type-body-size);
    line-height: var(--type-body-leading);
    color: var(--color-text-secondary);
  }

  .bad {
    color: var(--color-text);
  }

  .quiet {
    font-size: var(--type-secondary-size);
    color: var(--color-text-tertiary);
  }

  .actions {
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-2);
  }

  .row {
    display: flex;
    align-items: center;
    gap: var(--space-3);
    padding: var(--space-2) var(--space-4);
    box-shadow: 0 calc(-1 * var(--hairline)) 0 var(--control-hairline);
  }

  .row:first-child {
    box-shadow: none;
  }

  .name {
    padding: 0;
    font-size: var(--type-body-size);
    font-weight: var(--type-heading-weight);
    color: var(--color-text);
    text-align: left;
  }

  .name:hover {
    text-decoration: underline;
    text-underline-offset: 3px;
  }

  .row .quiet {
    flex: 1;
  }

  .named {
    font-size: var(--type-caption-size);
    color: var(--color-warning-text);
    white-space: nowrap;
  }

  .evidence {
    margin: 0;
    padding: var(--space-3) var(--space-4);
    list-style: none;
    display: flex;
    flex-direction: column;
    gap: var(--space-2);
    font-size: var(--type-secondary-size);
    color: var(--color-text-secondary);
  }

  .evidence li {
    display: flex;
    flex-direction: column;
    gap: 2px;
  }

  .source {
    font-size: var(--type-caption-size);
    color: var(--color-text-tertiary);
    text-transform: capitalize;
  }

  code {
    font-family: var(--font-mono, ui-monospace, monospace);
    font-size: var(--type-caption-size);
    color: var(--color-text-secondary);
    overflow-wrap: anywhere;
    white-space: pre-wrap;
  }

  .ok {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    font-size: var(--type-secondary-size);
    color: var(--color-text-secondary);
  }
</style>

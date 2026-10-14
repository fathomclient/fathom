<!--
  One mod from an index, as a page.

  Opened from Browse. Everything a person needs to decide whether to install
  it: who made it, what the compatibility resolver found and why, the builds
  for this Minecraft version (including the ones that will not load, with the
  reason), what each needs, screenshots, and the author's own description.

  The header renders from the search result at once; the rest arrives from
  Rust when the project and its builds have been read.
-->
<script lang="ts">
  import { onMount } from 'svelte';
  import { fade } from 'svelte/transition';
  import {
    ArrowLeft,
    Download,
    ExternalLink,
    Code,
    Bug,
    RefreshCw,
    Trash2,
    Check,
    ChevronDown,
    X,
  } from '@lucide/svelte';
  import { ICON } from '../../lib/metrics.generated';
  import { EASE, MOTION_HOVER, MOTION_PAGE } from '../../lib/motion';
  import Page from '../../lib/ui/Page.svelte';
  import Section from '../../lib/ui/Section.svelte';
  import Button from '../../lib/ui/Button.svelte';
  import Notice from '../../lib/ui/Notice.svelte';
  import RemoteImage from '../../lib/ui/RemoteImage.svelte';
  import RichText from '../../lib/ui/RichText.svelte';
  import VerdictBadge from './VerdictBadge.svelte';
  import { parseDescription } from '../../lib/richtext';
  import { formatCount, formatWhen } from './format';
  import {
    catalogDetail,
    catalogOpenLink,
    type CatalogDetail,
    type CatalogEntry,
    type CatalogVersion,
  } from '../../lib/api';

  export let entry: CatalogEntry;
  export let profileName: string;
  export let minecraft = '1.8.9';
  export let loader = 'Legacy Fabric';
  /** Bumped by the parent after an install, so the page re-reads its state. */
  export let revision = 0;
  export let busy = false;
  export let onBack: () => void;
  export let onInstall: (versionId: string | null) => void;
  export let onUpdate: (installedId: string) => void;
  export let onRemove: (installedId: string, name: string) => void;

  let detail: CatalogDetail | null = null;
  let problem = '';
  let loading = true;
  let showIncompatible = false;
  let expanded: string | null = null;
  let viewing: number | null = null;

  async function load() {
    loading = true;
    problem = '';
    try {
      detail = await catalogDetail(entry.reference);
    } catch (cause) {
      problem = String(cause instanceof Error ? cause.message : cause);
    } finally {
      loading = false;
    }
  }

  onMount(() => void load());
  let seen = revision;
  $: if (revision !== seen) {
    seen = revision;
    void load();
  }

  $: current = detail?.entry ?? entry;
  $: blocks = detail?.description ? parseDescription(detail.description, detail.descriptionFormat) : [];
  $: installable = detail?.versions.filter((v) => v.verdict !== 'incompatible') ?? [];
  $: incompatible = detail?.versions.filter((v) => v.verdict === 'incompatible') ?? [];
  $: install = current.install;
  $: canInstall =
    !!detail && detail.verdict.verdict !== 'incompatible' && !detail.withheld && install.state === 'not-installed';

  function link(which: 'page' | 'source' | 'issues') {
    void catalogOpenLink(entry.reference, which).catch((cause) => (problem = String(cause)));
  }

  function follow(href: string) {
    void catalogOpenLink(entry.reference, 'description', href).catch(
      (cause) => (problem = String(cause instanceof Error ? cause.message : cause)),
    );
  }

  function channelLabel(version: CatalogVersion): string {
    return version.channel === 'release' ? '' : version.channel === 'beta' ? 'Beta' : 'Alpha';
  }

  function size(bytes: number | null): string {
    if (!bytes) return '';
    if (bytes >= 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
    return `${Math.max(1, Math.round(bytes / 1024))} KB`;
  }

  function onKey(event: KeyboardEvent) {
    const at = viewing;
    if (at === null || !detail) return;
    const count = detail.gallery.length;
    if (event.key === 'Escape') viewing = null;
    if (event.key === 'ArrowRight') viewing = (at + 1) % count;
    if (event.key === 'ArrowLeft') viewing = (at - 1 + count) % count;
  }
</script>

<svelte:window on:keydown={onKey} />

<Page title={current.name} summary={current.authors.length ? `by ${current.authors.slice(0, 3).join(', ')}` : current.providerLabel} wide>
  <svelte:fragment slot="lead">
    <button class="ui-capsule small material-clear material-interactive" on:click={onBack}>
      <ArrowLeft size={ICON.inline} />
      Browse
    </button>
  </svelte:fragment>

  <svelte:fragment slot="actions">
    {#if install.state === 'installed' && current.installedId}
      <Button disabled={busy} onClick={() => onRemove(current.installedId ?? '', current.name)}>
        <Trash2 size={ICON.inline} />
        Remove
      </Button>
    {:else if install.state === 'update-available' && current.installedId}
      <Button variant="primary" disabled={busy} onClick={() => onUpdate(current.installedId ?? '')}>
        <RefreshCw size={ICON.inline} />
        Update to {install.latest}
      </Button>
    {:else if install.state === 'installing' || install.state === 'updating'}
      <Button disabled onClick={() => {}}>Installing…</Button>
    {:else if canInstall}
      <Button variant="primary" disabled={busy} onClick={() => onInstall(null)}>
        <Download size={ICON.inline} />
        {detail?.verdict.verdict === 'unverified' ? 'Review and install' : 'Install'}
      </Button>
    {/if}
  </svelte:fragment>

  {#if problem}
    <div transition:fade={{ duration: MOTION_HOVER }}>
      <Notice tone="danger">{problem}</Notice>
    </div>
  {/if}

  <div class="frame" in:fade={{ duration: MOTION_PAGE, easing: EASE }}>
    <aside class="side">
      <div class="identity">
        <span style="--image-size: 72px"
          ><RemoteImage provider={current.reference.provider} url={current.icon} alt="" fallback={current.name[0]?.toUpperCase() ?? '?'} /></span
        >
        <p class="summary">{current.summary}</p>
      </div>

      <div class="verdict">
        {#if detail}
          <VerdictBadge verdict={detail.verdict.verdict} detail={detail.verdict.headline} />
          <p>{detail.verdict.headline}</p>
          {#if detail.withheld}
            <p class="warn">Its author does not let launchers download it. Get it from its page.</p>
          {/if}
        {:else if loading}
          <VerdictBadge verdict="checking" />
          <div class="skeleton line"></div>
        {/if}
        {#if install.state === 'installed'}
          <p class="installed"><Check size={ICON.inline} /> In {profileName}{install.version ? ` · ${install.version}` : ''}</p>
        {:else if install.state === 'update-available'}
          <p class="installed">In {profileName} · {install.installed ?? 'installed'} → {install.latest} available</p>
        {:else if install.state === 'failed'}
          <p class="warn">Last attempt failed: {install.reason}</p>
        {/if}
      </div>

      <dl class="facts">
        <dt>Downloads</dt><dd class="tnum">{formatCount(current.downloads)}</dd>
        {#if current.updated}<dt>Updated</dt><dd>{formatWhen(current.updated)}</dd>{/if}
        {#if detail}<dt>Licence</dt><dd>{detail.license}</dd>{/if}
        <dt>Source</dt><dd>{current.providerLabel}</dd>
        {#if current.categories.length}
          <dt>Tags</dt><dd>{current.categories.slice(0, 6).join(', ')}</dd>
        {/if}
      </dl>

      <div class="links">
        <button class="ui-capsule small" on:click={() => link('page')}>
          <ExternalLink size={ICON.inline} /> {current.providerLabel} page
        </button>
        {#if detail?.hasSource}
          <button class="ui-capsule small" on:click={() => link('source')}>
            <Code size={ICON.inline} /> Source
          </button>
        {/if}
        {#if detail?.hasIssues}
          <button class="ui-capsule small" on:click={() => link('issues')}>
            <Bug size={ICON.inline} /> Issues
          </button>
        {/if}
      </div>
    </aside>

    <div class="main">
      {#if detail?.gallery.length}
        <div class="gallery">
          {#each detail.gallery.slice(0, 12) as shot, index}
            <button class="shot" aria-label={shot.title ?? `Screenshot ${index + 1}`} on:click={() => (viewing = index)}>
              <RemoteImage provider={current.reference.provider} url={shot.url} alt={shot.title ?? ''} kind="shot" />
            </button>
          {/each}
        </div>
      {/if}

      <Section label="Versions for Minecraft {minecraft}" summary={detail ? `${installable.length} installable${incompatible.length ? ` · ${incompatible.length} won't load` : ''}` : ''} material>
        {#if !detail && loading}
          {#each [0, 1, 2] as _}
            <div class="version skeleton-row"><div class="skeleton line short"></div><div class="skeleton line"></div></div>
          {/each}
        {:else if detail}
          {#each [...installable, ...(showIncompatible ? incompatible : [])] as version (version.versionId)}
            {@const open = expanded === version.versionId}
            <div class="version" class:muted={version.verdict === 'incompatible'}>
              <div class="version-head">
                <button class="toggle" aria-expanded={open} on:click={() => (expanded = open ? null : version.versionId)}>
                  <span class="number tnum">{version.number}</span>
                  {#if channelLabel(version)}<span class="channel">{channelLabel(version)}</span>{/if}
                  <span class="meta tnum">
                    {[formatWhen(version.published), version.loaders.join(', '), size(version.size)].filter(Boolean).join(' · ')}
                  </span>
                  <span class="chev" class:open><ChevronDown size={14} /></span>
                </button>
                <VerdictBadge verdict={version.verdict} detail={version.findings.find((f) => f.verdict === version.verdict)?.detail ?? ''} />
                {#if version.installed}
                  <span class="installed"><Check size={ICON.inline} /> Installed</span>
                {:else if version.verdict !== 'incompatible' && version.downloadable && install.state === 'not-installed'}
                  <Button small disabled={busy} onClick={() => onInstall(version.versionId)}>Install</Button>
                {/if}
              </div>
              {#if open}
                <div class="version-body" transition:fade={{ duration: MOTION_HOVER }}>
                  <ul class="findings">
                    {#each version.findings as finding}
                      <li class={finding.verdict}>{finding.detail}</li>
                    {/each}
                  </ul>
                  {#if version.dependencies.length}
                    <p class="deps-label">Needs</p>
                    <ul class="deps">
                      {#each version.dependencies as dependency}
                        <li>
                          <span class="kind">{dependency.kind}</span>
                          {dependency.name ?? dependency.key ?? 'a file its index does not name'}
                          {#if dependency.installed}<span class="meta">· installed</span>{/if}
                        </li>
                      {/each}
                    </ul>
                  {/if}
                  <p class="meta mono">{version.fileName} · {version.gameVersions}</p>
                </div>
              {/if}
            </div>
          {/each}
          {#if installable.length === 0 && !showIncompatible}
            <p class="none">
              {detail.versions.length
                ? `No build for Minecraft ${minecraft} will load on ${loader}.`
                : `No builds for Minecraft ${minecraft}.`}
            </p>
          {/if}
          {#if incompatible.length}
            <div class="more">
              <button class="ui-capsule small" on:click={() => (showIncompatible = !showIncompatible)}>
                {showIncompatible ? 'Hide' : 'Show'} {incompatible.length} that won't load
              </button>
            </div>
          {/if}
        {/if}
      </Section>

      <Section label="About" material>
        <div class="about">
          {#if blocks.length}
            <RichText {blocks} provider={current.reference.provider} onLink={follow} />
          {:else if loading}
            <div class="skeleton line"></div>
            <div class="skeleton line"></div>
            <div class="skeleton line short"></div>
          {:else}
            <p class="none">{current.summary || 'The author did not write a description.'}</p>
          {/if}
        </div>
      </Section>
    </div>
  </div>
</Page>

{#if viewing !== null && detail}
  {@const shot = detail.gallery[viewing]}
  <!-- svelte-ignore a11y-click-events-have-key-events -->
  <div class="lightbox" role="dialog" tabindex="-1" aria-modal="true" aria-label={shot.title ?? 'Screenshot'} on:click={() => (viewing = null)} transition:fade={{ duration: MOTION_HOVER }}>
    <!-- svelte-ignore a11y-click-events-have-key-events a11y-no-static-element-interactions -->
    <div class="lightbox-image" on:click|stopPropagation>
      <RemoteImage provider={current.reference.provider} url={shot.url} alt={shot.title ?? ''} kind="shot" />
    </div>
    {#if shot.title}<p class="lightbox-caption">{shot.title}</p>{/if}
    <button class="ui-icon-button lightbox-close" aria-label="Close" on:click={() => (viewing = null)}>
      <X size={ICON.inline} />
    </button>
  </div>
{/if}

<style>
  .frame {
    display: grid;
    grid-template-columns: minmax(0, 280px) minmax(0, 1fr);
    gap: var(--space-6);
    align-items: start;
  }

  @media (max-width: 980px) {
    .frame {
      grid-template-columns: minmax(0, 1fr);
    }
  }

  .side {
    position: sticky;
    top: var(--space-4);
    display: flex;
    flex-direction: column;
    gap: var(--space-5);
  }

  .identity {
    display: flex;
    flex-direction: column;
    gap: var(--space-3);
  }

  .summary,
  .verdict p,
  .none {
    margin: 0;
    font-size: var(--type-secondary-size);
    line-height: var(--type-secondary-leading);
    color: var(--color-text-secondary);
  }

  .verdict {
    display: flex;
    flex-direction: column;
    align-items: flex-start;
    gap: var(--space-2);
  }

  .warn {
    color: var(--color-warning-text) !important;
  }

  .installed {
    display: inline-flex;
    align-items: center;
    gap: var(--space-1);
    font-size: var(--type-secondary-size);
    color: var(--color-text-secondary);
    white-space: nowrap;
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

  .links {
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-2);
  }

  .links button {
    display: inline-flex;
    align-items: center;
    gap: var(--space-1);
  }

  .main {
    display: flex;
    flex-direction: column;
    gap: var(--space-5);
    min-width: 0;
  }

  .gallery {
    display: grid;
    grid-auto-flow: column;
    grid-auto-columns: minmax(220px, 32%);
    gap: var(--space-3);
    overflow-x: auto;
    padding-bottom: var(--space-2);
    scroll-snap-type: x proximity;
  }

  .shot {
    aspect-ratio: 16 / 9;
    padding: 0;
    border-radius: var(--radius-md);
    scroll-snap-align: start;
    cursor: zoom-in;
    transition: transform var(--motion-hover) var(--ease-standard);
  }

  .shot:hover {
    transform: translateY(-1px);
  }

  .shot:focus-visible {
    outline: var(--focus-ring-width) solid var(--color-focus);
    outline-offset: 2px;
  }

  .version {
    display: flex;
    flex-direction: column;
    gap: var(--space-2);
    padding: var(--space-2) var(--space-4);
    box-shadow: 0 calc(-1 * var(--hairline)) 0 var(--control-hairline);
  }

  .version:first-child {
    box-shadow: none;
  }

  .version.muted .number {
    color: var(--color-text-tertiary);
  }

  .version-head {
    display: flex;
    align-items: center;
    gap: var(--space-3);
    min-height: 36px;
  }

  .toggle {
    flex: 1;
    min-width: 0;
    display: flex;
    align-items: baseline;
    gap: var(--space-2);
    padding: 0;
    text-align: left;
    color: inherit;
  }

  .toggle:focus-visible {
    outline: var(--focus-ring-width) solid var(--color-focus);
    outline-offset: 2px;
    border-radius: var(--radius-xs);
  }

  .number {
    font-size: var(--type-body-size);
    font-weight: var(--type-heading-weight);
    color: var(--color-text);
    white-space: nowrap;
  }

  .channel {
    font-size: var(--type-caption-size);
    color: var(--color-warning-text);
  }

  .meta {
    font-size: var(--type-caption-size);
    color: var(--color-text-tertiary);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .mono {
    font-family: var(--font-mono, ui-monospace, monospace);
    white-space: normal;
    overflow-wrap: anywhere;
    margin: 0;
  }

  .chev {
    display: inline-flex;
    align-self: center;
    color: var(--color-text-tertiary);
    transition: transform var(--motion-hover) var(--ease-standard);
  }

  .chev.open {
    transform: rotate(180deg);
  }

  .version-body {
    display: flex;
    flex-direction: column;
    gap: var(--space-2);
    padding: 0 0 var(--space-2);
  }

  .findings,
  .deps {
    margin: 0;
    padding: 0;
    list-style: none;
    display: flex;
    flex-direction: column;
    gap: var(--space-1);
    font-size: var(--type-secondary-size);
    line-height: var(--type-secondary-leading);
    color: var(--color-text-secondary);
  }

  .findings li {
    padding-left: var(--space-3);
    border-left: 2px solid var(--control-hairline);
  }

  .findings li.compatible {
    border-left-color: color-mix(in srgb, var(--color-signal) 60%, transparent);
  }

  .findings li.unverified {
    border-left-color: var(--color-warning);
  }

  .findings li.incompatible {
    border-left-color: var(--color-danger);
  }

  .deps-label {
    margin: var(--space-1) 0 0;
    font-size: var(--type-caption-size);
    color: var(--color-text-tertiary);
  }

  .kind {
    display: inline-block;
    min-width: 76px;
    font-size: var(--type-caption-size);
    color: var(--color-text-tertiary);
    text-transform: capitalize;
  }

  .about {
    padding: var(--space-4);
  }

  .more {
    display: flex;
    justify-content: center;
    padding: var(--space-2);
  }

  .none {
    padding: var(--space-4);
  }

  .lightbox {
    position: fixed;
    inset: 0;
    z-index: 50;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: var(--space-3);
    padding: var(--space-8);
    background: var(--backdrop-modal-scrim);
    backdrop-filter: blur(6px);
  }

  .lightbox-image {
    width: min(1100px, 100%);
    aspect-ratio: 16 / 9;
  }

  .lightbox-image :global(img) {
    object-fit: contain !important;
  }

  .lightbox-caption {
    margin: 0;
    font-size: var(--type-secondary-size);
    color: var(--color-text);
  }

  .lightbox-close {
    position: absolute;
    top: var(--space-4);
    right: var(--space-4);
  }

  /* Loading placeholders: still shapes, a slow breath, nothing that shimmers. */
  .skeleton {
    border-radius: var(--radius-xs);
    background-color: var(--control-fill-faint);
    animation: breathe 1.6s var(--ease-standard) infinite alternate;
  }

  .skeleton.line {
    height: 12px;
    width: 100%;
  }

  .skeleton.line.short {
    width: 40%;
  }

  .skeleton-row {
    gap: var(--space-2);
    padding: var(--space-3) var(--space-4);
  }

  @keyframes breathe {
    from {
      opacity: 0.45;
    }
    to {
      opacity: 0.9;
    }
  }

  @media (prefers-reduced-motion: reduce) {
    .skeleton {
      animation: none;
    }
  }
</style>

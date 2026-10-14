<!--
  Installing a mod from the browser: what will happen, then doing it.

  Clicking Install never installs straight away. It asks Rust for a plan — the
  build that will be fetched, every library it needs, what the profile already
  has — and shows it. Anything the user has to decide is a checkbox here:
  optional extras, a build the compatibility resolver could not vouch for, a
  file that may duplicate one already installed. Nothing is ticked for them.

  The plan is rebuilt in Rust when Install is pressed, so what is shown here
  is a preview, not an instruction; the installer re-checks everything.
-->
<script lang="ts">
  import { onDestroy, onMount } from 'svelte';
  import { fade } from 'svelte/transition';
  import { Download, Package, TriangleAlert, Check } from '@lucide/svelte';
  import { ICON } from '../../lib/metrics.generated';
  import { MOTION_HOVER } from '../../lib/motion';
  import Sheet from '../../lib/ui/Sheet.svelte';
  import Button from '../../lib/ui/Button.svelte';
  import Notice from '../../lib/ui/Notice.svelte';
  import Progress from '../../lib/ui/Progress.svelte';
  import {
    catalogCancelInstall,
    catalogInstall,
    catalogPlan,
    onCatalogInstall,
    type InstallArgs,
    type InstallEvent,
    type InstallOutcome,
    type PlanView,
    type ProjectRef,
  } from '../../lib/api';

  export let args: InstallArgs;
  export let name: string;
  export let profileName: string;
  export let onClose: () => void;
  export let onDone: (outcome: InstallOutcome) => void;
  /**
   * One click: when the plan turns out to be just this mod -- compatible, no
   * libraries to add, nothing optional, nothing to confirm -- install it
   * straight away instead of asking for a second click. Anything more than
   * that is shown and waits for the user.
   */
  export let quick = false;

  let plan: PlanView | null = null;
  let planning = true;
  let problem = '';
  let optional: ProjectRef[] = [];
  let acceptUnverified = false;
  let acceptDuplicates = false;
  let installing = false;
  let progress: InstallEvent['progress'] = null;
  let failure = '';
  let stop: (() => void) | null = null;

  const key = `${args.reference.provider}:${args.reference.id}`;

  async function load() {
    planning = true;
    problem = '';
    try {
      plan = await catalogPlan({ ...args, optional });
      if (
        quick &&
        plan.blockers.length === 0 &&
        plan.unverified.length === 0 &&
        plan.dependencies.length === 0 &&
        plan.optional.length === 0 &&
        !plan.needsDuplicateConfirmation
      ) {
        quick = false;
        void install();
      }
    } catch (cause) {
      problem = String(cause instanceof Error ? cause.message : cause);
      plan = null;
    } finally {
      planning = false;
    }
  }

  onMount(() => {
    void load();
    void onCatalogInstall((event) => {
      if (event.key === key && event.progress) progress = event.progress;
    }).then((unsubscribe) => (stop = unsubscribe));
  });

  onDestroy(() => stop?.());

  function toggleOptional(reference: ProjectRef, on: boolean) {
    optional = on
      ? [...optional, reference]
      : optional.filter((other) => other.id !== reference.id || other.provider !== reference.provider);
    void load();
  }

  async function install() {
    if (!plan || installing) return;
    installing = true;
    failure = '';
    progress = null;
    try {
      const outcome = await catalogInstall({
        ...args,
        optional,
        acceptUnverified,
        acceptDuplicates,
      });
      if (outcome.ok) {
        onDone(outcome);
      } else if (outcome.cancelled) {
        failure = outcome.message;
      } else {
        failure = outcome.message;
      }
    } catch (cause) {
      failure = String(cause instanceof Error ? cause.message : cause);
    } finally {
      installing = false;
    }
  }

  function cancel() {
    if (installing) void catalogCancelInstall(args.reference);
    else onClose();
  }

  function size(bytes: number | null): string {
    if (!bytes) return '';
    if (bytes >= 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
    return `${Math.max(1, Math.round(bytes / 1024))} KB`;
  }

  $: blocked = !!plan && plan.blockers.length > 0;
  $: consentMissing =
    !!plan &&
    ((plan.unverified.length > 0 && !acceptUnverified) ||
      (plan.needsDuplicateConfirmation && !acceptDuplicates));
  $: verb = plan?.isUpdate || args.replacing ? 'Update' : 'Install';
  $: fraction = progress && progress.total > 0 ? progress.completed / progress.total : null;
</script>

<Sheet title="{verb} {name}" onClose={cancel} width={460}>
  {#if planning && !plan}
    <div class="status">
      <span>Working out what {name} needs…</span>
      <Progress label="Planning" />
    </div>
  {:else if problem}
    <Notice tone="danger">{problem}</Notice>
    <div><Button onClick={() => void load()}>Try again</Button></div>
  {:else if plan}
    <p class="lede">
      Into <strong>{profileName}</strong>. Every file is checked against the hash its index publishes
      before it is used.
    </p>

    {#if blocked}
      <Notice tone="danger">
        {plan.blockers.length === 1 ? "This can't be installed:" : "This can't be installed, for these reasons:"}
        <ul class="reasons">
          {#each plan.blockers as blocker}<li>{blocker}</li>{/each}
        </ul>
      </Notice>
    {/if}

    <section>
      <h3>Will download</h3>
      <ul class="files">
        {#each [plan.root, ...plan.dependencies] as planned (planned.key)}
          <li>
            <Package size={ICON.inline} />
            <span class="what">
              <span class="name">{planned.name}</span>
              <span class="meta tnum">
                {planned.number}{planned.size ? ` · ${size(planned.size)}` : ''}
                {#if planned.requiredBy}· needed by {planned.requiredBy}{/if}
                {#if planned.optional}· optional{/if}
              </span>
              {#if planned.possibleDuplicate}
                <span class="flag">{planned.possibleDuplicate}</span>
              {/if}
            </span>
          </li>
        {/each}
      </ul>
      {#if plan.downloadBytes}
        <p class="quiet tnum">{size(plan.downloadBytes)} in total, unless already downloaded.</p>
      {/if}
    </section>

    {#if plan.satisfied.length}
      <section>
        <h3>Already in this profile</h3>
        <ul class="plain">
          {#each plan.satisfied as file}
            <li><Check size={ICON.inline} /> {file}</li>
          {/each}
        </ul>
        {#if plan.disabledDependencies.length}
          <Notice tone="warning">
            {plan.disabledDependencies.join(', ')}
            {plan.disabledDependencies.length === 1 ? 'is' : 'are'} switched off. Switch
            {plan.disabledDependencies.length === 1 ? 'it' : 'them'} on in Installed, or {name} will
            not load.
          </Notice>
        {/if}
      </section>
    {/if}

    {#if plan.optional.length}
      <section>
        <h3>Optional extras</h3>
        <p class="quiet">Suggested by the author. Not installed unless you tick them.</p>
        {#each plan.optional as extra (extra.reference.id)}
          <label class="choice">
            <input
              type="checkbox"
              disabled={extra.alreadyInstalled || planning}
              checked={extra.alreadyInstalled ||
                optional.some((o) => o.id === extra.reference.id)}
              on:change={(event) => toggleOptional(extra.reference, event.currentTarget.checked)}
            />
            <span>
              {extra.name ?? extra.reference.id}
              <span class="meta">
                · suggested by {extra.suggestedBy}{extra.alreadyInstalled ? ' · installed' : ''}
              </span>
            </span>
          </label>
        {/each}
      </section>
    {/if}

    {#if plan.unverified.length}
      <section class="caution">
        <h3><TriangleAlert size={ICON.inline} /> Fathom can't confirm this works</h3>
        {#each plan.unverified as build}
          <div class="build">
            <span class="name">{build.name} {build.number}</span>
            {#each build.reasons as reason}<p>{reason}</p>{/each}
          </div>
        {/each}
        <label class="choice">
          <input type="checkbox" bind:checked={acceptUnverified} />
          <span>
            Install anyway. If the game fails to start, turn it off in Installed.
          </span>
        </label>
      </section>
    {/if}

    {#if plan.needsDuplicateConfirmation}
      <section class="caution">
        <h3><TriangleAlert size={ICON.inline} /> This may already be installed</h3>
        <label class="choice">
          <input type="checkbox" bind:checked={acceptDuplicates} />
          <span>It is a different mod; install it as well.</span>
        </label>
      </section>
    {/if}

    {#if installing}
      <div class="status" in:fade={{ duration: MOTION_HOVER }}>
        <span>
          {#if progress?.phase === 'downloading'}
            Downloading {progress.current} ({progress.completed + 1} of {progress.total})
          {:else if progress?.phase === 'recording'}
            Adding to {profileName}
          {:else}
            Checking
          {/if}
        </span>
        <Progress value={fraction} label="Installing" />
      </div>
    {/if}

    {#if failure}
      <Notice tone="danger">{failure}</Notice>
    {/if}
  {/if}

  <svelte:fragment slot="footer">
    <Button onClick={cancel}>{installing ? 'Stop' : 'Cancel'}</Button>
    <Button
      variant="primary"
      disabled={!plan || planning || blocked || consentMissing || installing}
      onClick={() => void install()}
    >
      <Download size={ICON.inline} />
      {installing ? `${verb === 'Update' ? 'Updating' : 'Installing'}…` : failure ? 'Try again' : verb}
    </Button>
  </svelte:fragment>
</Sheet>

<style>
  .lede,
  .quiet,
  p {
    margin: 0;
    font-size: var(--type-secondary-size);
    line-height: var(--type-secondary-leading);
    color: var(--color-text-secondary);
  }

  .lede strong {
    color: var(--color-text);
    font-weight: var(--type-heading-weight);
  }

  .quiet {
    color: var(--color-text-tertiary);
  }

  section {
    display: flex;
    flex-direction: column;
    gap: var(--space-2);
  }

  h3 {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    margin: 0;
    font-size: var(--type-caption-size);
    font-weight: var(--type-heading-weight);
    letter-spacing: 0.04em;
    text-transform: uppercase;
    color: var(--color-text-tertiary);
  }

  .caution h3 {
    color: var(--color-warning-text);
  }

  .files,
  .plain,
  .reasons {
    margin: 0;
    padding: 0;
    list-style: none;
    display: flex;
    flex-direction: column;
    gap: var(--space-2);
  }

  .reasons {
    margin-top: var(--space-2);
    padding-left: var(--space-4);
    list-style: disc;
    gap: var(--space-1);
  }

  .files li {
    display: flex;
    align-items: flex-start;
    gap: var(--space-3);
    padding: var(--space-2) var(--space-3);
    border-radius: var(--radius-md);
    background-color: var(--control-fill-faint);
    color: var(--color-text-secondary);
  }

  .files li :global(svg) {
    margin-top: 2px;
    flex: none;
  }

  .plain li {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    font-size: var(--type-secondary-size);
    color: var(--color-text-secondary);
  }

  .what {
    display: flex;
    flex-direction: column;
    gap: 2px;
    min-width: 0;
  }

  .name {
    font-size: var(--type-body-size);
    font-weight: var(--type-heading-weight);
    color: var(--color-text);
    overflow-wrap: anywhere;
  }

  .meta {
    font-size: var(--type-caption-size);
    color: var(--color-text-tertiary);
  }

  .flag {
    font-size: var(--type-caption-size);
    color: var(--color-warning-text);
  }

  .caution {
    padding: var(--space-3);
    border-radius: var(--radius-md);
    background-color: color-mix(in srgb, var(--color-warning-tint) 45%, transparent);
    box-shadow: inset 0 0 0 var(--hairline) var(--color-warning-rim);
  }

  .build {
    display: flex;
    flex-direction: column;
    gap: var(--space-1);
  }

  .choice {
    display: flex;
    align-items: flex-start;
    gap: var(--space-2);
    font-size: var(--type-secondary-size);
    line-height: var(--type-secondary-leading);
    color: var(--color-text);
    cursor: pointer;
  }

  .choice input {
    margin-top: 3px;
    accent-color: var(--color-signal);
  }

  .status {
    display: flex;
    flex-direction: column;
    gap: var(--space-2);
    font-size: var(--type-secondary-size);
    color: var(--color-text-secondary);
  }
</style>

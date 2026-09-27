<!--
  One mod, as a page.

  Opened from the library, and a full page rather than a drawer: configuring a
  mod gets the whole content area, with its state and actions on the left and
  the facts about the file beside them when the window is wide enough, stacked
  when it is not.

  What a launcher can configure about a mod is what is here: whether this
  profile loads it, whether it can be updated, and removing it -- plus, for a
  Fathom component, installing it and what it needs and clashes with. A mod's
  own in-game options (a crosshair's shape, a HUD element's colour) belong to the
  game and are set in the Hub, not here.

  Every action calls the same backend function the library row used; this page
  only lays them out with room to read them.
-->
<script lang="ts">
  import { ArrowLeft, Download, ExternalLink, Trash2 } from '@lucide/svelte';

  import Page from '../../lib/ui/Page.svelte';
  import Section from '../../lib/ui/Section.svelte';
  import Row from '../../lib/ui/Row.svelte';
  import Switch from '../../lib/ui/Switch.svelte';
  import Button from '../../lib/ui/Button.svelte';
  import Notice from '../../lib/ui/Notice.svelte';
  import type { ComponentRow, ModRow } from '../../lib/api';

  /** Exactly one of these is set. */
  export let mod: ModRow | null = null;
  export let component: ComponentRow | null = null;
  export let profileName = '';
  export let busy = false;
  export let onBack: () => void;
  export let onToggle: (enabled: boolean) => void = () => {};
  export let onRemove: () => void = () => {};
  export let onInstall: () => void = () => {};
  export let onOpenProject: (slug: string) => void = () => {};
  export let onOpenPage: (page: string) => void = () => {};

  /* Facts that identify a build are set in Geist Mono; names and sentences
     are not. */
  const MONO = new Set(['Version', 'Size', 'File', 'Minecraft']);

  $: title = mod?.name ?? component?.name ?? '';
  $: summary = mod ? mod.version : (component?.version ?? component?.state ?? '');
  $: about = mod?.summary || component?.summary || '';
  $: facts = mod
    ? [
        ['Version', mod.version],
        ['Source', mod.origin],
        ['Licence', mod.license],
        ['Size', mod.size],
      ]
    : component
      ? [
          ['Role', component.tier],
          ['Version', component.version ?? '—'],
          ['Licence', component.license],
          ['Minecraft', component.minecraft],
          ['Loaders', component.loaders],
          ['Source', component.source],
          ...(component.fingerprint ? [['File', component.fingerprint]] : []),
        ]
      : [];
</script>

<Page {title} {summary} wide>
  <svelte:fragment slot="lead">
    <button class="ui-capsule small material-clear material-interactive" on:click={onBack}>
      <ArrowLeft size={14} />
      Mods
    </button>
  </svelte:fragment>

  <div class="frame">
  <div class="layout">
    <div class="main">
      {#if mod}
        {#if mod.missing}
          <Notice tone="danger">The file for this mod is missing, so it cannot load.</Notice>
        {/if}
        {#if mod.warning}
          <Notice tone="warning">{mod.warning}</Notice>
        {/if}

        <Section label="In this profile" material>
          <Row
            title={mod.enabled ? 'Loaded' : 'Not loaded'}
            detail={mod.enabled
              ? `${profileName} loads this mod when the game starts.`
              : `${profileName} skips this mod. Its file stays where it is.`}
          >
            <svelte:fragment slot="control">
              <Switch
                checked={mod.enabled}
                disabled={busy || !mod.canToggle}
                label={`Load ${mod.name}`}
                onChange={onToggle}
              />
            </svelte:fragment>
          </Row>
          <Row
            title="Updates"
            detail={mod.updatable
              ? 'Fathom can check this for updates.'
              : 'Your own file. Fathom has nothing to check it against.'}
          >
            <svelte:fragment slot="control">
              {#if mod.slug}
                <Button onClick={() => mod?.slug && onOpenProject(mod.slug)}>
                  <ExternalLink size={14} />
                  Project page
                </Button>
              {/if}
            </svelte:fragment>
          </Row>
        </Section>
      {:else if component}
        {#if component.blocker}
          <Notice tone="warning">{component.blocker}</Notice>
        {/if}

        <Section label="In this profile" material>
          <Row title={component.state} detail="Installed and kept current by Fathom.">
            <svelte:fragment slot="control">
              {#if component.installable}
                <Button variant="primary" disabled={busy} onClick={onInstall}>
                  <Download size={14} />
                  Install
                </Button>
              {/if}
              {#if component.page}
                <Button onClick={() => component?.page && onOpenPage(component.page)}>
                  <ExternalLink size={14} />
                  Page
                </Button>
              {/if}
            </svelte:fragment>
          </Row>
        </Section>

        {#if component.dependencies.length || component.conflicts.length}
          <Section label="Works with" material>
            {#if component.dependencies.length}
              <Row title="Needs" detail={component.dependencies.join(', ')} />
            {/if}
            {#if component.conflicts.length}
              <Row title="Clashes with" detail={component.conflicts.join(', ')} />
            {/if}
          </Section>
        {/if}
      {/if}

      {#if about}
        <section class="about">
          <h2 class="heading">About</h2>
          <p>{about}</p>
        </section>
      {/if}

      {#if mod}
        <!-- Removing is the one irreversible thing here, so it stands apart
             from the settings above rather than sitting in a row of them. -->
        <div class="remove">
          <Button variant="caution" disabled={busy} onClick={onRemove}>
            <Trash2 size={14} />
            Remove from {profileName}
          </Button>
        </div>
      {/if}
    </div>

    <aside class="side">
      <Section label="Details" material>
        <dl>
          {#each facts as [term, value]}
            <div>
              <dt>{term}</dt>
              <dd class:mono={MONO.has(term)}>{value}</dd>
            </div>
          {/each}
        </dl>
      </Section>
    </aside>
  </div>
  </div>
</Page>

<style>
  /* Two columns when there is room for both to be readable, one otherwise.
     Measured on the page's own width, not the window's: the frame is the
     container, because an element cannot answer a query about itself. */
  .frame {
    container-type: inline-size;
  }

  .layout {
    display: grid;
    grid-template-columns: minmax(0, 3fr) minmax(260px, 2fr);
    gap: var(--layout-section-gap) var(--space-6);
    align-items: start;
  }

  @container (max-width: 720px) {
    .layout {
      grid-template-columns: minmax(0, 1fr);
    }
  }

  .main,
  .side {
    display: flex;
    flex-direction: column;
    gap: var(--layout-section-gap);
    min-width: 0;
  }

  .about {
    padding: 0 var(--space-1);
  }

  .heading {
    margin: 0 0 var(--space-2);
    font-size: var(--type-label-size);
    font-weight: var(--type-heading-weight);
    letter-spacing: var(--type-label-tracking);
    color: var(--color-text-secondary);
  }

  .about p {
    margin: 0;
    max-width: 62ch;
    font-size: var(--type-body-size);
    line-height: var(--type-body-leading);
    color: var(--color-text);
    overflow-wrap: anywhere;
  }

  .remove {
    display: flex;
    padding: 0 var(--space-1);
  }

  dl {
    display: flex;
    flex-direction: column;
    gap: var(--space-3);
    margin: 0;
    padding: var(--space-4);
  }

  dl div {
    display: grid;
    grid-template-columns: 88px minmax(0, 1fr);
    gap: var(--space-3);
    align-items: baseline;
  }

  dt {
    font-size: var(--type-caption-size);
    color: var(--color-text-tertiary);
  }

  dd {
    margin: 0;
    font-size: var(--type-secondary-size);
    color: var(--color-text);
    overflow-wrap: anywhere;
  }

  dd.mono {
    font-size: var(--type-caption-size);
  }
</style>

<!--
  One installation, on its own page.

  This used to be a 420px sheet, and the thing people most wanted to do from
  it -- put OptiFine into this installation -- was not on it at all: it lived
  on a separate Add OptiFine screen that asked which installation, again, after
  you had already picked one. So the editor is a page, and OptiFine has a pane
  on it that says, before anything else, whether this installation runs it.

  The OptiFine pane acts on THIS installation only. A jar chosen or dropped
  here is checked by Rust exactly as on the Add OptiFine screen and then added
  straight to this profile; the Add OptiFine screen remains for somebody who
  arrives with a file and no installation in mind.

  What it still does not do is fetch OptiFine. See AddOptiFine.svelte.
-->
<script lang="ts">
  import { ICON } from '../lib/metrics.generated';
  import { onMount } from 'svelte';
  import { fade } from 'svelte/transition';
  import { ArrowLeft, Download, FolderOpen, HardDriveDownload, TriangleAlert } from '@lucide/svelte';

  import Page from '../lib/ui/Page.svelte';
  import Section from '../lib/ui/Section.svelte';
  import Button from '../lib/ui/Button.svelte';
  import Notice from '../lib/ui/Notice.svelte';
  import Switch from '../lib/ui/Switch.svelte';
  import Select from '../lib/ui/Select.svelte';
  import { rangeFill } from '../lib/range';
  import { MOTION_HOVER } from '../lib/motion';
  import {
    addSuppliedJar,
    installComponent,
    modsView,
    openPage,
    profileDetail,
    saveProfile,
    setModEnabled,
    supplyDroppedJar,
    supplyJar,
    type ModsView,
    type ProfileDetail,
    type SuppliedJar,
  } from '../lib/api';

  export let id: string;
  export let onBack: () => void;
  /** Something about this installation changed: the list and shell re-read. */
  export let onChanged: () => void = () => {};

  const OPTIFINE = 'optifine';
  const BRIDGE = 'optifabric';

  let editing: ProfileDetail | null = null;
  let mods: ModsView | null = null;
  let busy = false;
  let error = '';
  let message = '';
  let dropping = false;

  onMount(() => {
    void (async () => {
      editing = await profileDetail(id);
      await readMods();
    })();

    /* The window is the drop target while this page is up, as on the Add
       OptiFine screen: a jar dropped anywhere here goes into this installation. */
    let stop: (() => void) | null = null;
    void (async () => {
      try {
        const { getCurrentWebview } = await import('@tauri-apps/api/webview');
        stop = await getCurrentWebview().onDragDropEvent((event) => {
          if (event.payload.type === 'over') {
            dropping = true;
          } else if (event.payload.type === 'drop') {
            dropping = false;
            const path = event.payload.paths.find((p) => p.toLowerCase().endsWith('.jar'));
            if (path) void supply(() => supplyDroppedJar(path));
            else error = 'That is not a .jar. OptiFine downloads as a .jar file.';
          } else {
            dropping = false;
          }
        });
      } catch {
        /* Outside the app shell: Choose JAR does the same thing. */
      }
    })();
    return () => stop?.();
  });

  async function readMods() {
    mods = await modsView(id).catch(() => null);
  }

  async function guarded(action: () => Promise<void>) {
    if (busy) return;
    busy = true;
    error = '';
    try {
      await action();
    } catch (cause) {
      error = String(cause);
    } finally {
      busy = false;
    }
  }

  /** Choose or drop a jar, then add it to this installation if it fits. */
  async function supply(pick: () => Promise<SuppliedJar>) {
    await guarded(async () => {
      message = '';
      const jar = await pick();
      if (!jar.chosen) return;
      if (!jar.isOptifine) {
        throw new Error(`${jar.fileName} is not OptiFine. Add other mods from the Mods page.`);
      }
      const fit = jar.profiles.find((candidate) => candidate.id === id);
      if (!fit || !fit.compatible) {
        throw new Error(fit?.note || `${jar.description} does not fit this installation.`);
      }
      const added = await addSuppliedJar(id);
      message = added.message || `${jar.description} added.`;
      await readMods();
      onChanged();
    });
  }

  async function toggle(on: boolean) {
    await guarded(async () => {
      mods = await setModEnabled(OPTIFINE, on, id);
      onChanged();
    });
  }

  async function installBridge() {
    await guarded(async () => {
      mods = await installComponent(BRIDGE, id);
      onChanged();
    });
  }

  async function save() {
    if (!editing) return;
    const detail = editing;
    await guarded(async () => {
      await saveProfile({
        id: detail.id,
        name: detail.name,
        memoryMb: detail.memoryMb,
        extraArgs: detail.extraArgs,
        keepLauncherOpen: detail.keepLauncherOpen,
        loader: detail.loader,
        java: detail.java,
      });
      onChanged();
      onBack();
    });
  }

  $: optifine = mods?.rows.find((row) => row.id === OPTIFINE) ?? null;
  $: bridge = mods?.components.find((component) => component.id === BRIDGE) ?? null;
  /* OptiFabric only appears for a Fabric loader; elsewhere it is not needed. */
  $: bridgeMissing = Boolean(optifine && bridge && !['Installed', 'Yours'].includes(bridge.state));
  $: loaderChanged = Boolean(editing && mods && editing.loader !== loaderAtLoad);
  let loaderAtLoad = '';
  $: if (editing && !loaderAtLoad) loaderAtLoad = editing.loader;

  /** One word for the pill, and its tone. */
  $: status = !mods
    ? { label: 'Checking…', tone: 'quiet' }
    : !mods.loaderSupportsMods
      ? { label: 'Not supported', tone: 'quiet' }
      : !optifine
        ? { label: 'Not added', tone: 'quiet' }
        : optifine.missing
          ? { label: 'File missing', tone: 'bad' }
          : optifine.enabled && bridgeMissing
            ? { label: 'Needs OptiFabric', tone: 'warn' }
            : optifine.enabled
              ? { label: 'Enabled', tone: 'good' }
              : { label: 'Disabled', tone: 'warn' };
</script>

<Page title={editing?.name || 'Installation'} summary={editing ? `${editing.kindLabel} · Minecraft ${editing.version}` : ''} wide>
  <svelte:fragment slot="lead">
    <button class="ui-capsule small material-clear material-interactive" on:click={onBack}>
      <ArrowLeft size={ICON.inline} />
      Installations
    </button>
  </svelte:fragment>

  <svelte:fragment slot="actions">
    <Button onClick={onBack}>Cancel</Button>
    <Button variant="primary" disabled={busy || !editing} onClick={save}>Save</Button>
  </svelte:fragment>

  {#if error}
    <div transition:fade={{ duration: MOTION_HOVER }}>
      <Notice tone="danger" onDismiss={() => (error = '')}>{error}</Notice>
    </div>
  {/if}

  {#if editing}
    <div class="frame">
      <div class="main">
        <Section label="Settings" material>
          <div class="fields">
            {#if editing.parent}
              <!-- A derived profile's technical half comes from its parent, so
                   the fields that would be overwritten at launch are removed
                   rather than shown greyed. -->
              <p class="fine">Based on {editing.parent}. Version, loader, memory and Java come from it.</p>
            {/if}

            <label class="field">
              <span class="field-label">Name</span>
              <input class="ui-field" bind:value={editing.name} spellcheck="false" />
            </label>

            {#if !editing.parent}
              <div class="field">
                <span class="field-label">Loader</span>
                <Select
                  label="Loader"
                  bind:value={editing.loader}
                  options={editing.loaders.map((loader) => {
                    // Fathom's client is built for Legacy Fabric and for Forge.
                    const outsideFathom = editing?.kind === 'fathom' && loader.id === 'vanilla';
                    return {
                      id: loader.id,
                      label: loader.label,
                      disabled: !loader.available || outsideFathom,
                      note: !loader.available ? 'Not ready' : outsideFathom ? 'Not for Fathom' : '',
                    };
                  })}
                />
                {#if editing.kind === 'fathom'}
                  <span class="fine">Fathom runs on Legacy Fabric or Forge; switching installs the matching build.</span>
                {/if}
              </div>

              <div class="field">
                <span class="field-label">Java runtime</span>
                <Select
                  label="Java runtime"
                  bind:value={editing.java}
                  options={[
                    { id: 'automatic', label: 'Automatic' },
                    { id: 'managed', label: 'Fathom managed' },
                    { id: 'system', label: 'System' },
                    ...(editing.javaPath ? [{ id: 'custom', label: `Custom — ${editing.javaPath}` }] : []),
                  ]}
                />
              </div>

              <label class="field">
                <span class="field-line">
                  <span class="field-label">Memory</span>
                  <span class="value mono">{(editing.memoryMb / 1024).toFixed(1)} GB</span>
                </span>
                <input
                  type="range"
                  min="512"
                  max="8192"
                  step="256"
                  bind:value={editing.memoryMb}
                  use:rangeFill={editing.memoryMb}
                />
              </label>

              <label class="field">
                <span class="field-label">Extra Java arguments</span>
                <input class="ui-field" bind:value={editing.extraArgs} spellcheck="false" placeholder="None" />
              </label>
            {/if}

            <div class="toggle">
              <span class="toggle-text">Keep Fathom open while playing</span>
              <Switch
                checked={editing.keepLauncherOpen}
                label="Keep Fathom open while playing"
                onChange={(value) => editing && (editing.keepLauncherOpen = value)}
              />
            </div>
          </div>
        </Section>

        {#if editing.identity.length}
          <Section label="Look" material>
            <dl>
              {#each editing.identity as row}
                <div>
                  <dt>{row.label}</dt>
                  <dd class:quiet={row.unset}>{row.value}</dd>
                </div>
              {/each}
            </dl>
          </Section>
        {/if}

        {#if editing.servers.length}
          <Section label="Servers" material>
            <dl>
              {#each editing.servers as server}
                <div>
                  <dt>{server.name}</dt>
                  <dd>{server.address}</dd>
                </div>
              {/each}
            </dl>
          </Section>
        {/if}
      </div>

      <div class="side">
        <!-- OPTIFINE: status first, then the one thing to do about it. -->
        <Section label="OptiFine" material>
          <div class="optifine">
            <div class="status-line">
              <span class="pill {status.tone}">
                <span class="dot" aria-hidden="true"></span>
                {status.label}
              </span>
              {#if optifine && !optifine.missing && mods?.loaderSupportsMods}
                <Switch
                  checked={optifine.enabled}
                  disabled={busy || !optifine.canToggle}
                  label="OptiFine enabled"
                  onChange={(on) => void toggle(on)}
                />
              {/if}
            </div>

            {#if optifine}
              <p class="detail">
                {optifine.name}{optifine.version ? ` · ${optifine.version.replace(/_/g, ' ')}` : ''}
              </p>
              {#if optifine.warning}
                <p class="warn-line"><TriangleAlert size={ICON.caption} /> {optifine.warning}</p>
              {/if}
              {#if !optifine.enabled}
                <p class="fine">Added, but switched off: it will not load until you switch it on.</p>
              {/if}
            {:else if mods && !mods.loaderSupportsMods}
              <p class="fine">Vanilla can't load OptiFine. Switch the loader to Legacy Fabric or Forge and save first.</p>
            {:else if mods}
              <p class="fine">This installation doesn't run OptiFine. Fathom can't download it for you — get it from the author, then add the JAR here.</p>
            {/if}

            {#if bridgeMissing && bridge}
              <div class="bridge">
                <p class="warn-line">
                  <TriangleAlert size={ICON.caption} />
                  On Fabric, OptiFine also needs {bridge.name}, which this installation doesn't have.
                </p>
                <Button small disabled={busy} onClick={() => void installBridge()}>Install {bridge.name}</Button>
              </div>
            {/if}

            {#if loaderChanged}
              <p class="fine">You changed the loader. Save first, so OptiFine is checked against the new one.</p>
            {/if}

            {#if mods?.loaderSupportsMods}
              <div class="drop" class:active={dropping}>
                <HardDriveDownload size={ICON.tile} />
                <span class="headline">
                  {dropping ? 'Let go to add it' : optifine ? 'Drop a JAR to replace it' : 'Drop the OptiFine JAR here'}
                </span>
                <span class="fine">Nothing is uploaded. The file is copied into Fathom's local store.</span>
              </div>

              <div class="actions">
                <Button onClick={() => void openPage('optifine')}>
                  <Download size={ICON.inline} />
                  Get OptiFine
                </Button>
                <Button variant="primary" disabled={busy || loaderChanged} onClick={() => void supply(supplyJar)}>
                  <FolderOpen size={ICON.inline} />
                  {optifine ? 'Replace JAR…' : 'Upload JAR…'}
                </Button>
              </div>
            {/if}

            {#if message}
              <p class="ok" transition:fade={{ duration: MOTION_HOVER }}>{message}</p>
            {/if}
          </div>
        </Section>

        {#if editing.components.length}
          <Section label="Components" material>
            <dl>
              {#each editing.components as component}
                <div>
                  <dt>{component.name}</dt>
                  <dd class:quiet={component.state === 'unavailable'} class:flag={component.state === 'missing'}>
                    {component.detail}
                  </dd>
                </div>
              {/each}
            </dl>
          </Section>
        {/if}
      </div>
    </div>
  {/if}
</Page>

<style>
  .frame {
    display: grid;
    grid-template-columns: minmax(0, 3fr) minmax(0, 2fr);
    gap: var(--space-6);
    align-items: start;
  }

  @media (max-width: 900px) {
    .frame {
      grid-template-columns: minmax(0, 1fr);
    }
  }

  .main,
  .side {
    display: flex;
    flex-direction: column;
    gap: var(--space-4);
    min-width: 0;
  }

  .fields,
  .optifine {
    display: flex;
    flex-direction: column;
    gap: var(--space-4);
    padding: var(--space-4);
  }

  .fine {
    margin: 0;
    font-size: var(--type-caption-size);
    line-height: var(--type-caption-leading);
    color: var(--color-text-tertiary);
  }

  .field {
    display: flex;
    flex-direction: column;
    gap: var(--space-2);
  }

  .field-line {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
  }

  .field-label {
    font-size: var(--type-label-size);
    font-weight: var(--type-heading-weight);
    letter-spacing: var(--type-label-tracking);
    color: var(--color-text-secondary);
  }

  .value {
    min-width: 7ch;
    text-align: right;
    font-size: var(--type-secondary-size);
    font-weight: var(--type-button-weight);
    color: var(--color-text);
  }

  .field input[type='range'] {
    width: 100%;
  }

  .toggle {
    display: flex;
    align-items: center;
    gap: var(--space-3);
    padding-top: var(--space-4);
    box-shadow: 0 calc(-1 * var(--hairline)) 0 var(--control-hairline);
  }

  .toggle-text {
    flex: 1;
    font-size: var(--type-body-size);
    color: var(--color-text);
  }

  /* -- OptiFine ------------------------------------------------------------- */
  .status-line {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: var(--space-3);
  }

  .pill {
    display: inline-flex;
    align-items: center;
    gap: var(--space-2);
    padding: 4px var(--space-3);
    border-radius: var(--radius-pill);
    font-size: var(--type-secondary-size);
    font-weight: var(--type-button-weight);
    color: var(--color-text-secondary);
    background-color: var(--control-fill);
  }

  .dot {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    background-color: currentColor;
  }

  .pill.good {
    color: var(--color-success-text);
  }

  .pill.warn {
    color: var(--color-warning-text);
  }

  .pill.bad {
    color: var(--color-danger-text);
  }

  .detail {
    margin: 0;
    font-size: var(--type-body-size);
    color: var(--color-text);
  }

  .warn-line {
    display: flex;
    align-items: center;
    gap: 6px;
    margin: 0;
    font-size: var(--type-caption-size);
    color: var(--color-warning-text);
  }

  .bridge {
    display: flex;
    flex-direction: column;
    align-items: flex-start;
    gap: var(--space-2);
  }

  .drop {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: var(--space-1);
    min-height: 132px;
    padding: var(--space-4);
    border-radius: var(--radius-md);
    background-color: var(--control-fill-sunken);
    outline: 1.5px dashed var(--control-hairline-strong);
    outline-offset: -1.5px;
    color: var(--color-text-tertiary);
    text-align: center;
    transition:
      background-color var(--motion-state) var(--ease-standard),
      color var(--motion-state) var(--ease-standard);
  }

  .drop.active {
    background-color: var(--control-fill-selected);
    color: var(--color-text);
  }

  .headline {
    margin-top: var(--space-2);
    font-size: var(--type-body-size);
    font-weight: var(--type-button-weight);
    color: var(--color-text);
  }

  .actions {
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-2);
  }

  .ok {
    margin: 0;
    font-size: var(--type-secondary-size);
    color: var(--color-success-text);
  }

  /* -- facts ---------------------------------------------------------------- */
  dl {
    display: flex;
    flex-direction: column;
    gap: var(--space-2);
    margin: 0;
    padding: var(--space-4);
  }

  dl div {
    display: grid;
    grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
    gap: var(--space-3);
    align-items: baseline;
    font-size: var(--type-secondary-size);
  }

  dt {
    color: var(--color-text-secondary);
    overflow-wrap: anywhere;
  }

  dd {
    margin: 0;
    text-align: right;
    color: var(--color-text);
    overflow-wrap: anywhere;
  }

  dd.quiet {
    color: var(--color-text-disabled);
  }

  dd.flag {
    color: var(--color-warning-text);
  }
</style>

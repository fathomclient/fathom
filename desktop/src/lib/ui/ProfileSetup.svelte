<!--
  Setting up a profile.

  ONE DRAWER, NOT A WIZARD.

  The spec asked for five steps and explicitly asked for them not to become
  six pages. So this is a single scrolling surface with numbered sections: every
  answer is visible at once, changing an earlier one does not throw away a later
  one, and Create is reachable from the first second because every question
  already has a good answer in it.

  The sections reveal rather than gate. Choosing Vanilla removes the components
  section entirely — not greys it out — because a Vanilla profile has no
  components and a disabled section is a question you are being made to read and
  not allowed to answer.

  WHAT THIS SCREEN DOES NOT KNOW

  It does not know that Fathom means Legacy Fabric, which components exist, what
  Automatic memory resolves to, or whether a combination is valid. All of that
  comes from Rust as `SetupOptions`, and the answers go back as a draft. The one
  thing that would make this file wrong — a second copy of the product rules,
  in TypeScript — is the thing it deliberately cannot contain.
-->
<script lang="ts">
  import { ICON } from '../metrics.generated';
  import { onMount } from 'svelte';
  import { slide } from 'svelte/transition';
  import { Check, Download, Sparkles } from '@lucide/svelte';

  import Button from './Button.svelte';
  import Segmented from './Segmented.svelte';
  import Switch from './Switch.svelte';
  import Sheet from './Sheet.svelte';
  import Notice from './Notice.svelte';
  import { rangeFill } from '../range';
  import { tooltip } from '../tooltip';
  import { EASE, MOTION_HOVER, MOTION_STATE } from '../motion';
  import {
    createProfileFromSetup,
    openPage,
    setupComponents,
    setupOptions,
    setupProblems,
    type ComponentOption,
    type SetupDraft,
    type SetupOptions,
  } from '../api';

  export let onClose: () => void;
  export let onCreated: (id: string) => void = () => {};
  /** Leave setup for the full Add OptiFine screen. Set by the shell. */
  export let onAddOptiFine: () => void = () => {};

  let options: SetupOptions | null = null;
  let draft: SetupDraft | null = null;
  let components: ComponentOption[] = [];
  let problems: string[] = [];
  let busy = false;
  let error = '';

  onMount(() => {
    void (async () => {
      options = await setupOptions();
      draft = { ...options.defaultDraft };
      await refresh();
    })();
  });

  /* The loader the current answers imply, which is what components hang off.

     A plain function of the draft rather than a `$:` variable. A reactive
     variable is not updated until Svelte flushes, so `refresh()` called right
     after `draft = {...}` read the PREVIOUS loader -- and the add-ons list sat
     one choice behind: picking Vanilla still showed Fabric API and OptiFine. */
  function loaderFor(current: SetupDraft): string {
    if (current.kind === 'vanilla') return 'vanilla';
    if (current.kind === 'fathom') return 'legacy-fabric';
    return current.loader ?? DEFAULT_CUSTOM_LOADER;
  }

  async function refresh() {
    if (!draft) return;
    const current = draft;
    [components, problems] = await Promise.all([
      setupComponents(loaderFor(current), current.components),
      setupProblems(current),
    ]);
  }

  /** What the loader control shows for a Custom profile before one is picked. */
  const DEFAULT_CUSTOM_LOADER = 'legacy-fabric';

  async function choose<K extends keyof SetupDraft>(key: K, value: SetupDraft[K]) {
    if (!draft) return;
    draft = { ...draft, [key]: value };

    if (key === 'kind') {
      if (value === 'custom') {
        /* Set explicitly rather than left null. The control renders
           `loader ?? 'legacy-fabric'`, but a null loader is evaluated by Rust
           as Vanilla -- so the drawer showed Legacy Fabric selected while the
           backend refused the add-ons for not working with Vanilla. Sending
           what is on screen removes the disagreement. */
        draft.loader = draft.loader ?? DEFAULT_CUSTOM_LOADER;
      } else {
        /* The other two have their loader decided for them, and sending a
           stale one would only be ignored. */
        draft.loader = null;
      }
      if (value === 'vanilla') draft.components = [];
    }

    await refresh();
  }

  async function toggleComponent(id: string, on: boolean) {
    if (!draft) return;
    const next = draft.components.filter((existing) => existing !== id);
    if (on) next.push(id);
    draft = { ...draft, components: next };
    await refresh();
  }

  async function create() {
    if (!draft || busy) return;
    busy = true;
    error = '';
    try {
      const created = await createProfileFromSetup(draft);
      onCreated(created.id);
      onClose();
    } catch (cause) {
      error = String(cause);
    } finally {
      busy = false;
    }
  }

  /* Numbered in sequence over the sections that are actually showing. Hard
     numbers left 1, 2, 4, 5 on screen the moment the add-ons section dropped
     out for a Vanilla profile, which reads as a rendering fault rather than as
     a question that does not apply. */
  $: addOnsShown = components.length > 0;
  $: step = (name: 'version' | 'type' | 'addons' | 'memory' | 'name') => {
    const order = ['version', 'type', ...(addOnsShown ? ['addons'] : []), 'memory', 'name'];
    return order.indexOf(name) + 1;
  };

  $: memoryAutomatic = draft?.memoryMb === null;
  $: ready = problems.length === 0 && !busy;
</script>

<Sheet title="New installation" width={440} {onClose}>
  {#if options && draft}
    <div class="body">
      <!-- 1. VERSION -->
      <section>
        <span class="step">{step('version')} · Version</span>
        <Segmented
          options={options.versions.map((version) => ({ id: version.id, label: version.label }))}
          value={draft.version}
          onChange={(id) => void choose('version', id)}
        />
      </section>

      <!-- 2. TYPE -->
      <section>
        <span class="step">{step('type')} · Type</span>
        <div class="kinds">
          {#each options.kinds as kind}
            <button
              class="kind"
              class:selected={draft.kind === kind.id}
              disabled={Boolean(kind.unavailable)}
              use:tooltip={kind.unavailable ?? ''}
              on:click={() => void choose('kind', kind.id)}
            >
              <span class="kind-head">
                <span class="kind-name">{kind.label}</span>
                {#if kind.recommended}
                  <span class="badge"><Sparkles size={ICON.caption} /> Recommended</span>
                {/if}
                {#if draft.kind === kind.id}
                  <span class="tick"><Check size={ICON.inline} /></span>
                {/if}
              </span>
              <span class="kind-summary">{kind.summary}</span>
              <ul class="kind-includes">
                {#each kind.includes as line}
                  <li>{line}</li>
                {/each}
              </ul>
            </button>
          {/each}
        </div>

        {#if draft.kind === 'custom'}
          <div class="sub" transition:slide={{ duration: MOTION_STATE, easing: EASE }}>
            <span class="label">Loader</span>
            <Segmented
              options={options.loaders.map((loader) => ({
                id: loader.id,
                label: loader.available ? loader.label : `${loader.label} — soon`,
              }))}
              value={draft.loader ?? DEFAULT_CUSTOM_LOADER}
              onChange={(id) => void choose('loader', id)}
            />
          </div>
        {/if}
      </section>

      <!-- 3. COMPONENTS -->
      {#if addOnsShown}
        <section transition:slide={{ duration: MOTION_STATE, easing: EASE }}>
          <span class="step">{step('addons')} · Add-ons</span>
          {#each components as component}
            <div class="component">
              <div class="component-text">
                <span class="component-name">{component.name}</span>
                <p>{component.summary}</p>
                {#if component.blocker}
                  <p class="quiet">{component.blocker}</p>
                {/if}
              </div>

              {#if component.installable}
                <Switch
                  checked={component.selected}
                  label={component.name}
                  onChange={(on) => void toggleComponent(component.id, on)}
                />
              {:else if component.whereToGet}
                <!-- Fathom cannot bundle this one. The honest offer is a link
                     to the author and a drop target on the Mods page, not a
                     switch that would do nothing. -->
                <Button onClick={() => void openPage(component.id)}>
                  <Download size={ICON.inline} />
                  Get it
                </Button>
              {/if}
            </div>
          {/each}
          {#if components.some((component) => !component.installable)}
            <!-- The one component the launcher can never fetch, and the point in
                 setup where somebody decides they want it. Pointing at the full
                 flow rather than at "Mods" is the difference between an
                 instruction and a destination. -->
            <p class="note">
              OptiFine can't be bundled by Fathom. Download it from the author, then add the JAR —
              Fathom checks it, files it locally, and says which profiles it fits.
            </p>
            <div class="row">
              <span class="label">Have the JAR already?</span>
              <div class="right">
                <Button onClick={onAddOptiFine}>Add OptiFine…</Button>
              </div>
            </div>
          {/if}
        </section>
      {/if}

      <!-- 4. MEMORY AND JAVA -->
      <section>
        <span class="step">{step('memory')} · Memory and Java</span>

        <div class="row">
          <span class="label">Memory</span>
          <span class="value mono">
            {memoryAutomatic ? `${options.recommendedMemoryMb} MB` : `${draft.memoryMb} MB`}
          </span>
        </div>
        <div class="row">
          <span class="quiet grow">
            {memoryAutomatic ? 'Chosen for this computer' : 'Set by hand'}
          </span>
          <span class="quiet">Automatic</span>
          <Switch
            checked={memoryAutomatic}
            label="Automatic memory"
            onChange={(on) =>
              void choose('memoryMb', on ? null : (options?.recommendedMemoryMb ?? 2048))}
          />
        </div>

        {#if !memoryAutomatic}
          <div class="slider" transition:slide={{ duration: MOTION_HOVER, easing: EASE }}>
            <input
              type="range"
              min={options.minMemoryMb}
              max={8192}
              step={512}
              value={draft.memoryMb ?? options.recommendedMemoryMb}
              aria-label="Memory"
              use:rangeFill={draft.memoryMb}
              on:input={(event) => void choose('memoryMb', Number(event.currentTarget.value))}
            />
          </div>
        {/if}

        <div class="sub">
          <span class="label">Java</span>
          <Segmented
            options={options.java
              .filter((option) => option.id !== 'custom')
              .map((option) => ({ id: option.id, label: option.label }))}
            value={draft.java}
            onChange={(id) => void choose('java', id)}
          />
          <p class="quiet indent">
            {options.java.find((option) => option.id === draft?.java)?.detail ?? ''}
          </p>
        </div>
      </section>

      <!-- 5. NAME -->
      <section>
        <span class="step">{step('name')} · Name</span>
        <input
          class="name ui-field"
          bind:value={draft.name}
          on:input={() => void refresh()}
          spellcheck="false"
          placeholder="Installation name"
        />
      </section>
    </div>
  {/if}

  <svelte:fragment slot="footer">
    {#if options && draft}
      {#if error}
        <Notice tone="danger">{error}</Notice>
      {:else if problems.length > 0}
        <p class="problem">{problems[0]}</p>
      {/if}
      <Button block variant="primary" disabled={!ready} onClick={create}>
        {busy ? 'Creating…' : 'Create installation'}
      </Button>
    {/if}
  </svelte:fragment>
</Sheet>

<style>
  /* The sheet scrolls; this is only the rhythm between the questions. */
  .body {
    display: flex;
    flex-direction: column;
    gap: var(--space-6);
  }

  section {
    display: flex;
    flex-direction: column;
    gap: var(--space-3);
  }

  .step {
    font-size: var(--type-label-size);
    font-weight: var(--type-heading-weight);
    letter-spacing: var(--type-label-tracking);
    color: var(--color-text-secondary);
  }

  /* -- the type cards --------------------------------------------------- */
  .kinds {
    display: flex;
    flex-direction: column;
    gap: var(--space-2);
  }

  .kind {
    display: flex;
    flex-direction: column;
    gap: var(--space-1);
    padding: var(--space-3) var(--space-4);
    text-align: left;
    border-radius: var(--radius-md);
    background-color: var(--control-fill-faint);
    box-shadow: var(--control-rim);
    transition:
      background-color var(--motion-hover) var(--ease-standard),
      box-shadow var(--motion-hover) var(--ease-standard);
  }

  .kind:not(:disabled):hover {
    background-color: var(--control-fill);
  }

  .kind:focus-visible {
    outline: var(--focus-ring-width) solid var(--color-focus);
    outline-offset: 2px;
  }

  /* Chosen is denser and brighter-rimmed, not tinted. */
  .kind.selected {
    background-color: var(--control-fill-selected);
    box-shadow: var(--control-rim-strong);
  }

  .kind:disabled {
    opacity: 0.45;
    cursor: default;
  }

  .kind-head {
    display: flex;
    align-items: center;
    gap: var(--space-2);
  }

  .kind-name {
    font-size: var(--type-body-size);
    font-weight: var(--type-heading-weight);
    color: var(--color-text);
  }

  .badge {
    display: inline-flex;
    align-items: center;
    gap: 3px;
    padding: 2px var(--space-2);
    border-radius: var(--radius-pill);
    font-size: var(--type-caption-size);
    font-weight: var(--type-label-weight);
    color: var(--color-text);
    background-color: var(--control-fill-hover);
  }

  .tick {
    margin-left: auto;
    color: var(--color-text);
  }

  .kind-summary {
    font-size: var(--type-secondary-size);
    color: var(--color-text-secondary);
  }

  /* Secondary rather than tertiary: on the chosen card's denser fill, tertiary
     measured 3.5:1. */
  .kind-includes {
    margin: 2px 0 0;
    padding-left: var(--space-4);
    font-size: var(--type-caption-size);
    color: var(--color-text-secondary);
  }

  .kind-includes li {
    margin-top: 2px;
  }

  .sub {
    display: flex;
    flex-direction: column;
    gap: var(--space-2);
  }

  /* -- components ------------------------------------------------------- */
  .component {
    display: flex;
    align-items: center;
    gap: var(--space-3);
    padding: var(--space-3) 0 var(--space-1);
    box-shadow: 0 calc(-1 * var(--hairline)) 0 var(--control-hairline);
  }

  .component-text {
    flex: 1;
    min-width: 0;
  }

  .component-name {
    font-size: var(--type-body-size);
    font-weight: var(--type-button-weight);
  }

  .component p {
    margin: 2px 0 0;
    font-size: var(--type-caption-size);
    color: var(--color-text-secondary);
  }

  .quiet {
    margin: 0;
    font-size: var(--type-caption-size);
    line-height: var(--type-caption-leading);
    color: var(--color-text-tertiary);
  }

  .indent {
    margin: 0;
  }

  .note {
    margin: var(--space-2) 0 0;
    font-size: var(--type-caption-size);
    color: var(--color-text-tertiary);
    line-height: 1.5;
  }

  /* -- rows -------------------------------------------------------------- */
  .row {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: var(--space-3);
  }

  .label {
    font-size: var(--type-body-size);
    color: var(--color-text-secondary);
  }

  .right {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    margin-left: auto;
  }

  .grow {
    flex: 1;
  }

  /* Fixed width, so the row does not shuffle while the slider is dragged
     through 512 MB and 16384 MB. */
  .value {
    margin-left: auto;
    min-width: 8ch;
    text-align: right;
    font-size: var(--type-secondary-size);
    font-weight: var(--type-button-weight);
  }

  .slider input {
    width: 100%;
  }

  .problem {
    margin: 0;
    font-size: var(--type-caption-size);
    color: var(--color-warning-text);
  }
</style>

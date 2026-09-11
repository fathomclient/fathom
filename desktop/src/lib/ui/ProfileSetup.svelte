<!--
  Setting up a profile.

  ONE DRAWER, NOT A WIZARD.

  The brief asked for five steps and explicitly asked for them not to become
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
  import { onMount } from 'svelte';
  import { fade, fly, slide } from 'svelte/transition';
  import { Check, Download, Sparkles, X } from '@lucide/svelte';

  import Button from './Button.svelte';
  import Segmented from './Segmented.svelte';
  import Switch from './Switch.svelte';
  import { EASE, FAST, NORMAL } from '../motion';
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

<div
  class="scrim"
  role="button"
  tabindex="-1"
  aria-label="Close"
  on:click={onClose}
  on:keydown={(event) => event.key === 'Escape' && onClose()}
  transition:fade={{ duration: FAST }}
></div>

<aside class="drawer glass-strong" transition:fly={{ x: 420, duration: NORMAL, easing: EASE }}>
  <header>
    <h2>New installation</h2>
    <button class="close" title="Close" on:click={onClose}>
      <X size={15} strokeWidth={2.2} />
    </button>
  </header>

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
              title={kind.unavailable ?? ''}
              on:click={() => void choose('kind', kind.id)}
            >
              <span class="kind-head">
                <span class="kind-name">{kind.label}</span>
                {#if kind.recommended}
                  <span class="badge"><Sparkles size={10} strokeWidth={2.4} /> Recommended</span>
                {/if}
                {#if draft.kind === kind.id}
                  <span class="tick"><Check size={13} strokeWidth={2.6} /></span>
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
          <div class="sub" transition:slide={{ duration: NORMAL, easing: EASE }}>
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
        <section transition:slide={{ duration: NORMAL, easing: EASE }}>
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
                  <Download size={13} strokeWidth={2} />
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
          <div class="right">
            <span class="value tnum">
              {memoryAutomatic ? `${options.recommendedMemoryMb} MB` : `${draft.memoryMb} MB`}
            </span>
            <Switch
              checked={memoryAutomatic}
              label="Automatic memory"
              onChange={(on) =>
                void choose('memoryMb', on ? null : (options?.recommendedMemoryMb ?? 2048))}
            />
            <span class="quiet">Automatic</span>
          </div>
        </div>

        {#if !memoryAutomatic}
          <div class="slider" transition:slide={{ duration: FAST, easing: EASE }}>
            <input
              type="range"
              min={options.minMemoryMb}
              max={8192}
              step={512}
              value={draft.memoryMb ?? options.recommendedMemoryMb}
              on:input={(event) => void choose('memoryMb', Number(event.currentTarget.value))}
            />
          </div>
        {/if}

        <div class="row">
          <span class="label">Java</span>
          <div class="right">
            <Segmented
              options={options.java
                .filter((option) => option.id !== 'custom')
                .map((option) => ({ id: option.id, label: option.label }))}
              value={draft.java}
              onChange={(id) => void choose('java', id)}
            />
          </div>
        </div>
        <p class="quiet indent">
          {options.java.find((option) => option.id === draft?.java)?.detail ?? ''}
        </p>
      </section>

      <!-- 5. NAME -->
      <section>
        <span class="step">{step('name')} · Name</span>
        <input
          class="name"
          bind:value={draft.name}
          on:input={() => void refresh()}
          spellcheck="false"
          placeholder="Installation name"
        />
      </section>
    </div>

    <footer>
      {#if error}
        <p class="problem">{error}</p>
      {:else if problems.length > 0}
        <p class="problem">{problems[0]}</p>
      {/if}
      <Button block variant="primary" disabled={!ready} onClick={create}>
        {busy ? 'Creating…' : 'Create installation'}
      </Button>
    </footer>
  {/if}
</aside>

<style>
  .scrim {
    position: fixed;
    inset: 0;
    background: rgba(0, 0, 0, 0.45);
    z-index: 40;
  }

  .drawer {
    position: fixed;
    top: 0;
    right: 0;
    bottom: 0;
    z-index: 41;
    display: grid;
    grid-template-rows: auto 1fr auto;
    width: min(420px, 92vw);
    border-left: 1px solid var(--glass-edge);
  }

  header {
    display: flex;
    align-items: center;
    padding: var(--s-4) var(--s-5);
    border-bottom: 1px solid var(--chrome-line);
  }

  h2 {
    flex: 1;
    margin: 0;
    font-size: var(--t-section);
    font-weight: var(--w-semibold);
  }

  .close {
    display: grid;
    place-items: center;
    width: var(--h-sm);
    height: var(--h-sm);
    border-radius: var(--r-sm);
    color: var(--text-faint);
    transition: color var(--m-fast) var(--ease), background var(--m-fast) var(--ease);
  }

  .close:hover {
    color: var(--text);
    background: var(--fill-hover);
  }

  .body {
    overflow-y: auto;
    padding: var(--s-5);
    display: flex;
    flex-direction: column;
    gap: var(--s-6);
  }

  section {
    display: flex;
    flex-direction: column;
    gap: var(--s-3);
  }

  .step {
    font-size: var(--t-meta);
    font-weight: var(--w-semibold);
    letter-spacing: var(--track-caps);
    text-transform: uppercase;
    color: var(--text-label);
  }

  /* -- the type cards --------------------------------------------------- */
  .kinds {
    display: flex;
    flex-direction: column;
    gap: var(--s-2);
  }

  .kind {
    display: flex;
    flex-direction: column;
    gap: 5px;
    padding: var(--s-3) var(--s-4);
    text-align: left;
    border-radius: var(--r-md);
    background: var(--fill-sunken);
    border: 1px solid var(--chrome-line);
    transition:
      border-color var(--m-fast) var(--ease),
      background var(--m-fast) var(--ease);
  }

  .kind:not(:disabled):hover {
    background: var(--fill-hover);
  }

  .kind.selected {
    border-color: rgba(224, 80, 92, 0.45);
    background: linear-gradient(180deg, rgba(224, 80, 92, 0.14), rgba(132, 28, 36, 0.1));
  }

  .kind:disabled {
    opacity: 0.45;
    cursor: default;
  }

  .kind-head {
    display: flex;
    align-items: center;
    gap: var(--s-2);
  }

  .kind-name {
    font-size: var(--t-body-lg);
    font-weight: var(--w-semibold);
    color: var(--text);
  }

  .badge {
    display: inline-flex;
    align-items: center;
    gap: 3px;
    padding: 1px 6px;
    border-radius: var(--r-pill);
    font-size: var(--t-meta);
    font-weight: var(--w-medium);
    color: #ffb9bf;
    background: rgba(224, 80, 92, 0.18);
  }

  .tick {
    margin-left: auto;
    color: var(--accent);
  }

  .kind-summary {
    font-size: var(--t-body);
    color: var(--text-muted);
  }

  .kind-includes {
    margin: 2px 0 0;
    padding-left: 14px;
    font-size: var(--t-caption);
    color: var(--text-faint);
  }

  .kind-includes li {
    margin-top: 1px;
  }

  .sub {
    display: flex;
    flex-direction: column;
    gap: var(--s-2);
  }

  /* -- components ------------------------------------------------------- */
  .component {
    display: flex;
    align-items: center;
    gap: var(--s-3);
    padding: var(--s-2) 0;
    border-top: 1px solid var(--chrome-line);
  }

  .component-text {
    flex: 1;
  }

  .component-name {
    font-size: var(--t-body);
    font-weight: var(--w-medium);
  }

  .component p {
    margin: 1px 0 0;
    font-size: var(--t-caption);
    color: var(--text-muted);
  }

  .quiet {
    font-size: var(--t-caption);
    color: var(--text-faint);
  }

  .indent {
    margin: 0;
  }

  .note {
    margin: var(--s-2) 0 0;
    font-size: var(--t-caption);
    color: var(--text-faint);
    line-height: 1.5;
  }

  /* -- rows -------------------------------------------------------------- */
  .row {
    display: flex;
    align-items: center;
    gap: var(--s-3);
  }

  .label {
    font-size: var(--t-body);
    color: var(--text-muted);
  }

  .right {
    display: flex;
    align-items: center;
    gap: var(--s-2);
    margin-left: auto;
  }

  .value {
    font-size: var(--t-body);
    font-weight: var(--w-medium);
  }

  .slider input {
    width: 100%;
    accent-color: var(--accent);
  }

  .name {
    height: var(--h-lg);
    padding: 0 var(--s-3);
    border-radius: var(--r-sm);
    background: var(--fill-sunken);
    border: 1px solid var(--chrome-line-strong);
    font-size: var(--t-body-lg);
    color: var(--text);
    transition: border-color var(--m-fast) var(--ease);
  }

  .name:focus {
    border-color: var(--accent);
  }

  footer {
    display: flex;
    flex-direction: column;
    gap: var(--s-2);
    padding: var(--s-4) var(--s-5);
    border-top: 1px solid var(--chrome-line);
  }

  .problem {
    margin: 0;
    font-size: var(--t-caption);
    color: var(--warn);
  }
</style>

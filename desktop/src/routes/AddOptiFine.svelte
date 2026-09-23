<!--
  Add OptiFine.

  WHY THIS IS A SCREEN AND NOT A CARD

  There was a card on the Mods page, and it did the right thing in one step:
  drop a jar, it lands in the profile you are looking at. That is fine when you
  are already in a profile's mod list, and it is the wrong shape for the way
  people actually arrive here — having just come back from optifine.net with a
  file, not necessarily knowing which profile it belongs in.

  So this is the whole errand on one screen: where to get it, somewhere to put
  it, what it turned out to be, and which of your profiles it suits. The card
  stays as the shortcut for people already in a mod list.

  WHAT FATHOM DOES NOT DO

  Download it. OptiFine is proprietary and its licence does not permit
  redistribution, so the launcher will never fetch it, mirror it, or host it,
  and there is no version of this screen with a download button on it. What it
  can do is open the author's own page and then handle the file properly.

  Nothing is uploaded either. The jar is copied into a local content-addressed
  store; the network is not touched at any point in this flow.
-->
<script lang="ts">
  import { onMount } from 'svelte';
  import { Download, FolderOpen, HardDriveDownload, ShieldCheck, TriangleAlert } from '@lucide/svelte';

  import Page from '../lib/ui/Page.svelte';
  import Section from '../lib/ui/Section.svelte';
  import Button from '../lib/ui/Button.svelte';
  import Row from '../lib/ui/Row.svelte';
  import {
    addSuppliedJar,
    installComponent,
    openPage,
    supplyDroppedJar,
    supplyJar,
    suppliedJar,
    type SuppliedAdded,
    type SuppliedJar,
  } from '../lib/api';

  export let onChanged: () => void = () => {};

  let jar: SuppliedJar | null = null;
  let added: SuppliedAdded | null = null;
  let dropping = false;
  let busy = false;
  let error = '';

  onMount(() => {
    /* What was supplied last, if anything. Leaving this screen and coming back
       should not lose a file somebody has already chosen. */
    void (async () => {
      const held = await suppliedJar();
      if (held.chosen) jar = held;
    })();

    /* The drop target is the window, not the dashed rectangle: dropping a file
       near the box and having nothing happen is worse than having no box. The
       rectangle is what tells you the window will take it. */
    let stop: (() => void) | null = null;
    void (async () => {
      try {
        const { getCurrentWebview } = await import('@tauri-apps/api/webview');
        stop = await getCurrentWebview().onDragDropEvent((event) => {
          if (event.payload.type === 'over') {
            dropping = true;
          } else if (event.payload.type === 'drop') {
            dropping = false;
            void accept(event.payload.paths);
          } else {
            dropping = false;
          }
        });
      } catch {
        /* No drag-and-drop outside the app shell, and none needed: Choose JAR
           does the same thing. A missing convenience is not worth an error on
           the page. */
      }
    })();
    return () => stop?.();
  });

  async function accept(paths: string[]) {
    const path = paths.find((candidate) => candidate.toLowerCase().endsWith('.jar'));
    if (!path) {
      error = 'That is not a .jar. OptiFine downloads as a .jar file.';
      return;
    }
    await run(() => supplyDroppedJar(path));
  }

  async function choose() {
    await run(() => supplyJar());
  }

  async function run(action: () => Promise<SuppliedJar>) {
    if (busy) return;
    busy = true;
    error = '';
    try {
      const result = await action();
      if (result.chosen) {
        jar = result;
        added = null;
      }
    } catch (problem) {
      error = String(problem);
    } finally {
      busy = false;
    }
  }

  async function addTo(profileId: string) {
    if (busy || !jar) return;
    busy = true;
    error = '';
    try {
      added = await addSuppliedJar(profileId);
      /* Re-read the fits, so the profile just added to now says so instead of
         still offering a button that would add it twice. */
      jar = await suppliedJar();
      onChanged();
    } catch (problem) {
      error = String(problem);
    } finally {
      busy = false;
    }
  }

  /* Install the compatibility bridge into a profile.

     Separate from addTo() on purpose. Adding OptiFine and installing OptiFabric
     are two different things going into the profile, from two different places
     and under two different licences: OptiFine is the file the user brought,
     OptiFabric is fetched from Modrinth and needs their say-so. Collapsing them
     into one button would mean a download happening because somebody dropped a
     local file in. */
  async function addBridge(profileId: string) {
    if (busy || !jar?.bridge) return;
    busy = true;
    error = '';
    try {
      await installComponent(jar.bridge.id, profileId);
      jar = await suppliedJar();
      onChanged();
    } catch (problem) {
      error = String(problem);
    } finally {
      busy = false;
    }
  }

  function megabytes(bytes: number): string {
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  }

  $: usable = jar?.profiles.filter((fit) => fit.compatible) ?? [];
  $: unusable = jar?.profiles.filter((fit) => !fit.compatible) ?? [];
</script>

<Page title="Add OptiFine" summary={jar ? jar.description : 'User-supplied'}>
  <p class="lede">
    Download OptiFine from the author, then drop the JAR here.
  </p>

  <!-- The two ways in, side by side, because they are the same step done two
       ways rather than a primary action and a fallback. -->
  <div class="actions">
    <Button onClick={() => void openPage('optifine')}>
      <Download size={13} strokeWidth={2} />
      Open OptiFine Downloads
    </Button>
    <Button variant="primary" disabled={busy} onClick={() => void choose()}>
      <FolderOpen size={13} strokeWidth={2} />
      Choose JAR
    </Button>
  </div>

  <div class="drop" class:active={dropping} class:done={!!jar}>
    <HardDriveDownload size={26} strokeWidth={1.6} />
    <span class="headline">
      {dropping ? 'Let go to add it' : jar ? jar.fileName : 'Drop the OptiFine JAR anywhere on this window'}
    </span>
    <span class="quiet">
      {jar ? `${megabytes(jar.size)} · ${jar.entries} entries · ${jar.hash}` : 'Nothing is uploaded. The file is copied into Fathom’s local store.'}
    </span>
  </div>

  {#if error}
    <p class="error">
      <TriangleAlert size={14} strokeWidth={2} />
      {error}
    </p>
  {/if}

  {#if jar}
    <Section label="What arrived">
      <Row title={jar.name} detail={jar.description} compact />
      <Row
        title="Minecraft"
        detail={jar.minecraft ?? (jar.verified ? 'Not declared by the jar' : 'Not stated in the filename')}
        compact
      />
      <Row
        title="Version"
        detail={jar.version?.replace(/_/g, ' ') ??
          (jar.verified ? 'Not declared by the jar' : 'Not stated in the filename')}
        compact
      />
      {#if jar.verified}
        <!-- Worth saying out loud. Everything above was read out of the jar's
             own bytecode, so it holds even if the file has been renamed, and a
             jar merely *named* like OptiFine could not have produced it. -->
        <Row
          title="Identified"
          detail="Read from the jar itself, not its filename."
          compact
        />
      {/if}
      <Row title="Source" detail={jar.source} compact />
      <Row title="SHA-1" detail={jar.hash} compact />
      {#if !jar.recognised}
        <Row
          title="Not recognised"
          detail="Fathom does not know this file, so it is filed under its own name. It is still stored and can still be added."
          compact
        />
      {/if}
    </Section>

    {#if jar.bridge}
      <!-- Shown before the profile list, because it changes what "Add to
           profile" means. Adding OptiFine to a Legacy Fabric profile without
           this is the exact configuration that used to start the game and then
           fail inside class transformation. -->
      <Section label="Also needed">
        <Row title={jar.bridge.name} detail={jar.bridge.reason} compact />
        <Row title="Licence" detail={jar.bridge.summary} compact />
      </Section>
    {/if}

    <Section label="Add to installation" summary={`${usable.length} of ${jar.profiles.length} can take it`}>
      {#each usable as fit (fit.id)}
        <div class="fit">
          <div class="fit-text">
            <span class="fit-name">{fit.name}</span>
            {#if fit.note}<span class="quiet">{fit.note}</span>{/if}
            {#if fit.needsBridge && jar.bridge}
              <span class="needs">
                <TriangleAlert size={12} strokeWidth={2} />
                Needs {jar.bridge.name}, which this profile does not have yet.
              </span>
            {/if}
          </div>
          {#if fit.needsBridge && jar.bridge}
            <Button variant="normal" disabled={busy} onClick={() => void addBridge(fit.id)}>
              Install {jar.bridge.name}
            </Button>
          {/if}
          <Button variant="primary" disabled={busy} onClick={() => void addTo(fit.id)}>
            Add to profile
          </Button>
        </div>
      {/each}
      {#if usable.length === 0}
        <p class="none">
          None of your profiles can take this file. A profile needs a mod loader and the
          Minecraft version this jar was built for.
        </p>
      {/if}
      {#each unusable as fit (fit.id)}
        <Row title={fit.name} detail={fit.note} compact />
      {/each}
    </Section>
  {/if}

  {#if added}
    <Section label="Added" material>
      <p class="added">
        <ShieldCheck size={14} strokeWidth={2} />
        {added.message}
      </p>
      {#if added.standdowns.length > 0}
        <!-- The trade, stated at the moment it is made. Finding out later that
             an optimization you switched on is inert is the failure this is
             written against. -->
        <p class="quiet">Fathom stands down where OptiFine already does the work:</p>
        <ul>
          {#each added.standdowns as line}
            <li>{line}</li>
          {/each}
        </ul>
      {/if}
    </Section>
  {/if}
</Page>

<style>
  .lede {
    margin: 0 0 var(--s4);
    color: var(--text-muted);
    font-size: 13px;
    line-height: 1.5;
    max-width: 56ch;
  }

  .actions {
    display: flex;
    gap: var(--s2);
    flex-wrap: wrap;
    margin-bottom: var(--s4);
  }

  /* Large, and a real target: the window accepts the drop anywhere, and this is
     what says so. Dashed rather than solid, because a solid box reads as a
     container that already holds something. */
  .drop {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: var(--s1);
    min-height: 168px;
    padding: var(--s5);
    border: 1px dashed var(--glass-edge-strong);
    border-radius: var(--r-md);
    color: var(--text-faint);
    text-align: center;
    transition:
      border-color var(--m-fast) var(--ease),
      background var(--m-fast) var(--ease),
      color var(--m-fast) var(--ease);
  }

  .drop.active {
    border-color: var(--accent);
    background: var(--accent-wash);
    color: var(--text);
  }

  .drop.done {
    border-style: solid;
    border-color: var(--glass-edge);
    color: var(--text-muted);
  }

  .headline {
    font-size: 14px;
    font-weight: 500;
    color: var(--text);
  }

  .quiet {
    color: var(--text-faint);
    font-size: 12px;
  }

  .error {
    display: flex;
    align-items: center;
    gap: var(--s1);
    margin: var(--s3) 0 0;
    color: var(--bad);
    font-size: 13px;
  }

  .fit {
    display: flex;
    align-items: center;
    gap: var(--s3);
    padding: var(--s2) 0;
    border-bottom: 1px solid var(--chrome-line);
  }

  .needs {
    display: inline-flex;
    align-items: center;
    gap: 5px;
    font-size: var(--t-body);
    color: var(--warn);
  }

  .fit-text {
    display: flex;
    flex-direction: column;
    gap: 2px;
    flex: 1;
    min-width: 0;
  }

  .fit-name {
    font-size: 14px;
    color: var(--text);
  }

  .none {
    margin: 0;
    color: var(--text-faint);
    font-size: 13px;
    line-height: 1.5;
  }

  .added {
    display: flex;
    align-items: center;
    gap: var(--s1);
    margin: 0 0 var(--s2);
    color: var(--good);
    font-size: 13px;
  }

  ul {
    margin: var(--s1) 0 0;
    padding-left: var(--s4);
    color: var(--text-muted);
    font-size: 12px;
    line-height: 1.6;
  }
</style>

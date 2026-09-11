<!--
  The account drawer.

  # There is no Fathom account

  Fathom has no user database, no sign-up, and nothing to be a member of. So
  this drawer never says "Fathom account" — it says Microsoft, because a
  Microsoft account is the only account involved and Mojang is the party that
  requires it. Naming a product's own account system that does not exist is the
  kind of copy that gets written once and then has to be supported forever.

  # What is deliberately not shown

  The Microsoft email. It is an identifier for a different service, it is the
  most sensitive string in the session, and nobody standing in front of a
  launcher needs it to know who they are signed in as. The Minecraft username is
  the identity that matters here.

  Build-configuration detail — "this build has no application id" — sits behind
  a disclosure. It is true, actionable by exactly one person, and meaningless to
  everyone else.
-->
<script lang="ts">
  import { fly, fade, slide } from 'svelte/transition';
  import { X, ChevronDown, ShieldCheck, TriangleAlert } from '@lucide/svelte';
  import Button from './Button.svelte';
  import { EASE, FAST, NORMAL } from '../motion';
  import { NO_AUTH, type AccountSummary, type AuthProgress } from '../api';

  export let account: AccountSummary;
  /** Why sign-in is unavailable, when it is. Developer-facing. */
  export let note: string | null = null;
  export let canSignIn = false;
  export let credentialStore = '';
  /** False in every release build. See `AccountRow::development_available`. */
  export let developmentAvailable = false;
  /** The sign-in in flight, if any. */
  export let auth: AuthProgress = NO_AUTH;
  export let onSignIn: () => void = () => {};
  export let onSignOut: () => void = () => {};
  export let onCancelSignIn: () => void = () => {};
  export let onDevelopment: () => void = () => {};
  export let onClose: () => void;

  let detailsOpen = false;

  $: working = auth.active;
  $: failed = auth.phase === 'failed';
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

<aside class="drawer glass-strong" transition:fly={{ x: 340, duration: NORMAL, easing: EASE }}>
  <header>
    <h2>Account</h2>
    <button class="close" title="Close" on:click={onClose}>
      <X size={15} strokeWidth={2.2} />
    </button>
  </header>

  {#if account.signedIn}
    <div class="identity">
      <div class="avatar">{account.name?.[0]?.toUpperCase() ?? '?'}</div>
      <div class="who">
        <span class="name">{account.name}</span>
        <span class="kind" class:dev={account.development}>
          {#if account.development}
            <TriangleAlert size={12} strokeWidth={2.2} />
            Development identity
          {:else}
            <ShieldCheck size={12} strokeWidth={2.2} />
            Minecraft: Java Edition
          {/if}
        </span>
      </div>
    </div>

    {#if account.development}
      <p class="warn">
        Not a real account. It owns nothing and cannot join servers.
      </p>
    {/if}

    <div class="actions">
      <Button block variant="caution" disabled={working} onClick={onSignOut}>
        Forget this account
      </Button>
    </div>
  {:else if working}
    <!-- The device-code flow, mid-flight. The code is the whole screen while
         it is on offer: somebody is reading it off one device and typing it on
         another, and burying it under a paragraph is how it gets mistyped. -->
    <div class="signin">
      <span class="provider">Microsoft</span>
      <h3>{auth.headline}</h3>

      {#if auth.userCode}
        <div class="code">{auth.userCode}</div>
        <p class="fine">Go to <span class="uri">{auth.verificationUri}</span> and enter it.</p>
      {:else}
        <p class="fine">{auth.detail}</p>
      {/if}

      <Button block onClick={onCancelSignIn}>Cancel</Button>
    </div>
  {:else}
    <div class="signin">
      <span class="provider">Microsoft</span>
      <h3>Sign in to play Minecraft</h3>

      {#if failed}
        <p class="warn">{auth.failure}</p>
      {/if}

      <Button
        block
        variant="primary"
        disabled={!canSignIn}
        title={canSignIn ? '' : 'This build cannot sign in yet'}
        onClick={onSignIn}
      >
        {failed ? 'Try again' : 'Sign in with Microsoft'}
      </Button>
      <p class="fine">Fathom never receives your Microsoft password.</p>

      {#if developmentAvailable}
        <!-- Only ever rendered by a build compiled with `dev-auth` and run with
             the environment variable set, which is to say never by a release.
             Labelled as what it is, not as a second way to sign in. -->
        <div class="dev">
          <Button block onClick={onDevelopment}>Use the development identity</Button>
          <p class="fine">Owns nothing. Cannot join servers. For testing the launch path.</p>
        </div>
      {/if}
    </div>
  {/if}

  <div class="spacer"></div>

  <button class="disclose" on:click={() => (detailsOpen = !detailsOpen)}>
    <span>Authentication details</span>
    <span class="chevron" class:open={detailsOpen}>
      <ChevronDown size={14} strokeWidth={2.2} />
    </span>
  </button>

  {#if detailsOpen}
    <div class="details" transition:slide={{ duration: NORMAL, easing: EASE }}>
      <dl>
        <div><dt>Tokens</dt><dd>{credentialStore || 'OS credential store'}</dd></div>
        <div><dt>Password</dt><dd>Never seen by Fathom</dd></div>
        <div>
          <dt>Sign-in</dt>
          <dd>{canSignIn ? 'Available' : 'Unavailable in this build'}</dd>
        </div>
      </dl>
      {#if note}
        <p class="note">{note}</p>
      {/if}
    </div>
  {/if}
</aside>

<style>

  /* The device code. Large, monospace, and selectable -- somebody may want to
     copy it rather than read it aloud. Letter-spaced because the codes contain
     characters that are easy to confuse at a normal tracking. */
  .code {
    padding: var(--s-3);
    border-radius: var(--r-sm);
    background: var(--fill-sunken);
    border: 1px solid var(--chrome-line-strong);
    font-family: 'Consolas', 'Cascadia Mono', ui-monospace, monospace;
    font-size: 25px;
    font-weight: var(--w-semibold);
    letter-spacing: 0.12em;
    text-align: center;
    color: var(--text);
    user-select: text;
  }

  .uri {
    color: var(--text);
    user-select: text;
  }

  .dev {
    margin-top: var(--s-4);
    padding-top: var(--s-4);
    border-top: 1px solid var(--chrome-line);
  }
  .scrim {
    position: fixed;
    inset: 0;
    background: rgba(0, 0, 0, 0.5);
    z-index: 40;
  }

  .drawer {
    position: fixed;
    top: var(--topbar-height);
    right: 0;
    bottom: var(--bottombar-height);
    width: 340px;
    display: flex;
    flex-direction: column;
    gap: var(--s-4);
    padding: var(--s-5);
    border-radius: 0;
    border-top: none;
    border-right: none;
    border-bottom: none;
    overflow-y: auto;
    z-index: 41;
  }

  header {
    display: flex;
    align-items: center;
  }

  h2 {
    flex: 1;
    margin: 0;
    font-size: var(--t-section);
    font-weight: var(--w-semibold);
    letter-spacing: var(--track-snug);
  }

  .close {
    display: grid;
    place-items: center;
    width: var(--h-sm);
    height: var(--h-sm);
    border-radius: var(--r-xs);
    color: var(--text-faint);
    transition:
      color var(--m-fast) var(--ease),
      background var(--m-fast) var(--ease);
  }

  .close:hover {
    color: var(--text);
    background: var(--fill-hover);
  }

  /* -- signed in --------------------------------------------------------- */
  .identity {
    display: flex;
    align-items: center;
    gap: var(--s-3);
  }

  .avatar {
    display: grid;
    place-items: center;
    width: 44px;
    height: 44px;
    border-radius: var(--r-md);
    background: linear-gradient(160deg, var(--accent-strong), var(--accent-deep));
    color: #fff;
    font-size: var(--t-section);
    font-weight: var(--w-bold);
    box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.25);
  }

  .who {
    display: flex;
    flex-direction: column;
    gap: var(--s-1);
    min-width: 0;
  }

  .name {
    font-size: var(--t-row);
    font-weight: var(--w-semibold);
    letter-spacing: var(--track-snug);
  }

  .kind {
    display: flex;
    align-items: center;
    gap: var(--s-1);
    font-size: var(--t-caption);
    color: var(--good);
  }

  .kind.dev {
    color: var(--warn);
  }

  .warn {
    margin: 0;
    padding: var(--s-2) var(--s-3);
    border-radius: var(--r-sm);
    background: rgba(224, 174, 87, 0.1);
    border: 1px solid rgba(224, 174, 87, 0.26);
    color: var(--warn);
    font-size: var(--t-caption);
  }

  .actions {
    display: flex;
    flex-direction: column;
    gap: var(--s-2);
  }

  /* -- signed out -------------------------------------------------------- */
  .signin {
    display: flex;
    flex-direction: column;
    gap: var(--s-3);
  }

  .provider {
    font-size: var(--t-meta);
    font-weight: var(--w-semibold);
    letter-spacing: var(--track-caps);
    text-transform: uppercase;
    color: var(--text-faint);
  }

  h3 {
    margin: 0;
    font-size: var(--t-emphasis, 16px);
    font-weight: var(--w-semibold);
    letter-spacing: var(--track-snug);
  }

  .fine {
    margin: 0;
    font-size: var(--t-caption);
    color: var(--text-faint);
  }

  .spacer {
    flex: 1;
  }

  /* -- details ----------------------------------------------------------- */
  .disclose {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: var(--s-2) 0;
    font-size: var(--t-body);
    color: var(--text-muted);
    border-top: 1px solid var(--chrome-line);
    transition: color var(--m-fast) var(--ease);
  }

  .disclose:hover {
    color: var(--text);
  }

  .chevron {
    display: grid;
    place-items: center;
    transition: transform var(--m-normal) var(--ease);
  }

  .chevron.open {
    transform: rotate(180deg);
  }

  .details {
    font-size: var(--t-caption);
    color: var(--text-muted);
  }

  dl {
    display: flex;
    flex-direction: column;
    gap: var(--s-1);
    margin: 0;
  }

  dl div {
    display: flex;
    gap: var(--s-2);
  }

  dt {
    width: 76px;
    flex-shrink: 0;
    color: var(--text-faint);
  }

  dd {
    margin: 0;
    flex: 1;
  }

  .note {
    margin: var(--s-2) 0 0;
    padding: var(--s-2);
    border-radius: var(--r-xs);
    background: var(--fill-sunken);
    border: 1px solid var(--chrome-line);
    line-height: 1.5;
  }
</style>

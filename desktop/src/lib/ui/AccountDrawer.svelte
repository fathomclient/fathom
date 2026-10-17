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
  import { ICON } from '../metrics.generated';
  import { slide } from 'svelte/transition';
  import { X, ChevronDown, ShieldCheck, TriangleAlert, Plus, Check, LogIn } from '@lucide/svelte';
  import Button from './Button.svelte';
  import Sheet from './Sheet.svelte';
  import Notice from './Notice.svelte';
  import OrbitMark from '../brand/OrbitMark.svelte';
  import { tooltip } from '../tooltip';
  import { EASE, NORMAL } from '../motion';
  import { NO_AUTH, type AccountSummary, type AuthProgress } from '../api';

  export let account: AccountSummary;
  export let accounts: AccountSummary[] = [];
  /** Why sign-in is unavailable, when it is. Developer-facing. */
  export let note: string | null = null;
  export let canSignIn = false;
  export let credentialStore = '';
  /** False in every release build. See `AccountRow::development_available`. */
  export let developmentAvailable = false;
  /** The sign-in in flight, if any. */
  export let auth: AuthProgress = NO_AUTH;
  export let onSignIn: () => void = () => {};
  export let onSwitch: (id: string) => void = () => {};
  export let onForget: (id: string) => void = () => {};
  export let onCancelSignIn: () => void = () => {};
  export let onReopenPage: () => void = () => {};
  export let onDevelopment: () => void = () => {};
  export let onClose: () => void;

  let detailsOpen = false;

  $: working = auth.active;
  $: failed = auth.phase === 'failed';
</script>

<Sheet title="Account" width={380} {onClose}>

  {#if account.signedIn}
    <div class="identity">
      <div class="avatar" class:has-face={!!account.avatar}>
        {#if account.avatar}
          <img class="face" src={account.avatar} alt="" draggable="false" />
        {:else}
          {account.name?.[0]?.toUpperCase() ?? '?'}
        {/if}
      </div>
      <div class="who">
        <span class="name">{account.name}</span>
        <span class="kind" class:dev={account.development}>
          {#if account.development}
            <TriangleAlert size={ICON.caption} />
            Development identity
          {:else}
            <ShieldCheck size={ICON.caption} />
            Minecraft: Java Edition
          {/if}
        </span>
      </div>
    </div>

    {#if account.development}
      <Notice tone="warning">Not a real account. It owns nothing and cannot join servers.</Notice>
    {/if}

    <div class="actions">
      <Button block disabled={working} onClick={onSignIn}>
        <Plus size={ICON.inline} />
        Add another Microsoft account
      </Button>
    </div>

  {:else if account.needsSignIn && !working}
    <!-- A known account whose session did not come back: the restore failed,
         or Rust gave up on it after 20 seconds. Who it is stays on screen, so
         this reads as "sign this account in again", not "who are you?". -->
    <div class="identity expired">
      <div class="avatar" class:has-face={!!account.avatar}>
        {#if account.avatar}
          <img class="face" src={account.avatar} alt="" draggable="false" />
        {:else}
          {account.name?.[0]?.toUpperCase() ?? '?'}
        {/if}
      </div>
      <div class="who">
        <span class="name">{account.name}</span>
        <span class="kind">Signed out</span>
      </div>
    </div>
    <Notice tone="warning">
      {auth.failure ?? 'Your saved session could not be restored. Sign in again to continue.'}
    </Notice>
    <div class="actions">
      <Button
        block
        variant="primary"
        disabled={!canSignIn}
        title={canSignIn ? '' : 'This build cannot sign in yet'}
        onClick={onSignIn}
      >
        Sign in again
      </Button>
    </div>
    <p class="fine">Fathom never receives your Microsoft password.</p>

  {:else if account.remembered && !working}
    <!-- A remembered account whose session is coming back on its own. Saying
         "Sign in" here would read as though Fathom forgot who this is. -->
    <div class="identity restoring">
      <div class="avatar" class:has-face={!!account.avatar}>
        {#if account.avatar}
          <img class="face" src={account.avatar} alt="" draggable="false" />
        {:else}
          {account.name?.[0]?.toUpperCase() ?? '?'}
        {/if}
      </div>
      <div class="who">
        <span class="name">{account.name}</span>
        <span class="kind">
          <OrbitMark height={12} working label="Restoring" />
          Restoring your session
        </span>
      </div>
    </div>
    <p class="fine">This takes a few seconds. Nothing to do.</p>

  {:else if working}
    <!-- The device-code flow, mid-flight. The code is the whole screen while
         it is on offer: somebody is reading it off one device and typing it on
         another, and burying it under a paragraph is how it gets mistyped. -->
    <div class="signin">
      <span class="provider">Microsoft</span>
      <h3 class="working"><OrbitMark height={14} working label="Signing in" /> {auth.headline}</h3>

      {#if auth.userCode}
        {#if auth.browserOpened}
          <p class="fine">{auth.detail}</p>
          <p class="fine">
            {auth.codeCopied
              ? 'The code is copied. Paste it on Microsoft’s page:'
              : 'Paste or enter this code on Microsoft’s page:'}
          </p>
        {/if}
        <div class="code">{auth.userCode}</div>
        {#if !auth.browserOpened}
          <p class="fine">Go to <span class="uri">{auth.verificationUri}</span> and enter it.</p>
        {/if}
        <Button block variant="primary" onClick={onReopenPage}>Copy code and open Microsoft’s page</Button>
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
        <Notice tone="danger">{auth.failure}</Notice>
      {/if}

      <Button
        block
        variant="primary"
        disabled={!canSignIn}
        title={canSignIn ? '' : 'This build cannot sign in yet'}
        onClick={onSignIn}
      >
        {failed ? 'Try again' : accounts.length ? 'Add another Microsoft account' : 'Sign in with Microsoft'}
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

  {#if !working && accounts.length}
    <section class="account-list">
      <span class="provider">Saved accounts</span>
      {#each accounts as saved (saved.uuid)}
        <div class="account-row" class:current={saved.signedIn}>
          <button
            class="account-choice"
            disabled={saved.signedIn || !saved.uuid}
            use:tooltip={saved.signedIn ? 'Currently active' : `Switch to ${saved.name}`}
            on:click={() => saved.uuid && onSwitch(saved.uuid)}
          >
            <span class="mini-avatar" class:has-face={!!saved.avatar}>
              {#if saved.avatar}
                <img class="face" src={saved.avatar} alt="" draggable="false" />
              {:else}
                {saved.name?.[0]?.toUpperCase() ?? '?'}
              {/if}
            </span>
            <span class="saved-name">{saved.name}</span>
            {#if saved.signedIn}
              <Check size={ICON.inline} />
            {:else}
              <LogIn size={ICON.inline} />
            {/if}
          </button>
          <button
            class="forget ui-icon-button danger"
            use:tooltip={`Forget ${saved.name}`}
            aria-label={`Forget ${saved.name}`}
            disabled={!saved.uuid}
            on:click={() => saved.uuid && onForget(saved.uuid)}
          ><X size={ICON.inline} /></button>
        </div>
      {/each}
    </section>
  {/if}

  <div class="spacer"></div>

  <button class="disclose" aria-expanded={detailsOpen} on:click={() => (detailsOpen = !detailsOpen)}>
    <span>Authentication details</span>
    <span class="chevron" class:open={detailsOpen}>
      <ChevronDown size={ICON.inline} />
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
</Sheet>

<style>
  /* The device code. Large, monospace, and selectable -- somebody may want to
     copy it rather than read it aloud. Letter-spaced because the codes contain
     characters that are easy to confuse at a normal tracking. */
  .code {
    padding: var(--space-4) var(--space-3);
    border-radius: var(--radius-md);
    background-color: var(--control-fill-sunken);
    box-shadow: var(--control-rim);
    font-family: var(--type-family-mono);
    font-size: var(--type-title-size);
    font-weight: var(--type-heading-weight);
    letter-spacing: 0.12em;
    text-align: center;
    color: var(--color-text);
    user-select: text;
  }

  .uri {
    color: var(--color-text);
    user-select: text;
  }

  .dev {
    display: flex;
    flex-direction: column;
    gap: var(--space-2);
    margin-top: var(--space-2);
    padding-top: var(--space-4);
    box-shadow: 0 calc(-1 * var(--hairline)) 0 var(--control-hairline);
  }

  /* -- signed in --------------------------------------------------------- */
  .identity {
    display: flex;
    align-items: center;
    gap: var(--space-3);
  }

  .avatar {
    display: grid;
    place-items: center;
    width: 48px;
    height: 48px;
    flex-shrink: 0;
    /* Round, like the account capsule's face in the header: one avatar shape
       everywhere. */
    border-radius: var(--radius-pill);
    background-color: var(--control-fill-selected);
    box-shadow: var(--control-rim-strong);
    color: var(--color-text);
    font-size: var(--type-heading-size);
    font-weight: var(--type-title-weight);
  }

  /* The player's head. The pixels are kept square rather than smoothed, at a
     whole multiple of the 8px face (48 = 6x), and the rim is drawn over the
     image: an inset shadow on the box itself would be painted beneath it. */
  .avatar.has-face {
    position: relative;
    overflow: hidden;
    padding: 0;
  }

  .avatar.has-face::after {
    content: '';
    position: absolute;
    inset: 0;
    border-radius: inherit;
    box-shadow: var(--control-rim-strong);
    pointer-events: none;
  }

  .face {
    width: 100%;
    height: 100%;
    image-rendering: auto;
    object-fit: cover;
  }

  .who {
    display: flex;
    flex-direction: column;
    gap: var(--space-1);
    min-width: 0;
  }

  /* A Minecraft name is at most sixteen characters; it always fits, so it is
     never cut. */
  .name {
    font-size: var(--type-heading-size);
    font-weight: var(--type-heading-weight);
    letter-spacing: var(--type-heading-tracking);
    overflow-wrap: anywhere;
  }

  /* What the identity is, in the secondary colour: a signed-in account is the
     normal state and is not celebrated in green. */
  .kind {
    display: flex;
    align-items: center;
    gap: var(--space-1);
    font-size: var(--type-caption-size);
    color: var(--color-text-secondary);
  }

  .identity.restoring .avatar,
  .identity.expired .avatar {
    opacity: 0.7;
  }

  .kind.dev {
    color: var(--color-warning-text);
  }

  .actions {
    display: flex;
    flex-direction: column;
    gap: var(--space-2);
  }

  .account-list {
    display: flex;
    flex-direction: column;
    gap: var(--space-2);
    padding-top: var(--space-1);
  }

  .account-row {
    display: grid;
    grid-template-columns: 1fr auto;
    align-items: center;
    gap: var(--space-1);
  }

  .account-choice {
    min-width: 0;
    height: var(--control-height-lg);
    display: flex;
    align-items: center;
    gap: var(--space-3);
    padding: 0 var(--space-3) 0 var(--space-2);
    border-radius: var(--radius-md);
    color: var(--color-text-secondary);
    background-color: var(--control-fill);
    box-shadow: var(--control-rim);
    transition:
      background-color var(--motion-hover) var(--ease-standard),
      color var(--motion-hover) var(--ease-standard);
  }

  .account-choice:hover:not(:disabled) {
    background-color: var(--control-fill-hover);
    color: var(--color-text);
  }

  .account-choice:focus-visible {
    outline: var(--focus-ring-width) solid var(--color-focus);
    outline-offset: 2px;
  }

  /* The active account is the denser row, not a coloured one. */
  .account-row.current .account-choice {
    color: var(--color-text);
    background-color: var(--control-fill-selected);
    box-shadow: var(--control-rim-strong);
    cursor: default;
  }

  .mini-avatar {
    position: relative;
    width: 24px;
    height: 24px;
    display: grid;
    place-items: center;
    flex: 0 0 auto;
    overflow: hidden;
    border-radius: var(--radius-pill);
    background-color: var(--control-fill-selected);
    color: var(--color-text);
    font-size: var(--type-caption-size);
    font-weight: var(--type-heading-weight);
  }

  .mini-avatar::after {
    content: '';
    position: absolute;
    inset: 0;
    border-radius: inherit;
    box-shadow: var(--control-rim);
    pointer-events: none;
  }

  .saved-name {
    flex: 1;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    text-align: left;
    font-size: var(--type-body-size);
  }

  /* -- signed out -------------------------------------------------------- */
  .signin {
    display: flex;
    flex-direction: column;
    gap: var(--space-3);
  }

  .provider {
    font-size: var(--type-label-size);
    font-weight: var(--type-heading-weight);
    letter-spacing: var(--type-label-tracking);
    color: var(--color-text-tertiary);
  }

  h3 {
    margin: 0;
    font-size: var(--type-heading-size);
    font-weight: var(--type-heading-weight);
    letter-spacing: var(--type-heading-tracking);
    line-height: var(--type-heading-leading);
  }

  h3.working {
    display: flex;
    align-items: center;
    gap: var(--space-2);
  }

  .fine {
    margin: 0;
    font-size: var(--type-caption-size);
    line-height: var(--type-caption-leading);
    color: var(--color-text-tertiary);
  }

  .spacer {
    flex: 1;
  }

  /* -- details ----------------------------------------------------------- */
  .disclose {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: var(--space-3) 0 var(--space-1);
    font-size: var(--type-secondary-size);
    color: var(--color-text-secondary);
    box-shadow: 0 calc(-1 * var(--hairline)) 0 var(--control-hairline);
    transition: color var(--motion-hover) var(--ease-standard);
  }

  .disclose:hover {
    color: var(--color-text);
  }

  .chevron {
    display: grid;
    place-items: center;
    transition: transform var(--motion-state) var(--ease-standard);
  }

  .chevron.open {
    transform: rotate(180deg);
  }

  .details {
    font-size: var(--type-caption-size);
    color: var(--color-text-secondary);
  }

  dl {
    display: flex;
    flex-direction: column;
    gap: var(--space-1);
    margin: 0;
  }

  dl div {
    display: flex;
    gap: var(--space-2);
  }

  dt {
    width: 76px;
    flex-shrink: 0;
    color: var(--color-text-tertiary);
  }

  dd {
    margin: 0;
    flex: 1;
  }

  .note {
    margin: var(--space-2) 0 0;
    padding: var(--space-2) var(--space-3);
    border-radius: var(--radius-sm);
    background-color: var(--control-fill-sunken);
    box-shadow: var(--control-rim);
    line-height: 1.5;
  }
</style>

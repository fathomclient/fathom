<!--
  What the compatibility resolver said, as one small mark.

  Four states, not three: "checking" is shown honestly while a project's builds
  are being read, rather than letting an unchecked card look compatible.
-->
<script lang="ts">
  import { CircleCheck, CircleQuestionMark, CircleX, LoaderCircle } from '@lucide/svelte';
  import { tooltip } from '../../lib/tooltip';
  import type { Verdict } from '../../lib/api';

  export let verdict: Verdict | 'checking' | 'unknown';
  /** The resolver's sentence, shown on hover. */
  export let detail = '';

  const LABEL: Record<typeof verdict, string> = {
    compatible: 'Compatible',
    unverified: 'Unverified',
    incompatible: 'Incompatible',
    checking: 'Checking',
    unknown: 'Unknown',
  };
</script>

<span class="badge {verdict}" use:tooltip={detail || LABEL[verdict]}>
  {#if verdict === 'compatible'}
    <CircleCheck size={13} strokeWidth={2.2} />
  {:else if verdict === 'unverified'}
    <CircleQuestionMark size={13} strokeWidth={2.2} />
  {:else if verdict === 'incompatible'}
    <CircleX size={13} strokeWidth={2.2} />
  {:else if verdict === 'checking'}
    <span class="spin"><LoaderCircle size={13} strokeWidth={2.2} /></span>
  {/if}
  {LABEL[verdict]}
</span>

<style>
  .badge {
    display: inline-flex;
    align-items: center;
    gap: 5px;
    height: 22px;
    padding: 0 9px 0 7px;
    border-radius: var(--radius-pill);
    font-size: var(--type-caption-size);
    font-weight: var(--type-button-weight);
    white-space: nowrap;
    color: var(--color-text-tertiary);
    background-color: var(--control-fill-faint);
    box-shadow: inset 0 0 0 var(--hairline) var(--control-hairline);
  }

  /* Steel-cyan, and only here: the one claim the browser makes in colour. */
  .compatible {
    color: var(--color-signal);
    background-color: var(--color-signal-area);
    box-shadow: inset 0 0 0 var(--hairline) color-mix(in srgb, var(--color-signal) 35%, transparent);
  }

  .unverified {
    color: var(--color-warning-text);
    background-color: color-mix(in srgb, var(--color-warning-tint) 60%, transparent);
    box-shadow: inset 0 0 0 var(--hairline) var(--color-warning-rim);
  }

  .incompatible {
    color: var(--color-danger-text);
    background-color: color-mix(in srgb, var(--color-danger-tint) 50%, transparent);
    box-shadow: inset 0 0 0 var(--hairline) var(--color-danger-rim);
  }

  .spin {
    display: inline-flex;
    animation: spin 1.1s linear infinite;
  }

  @keyframes spin {
    to {
      transform: rotate(360deg);
    }
  }

  @media (prefers-reduced-motion: reduce) {
    .spin {
      animation: none;
    }
  }
</style>

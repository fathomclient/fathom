<!--
  A notice: status that stays on the surface where it happened.

  Fathom has no toast system on purpose -- an outcome that vanishes before it is
  read is not reported -- so this is the notification surface. It sits inline,
  in the flow of the page or sheet it concerns, and stays until the state it
  describes changes or it is dismissed.

  Every notice is standard glass. Tones are the only place a hue carries
  meaning: `neutral` is the glass alone, and `success`, `warning` and `danger`
  lay a wash and a rim in their status colour over it. `floating` adds the
  pane's shadow, for status that has no page to sit in.
-->
<script lang="ts">
  import { X } from '@lucide/svelte';

  export let tone: 'neutral' | 'success' | 'warning' | 'danger' = 'neutral';
  export let floating = false;
  /** Shows a dismiss control when set. */
  export let onDismiss: (() => void) | null = null;
</script>

<div
  class="notice"
  class:tone-success={tone === 'success'}
  class:tone-warning={tone === 'warning'}
  class:tone-danger={tone === 'danger'}
  class:neutral={tone === 'neutral'}
  class:material-standard={floating}
  role={tone === 'danger' || tone === 'warning' ? 'alert' : 'status'}
>
  <div class="content"><slot /></div>
  {#if onDismiss}
    <button class="ui-icon-button small" aria-label="Dismiss" on:click={onDismiss}>
      <X size={14} strokeWidth={2.2} />
    </button>
  {/if}
</div>

<style>
  /* Always on standard glass, with the status wash laid over it: a notice can
     sit straight on the photograph, and a bare tint over bright sky would leave
     its text unreadable. The tone classes supply only the wash, rim and text
     colour, through their custom properties. */
  .notice {
    --surface-radius: var(--radius-md);
    display: flex;
    align-items: flex-start;
    gap: var(--space-2);
    padding: var(--space-3) var(--space-4);
    border-radius: var(--radius-md);
    background:
      linear-gradient(var(--tone-tint, transparent), var(--tone-tint, transparent)),
      var(--mat-standard-fill);
    -webkit-backdrop-filter: var(--mat-standard-filter);
    backdrop-filter: var(--mat-standard-filter);
    box-shadow:
      inset 0 0 0 1px var(--tone-rim, transparent),
      var(--mat-standard-rim);
    color: var(--tone-text, var(--color-text-secondary));
    font-size: var(--type-secondary-size);
    line-height: var(--type-secondary-leading);
  }

  .notice.material-standard {
    box-shadow:
      inset 0 0 0 1px var(--tone-rim, transparent),
      var(--mat-standard-rim),
      var(--mat-standard-shadow);
  }

  /* Inside a pane that is already glass -- a sheet, a popover, a section -- a
     second blur would sample the first one's blur for nothing. The notice goes
     flat there: the pane's own fill under a control fill, with the same wash,
     rim and text colour. */
  :global(:is(.material-light, .material-standard, .material-dense, .ui-popover)) .notice {
    background:
      linear-gradient(var(--tone-tint, transparent), var(--tone-tint, transparent)),
      var(--control-fill);
    -webkit-backdrop-filter: none;
    backdrop-filter: none;
    box-shadow:
      inset 0 0 0 1px var(--tone-rim, transparent),
      var(--control-rim);
  }

  .content {
    flex: 1;
    min-width: 0;
    overflow-wrap: anywhere;
  }

  .notice .ui-icon-button {
    margin: -4px -8px -4px 0;
    color: inherit;
  }
</style>

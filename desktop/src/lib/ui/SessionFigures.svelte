<!--
  The session's figures in one row: the launcher's half of the performance
  instrument, read from what the game wrote (`SessionPulse`).

  `live` is the session in progress: the frame rate now, then the session's
  percentiles, memory and modules. Otherwise it is a summary: the session's
  average, percentiles, hitches and how much play it measured.

  Each figure explains itself on hover or focus. The numbers are Geist Mono,
  so they hold still as they change.
-->
<script lang="ts">
  import { tooltip } from '../tooltip';
  import { EXPLAIN, elapsed, fps, gigabytes, ms } from '../session';
  import type { SessionPulse } from '../api';

  export let pulse: SessionPulse;
  export let live = false;

  $: figures = live
    ? [
        { label: 'Frame rate', value: fps(pulse.currentFps), unit: 'fps', explain: EXPLAIN.fps },
        { label: 'p95', value: ms(pulse.p95Ms), unit: 'ms', explain: `${EXPLAIN.p95} This session.` },
        { label: 'p99', value: ms(pulse.p99Ms), unit: 'ms', explain: `${EXPLAIN.p99} This session.` },
        {
          label: 'Memory',
          value: `${gigabytes(pulse.heapUsedMb)}/${gigabytes(pulse.heapMaxMb)}`,
          unit: 'GB',
          explain: EXPLAIN.memory,
        },
        { label: 'Modules', value: `${pulse.modules}`, unit: 'on', explain: EXPLAIN.modules },
      ]
    : [
        { label: 'Average', value: fps(pulse.averageFps), unit: 'fps', explain: EXPLAIN.average },
        { label: 'p95', value: ms(pulse.p95Ms), unit: 'ms', explain: EXPLAIN.p95 },
        { label: 'p99', value: ms(pulse.p99Ms), unit: 'ms', explain: EXPLAIN.p99 },
        { label: 'Hitches', value: `${pulse.hitches}`, unit: '', explain: EXPLAIN.hitches },
        { label: 'Play measured', value: elapsed(pulse.secondsOfPlay), unit: '', explain: EXPLAIN.play },
      ];
</script>

<dl class="figures">
  {#each figures as figure (figure.label)}
    <!-- svelte-ignore a11y-no-noninteractive-tabindex -->
    <div class="figure" tabindex="0" use:tooltip={figure.explain}>
      <dt>{figure.label}</dt>
      <dd>
        <span class="value mono">{figure.value}</span>{#if figure.unit}<span class="unit">{figure.unit}</span>{/if}
      </dd>
    </div>
  {/each}
</dl>

<style>
  .figures {
    display: grid;
    grid-template-columns: repeat(5, minmax(0, 1fr));
    gap: var(--g-s-3);
    margin: 0;
  }

  .figure {
    min-width: 0;
    border-radius: var(--radius-xs);
    outline: none;
  }

  .figure:focus-visible {
    box-shadow: 0 0 0 var(--focus-ring-width) var(--color-focus);
  }

  dt {
    margin-bottom: 2px;
    overflow: hidden;
    color: var(--g-text-tertiary);
    font: var(--g-type-caption-weight) var(--g-type-caption-size) / var(--g-type-caption-line) var(--g-type-caption-family);
    letter-spacing: var(--g-type-caption-tracking);
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  dd {
    display: flex;
    align-items: baseline;
    gap: 4px;
    min-width: 0;
    margin: 0;
    white-space: nowrap;
  }

  .value {
    overflow: hidden;
    color: var(--g-text-primary);
    font-size: var(--g-type-heading-size);
    font-variant-numeric: tabular-nums;
    text-overflow: ellipsis;
  }

  .unit {
    color: var(--g-text-tertiary);
    font: var(--g-type-caption-weight) var(--g-type-caption-size) / 1 var(--g-type-caption-family);
  }
</style>

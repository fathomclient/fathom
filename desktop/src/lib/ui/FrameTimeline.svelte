<!--
  The session timeline: the same drawing as the in-game instrument
  (`fabric/hub/Instrument.java`), so the launcher and the Hub read alike.

  One column per bucket of play. The faint column is the bucket's mean frame
  time; the bright tip above it is its slowest frame. A smooth stretch is short
  columns with their tips resting on them; a stutter is a tip standing clear,
  in the warning colour once it is a hitch. A hairline marks a change -- a
  world joined, a setting changed -- so a step can be read against its cause.
  Newest on the right.

  Hovering reads a column out in the caption line, in words, rather than
  floating a box over the graph it is describing.
-->
<script lang="ts">
  import { fade } from 'svelte/transition';
  import { EASE, MOTION_STATE } from '../motion';
  import { HITCH_MS, ms } from '../session';
  import type { SessionPulse } from '../api';

  /** A 60 fps frame: the reference line across the graph. */
  const FRAME_60 = 1000 / 60;
  /** How far the slowest frame must stand above the mean to get a whisker. */
  const UNEVEN = 2.5;

  export let pulse: SessionPulse;
  export let height = 40;
  /** The caption when nothing is hovered. */
  export let caption = '';

  let width = 0;
  let hovered: number | null = null;

  $: count = Math.min(pulse.timelineFps.length, pulse.timelineWorstMs.length);
  $: slots = Math.max(count, 30);
  $: column = width > 0 ? width / slots : 0;
  $: gap = column >= 4 ? 1 : 0;
  $: start = width - count * column;
  /* The ceiling: the 90th-percentile slowest frame with room above it, and
     never less than a little over a 60 fps frame, so the reference line is
     always in view. The rare spike clips at the top -- its readout still says
     exactly how slow -- instead of flattening every other bucket into the
     floor. The same rule as the game's instrument. */
  $: ceiling = (() => {
    const sorted = [...pulse.timelineWorstMs].sort((a, b) => a - b);
    const p90 = sorted.length ? sorted[Math.min(sorted.length - 1, Math.ceil(sorted.length * 0.9) - 1)] : 0;
    return Math.max(FRAME_60 * 1.2, p90 * 1.25);
  })();
  $: reference = Math.round(height - 1 - scaled(FRAME_60)) + 0.5;
  $: columns = Array.from({ length: count }, (_, index) => {
    const rate = pulse.timelineFps[index];
    const mean = rate > 0 ? 1000 / rate : 0;
    const worst = pulse.timelineWorstMs[index];
    return {
      x: Math.round(start + index * column),
      meanTop: height - scaled(mean),
      worstTop: height - scaled(worst),
      hitch: worst >= HITCH_MS,
      /* A whisker only where the slowest frame stands well clear of the mean.
         Every ten seconds of Minecraft has a frame twice the mean (a chunk, a
         sound load); drawn on every column that is texture, not information. */
      uneven: worst >= mean * UNEVEN,
    };
  });
  $: marks = pulse.marks
    .filter((mark) => mark.bucket < count)
    .map((mark) => ({ x: Math.round(start + mark.bucket * column), kind: mark.kind }));

  function scaled(millis: number) {
    return Math.max(0, Math.min(1, millis / ceiling)) * (height - 1);
  }

  function pointer(event: PointerEvent) {
    const box = (event.currentTarget as SVGElement).getBoundingClientRect();
    const index = Math.floor((event.clientX - box.left - start) / (column || 1));
    hovered = index >= 0 && index < count ? index : null;
  }

  function ago(index: number) {
    const seconds = (count - index) * pulse.bucketSeconds;
    if (seconds <= pulse.bucketSeconds) return 'Latest';
    if (seconds < 60) return `${seconds} s of play ago`;
    return `${Math.round(seconds / 60)} min of play ago`;
  }

  $: readout =
    hovered === null
      ? caption
      : [
          ago(hovered),
          `${Math.round(pulse.timelineFps[hovered])} fps`,
          `slowest frame ${ms(pulse.timelineWorstMs[hovered])} ms`,
          ...pulse.marks
            .filter((mark) => mark.bucket === hovered)
            .map((mark) => (mark.kind === 'world' ? 'world changed' : 'settings changed')),
        ].join(' · ');
  $: summary = `Frame time over ${count * pulse.bucketSeconds} seconds of play, average ${Math.round(pulse.averageFps)} fps.`;
</script>

<div class="timeline" bind:clientWidth={width} in:fade={{ duration: MOTION_STATE, easing: EASE }}>
  <svg
    {height}
    width="100%"
    viewBox="0 0 {Math.max(width, 1)} {height}"
    role="img"
    aria-label={summary}
    on:pointermove={pointer}
    on:pointerleave={() => (hovered = null)}
  >
    <line class="floor" x1="0" x2={width} y1={height - 0.5} y2={height - 0.5} />
    <line class="reference" x1="0" x2={width} y1={reference} y2={reference}>
      <title>60 fps</title>
    </line>
    {#each columns as bar, index}
      {#if hovered === index}
        <rect class="hover" x={bar.x} y="0" width={column} {height} />
      {/if}
      <rect class="mean" x={bar.x} y={bar.meanTop} width={Math.max(column - gap, 0.5)} height={height - 1 - bar.meanTop} />
      {#if bar.uneven}
        <line
          class="whisker"
          class:hitch={bar.hitch}
          x1={bar.x + (column - gap) / 2}
          x2={bar.x + (column - gap) / 2}
          y1={bar.worstTop}
          y2={bar.meanTop}
        />
      {/if}
      <rect
        class="tip"
        class:hitch={bar.hitch}
        x={bar.x}
        y={bar.uneven ? bar.worstTop : bar.meanTop - 1}
        width={Math.max(column - gap, 0.5)}
        height={bar.uneven ? Math.min(1.5, height - bar.worstTop) : 1.5}
      />
    {/each}
    {#each marks as mark}
      <line class="mark" x1={mark.x + 0.5} x2={mark.x + 0.5} y1="0" y2={height} />
    {/each}
  </svg>
  {#if readout}
    <p class="readout tnum" aria-live="off">{readout}</p>
  {/if}
</div>

<style>
  .timeline {
    min-width: 0;
  }

  svg {
    display: block;
    overflow: visible;
    cursor: crosshair;
  }

  .floor {
    stroke: var(--g-hairline);
    stroke-width: var(--hairline);
  }

  /* The signal blue is telemetry's colour: small marks, never a panel. The
     mean column is a faint wash of it; only the tip is the full colour. */
  .mean {
    fill: var(--color-signal);
    opacity: 0.3;
  }

  .tip {
    fill: var(--color-signal);
  }

  .whisker {
    stroke: var(--color-signal);
    stroke-width: 1;
    opacity: 0.55;
  }

  .tip.hitch {
    fill: var(--color-warning-text);
  }

  .whisker.hitch {
    stroke: var(--color-warning-text);
  }

  /* 60 fps: one faint hairline, a guide rather than data. The game draws the
     same line. */
  .reference {
    stroke: var(--g-text-tertiary);
    stroke-width: var(--hairline);
    opacity: 0.45;
  }

  .mark {
    stroke: var(--g-text-tertiary);
    stroke-width: var(--hairline);
  }

  .hover {
    fill: var(--g-text-primary);
    opacity: 0.06;
  }

  .readout {
    margin: var(--g-s-2) 0 0;
    overflow: hidden;
    color: var(--g-text-tertiary);
    font: var(--g-type-caption-weight) var(--g-type-caption-size) / var(--g-type-caption-line) var(--g-type-caption-family);
    letter-spacing: var(--g-type-caption-tracking);
    text-overflow: ellipsis;
    white-space: nowrap;
  }
</style>

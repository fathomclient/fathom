<!--
  The geometry debug overlay. DEVELOPMENT BUILDS ONLY.

  Mounted from `main.ts` behind `import.meta.env.DEV`, so a release bundle does
  not contain it and no user can open it. Ctrl+Shift+G toggles it (and
  `?geometry` in the dev URL starts with it on).

  What it is for: catching inconsistent geometry -- a control 34 tall beside
  one 36 tall, a rim that is 1.5 device pixels, a radius from outside the
  metric set. It outlines every control, row and pane on screen with its size,
  and the element under the pointer gets the full readout:

    size in CSS px (dp) and in device px, border radius, rim width (from the
    inset box-shadow, in device px), font size, and the display scale.

  Anything off-metric is marked in the warning colour, so a stray value reads
  at a glance rather than by measuring screenshots.
-->
<script lang="ts">
  import { onMount } from 'svelte';
  import { METRIC } from './metrics.generated';

  let on = false;
  let boxes: Box[] = [];
  let hover: Readout | null = null;
  let dpr = 1;

  type Box = { x: number; y: number; w: number; h: number; label: string; off: boolean };
  type Readout = Box & { lines: string[] };

  /* What counts as a control-family element worth outlining. */
  const SELECTOR = [
    'button', 'input', 'select', 'textarea', '[role="radio"]',
    '.ui-capsule', '.ui-icon-button', '.ui-field', '.ui-item', '.ui-mark', '.ui-popover-item',
    '[class*="material-"]', '.row', '.switch', '.track', '.segment',
  ].join(',');

  const HEIGHTS = new Set<number>([
    METRIC.control, METRIC.controlCompact, METRIC.controlPrimary, METRIC.row, METRIC.rowDense,
    METRIC.switchHeight, METRIC.tile,
  ]);
  const RADII = new Set<number>([
    0, METRIC.radiusPanel, METRIC.radiusControl, METRIC.radiusSmall,
  ]);

  function round(n: number): number {
    return Math.round(n * 100) / 100;
  }

  /* The rim width is the spread of the first `inset 0 0 0 <n>px` in the shadow. */
  function rimPx(shadow: string): number | null {
    const match = /inset\s+0(?:px)?\s+0(?:px)?\s+0(?:px)?\s+([\d.]+)px/.exec(shadow);
    return match ? Number(match[1]) : null;
  }

  function radiusOf(style: CSSStyleDeclaration, h: number): { value: number; pill: boolean } {
    const value = parseFloat(style.borderTopLeftRadius) || 0;
    return { value, pill: value >= h / 2 - 0.5 && value > 0 };
  }

  function describe(el: Element): Readout | null {
    const rect = el.getBoundingClientRect();
    if (rect.width < 4 || rect.height < 4) return null;
    const style = getComputedStyle(el);
    const radius = radiusOf(style, rect.height);
    const rim = rimPx(style.boxShadow);
    const rimDevice = rim === null ? null : round(rim * dpr);
    const h = round(rect.height);
    const radiusOff = !radius.pill && !RADII.has(Math.round(radius.value));
    const rimOff = rimDevice !== null && Math.abs(rimDevice - 1) > 0.05;
    // Only real controls have a canonical height; an inline text link is a
    // <button> too, and is as tall as its line.
    const heightOff = el.matches('.ui-capsule, .ui-field, .ui-icon-button, .segment, select, input:not([type="range"])')
      && !HEIGHTS.has(Math.round(h)) && h < 60;
    const lines = [
      `${round(rect.width)} × ${h} dp`,
      `${round(rect.width * dpr)} × ${round(rect.height * dpr)} device px`,
      `radius ${radius.pill ? 'pill' : `${round(radius.value)}`}${radiusOff ? '  ⚠ off-metric' : ''}`,
      `rim ${rimDevice === null ? 'none' : `${rimDevice} device px`}${rimOff ? '  ⚠' : ''}`,
      `font ${style.fontSize} / ${style.fontWeight}`,
      `ui scale ${round(dpr)}×`,
    ];
    return {
      x: rect.left, y: rect.top, w: rect.width, h: rect.height,
      label: `${Math.round(rect.width)}×${Math.round(h)}`,
      off: radiusOff || rimOff || heightOff,
      lines,
    };
  }

  function scan() {
    if (!on) return;
    dpr = window.devicePixelRatio || 1;
    const found: Box[] = [];
    for (const el of document.querySelectorAll(SELECTOR)) {
      if (el.closest('.geometry-debug')) continue;
      const box = describe(el);
      if (box) found.push(box);
      if (found.length > 400) break;
    }
    boxes = found;
    requestAnimationFrame(scan);
  }

  function pointer(event: PointerEvent) {
    if (!on) return;
    const target = document.elementsFromPoint(event.clientX, event.clientY)
      .find((el) => !el.closest('.geometry-debug') && el.matches(SELECTOR));
    hover = target ? describe(target) : null;
  }

  function toggle(value = !on) {
    on = value;
    if (on) requestAnimationFrame(scan);
    else {
      boxes = [];
      hover = null;
    }
  }

  onMount(() => {
    const key = (event: KeyboardEvent) => {
      if (event.ctrlKey && event.shiftKey && event.key.toLowerCase() === 'g') {
        event.preventDefault();
        toggle();
      }
    };
    window.addEventListener('keydown', key);
    window.addEventListener('pointermove', pointer, { passive: true });
    if (new URLSearchParams(location.search).has('geometry')) toggle(true);
    return () => {
      window.removeEventListener('keydown', key);
      window.removeEventListener('pointermove', pointer);
    };
  });
</script>

{#if on}
  <div class="geometry-debug keep-case" aria-hidden="true">
    {#each boxes as box}
      <div class="box" class:off={box.off} style="left:{box.x}px;top:{box.y}px;width:{box.w}px;height:{box.h}px">
        <span>{box.label}</span>
      </div>
    {/each}
    {#if hover}
      <div class="focus" style="left:{hover.x}px;top:{hover.y}px;width:{hover.w}px;height:{hover.h}px"></div>
      <div class="readout" style="left:{Math.min(hover.x, innerWidth - 240)}px;top:{hover.y + hover.h + 6 > innerHeight - 120 ? hover.y - 118 : hover.y + hover.h + 6}px">
        {#each hover.lines as line}<div>{line}</div>{/each}
      </div>
    {/if}
    <div class="badge">geometry · {round(dpr)}× · Ctrl+Shift+G</div>
  </div>
{/if}

<style>
  .geometry-debug {
    position: fixed;
    inset: 0;
    z-index: 10000;
    pointer-events: none;
    font: 500 10px/1.3 var(--type-family-mono);
    text-transform: none;
  }

  .box {
    position: fixed;
    box-shadow: inset 0 0 0 var(--hairline) rgba(0, 255, 200, 0.55);
  }

  .box.off {
    box-shadow: inset 0 0 0 var(--hairline) rgba(255, 90, 60, 0.95);
  }

  .box span {
    position: absolute;
    top: 0;
    left: 0;
    padding: 0 2px;
    background: rgba(0, 0, 0, 0.6);
    color: rgb(140, 255, 225);
    white-space: nowrap;
  }

  .box.off span {
    color: rgb(255, 160, 140);
  }

  .focus {
    position: fixed;
    box-shadow: 0 0 0 var(--hairline) #fff, 0 0 0 calc(var(--hairline) * 2) #000;
  }

  .readout {
    position: fixed;
    width: 232px;
    padding: 6px 8px;
    background: rgba(0, 0, 0, 0.85);
    color: #fff;
    border-radius: 6px;
  }

  .badge {
    position: fixed;
    right: 8px;
    bottom: 8px;
    padding: 2px 6px;
    background: rgba(0, 0, 0, 0.7);
    color: rgb(140, 255, 225);
    border-radius: 4px;
  }
</style>

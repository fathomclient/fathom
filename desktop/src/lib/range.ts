/**
 * `use:rangeFill={value}` on an `<input type="range">` -- publishes how far
 * along the value is as `--range-fill`, which the shared slider style in
 * `controls.css` paints as the brighter part of the channel.
 *
 * The fill ends at the thumb's centre, not at a plain percentage of the track:
 * the thumb travels between half its width from each end, so a bare `50%` would
 * sit visibly beside it near either extreme.
 *
 * Pass the bound value so a programmatic change (a reset, a preset) repaints the
 * fill too; `bind:value` sets the property without firing `input`.
 */
const THUMB = 14;

export function rangeFill(node: HTMLInputElement, _value?: unknown) {
  function paint() {
    const min = node.min === '' ? 0 : Number(node.min);
    const max = node.max === '' ? 100 : Number(node.max);
    const span = max - min;
    const fraction = span > 0 ? Math.min(1, Math.max(0, (Number(node.value) - min) / span)) : 0;
    node.style.setProperty(
      '--range-fill',
      `calc(${THUMB / 2}px + (100% - ${THUMB}px) * ${fraction.toFixed(4)})`,
    );
  }

  paint();
  node.addEventListener('input', paint);

  return {
    update() {
      paint();
    },
    destroy() {
      node.removeEventListener('input', paint);
    },
  };
}

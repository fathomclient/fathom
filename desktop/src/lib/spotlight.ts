/*
 * `use:spotlight`: a faint circle of light that follows the pointer across a
 * pane (the `.spot` rules in glow.css).
 *
 * Only custom properties change while the pointer moves, one write per frame
 * at most, so nothing is laid out again. Touch and pen get nothing: there is no
 * pointer hovering over the pane to follow. `use:spotlight={false}` turns it
 * off, for a component whose pane is optional.
 */
export function spotlight(node: HTMLElement, enabled = true) {
  let on = enabled;
  node.classList.toggle('spot', on);
  let frame = 0;
  let x = 0;
  let y = 0;

  const paint = () => {
    frame = 0;
    const box = node.getBoundingClientRect();
    node.style.setProperty('--spot-x', `${x - box.left}px`);
    node.style.setProperty('--spot-y', `${y - box.top}px`);
  };

  const move = (event: PointerEvent) => {
    if (!on || event.pointerType !== 'mouse') return;
    x = event.clientX;
    y = event.clientY;
    node.classList.add('lit');
    if (!frame) frame = requestAnimationFrame(paint);
  };

  const leave = () => node.classList.remove('lit');

  node.addEventListener('pointermove', move, { passive: true });
  node.addEventListener('pointerleave', leave);
  return {
    update(next = true) {
      on = next;
      node.classList.toggle('spot', on);
      if (!on) node.classList.remove('lit');
    },
    destroy() {
      if (frame) cancelAnimationFrame(frame);
      node.removeEventListener('pointermove', move);
      node.removeEventListener('pointerleave', leave);
      node.classList.remove('spot', 'lit');
    },
  };
}

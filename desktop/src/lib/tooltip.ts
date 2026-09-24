/**
 * `use:tooltip={'Text'}` -- a small pane of dense glass that names a control.
 *
 * It replaces the native `title` attribute on icon-only controls, whose yellow
 * system tooltip is from another design system and arrives after a full second.
 * This one appears after a short pause, fades in quietly, sits above the
 * control (below it when there is no room), and never takes the pointer.
 *
 * Accessibility is not delegated to it: an icon-only control keeps its
 * `aria-label`. An empty string disables the tooltip, which is how the rail
 * turns them off once its labels are visible.
 */
const SHOW_AFTER = 450;
const GAP = 8;

export function tooltip(node: HTMLElement, text: string) {
  let current = text;
  let bubble: HTMLDivElement | null = null;
  let timer: ReturnType<typeof setTimeout> | null = null;

  function place() {
    if (!bubble) return;
    const anchor = node.getBoundingClientRect();
    const own = bubble.getBoundingClientRect();
    const above = anchor.top - own.height - GAP;
    const top = above >= GAP ? above : anchor.bottom + GAP;
    const centred = anchor.left + anchor.width / 2 - own.width / 2;
    const left = Math.min(Math.max(GAP, centred), window.innerWidth - own.width - GAP);
    bubble.style.top = `${Math.round(top)}px`;
    bubble.style.left = `${Math.round(left)}px`;
  }

  function show() {
    if (!current || bubble) return;
    bubble = document.createElement('div');
    bubble.className = 'ui-tooltip';
    bubble.setAttribute('role', 'tooltip');
    bubble.textContent = current;
    document.body.appendChild(bubble);
    place();
    // Next frame, so the entrance transition has a starting state to leave.
    requestAnimationFrame(() => bubble?.classList.add('shown'));
  }

  function hide() {
    if (timer !== null) {
      clearTimeout(timer);
      timer = null;
    }
    bubble?.remove();
    bubble = null;
  }

  function schedule() {
    if (!current) return;
    if (timer !== null) clearTimeout(timer);
    timer = setTimeout(show, SHOW_AFTER);
  }

  node.addEventListener('mouseenter', schedule);
  node.addEventListener('mouseleave', hide);
  node.addEventListener('mousedown', hide);
  node.addEventListener('focusin', schedule);
  node.addEventListener('focusout', hide);

  return {
    update(next: string) {
      current = next;
      if (!current) hide();
      else if (bubble) {
        bubble.textContent = current;
        place();
      }
    },
    destroy() {
      hide();
      node.removeEventListener('mouseenter', schedule);
      node.removeEventListener('mouseleave', hide);
      node.removeEventListener('mousedown', hide);
      node.removeEventListener('focusin', schedule);
      node.removeEventListener('focusout', hide);
    },
  };
}

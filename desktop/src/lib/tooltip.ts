/**
 * `use:tooltip={'Text'}` -- a small pane of dense glass that names a control.
 *
 * The one tooltip. It replaces the native `title` attribute on icon-only
 * controls, whose system tooltip is from another design system and arrives
 * after a full second, and the CSS `::after` labels Home's rail used to draw.
 *
 * - Pointer: appears after 400ms of rest, fades in over 120ms, and leaves at
 *   once when the pointer does.
 * - Keyboard: appears immediately on `:focus-visible`, because somebody tabbing
 *   through icon buttons is reading them one at a time.
 * - It fades; it does not travel. It sits above the control (below it when
 *   there is no room), or to its right for a vertical rail, and it never takes
 *   the pointer.
 *
 * Accessibility is not delegated to it: an icon-only control keeps its
 * `aria-label`. An empty string disables the tooltip, which is how the rail
 * turns them off once its labels are visible.
 */
type Placement = 'top' | 'right';
type Options = string | { text: string; placement?: Placement };

const SHOW_AFTER = 400;
const GAP = 8;

function read(options: Options): { text: string; placement: Placement } {
  return typeof options === 'string'
    ? { text: options, placement: 'top' }
    : { text: options.text, placement: options.placement ?? 'top' };
}

export function tooltip(node: HTMLElement, options: Options) {
  let { text: current, placement } = read(options);
  let bubble: HTMLDivElement | null = null;
  let timer: ReturnType<typeof setTimeout> | null = null;

  function place() {
    if (!bubble) return;
    const anchor = node.getBoundingClientRect();
    const own = bubble.getBoundingClientRect();
    let top: number;
    let left: number;
    if (placement === 'right') {
      top = anchor.top + anchor.height / 2 - own.height / 2;
      left = anchor.right + GAP;
    } else {
      const above = anchor.top - own.height - GAP;
      top = above >= GAP ? above : anchor.bottom + GAP;
      left = anchor.left + anchor.width / 2 - own.width / 2;
    }
    left = Math.min(Math.max(GAP, left), window.innerWidth - own.width - GAP);
    top = Math.min(Math.max(GAP, top), window.innerHeight - own.height - GAP);
    bubble.style.top = `${Math.round(top)}px`;
    bubble.style.left = `${Math.round(left)}px`;
  }

  function show() {
    timer = null;
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

  function focused() {
    if (node.matches(':focus-visible')) show();
  }

  node.addEventListener('mouseenter', schedule);
  node.addEventListener('mouseleave', hide);
  node.addEventListener('mousedown', hide);
  node.addEventListener('focus', focused);
  node.addEventListener('blur', hide);

  return {
    update(next: Options) {
      ({ text: current, placement } = read(next));
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
      node.removeEventListener('focus', focused);
      node.removeEventListener('blur', hide);
    },
  };
}

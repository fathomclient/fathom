const params = new URLSearchParams(location.search);
const src = params.get('src') ?? '/?proof=home';
const settle = Number(params.get('settle') ?? '6000');
const frame = document.getElementById('app') as HTMLIFrameElement;
frame.style.width = `${params.get('w') ?? '1280'}px`;
frame.style.height = `${params.get('h') ?? '720'}px`;

interface Box {
  path: string;
  tag: string;
  cls: string;
  text: string;
  x: number;
  y: number;
  w: number;
  h: number;
  radius: string;
  background: string;
  shadow: string;
  font: string;
  colour: string;
  tracking: string;
  lineHeight: string;
  filter: string;
}

function describe(el: Element, path: string): Box | null {
  const rect = el.getBoundingClientRect();
  if (rect.width < 1 || rect.height < 1) return null;
  const style = (el.ownerDocument.defaultView as Window).getComputedStyle(el);
  if (style.visibility === 'hidden' || style.display === 'none' || Number(style.opacity) === 0) return null;
  const own = Array.from(el.childNodes)
    .filter((node) => node.nodeType === Node.TEXT_NODE)
    .map((node) => (node.textContent ?? '').trim())
    .join(' ')
    .trim();
  const draws =
    own.length > 0 ||
    style.backgroundColor !== 'rgba(0, 0, 0, 0)' ||
    style.boxShadow !== 'none' ||
    style.backdropFilter !== 'none' ||
    el.tagName.toLowerCase() === 'svg' ||
    el.tagName.toLowerCase() === 'img';
  if (!draws) return null;
  return {
    path,
    tag: el.tagName.toLowerCase(),
    cls: typeof el.className === 'string' ? el.className : '',
    text: own,
    x: Math.round(rect.x * 100) / 100,
    y: Math.round(rect.y * 100) / 100,
    w: Math.round(rect.width * 100) / 100,
    h: Math.round(rect.height * 100) / 100,
    radius: style.borderRadius,
    background: style.backgroundColor,
    shadow: style.boxShadow,
    font: `${style.fontWeight} ${style.fontSize} ${style.fontFamily.split(',')[0]}`,
    colour: style.color,
    tracking: style.letterSpacing,
    lineHeight: style.lineHeight,
    filter: style.backdropFilter,
  };
}

function walk(el: Element, path: string, out: Box[]) {
  const box = describe(el, path);
  if (box) out.push(box);
  if (el.tagName.toLowerCase() === 'svg') return;
  Array.from(el.children).forEach((child, index) => walk(child, `${path}/${child.tagName.toLowerCase()}${index}`, out));
}

frame.addEventListener('load', () => {
  setTimeout(() => {
    const doc = frame.contentDocument as Document;
    const out: Box[] = [];
    walk(doc.body, 'body', out);
    (document.getElementById('out') as HTMLElement).textContent = JSON.stringify({
      src,
      viewport: {w: frame.clientWidth, h: frame.clientHeight},
      boxes: out,
    });
  }, settle);
});
frame.src = src;

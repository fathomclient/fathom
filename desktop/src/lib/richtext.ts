/**
 * Mod descriptions, turned into something safe to render.
 *
 * A description is written by a stranger and fetched from the internet. It is
 * Markdown on Modrinth and HTML on CurseForge, and either may contain raw HTML,
 * scripts, iframes, tracking pixels and links to anywhere. So it is never given
 * to the WebView as markup. It is parsed here into a small tree of plain data —
 * headings, paragraphs, lists, code, emphasis, links, images — and the Svelte
 * components render that tree with ordinary elements and text nodes. There is
 * no `{@html}` anywhere on this path, so nothing in a description can become an
 * element, an attribute or a script.
 *
 * What is lost is decoration: tables become lines of text, alignment and
 * colours are dropped, embedded video becomes nothing. What is kept is what a
 * person needs to decide whether to install a mod.
 *
 * Links and images are kept as data. Following a link goes back to Rust, which
 * opens it only if that exact URL is in the description it fetched; an image
 * is loaded through the same allowlisted fetch as icons, and an image from
 * anywhere else is shown as its alt text.
 */

export type Inline =
  | { kind: 'text'; text: string }
  | { kind: 'strong'; children: Inline[] }
  | { kind: 'em'; children: Inline[] }
  | { kind: 'code'; text: string }
  | { kind: 'link'; href: string; children: Inline[] }
  | { kind: 'image'; src: string; alt: string }
  | { kind: 'break' };

export type Block =
  | { kind: 'heading'; level: number; inlines: Inline[] }
  | { kind: 'paragraph'; inlines: Inline[] }
  | { kind: 'list'; ordered: boolean; items: Inline[][] }
  | { kind: 'quote'; inlines: Inline[] }
  | { kind: 'code'; text: string }
  | { kind: 'rule' };

/** Past this the rest of a description is not worth the layout. */
const MAX_BLOCKS = 400;
const MAX_DEPTH = 6;

const ENTITIES: Record<string, string> = {
  amp: '&',
  lt: '<',
  gt: '>',
  quot: '"',
  apos: "'",
  nbsp: ' ',
  mdash: '—',
  ndash: '–',
  hellip: '…',
  copy: '©',
  rsquo: '’',
  lsquo: '‘',
  rdquo: '”',
  ldquo: '“',
};

export function decodeEntities(text: string): string {
  return text.replace(/&(#x[0-9a-f]+|#[0-9]+|[a-z]+);/gi, (whole, body: string) => {
    if (body[0] === '#') {
      const code =
        body[1] === 'x' || body[1] === 'X' ? parseInt(body.slice(2), 16) : parseInt(body.slice(1), 10);
      // Control characters and lone surrogates are not text.
      if (!Number.isFinite(code) || code < 32 || code > 0x10ffff || (code >= 0xd800 && code <= 0xdfff)) {
        return '';
      }
      return String.fromCodePoint(code);
    }
    return ENTITIES[body.toLowerCase()] ?? whole;
  });
}

function attribute(tag: string, name: string): string | null {
  const match = new RegExp(`\\b${name}\\s*=\\s*("([^"]*)"|'([^']*)'|([^\\s>]+))`, 'i').exec(tag);
  if (!match) return null;
  return decodeEntities(match[2] ?? match[3] ?? match[4] ?? '');
}

/**
 * Reduce HTML to the Markdown this file already parses.
 *
 * Not an HTML parser and not trying to be one: it maps the handful of tags
 * that carry structure onto their Markdown equivalents and deletes every
 * other tag outright, contents of `script`, `style` and `iframe` included.
 */
export function htmlToMarkdown(html: string): string {
  let text = html
    // A Markdown autolink is not a tag.
    .replace(/<(https:\/\/[^>\s]+)>/gi, '$1')
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/<(script|style|iframe|object|embed|svg|noscript)\b[\s\S]*?<\/\1\s*>/gi, '')
    .replace(/<(script|style|iframe|object|embed|svg|noscript)\b[^>]*\/?>/gi, '');

  text = text
    .replace(/<img\b[^>]*>/gi, (tag) => {
      const src = attribute(tag, 'src');
      const alt = (attribute(tag, 'alt') ?? '').replace(/[[\]]/g, '');
      return src ? `![${alt}](${src})` : alt;
    })
    .replace(/<a\b[^>]*>([\s\S]*?)<\/a\s*>/gi, (whole, inner: string) => {
      const href = attribute(whole, 'href');
      const label = inner.replace(/<[^>]*>/g, '').trim();
      if (!href || !label) return inner;
      return `[${label.replace(/[[\]]/g, '')}](${href})`;
    })
    .replace(/<h([1-6])\b[^>]*>/gi, (_, level: string) => `\n\n${'#'.repeat(Number(level))} `)
    .replace(/<\/h[1-6]\s*>/gi, '\n\n')
    .replace(/<br\s*\/?>/gi, '  \n')
    .replace(/<hr\b[^>]*>/gi, '\n\n---\n\n')
    .replace(/<li\b[^>]*>/gi, '\n- ')
    .replace(/<\/li\s*>/gi, '')
    .replace(/<\/?(ul|ol)\b[^>]*>/gi, '\n\n')
    .replace(/<\/?(p|div|section|article|center|table|tbody|thead|tr|blockquote)\b[^>]*>/gi, '\n\n')
    .replace(/<\/?(td|th)\b[^>]*>/gi, ' ')
    .replace(/<\/?(strong|b)\b[^>]*>/gi, '**')
    .replace(/<\/?(em|i)\b[^>]*>/gi, '*')
    .replace(/<pre\b[^>]*>/gi, '\n\n```\n')
    .replace(/<\/pre\s*>/gi, '\n```\n\n')
    .replace(/<\/?code\b[^>]*>/gi, '`')
    .replace(/<[^>]*>/g, '');

  return decodeEntities(text);
}

// ---------------------------------------------------------------------------
// Inline
// ---------------------------------------------------------------------------

/** Whether a URL is one a link or image could ever lead to. */
export function isWebUrl(url: string): boolean {
  return /^https:\/\/[^\s/?#@]+\.[^\s/?#@]+([/?#][^\s]*)?$/i.test(url) && url.length <= 512;
}

function findClosing(text: string, from: number, marker: string): number {
  let index = text.indexOf(marker, from);
  while (index !== -1 && text[index - 1] === '\\') index = text.indexOf(marker, index + 1);
  return index;
}

export function parseInline(source: string, depth = 0): Inline[] {
  const out: Inline[] = [];
  let buffer = '';
  const flush = () => {
    if (buffer) out.push({ kind: 'text', text: buffer });
    buffer = '';
  };
  if (depth > MAX_DEPTH) return [{ kind: 'text', text: source }];

  let i = 0;
  while (i < source.length) {
    const rest = source.slice(i);

    // Hard break: two spaces, or a backslash, before a newline.
    const hard = /^( {2,}|\\)\n/.exec(rest);
    if (hard) {
      flush();
      out.push({ kind: 'break' });
      i += hard[0].length;
      continue;
    }
    if (source[i] === '\n') {
      buffer += ' ';
      i += 1;
      continue;
    }
    if (source[i] === '\\' && i + 1 < source.length && /[\\`*_[\]()#!-]/.test(source[i + 1])) {
      buffer += source[i + 1];
      i += 2;
      continue;
    }
    if (source[i] === '`') {
      const end = source.indexOf('`', i + 1);
      if (end > i) {
        flush();
        out.push({ kind: 'code', text: source.slice(i + 1, end) });
        i = end + 1;
        continue;
      }
    }

    // ![alt](src) and [label](href), with an optional "title" ignored.
    const image = /^!\[([^\]]*)\]\(\s*<?([^)\s>]+)>?(?:\s+"[^"]*")?\s*\)/.exec(rest);
    if (image) {
      flush();
      out.push({ kind: 'image', alt: image[1], src: image[2] });
      i += image[0].length;
      continue;
    }
    const link = /^\[((?:[^\][]|!\[[^\]]*\]\([^)]*\))*)\]\(\s*<?([^)\s>]+)>?(?:\s+"[^"]*")?\s*\)/.exec(rest);
    if (link) {
      flush();
      const children = parseInline(link[1], depth + 1);
      if (isWebUrl(link[2])) {
        out.push({ kind: 'link', href: link[2], children });
      } else {
        out.push(...children);
      }
      i += link[0].length;
      continue;
    }

    // A bare URL is a link to itself.
    const bare = /^https:\/\/[^\s<>()[\]]+[^\s<>()[\].,;:!?'"]/.exec(rest);
    if (bare && (i === 0 || /[\s(]/.test(source[i - 1]))) {
      flush();
      if (isWebUrl(bare[0])) {
        out.push({ kind: 'link', href: bare[0], children: [{ kind: 'text', text: bare[0] }] });
      } else {
        out.push({ kind: 'text', text: bare[0] });
      }
      i += bare[0].length;
      continue;
    }

    const strong = rest.startsWith('**') ? '**' : rest.startsWith('__') ? '__' : null;
    if (strong) {
      const end = findClosing(source, i + 2, strong);
      if (end > i + 2) {
        flush();
        out.push({ kind: 'strong', children: parseInline(source.slice(i + 2, end), depth + 1) });
        i = end + 2;
        continue;
      }
    }

    if ((source[i] === '*' || source[i] === '_') && source[i + 1] !== ' ') {
      const marker = source[i];
      // Underscores inside words (snake_case) are not emphasis.
      const wordBefore = marker === '_' && i > 0 && /\w/.test(source[i - 1]);
      const end = wordBefore ? -1 : findClosing(source, i + 1, marker);
      if (end > i + 1 && source[end - 1] !== ' ') {
        flush();
        out.push({ kind: 'em', children: parseInline(source.slice(i + 1, end), depth + 1) });
        i = end + 1;
        continue;
      }
    }

    buffer += source[i];
    i += 1;
  }
  flush();
  return out;
}

// ---------------------------------------------------------------------------
// Blocks
// ---------------------------------------------------------------------------

export function parseMarkdown(source: string): Block[] {
  const lines = source.replace(/\r\n?/g, '\n').split('\n');
  const blocks: Block[] = [];
  let paragraph: string[] = [];

  const endParagraph = () => {
    const text = paragraph.join('\n').trim();
    if (text) blocks.push({ kind: 'paragraph', inlines: parseInline(text) });
    paragraph = [];
  };

  let i = 0;
  while (i < lines.length && blocks.length < MAX_BLOCKS) {
    const line = lines[i];
    const trimmed = line.trim();

    if (!trimmed) {
      endParagraph();
      i += 1;
      continue;
    }

    const fence = /^(```|~~~)/.exec(trimmed);
    if (fence) {
      endParagraph();
      const body: string[] = [];
      i += 1;
      while (i < lines.length && !lines[i].trim().startsWith(fence[1])) {
        body.push(lines[i]);
        i += 1;
      }
      blocks.push({ kind: 'code', text: body.join('\n') });
      i += 1;
      continue;
    }

    const heading = /^(#{1,6})\s+(.*?)\s*#*$/.exec(trimmed);
    if (heading) {
      endParagraph();
      blocks.push({ kind: 'heading', level: heading[1].length, inlines: parseInline(heading[2]) });
      i += 1;
      continue;
    }

    if (/^([-*_])(\s*\1){2,}$/.test(trimmed)) {
      endParagraph();
      blocks.push({ kind: 'rule' });
      i += 1;
      continue;
    }

    // Setext headings: a line of text underlined with === or ---.
    if (paragraph.length === 0 && i + 1 < lines.length && /^(=+|-+)\s*$/.test(lines[i + 1].trim()) && lines[i + 1].trim().length >= 3) {
      blocks.push({
        kind: 'heading',
        level: lines[i + 1].trim()[0] === '=' ? 1 : 2,
        inlines: parseInline(trimmed),
      });
      i += 2;
      continue;
    }

    if (trimmed.startsWith('>')) {
      endParagraph();
      const body: string[] = [];
      while (i < lines.length && lines[i].trim().startsWith('>')) {
        body.push(lines[i].trim().replace(/^>\s?/, ''));
        i += 1;
      }
      blocks.push({ kind: 'quote', inlines: parseInline(body.join('\n')) });
      continue;
    }

    const bullet = /^([-*+]|\d{1,3}[.)])\s+/.exec(trimmed);
    if (bullet) {
      endParagraph();
      const ordered = /\d/.test(bullet[1]);
      const items: string[] = [];
      while (i < lines.length) {
        const current = lines[i].trim();
        const item = /^([-*+]|\d{1,3}[.)])\s+(.*)$/.exec(current);
        if (item && /\d/.test(item[1]) === ordered) {
          items.push(item[2]);
        } else if (current && items.length && /^\s{2,}/.test(lines[i])) {
          // A continuation line, indented under its item.
          items[items.length - 1] += `\n${current}`;
        } else {
          break;
        }
        i += 1;
      }
      blocks.push({ kind: 'list', ordered, items: items.map((item) => parseInline(item)) });
      continue;
    }

    // A table row: keep the cells as one line of text.
    if (/^\|.*\|$/.test(trimmed)) {
      endParagraph();
      if (!/^\|[\s:|-]+\|$/.test(trimmed)) {
        const cells = trimmed
          .slice(1, -1)
          .split('|')
          .map((cell) => cell.trim())
          .filter(Boolean);
        blocks.push({ kind: 'paragraph', inlines: parseInline(cells.join(' · ')) });
      }
      i += 1;
      continue;
    }

    paragraph.push(line);
    i += 1;
  }
  endParagraph();
  return blocks;
}

/** A description, as blocks, whichever format its index used. */
export function parseDescription(text: string, format: 'markdown' | 'html'): Block[] {
  // Modrinth descriptions are Markdown that often contains HTML as well, so
  // both formats go through the HTML reduction first; for CurseForge's HTML
  // it is the whole conversion.
  void format;
  return parseMarkdown(htmlToMarkdown(text));
}

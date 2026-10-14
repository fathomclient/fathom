// Tests for src/lib/richtext.ts: mod descriptions are untrusted input, and this
// parser is the only thing between them and the WebView.
//
//   npm test
//
// Bundles the module with esbuild (already a dependency of Vite) into memory
// and runs it; no test framework, no extra dependencies.

import { build } from 'esbuild';
import { fileURLToPath } from 'node:url';

const entry = fileURLToPath(new URL('../src/lib/richtext.ts', import.meta.url));
const bundle = await build({ entryPoints: [entry], bundle: true, format: 'esm', write: false, logLevel: 'warning' });
const { parseDescription, parseInline, isWebUrl } = await import(
  `data:text/javascript;base64,${Buffer.from(bundle.outputFiles[0].text).toString('base64')}`
);

let failures = 0;
function check(name, condition, detail) {
  if (condition) {
    console.log('ok  ', name);
  } else {
    failures += 1;
    console.log('FAIL', name, detail === undefined ? '' : JSON.stringify(detail, null, 1));
  }
}

function texts(node) {
  if (Array.isArray(node)) return node.map(texts).join('');
  if (!node || typeof node !== 'object') return '';
  if (node.kind === 'text' || node.kind === 'code') return node.text;
  return texts(node.children ?? node.inlines ?? node.items ?? []);
}

function walk(node, visit) {
  if (Array.isArray(node)) return node.forEach((n) => walk(n, visit));
  if (!node || typeof node !== 'object') return;
  visit(node);
  for (const key of ['children', 'inlines', 'items']) if (node[key]) walk(node[key], visit);
}

// Hostile HTML never survives as markup or a usable link.
const hostile = parseDescription(
  `<script>alert(1)</script><img src=x onerror="alert(2)"><a href="javascript:alert(3)">click</a>
<iframe src="https://evil.example"></iframe><p onclick="x()">Hello <b>world</b></p>
[bad](javascript:alert(4)) [also bad](http://plain.example) [file](file:///C:/x)`,
  'markdown',
);
const all = JSON.stringify(hostile);
check('no script text', !all.includes('alert(1)'), hostile);
check('no onerror', !all.includes('onerror'));
check('no iframe', !all.includes('evil.example'));
let links = [];
walk(hostile, (n) => n.kind === 'link' && links.push(n.href));
check('no non-https links', links.every((h) => h.startsWith('https://')), links);
check('text kept', all.includes('Hello') && all.includes('world'));

// Markdown structure.
const md = parseDescription(
  `# Title\n\nSome **bold** and *em* and \`code\` text.\n\n- one\n- two [link](https://github.com/x/y)\n\n1. first\n2. second\n\n> quote\n\n\`\`\`\nfn main() {}\n\`\`\`\n\n---\n\n![shot](https://cdn.modrinth.com/data/x/images/a.png)\n\nhttps://modrinth.com/mod/ksyxis is bare.\n\nsnake_case_name stays.`,
  'markdown',
);
const kinds = md.map((b) => b.kind);
check('block kinds', JSON.stringify(kinds) === JSON.stringify(['heading', 'paragraph', 'list', 'list', 'quote', 'code', 'rule', 'paragraph', 'paragraph', 'paragraph']), kinds);
check('bold parsed', md[1].inlines.some((i) => i.kind === 'strong'));
check('em parsed', md[1].inlines.some((i) => i.kind === 'em'));
check('code parsed', md[1].inlines.some((i) => i.kind === 'code' && i.text === 'code'));
check('ordered list', md[3].ordered === true && md[3].items.length === 2);
check('image token', md[7].inlines[0].kind === 'image');
check('bare url link', md[8].inlines[0].kind === 'link' && md[8].inlines[0].href === 'https://modrinth.com/mod/ksyxis');
check('snake_case not emphasis', texts(md[9]) === 'snake_case_name stays.', md[9]);

// CurseForge-style HTML.
const cf = parseDescription(
  `<h2>Features</h2><ul><li>Fast&nbsp;&amp; light</li><li>Uses <a href="https://example.com/docs">docs</a></li></ul><p>Line<br>break</p>`,
  'html',
);
check('html heading', cf[0].kind === 'heading' && texts(cf[0]) === 'Features', cf);
check('html list', cf[1].kind === 'list' && cf[1].items.length === 2, cf[1]);
check('entities decoded', texts(cf[1].items[0]) === 'Fast\u00a0& light', texts(cf[1].items[0]));
check('html link kept', JSON.stringify(cf[1]).includes('https://example.com/docs'));
check('br is a break', cf[2].inlines.some((i) => i.kind === 'break'), cf[2]);

// URL rules.
for (const bad of ['javascript:alert(1)', 'http://x.com', 'https://user@evil.com/', 'https://nodot/', 'https://a.b/ c']) {
  check(`refuses ${bad}`, !isWebUrl(bad));
}
check('accepts https', isWebUrl('https://github.com/a/b?c=d#e'));
check('autolink', JSON.stringify(parseDescription('see <https://modrinth.com/mod/x>', 'markdown')).includes('"href":"https://modrinth.com/mod/x"'));

// Pathological input terminates.
const start = Date.now();
parseInline('*'.repeat(5000) + '_'.repeat(5000) + '['.repeat(2000));
parseDescription('**a'.repeat(3000), 'markdown');
check('pathological input is quick', Date.now() - start < 2000, Date.now() - start);

console.log(failures ? `${failures} failed` : 'all passed');
process.exit(failures ? 1 : 0);

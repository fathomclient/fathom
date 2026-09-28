"""Outline Fathom's wordmark from the Geist the launcher already vendors.

    python tools/generate-wordmark.py

Reads  assets/theme/fathom-theme.json          (graphite.wordmark: the text and
                                                 each optical tier's weight and
                                                 tracking)
       desktop/src/lib/fonts/Geist-Variable.woff2
Writes desktop/src/lib/brand/wordmark.generated.ts
       minecraft/fathom-optimizations/src/main/java/net/fathom/optimizations/generated/Wordmark.java

WHY OUTLINES

The launcher draws the wordmark as SVG and the game fills the same path into
its brand sheet. Neither sets it as text, so it is one drawing in both places
and cannot drift with a font fallback, a hinting setting or a missing weight.

WHY HARFBUZZ

Geist kerns every pair in "fathom" through GPOS (f->a alone is -30 units).
Java's per-glyph layout does not apply GPOS, so the previous Java outliner
would have set the word loose. HarfBuzz shapes it exactly as a browser does.

UNITS

The ascender (in Geist, the cap height) is 100 units, y runs down, the
baseline is at 100 and the first letter's ink starts at x 0. Each tier records
its x-height so a renderer can snap it to whole device pixels.

Requires `fonttools`, `brotli` and `uharfbuzz` on the developer's machine, like
tools/derive-ui-font.py. Nothing in the launcher or the mod depends on them:
this runs when the wordmark changes, and its output is committed.
"""
import io
import json
import os
import sys

from fontTools.pens.basePen import BasePen
from fontTools.pens.boundsPen import BoundsPen
from fontTools.ttLib import TTFont
import uharfbuzz as hb

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))
FONT = os.path.join(ROOT, 'desktop', 'src', 'lib', 'fonts', 'Geist-Variable.woff2')
MANIFEST = os.path.join(ROOT, 'assets', 'theme', 'fathom-theme.json')
OUT_TS = os.path.join(ROOT, 'desktop', 'src', 'lib', 'brand', 'wordmark.generated.ts')
OUT_JAVA = os.path.join(ROOT, 'minecraft', 'fathom-optimizations', 'src', 'main', 'java',
                        'net', 'fathom', 'optimizations', 'generated', 'Wordmark.java')
TIERS = ('small', 'text', 'display')
UNITS = 100.0


def num(value):
    text = '%.2f' % value
    text = text.rstrip('0').rstrip('.')
    return '0' if text in ('-0', '') else text


class AbsolutePathPen(BasePen):
    """Absolute M/L/Q/C/Z only, which every consumer's parser reads."""

    def __init__(self, glyph_set, transform):
        super().__init__(glyph_set)
        self.transform = transform
        self.parts = []

    def _pt(self, point):
        x, y = self.transform(point)
        return num(x) + ' ' + num(y)

    def _moveTo(self, pt):
        self.parts.append('M' + self._pt(pt))

    def _lineTo(self, pt):
        self.parts.append('L' + self._pt(pt))

    def _qCurveToOne(self, pt1, pt2):
        self.parts.append('Q' + self._pt(pt1) + ' ' + self._pt(pt2))

    def _curveToOne(self, pt1, pt2, pt3):
        self.parts.append('C' + self._pt(pt1) + ' ' + self._pt(pt2) + ' ' + self._pt(pt3))

    def _closePath(self):
        self.parts.append('Z')


def outline(font, face, text, weight, tracking):
    upm = font['head'].unitsPerEm
    glyph_set = font.getGlyphSet(location={'wght': weight})
    order = font.getGlyphOrder()

    hb_font = hb.Font(face)
    hb_font.set_variations({'wght': weight})
    buf = hb.Buffer()
    buf.add_str(text)
    buf.guess_segment_properties()
    hb.shape(hb_font, buf, {'kern': True, 'liga': False})

    placed = []
    pen_x = 0.0
    for index, (info, pos) in enumerate(zip(buf.glyph_infos, buf.glyph_positions)):
        placed.append((order[info.codepoint], pen_x + pos.x_offset))
        pen_x += pos.x_advance
        if index < len(buf.glyph_infos) - 1:
            pen_x += tracking * upm

    def bounds(name):
        pen = BoundsPen(glyph_set)
        glyph_set[name].draw(pen)
        return pen.bounds

    cmap = font.getBestCmap()
    ascender = bounds(cmap[ord('h')])[3]
    x_height = bounds(cmap[ord('x')])[3]
    ink_left = min(bounds(name)[0] + x for name, x in placed)
    ink_right = max(bounds(name)[2] + x for name, x in placed)
    ink_bottom = min(bounds(name)[1] for name, _ in placed)
    scale = UNITS / ascender

    parts = []
    for name, x in placed:
        def transform(point, x=x):
            px, py = point
            return ((px + x - ink_left) * scale, (ascender - py) * scale)
        pen = AbsolutePathPen(glyph_set, transform)
        glyph_set[name].draw(pen)
        parts.append(''.join(pen.parts))
    return {
        'weight': weight,
        'tracking': tracking,
        'width': (ink_right - ink_left) * scale,
        'xHeight': x_height * scale,
        'descent': -ink_bottom * scale,
        'd': ''.join(parts),
    }


def main():
    manifest = json.load(open(MANIFEST, encoding='utf-8'))
    spec = manifest['graphite']['wordmark']
    text = spec['text']

    font = TTFont(FONT)
    sfnt = io.BytesIO()
    plain = TTFont(FONT)
    plain.flavor = None
    plain.save(sfnt)
    face = hb.Face(sfnt.getvalue())

    tiers = {name: outline(font, face, text, spec[name + '-weight'], spec[name + '-tracking'])
             for name in TIERS}

    ts = [
        '// Generated by tools/generate-wordmark.py from desktop/src/lib/fonts/Geist-Variable.woff2. Do not edit.',
        '/**',
        ' * "%s" in Geist, shaped by HarfBuzz and outlined, one set per optical tier.' % text,
        ' * Ascender 100 units, y down, baseline at 100, ink from x 0. `descent` is the',
        ' * round letters\' overshoot below the baseline.',
        ' */',
        'export const WORDMARK = {',
    ]
    for name in TIERS:
        t = tiers[name]
        ts.append('  %s: {' % name)
        ts.append('    weight: %d,' % t['weight'])
        ts.append('    tracking: %s,' % (('%.3f' % t['tracking']).rstrip('0').rstrip('.') or '0'))
        ts.append('    width: %s,' % num(t['width']))
        ts.append('    xHeight: %s,' % num(t['xHeight']))
        ts.append('    descent: %s,' % num(t['descent']))
        ts.append("    d: '%s'," % t['d'])
        ts.append('  },')
    ts.append('} as const;')
    ts.append('')
    ts.append('export type WordmarkTier = keyof typeof WORDMARK;')
    ts.append('')
    ts.append('/** The largest ascender, in device pixels, each of the first two tiers covers. */')
    ts.append('export const WORDMARK_TIERS = { smallMax: %s, textMax: %s } as const;'
              % (num(spec['small-max']), num(spec['text-max'])))
    ts.append('')

    java = [
        'package net.fathom.optimizations.generated;',
        '',
        '/**',
        ' * The wordmark, "%s" in Geist, as outlines: one path per optical tier.' % text,
        ' * Generated by tools/generate-wordmark.py; the launcher draws the same paths.',
        ' * Ascender 100 units, y down, baseline at 100, ink from x 0. Do not edit.',
        ' */',
        'public final class Wordmark {',
        '    private Wordmark() {}',
        '',
        '    /** The ascender, which in Geist is the cap height. */',
        '    public static final float CAP = %sf;' % num(UNITS),
    ]
    for name in TIERS:
        t = tiers[name]
        key = name.upper()
        java.append('')
        java.append('    public static final int %s_WEIGHT = %d;' % (key, t['weight']))
        java.append('    public static final float %s_WIDTH = %sf;' % (key, num(t['width'])))
        java.append('    public static final float %s_X_HEIGHT = %sf;' % (key, num(t['xHeight'])))
        java.append('    public static final float %s_DESCENT = %sf;' % (key, num(t['descent'])))
        java.append('    public static final String %s_PATH =' % key)
        chunks = [c + 'Z' for c in t['d'].split('Z') if c]
        for index, piece in enumerate(chunks):
            java.append('            "%s"%s' % (piece, ' +' if index + 1 < len(chunks) else ';'))
    java.append('}')
    java.append('')

    for path, lines in ((OUT_TS, ts), (OUT_JAVA, java)):
        with open(path, 'w', encoding='utf-8', newline='\n') as out:
            out.write('\n'.join(lines))
        print('wrote', os.path.relpath(path, ROOT))
    for name in TIERS:
        t = tiers[name]
        print('  %-8s wght %d  tracking %+.3f  width %.2f  x-height %.2f' %
              (name, t['weight'], t['tracking'], t['width'], t['xHeight']))
    return 0


if __name__ == '__main__':
    sys.exit(main())

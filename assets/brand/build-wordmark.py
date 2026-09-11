"""
Fathom wordmark build -- treatment A.

FATHOM in Geist, weight 600, +0.30em tracking, converted to outlines.

    python assets/brand/build-wordmark.py

Outlines rather than live text, because a wordmark set as <text> is not a
wordmark: it renders in whatever the viewer happens to have installed, which
for an SVG dropped into a store listing, a README or an installer is usually
not Geist. Outlines make the file say the same thing everywhere.

The source is the same font file the launcher ships -- desktop/src/lib/fonts,
under the SIL Open Font License, which permits this. Nothing is redrawn and
nothing is scaled non-uniformly; the only decisions are weight, case and
tracking.

Requires fontTools with brotli (for the woff2), which is a heavier dependency
than the icon build wants -- hence a separate script. The wordmark changes
about as often as the company name, so this is not in any build pipeline; run
it by hand if the treatment ever changes.
"""

import os

from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.recordingPen import RecordingPen
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.misc.transform import Transform
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

BRAND = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(BRAND, "..", ".."))
FONT = os.path.join(REPO, "desktop", "src", "lib", "fonts", "Geist-Variable.woff2")

WORD = "FATHOM"
WEIGHT = 600
TRACKING = 0.30  # em, matching treatment A

FRAME_HEX = "#f4f6f9"   # --text, for dark grounds
INK_HEX = "#101215"     # for light grounds

# Display height of the width/height attributes. The viewBox is the ink box, so
# this only sets what the file does when nothing else states a size.
DEFAULT_HEIGHT = 32


def kern_pairs(font):
    """Pair kerning from GPOS, read directly rather than shaped.

    Tracking this wide does not make kerning irrelevant: FA and AT come to -83
    and -96 units against 300 of tracking, which is a third of the gap and
    plainly visible. The browser applied these when the treatment was chosen,
    so the outlines apply them too.
    """
    gpos = font["GPOS"].table
    lookups = set()
    for record in gpos.FeatureList.FeatureRecord:
        if record.FeatureTag == "kern":
            lookups.update(record.Feature.LookupListIndex)

    def value(first, second):
        total = 0
        for index in sorted(lookups):
            for sub in gpos.LookupList.Lookup[index].SubTable:
                if sub.__class__.__name__ == "ExtensionPos":
                    sub = sub.ExtSubTable
                if sub.__class__.__name__ != "PairPos":
                    continue
                coverage = {g: i for i, g in enumerate(sub.Coverage.glyphs)}
                if first not in coverage:
                    continue
                if sub.Format == 1:
                    for pair in sub.PairSet[coverage[first]].PairValueRecord:
                        if pair.SecondGlyph == second and pair.Value1 and pair.Value1.XAdvance:
                            total += pair.Value1.XAdvance
                elif sub.Format == 2:
                    c1 = sub.ClassDef1.classDefs.get(first, 0)
                    c2 = sub.ClassDef2.classDefs.get(second, 0)
                    rec = sub.Class1Record[c1].Class2Record[c2]
                    if rec.Value1 and rec.Value1.XAdvance:
                        total += rec.Value1.XAdvance
        return total

    return value


def build():
    font = TTFont(FONT)
    font = instancer.instantiateVariableFont(
        font, {"wght": WEIGHT}, inplace=True, updateFontNames=False
    )

    upem = font["head"].unitsPerEm
    tracking = TRACKING * upem
    glyphs = font.getGlyphSet()
    widths = font["hmtx"]
    kern = kern_pairs(font)

    # Lay the line out once, recording each glyph at its position. Tracking goes
    # between letters and not after the last one: a trailing space would push
    # the ink off-centre in every lockup that uses this file.
    placed = []
    pen_x = 0.0
    for i, char in enumerate(WORD):
        recording = RecordingPen()
        glyphs[char].draw(recording)
        placed.append((recording, pen_x))
        pen_x += widths[char][0]
        if i < len(WORD) - 1:
            pen_x += kern(char, WORD[i + 1]) + tracking

    # Ink bounds, curve extrema included, so the viewBox is the artwork and not
    # the em box with its arbitrary sidebearings and line gap.
    bounds = BoundsPen(None)
    for recording, x in placed:
        recording.replay(TransformPen(bounds, Transform(1, 0, 0, 1, x, 0)))
    min_x, min_y, max_x, max_y = bounds.bounds

    width = max_x - min_x
    height = max_y - min_y

    # Font space is y-up and SVG is y-down, so the flip is baked into the path
    # data. The finished file then carries no transform of its own.
    svg_pen = SVGPathPen(None, ntos=lambda v: repr(round(v, 1)))
    for recording, x in placed:
        recording.replay(TransformPen(svg_pen, Transform(1, 0, 0, -1, x - min_x, max_y)))
    path = svg_pen.getCommands()

    return path, width, height


TEMPLATE = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {w} {h}"\
 width="{dw}" height="{dh}" role="img" aria-label="Fathom">
  <title>Fathom</title>
  <!-- FATHOM in Geist 600, +0.30em tracking, converted to outlines.
       Generated by assets/brand/build-wordmark.py. Do not hand-edit. -->
  <path fill="{fill}" d="{d}"/>
</svg>
"""


def write(name, path, width, height, fill):
    display_w = round(DEFAULT_HEIGHT * width / height, 1)
    svg = TEMPLATE.format(
        w=round(width, 1), h=round(height, 1),
        dw=display_w, dh=DEFAULT_HEIGHT,
        fill=fill, d=path,
    )
    target = os.path.join(BRAND, name)
    with open(target, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(svg)
    print("  assets/brand/%s  (%d bytes)" % (name, len(svg.encode("utf-8"))))


if __name__ == "__main__":
    path, width, height = build()
    print("wordmark: %s, Geist %d, +%.2fem, ink %.1f x %.1f units"
          % (WORD, WEIGHT, TRACKING, width, height))
    write("wordmark.svg", path, width, height, FRAME_HEX)
    write("wordmark-on-light.svg", path, width, height, INK_HEX)
    write("wordmark-mono.svg", path, width, height, "currentColor")

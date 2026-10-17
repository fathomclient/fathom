"""Derive the in-game UI fonts from the font the launcher already vendors.

    python tools/derive-ui-font.py

Reads  desktop/src/lib/fonts/Geist-Variable.woff2
Writes minecraft/fathom-optimizations/src/main/resources/assets/fathom/font/
           Geist-Regular.ttf   (wght 400)
           Geist-Medium.ttf    (wght 500)
           Geist-SemiBold.ttf  (wght 600)
           Geist-LICENSE.txt

WHY THIS EXISTS

The launcher and the in-game hub have to be the same typeface or they are not
the same product. The launcher's copy is a variable WOFF2, which is the right
format for a WebView and the wrong one for a JVM: `java.awt.Font.createFont`
reads TrueType and OpenType and has no idea what Brotli is, and no JDK 8 can
pick an axis position out of a variable font.

So the game's copies are DERIVED from the launcher's copy rather than
downloaded separately. There is exactly one Geist in this repository; these are
three static instances of it, pinned to the three weights the interface uses.
A second upstream download would be a second thing to trust and a second thing
that can silently become a different font.

Requires `fonttools` and `brotli` on the developer's machine. Neither is a
dependency of the launcher or of the mod: this runs when the font changes,
which is approximately never, and its output is committed.

Geist is SIL Open Font License 1.1, which permits exactly this: modification
and redistribution as part of a larger work. The licence travels with the
files.
"""
import os
import shutil
import sys

from fontTools.ttLib import TTFont
from fontTools import subset
from fontTools.varLib import instancer

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SOURCE = os.path.join(HERE, 'desktop', 'src', 'lib', 'fonts', 'Geist-Variable.woff2')
LICENCE = os.path.join(HERE, 'desktop', 'src', 'lib', 'fonts', 'Geist-LICENSE.txt')
OUT = os.path.join(HERE, 'minecraft', 'fathom-optimizations', 'src', 'main',
                   'resources', 'assets', 'fathom', 'font')

# The three weights `theme.css` names: --w-normal, --w-medium, --w-semibold.
# 700 is in the token list and unused by the in-game interface; a fourth file
# would be 30 KB for nothing.
WEIGHTS = [('Regular', 400), ('Medium', 500), ('SemiBold', 600)]

# What the interface can be asked to draw.
#
# Latin-1 and Latin Extended-A because world names, server names and player
# names are written by people, and a missing glyph in somebody's name is worse
# than 8 KB. Currency, arrows and the typographic punctuation because the
# interface itself uses them -- the middle dot between profile and server, the
# ellipsis when a label is truncated, the multiplication sign in "1280 x 720".
RANGES = [
    (0x0020, 0x007E),   # ASCII
    (0x00A0, 0x00FF),   # Latin-1 Supplement
    (0x0100, 0x017F),   # Latin Extended-A
    (0x2018, 0x201F),   # quotes
    (0x2010, 0x2015),   # dashes
    (0x2026, 0x2026),   # ellipsis
    (0x2022, 0x2022),   # bullet
    (0x00D7, 0x00D7),   # multiplication sign
    (0x2190, 0x2193),   # arrows
    (0x00A7, 0x00A7),   # section sign, which Minecraft uses for colour codes
    (0x20AC, 0x20AC),   # euro
    (0x2713, 0x2717),   # check and cross, for status lines
]


def wanted():
    out = []
    for first, last in RANGES:
        out.extend(range(first, last + 1))
    return sorted(set(out))


def main():
    if not os.path.exists(SOURCE):
        sys.exit('the launcher font is missing: ' + SOURCE)
    if not os.path.isdir(OUT):
        os.makedirs(OUT)

    codepoints = wanted()
    for name, weight in WEIGHTS:
        font = TTFont(SOURCE)                       # brotli happens here
        static = instancer.instantiateVariableFont(font, {'wght': weight})

        options = subset.Options()
        options.layout_features = ['kern', 'liga', 'calt', 'ccmp', 'locl']
        options.name_IDs = ['*']
        options.notdef_outline = True
        options.recalc_bounds = True
        subsetter = subset.Subsetter(options=options)
        subsetter.populate(unicodes=codepoints)
        subsetter.subset(static)

        static['name'].setName('Geist ' + name, 4, 3, 1, 0x409)
        static['name'].setName('Geist', 1, 3, 1, 0x409)
        static['name'].setName(name, 2, 3, 1, 0x409)

        static.flavor = None
        path = os.path.join(OUT, 'Geist-' + name + '.ttf')
        static.save(path)
        kept = len(static.getGlyphOrder())
        print('%-22s %6d bytes  %d glyphs' % (os.path.basename(path),
                                              os.path.getsize(path), kept))

    shutil.copyfile(LICENCE, os.path.join(OUT, 'Geist-LICENSE.txt'))
    print('Geist-LICENSE.txt copied beside them.')


if __name__ == '__main__':
    main()

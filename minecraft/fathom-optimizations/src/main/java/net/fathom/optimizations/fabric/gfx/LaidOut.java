package net.fathom.optimizations.fabric.gfx;

import net.fathom.optimizations.core.ui.layout.Box;

/**
 * A screen whose layout is a {@link Box} tree: what the debug overlay draws
 * and what the UI tour's geometry dump compares with the launcher.
 */
public interface LaidOut {

    /** The tree as last laid out; null before the first frame. */
    Box layoutRoot();
}

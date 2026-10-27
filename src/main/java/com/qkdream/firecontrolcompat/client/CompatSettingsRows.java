package com.qkdream.firecontrolcompat.client;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import net.minecraft.client.gui.components.AbstractWidget;

/**
 * Bookkeeping for the rows this mod adds to the fire control settings page.
 *
 * <p>Two mixins add rows to that page, and each one used to work out where to put
 * them by asking the screen for its lowest control. That made them count each
 * other: the block of lead settings was pushed under the laser code row, the laser
 * code row stopped being last, and on every rebuild the base moved further down
 * until the rows were off the page entirely - which is what a crew saw as the laser
 * code field having vanished.</p>
 *
 * <p>Rows made here are registered, both mixins skip registered rows when measuring,
 * and the block records how low it reaches so the next block starts below it.</p>
 */
public final class CompatSettingsRows {

    /** Widgets this mod put on the page. */
    private static final Set<AbstractWidget> OURS =
            Collections.newSetFromMap(new IdentityHashMap<>());

    /** How low this mod's block of rows reaches, or 0 when nothing is placed. */
    private static int blockBottom;

    private CompatSettingsRows() {
    }

    /** Files a widget as ours, so no other row measurement counts it. */
    public static void register(AbstractWidget widget) {
        if (widget != null) {
            OURS.add(widget);
        }
    }

    /** Whether that widget was put there by this mod. */
    public static boolean isOurs(Object widget) {
        return widget != null && OURS.contains(widget);
    }

    /** Records how low our block reaches. */
    public static void rememberBottom(int bottom) {
        blockBottom = Math.max(blockBottom, bottom);
    }

    /** How low our block reaches, 0 when nothing has been placed yet. */
    public static int blockBottom() {
        return blockBottom;
    }

    /** Forgets both, when the page is built from scratch. */
    public static void reset() {
        OURS.clear();
        blockBottom = 0;
    }
}

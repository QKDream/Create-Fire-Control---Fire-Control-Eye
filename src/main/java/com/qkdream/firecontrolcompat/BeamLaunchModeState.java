package com.qkdream.firecontrolcompat;

/**
 * Client side mirror of the beam riding missile launch mode of the computer
 * whose fire control screen is open.
 *
 * <p>The server answers the screen opening with the stored value a moment after
 * the page is built, so the toggle reads it from here rather than from the
 * shared setting the screen used to be filled with.</p>
 */
public final class BeamLaunchModeState {

    private static volatile boolean screenVertical;

    private BeamLaunchModeState() {
    }

    /** Launch mode of the computer whose fire control screen is open. */
    public static boolean screenVertical() {
        return screenVertical;
    }

    public static void applyScreenVertical(boolean vertical) {
        screenVertical = vertical;
    }
}
package com.qkdream.firecontrolcompat.sonar;

import com.qkdream.firecontrolcompat.FireControlLeadSettings;

/**
 * The swim the computer whose screen is open files for its acoustic torpedoes,
 * as the server last reported it. The torpedo page reads them from here and
 * writes them back through the computer, so what the page shows is always the
 * computer's own answer rather than one shared by the world.
 */
public final class TorpedoSettingsState {

    /** What a computer with nothing filed runs on. */
    private static final boolean DEFAULT_HOLD_DEPTH = false;
    private static final double DEFAULT_DEPTH = 8.0D;
    private static final double DEFAULT_ACTIVATION = 0.0D;

    private static boolean holdDepth = DEFAULT_HOLD_DEPTH;
    private static double depth = DEFAULT_DEPTH;
    private static double activation = DEFAULT_ACTIVATION;

    private TorpedoSettingsState() {
    }

    /** Takes the values the server reported for the computer on screen. */
    public static void accept(boolean hold, double depthValue, double activationValue) {
        holdDepth = hold;
        depth = FireControlLeadSettings.clampTorpedoDepth(depthValue);
        activation = FireControlLeadSettings.clampTorpedoActivation(activationValue);
    }

    /** Whether an idle round this computer fires holds a depth. */
    public static boolean holdDepth() {
        return holdDepth;
    }

    /** Depth an idle round is held at, in blocks below the top of the water. */
    public static double depth() {
        return depth;
    }

    /** Blocks a round swims before its hydrophones come on. */
    public static double activation() {
        return activation;
    }
}

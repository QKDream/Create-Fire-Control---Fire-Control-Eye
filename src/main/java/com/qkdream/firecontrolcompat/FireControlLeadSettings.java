package com.qkdream.firecontrolcompat;

/**
 * Global lead settings mirrored on both logical sides.
 *
 * <p>The fire control computer screen edits these values; on save the client
 * applies them locally and streams them to the server, which broadcasts them
 * back to every client (dedicated server support). Integrated single player
 * shares the same class so both sides always agree.</p>
 */
public final class FireControlLeadSettings {

    /** Minimum allowed beam missile proximity fuse range. */
    public static final double MIN_PROXIMITY_RANGE = 2.0;
    /** Maximum allowed beam missile proximity fuse range. */
    public static final double MAX_PROXIMITY_RANGE = 40.0;

    private static boolean gunLeadEnabled = true;
    private static boolean beamVerticalLaunch = true;
    private static double beamProximityRange = 10.0;

    private FireControlLeadSettings() {
    }

    public static synchronized void apply(
            boolean gunLead, boolean verticalLaunch, double proximityRange) {
        gunLeadEnabled = gunLead;
        beamVerticalLaunch = verticalLaunch;
        beamProximityRange = Math.max(MIN_PROXIMITY_RANGE, Math.min(MAX_PROXIMITY_RANGE, proximityRange));
    }

    public static synchronized boolean gunLeadEnabled() {
        return gunLeadEnabled;
    }

    public static synchronized boolean beamVerticalLaunch() {
        return beamVerticalLaunch;
    }

    public static synchronized double beamProximityRange() {
        return beamProximityRange;
    }
}

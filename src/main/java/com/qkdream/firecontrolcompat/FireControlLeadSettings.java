package com.qkdream.firecontrolcompat;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Global lead settings mirrored on both logical sides.
 *
 * <p>The fire control computer screen edits these values; on save the client
 * applies them locally and streams them to the server, which broadcasts them
 * back to every client (dedicated server support). Integrated single player
 * shares the same class so both sides always agree.</p>
 *
 * <p>They are also written to this mod's config file as soon as they change.
 * Kept in memory only they were back at their compiled defaults on the next
 * start, so a launch mode chosen in the computer was forgotten the moment the
 * game was closed.</p>
 */
public final class FireControlLeadSettings {

    /** Minimum allowed beam missile proximity fuse range. */
    public static final double MIN_PROXIMITY_RANGE = 2.0;
    /** Maximum allowed beam missile proximity fuse range. */
    public static final double MAX_PROXIMITY_RANGE = 40.0;

    /** Minimum allowed radar speed gate, in metres per second. */
    public static final double MIN_SPEED_GATE = 1.0;
    /** Maximum allowed radar speed gate, in metres per second. */
    public static final double MAX_SPEED_GATE = 500.0;

    /** Shallowest cruise depth an idle acoustic torpedo may be held at, in blocks. */
    public static final double MIN_TORPEDO_DEPTH = 1.0;
    /** Deepest cruise depth an idle acoustic torpedo may be held at, in blocks. */
    public static final double MAX_TORPEDO_DEPTH = 64.0;
    /** Shortest arming run an acoustic torpedo may be given, in blocks. */
    public static final double MIN_TORPEDO_ACTIVATION = 0.0;
    /** Longest arming run an acoustic torpedo may be given, in blocks. */
    public static final double MAX_TORPEDO_ACTIVATION = 1000.0;

    private static ModConfigSpec spec;
    private static ModConfigSpec.BooleanValue gunLeadSetting;
    private static ModConfigSpec.BooleanValue verticalLaunchSetting;
    private static ModConfigSpec.DoubleValue proximityRangeSetting;
    private static ModConfigSpec.BooleanValue speedGateSetting;
    private static ModConfigSpec.DoubleValue speedGateSpeedSetting;
    private static ModConfigSpec.BooleanValue imageLockSetting;
    private static ModConfigSpec.BooleanValue torpedoHoldDepthSetting;
    private static ModConfigSpec.DoubleValue torpedoDepthSetting;
    private static ModConfigSpec.DoubleValue torpedoActivationSetting;
    private static ModConfigSpec.EnumValue<LeadCannonKind> leadCannonKindSetting;
    private static ModConfigSpec.IntValue bigCannonChargeSetting;

    /** Set once the file has been read, so writes only happen on a loaded spec. */
    private static boolean storedOnDisk;

    private static boolean gunLeadEnabled = true;
    /** Horizontal launch unless vertical launch is picked in the computer. */
    private static boolean beamVerticalLaunch = false;
    /** The value the settings page and the payload carry while the row is gone. */
    private static final double LEGACY_PROXIMITY_RANGE = 10.0;
    /**
     * @deprecated The beam rider's proximity fuse no longer reads this: it fuses on
     * its own fixed radius. The value is still loaded from and stored to the config
     * file so the file keeps its shape, but nothing in the flight code consults it.
     */
    @Deprecated
    private static double beamProximityRange = LEGACY_PROXIMITY_RANGE;
    private static boolean speedGateEnabled = false;
    private static double speedGateSpeed = 10.0;
    /** Infrared heads run on their plain heat seeker unless image lock is picked. */
    private static boolean imageLockEnabled = false;
    /** Acoustic torpedoes cruise on the surface unless a depth is picked for them. */
    private static boolean torpedoHoldDepth = false;
    private static double torpedoDepth = 8.0;
    /** Blocks an acoustic torpedo swims before its hydrophones come on. */
    private static double torpedoActivation = 0.0;
    /** Which cannon family the lead computer solves for; one at a time. */
    private static LeadCannonKind leadCannonKind = LeadCannonKind.SMALL_CANNON;
    /** Powder charge of a big cannon shot, 1..8; four is the round the guns are sighted for. */
    private static int bigCannonCharge = 4;

    /** Lowest powder charge a big cannon shot may be given. */
    public static final int MIN_BIG_CANNON_CHARGE = 1;
    /** Highest powder charge a big cannon shot may be given. */
    public static final int MAX_BIG_CANNON_CHARGE = 8;

    public static LeadCannonKind leadCannonKind() {
        return leadCannonKind;
    }

    public static void setLeadCannonKind(LeadCannonKind kind) {
        leadCannonKind = kind == null ? LeadCannonKind.SMALL_CANNON : kind;
    }

    public static int bigCannonCharge() {
        return bigCannonCharge;
    }

    public static void setBigCannonCharge(int charge) {
        bigCannonCharge = Math.max(MIN_BIG_CANNON_CHARGE, Math.min(MAX_BIG_CANNON_CHARGE, charge));
    }

    private FireControlLeadSettings() {
    }

    /** Builds the config file these values live in and hands it to the loader. */
    public static void register(ModContainer container) {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.comment(
                "Lead settings of the fire control computer's fire-control page.",
                "The computer stores them here whenever they change, so the values",
                "that were last used come back when the game is started again.");
        builder.push("leadSettings");
        gunLeadSetting = builder
                .comment("Draw the cannon lead indicator of the bound gun.")
                .define("gunLeadEnabled", true);
        verticalLaunchSetting = builder
                .comment("Beam riding missile launch mode. True launches vertically, turning",
                        "over with the slow high-G kick, false launches straight along the tube.")
                .define("verticalLaunchEnabled", false);
        proximityRangeSetting = builder
                .comment("Unused since 1.70: the beam rider's proximity fuse has a fixed radius",
                        "and the settings page no longer shows this row. The key stays so an",
                        "existing config file still loads without a warning.")
                .defineInRange("beamProximityRange", LEGACY_PROXIMITY_RANGE, MIN_PROXIMITY_RANGE, MAX_PROXIMITY_RANGE);
        speedGateSetting = builder
                .comment("Radar speed gate: hides radar contacts slower than the threshold below.")
                .define("speedGateEnabled", false);
        speedGateSpeedSetting = builder
                .comment("Radar speed gate threshold, in blocks per second.")
                .defineInRange("speedGateSpeed", 10.0, MIN_SPEED_GATE, MAX_SPEED_GATE);
        imageLockSetting = builder
                .comment("Infrared missile seeker head picked in the fire control computer.",
                        "True switches it to image lock: Sable structures only, daylight under",
                        "a clear sky, one thousand blocks of reach, and a contact that is",
                        "never dropped once the head has taken it.")
                .define("imageLockEnabled", false);
        builder.push("torpedo");
        torpedoHoldDepthSetting = builder
                .comment("Cruise state of an acoustic torpedo that has nothing to chase.",
                        "True holds the run at the depth below; false lets it come up and",
                        "patrol on the surface with its screws still turning.")
                .define("holdDepthEnabled", false);
        torpedoDepthSetting = builder
                .comment("Depth an idle acoustic torpedo is held at, in blocks below the surface.")
                .defineInRange("cruiseDepth", 8.0, MIN_TORPEDO_DEPTH, MAX_TORPEDO_DEPTH);
        torpedoActivationSetting = builder
                .comment("Distance an acoustic torpedo swims before its hydrophones come on,",
                        "in blocks. A run that is switched on at once would hear the hull that",
                        "fired it; zero keeps the old behaviour of listening from the launch.")
                .defineInRange("activationDistance", 0.0, MIN_TORPEDO_ACTIVATION, MAX_TORPEDO_ACTIVATION);
        leadCannonKindSetting = builder
                .comment("Cannon family the lead computer solves for. One at a time:",
                        "SMALL_CANNON, MEDIUM_CANNON or BIG_CANNON. (The autocannon is solved",
                        "by fire control itself since 0.9.3, so it is not listed here.)")
                .defineEnum("leadCannonKind", LeadCannonKind.SMALL_CANNON);
        bigCannonChargeSetting = builder
                .comment("Powder charge of a big cannon shot. Four is the charge the guns",
                        "are sighted for; a heavier charge throws the round faster, a lighter",
                        "one throws it slower, and the lead is solved with that speed.")
                .defineInRange("bigCannonCharge", 4, MIN_BIG_CANNON_CHARGE, MAX_BIG_CANNON_CHARGE);
        builder.pop();
        builder.pop();
        spec = builder.build();
        container.registerConfig(ModConfig.Type.COMMON, spec);
    }

    /** True for the spec this class stores its values in. */
    public static boolean owns(ModConfigSpec candidate) {
        return spec != null && spec == candidate;
    }

    /** Reads the stored values back; called whenever the config file is read. */
    public static synchronized void loadFromConfig() {
        if (spec == null || !spec.isLoaded()) {
            return;
        }
        storedOnDisk = true;
        apply(
                gunLeadSetting.get(),
                verticalLaunchSetting.get(),
                proximityRangeSetting.get(),
                speedGateSetting.get(),
                speedGateSpeedSetting.get(),
                imageLockSetting.get(),
                torpedoHoldDepthSetting.get(),
                torpedoDepthSetting.get(),
                torpedoActivationSetting.get());
        applyLeadTarget(leadCannonKindSetting.get(), bigCannonChargeSetting.get());
    }

    public static synchronized void apply(
            boolean gunLead,
            boolean verticalLaunch,
            double proximityRange,
            boolean speedGate,
            double speedGateSpeedValue,
            boolean imageLock,
            boolean torpedoDepthHold,
            double torpedoDepthValue,
            double torpedoActivationValue) {
        gunLeadEnabled = gunLead;
        beamVerticalLaunch = verticalLaunch;
        beamProximityRange = Math.max(MIN_PROXIMITY_RANGE, Math.min(MAX_PROXIMITY_RANGE, proximityRange));
        speedGateEnabled = speedGate;
        speedGateSpeed = clampSpeed(speedGateSpeedValue);
        imageLockEnabled = imageLock;
        torpedoHoldDepth = torpedoDepthHold;
        torpedoDepth = clampTorpedoDepth(torpedoDepthValue);
        torpedoActivation = clampTorpedoActivation(torpedoActivationValue);
        store();
    }

    /** Sets which cannon family the lead solves for and its powder charge. */
    public static synchronized void applyLeadTarget(LeadCannonKind kind, int charge) {
        setLeadCannonKind(kind);
        setBigCannonCharge(charge);
        store();
    }

    /** Writes the values back to the config file when they actually changed. */
    private static synchronized void store() {
        if (!storedOnDisk || spec == null || !spec.isLoaded()) {
            return;
        }
        try {
            boolean changed = (leadCannonKindSetting != null && leadCannonKindSetting.get() != leadCannonKind)
                    || (bigCannonChargeSetting != null && bigCannonChargeSetting.get() != bigCannonCharge)
                    || gunLeadSetting.get() != gunLeadEnabled
                    || verticalLaunchSetting.get() != beamVerticalLaunch
                    || proximityRangeSetting.get() != beamProximityRange
                    || speedGateSetting.get() != speedGateEnabled
                    || speedGateSpeedSetting.get() != speedGateSpeed
                    || imageLockSetting.get() != imageLockEnabled
                    || torpedoHoldDepthSetting.get() != torpedoHoldDepth
                    || torpedoDepthSetting.get() != torpedoDepth
                    || torpedoActivationSetting.get() != torpedoActivation;
            if (!changed) {
                return;
            }
            gunLeadSetting.set(gunLeadEnabled);
            verticalLaunchSetting.set(beamVerticalLaunch);
            proximityRangeSetting.set(beamProximityRange);
            speedGateSetting.set(speedGateEnabled);
            speedGateSpeedSetting.set(speedGateSpeed);
            imageLockSetting.set(imageLockEnabled);
            if (leadCannonKindSetting != null) {
                leadCannonKindSetting.set(leadCannonKind);
            }
            if (bigCannonChargeSetting != null) {
                bigCannonChargeSetting.set(bigCannonCharge);
            }
            torpedoHoldDepthSetting.set(torpedoHoldDepth);
            torpedoDepthSetting.set(torpedoDepth);
            torpedoActivationSetting.set(torpedoActivation);
            spec.save();
        } catch (Throwable ignored) {
            // Storing a setting must never take the game down.
        }
    }

    /** Clamps a speed gate value into the allowed window. */
    public static synchronized double clampSpeed(double value) {
        if (!Double.isFinite(value)) {
            return speedGateSpeed;
        }
        return Math.max(MIN_SPEED_GATE, Math.min(MAX_SPEED_GATE, value));
    }

    public static synchronized boolean gunLeadEnabled() {
        return gunLeadEnabled;
    }

    public static synchronized boolean beamVerticalLaunch() {
        return beamVerticalLaunch;
    }

    /**
     * @deprecated No longer consulted by the flight code: the beam rider fuses on
     * its own fixed radius. Kept only so the settings page and the lead payload
     * still carry a finite number without touching either codec.
     */
    @Deprecated
    public static synchronized double beamProximityRange() {
        return LEGACY_PROXIMITY_RANGE;
    }

    /** Whether the radar hides contacts slower than {@link #speedGateSpeed()}. */
    public static synchronized boolean speedGateEnabled() {
        // Speed gate removed: the fire control pack keeps one of its own now.
        return false;
    }

    /** Speed gate threshold in metres per second. */
    public static synchronized double speedGateSpeed() {
        return speedGateSpeed;
    }

    /** Whether the infrared seeker head runs in image lock mode. */
    public static synchronized boolean imageLockEnabled() {
        return imageLockEnabled;
    }

    /** Clamps a cruise depth into the allowed window. */
    public static synchronized double clampTorpedoDepth(double value) {
        if (!Double.isFinite(value)) {
            return torpedoDepth;
        }
        return Math.max(MIN_TORPEDO_DEPTH, Math.min(MAX_TORPEDO_DEPTH, value));
    }

    /** Clamps an arming run into the allowed window. */
    public static synchronized double clampTorpedoActivation(double value) {
        if (!Double.isFinite(value)) {
            return torpedoActivation;
        }
        return Math.max(MIN_TORPEDO_ACTIVATION, Math.min(MAX_TORPEDO_ACTIVATION, value));
    }

    /** Whether an idle acoustic torpedo holds a depth instead of surfacing. */
    public static synchronized boolean torpedoHoldDepth() {
        return torpedoHoldDepth;
    }

    /** Depth below the surface an idle acoustic torpedo is held at, in blocks. */
    public static synchronized double torpedoDepth() {
        return torpedoDepth;
    }

    /** Blocks an acoustic torpedo swims before its hydrophones come on. */
    public static synchronized double torpedoActivationDistance() {
        return torpedoActivation;
    }
}

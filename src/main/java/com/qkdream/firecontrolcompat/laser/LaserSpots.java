package com.qkdream.firecontrolcompat.laser;

import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.qkdream.firecontrolcompat.SubLevelPositions;
import com.hooya.stabilizedturret.content.controller.ControllerTracker;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * The laser spot every optical pod is painting, keyed by the code of the
 * computer that owns the pod.
 *
 * <p>Fire control's own laser round is flown at {@code getPodGuidanceTarget()}
 * directly, with nothing between the pod and the round. This mod rides the same
 * pod but keeps the spot on the server side and hands it out by code, so a round
 * can be told which pod to listen to ("laser encoding"): the code of a computer
 * is derived from its own position, so every rig has its own laser by default
 * and two aircraft lasing at once never steer each other's rounds.</p>
 *
 * <p>Nothing here is persisted: a spot only exists while somebody is looking
 * through the pod. A spot that is not refreshed within {@link #STALE_TICKS} is
 * dropped, which is what stops a round from chasing the point a gunner left
 * behind when he climbed out of the pod.</p>
 */
public final class LaserSpots {

    /**
     * Prints, a few times a minute, where the pod says its camera is next to
     * where the pod block actually stands.
     *
     * <p>A crew reported the view leaving the pod's model from its underside
     * while the lens is drawn on its side, which is a question about the frame
     * the camera offset is measured in, not about angles. This says which frame
     * it is: the offset between the block and the camera, the block's own state,
     * and the sub-level both stand on. If the offset turns with the structure,
     * the offset is being applied without the structure's rotation.</p>
     */
    static final class PodCameraProbe {

        private static final long INTERVAL_TICKS = 100L;
        private static long lastReport;

        private PodCameraProbe() {
        }

        static void report(StabilizerControllerBlockEntity controller, Vec3 camera) {
            try {
                if (camera == null || controller.getLevel() == null) {
                    return;
                }
                long now = controller.getLevel().getGameTime();
                if (now - lastReport < INTERVAL_TICKS) {
                    return;
                }
                lastReport = now;
                BlockPos pos = controller.getBlockPos();
                Vec3 center = Vec3.atCenterOf(pos);
                Vec3 offset = camera.subtract(center);
                // The same offset read in the pod's own frame: forward along its
                // facing, right, up. A camera sitting on the far side of the lens
                // shows up here as a negative forward (or a wrong up), which is
                // the number the config line should carry.
                Vec3 look = controller.getPodDesiredWorldDirection();
                String lookText = look == null
                        ? "none"
                        : String.format(java.util.Locale.ROOT, "yaw=%.1f pitch=%.1f",
                                Math.toDegrees(Math.atan2(-look.x, look.z)),
                                Math.toDegrees(Math.atan2(-look.y, Math.hypot(look.x, look.z))));
                Vec3 local = com.qkdream.firecontrolcompat.PodCameraOffset.local(offset, controller.getBlockState());
                com.qkdream.firecontrolcompat.FireControlCompat.LOGGER.info(
                        "[firecontrolcompat] pod camera probe block={} state={} center=({},{},{}) "
                                + "camera=({},{},{}) offset=({},{},{}) "
                                + "local(forward,right,up)=({},{},{}) podOwnLook={} config={} spin={} level={}",
                        pos, controller.getBlockState(), center.x, center.y, center.z,
                        camera.x, camera.y, camera.z, offset.x, offset.y, offset.z,
                        local.x, local.y, local.z,
                        lookText,
                        com.qkdream.firecontrolcompat.PodCameraOffset.offset(),
                        com.qkdream.firecontrolcompat.PodCameraOffset.spin(),
                        controller.getLevel().dimension().location());
            } catch (RuntimeException | LinkageError ignored) {
            }
        }
    }

    /**
     * One painted spot: where it is, when it was last refreshed, whether it is blocked, and what
     * kind of platform is doing the painting.
     *
     * <p>{@code airborne} is the classification the anti-tank round needs: a pod on an aircraft
     * designates for the aircraft weapon chain, a pod on the ground (a tank's or a tripod's
     * electro-optical head) designates for the ground launchers. Both paint the same way - the same
     * block, the same ray, the same {@link #SPOTS} table - so without this flag the two are
     * indistinguishable once the spot has been recorded, and a shot could not say which of the two
     * routes it belongs on.</p>
     */
    public record Spot(int code, Vec3 position, long stamp, boolean blocked, ServerLevel level,
            boolean airborne) {
    }

    /**
     * Whether the spot with this code is painted by an airborne platform.
     *
     * <p>"Airborne" is read from the computer that owns the pod, never from the launcher being
     * fired: a computer that has aircraft rails or an aircraft radar bound is a cockpit, and one
     * that has neither is a ground station. A code that is not painting answers {@code false}, so a
     * caller that lost the race with the two-second refresh never reports a pod it cannot see.</p>
     */
    public static boolean isAirborne(int code) {
        for (Spot spot : SPOTS.values()) {
            if (code <= 0 || spot.code() == code) {
                if (spot.airborne()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The spot with this code (or any, when the code is 0), so a caller can read its platform. */
    public static Spot spotFor(int code) {
        for (Spot spot : SPOTS.values()) {
            if (code <= 0 || spot.code() == code) {
                return spot;
            }
        }
        return null;
    }

    /**
     * Whether the pod of this computer is on an aircraft.
     *
     * <p>Two readings, either of which is enough: the computer has aircraft weapon rails bound (a
     * cockpit drives rails, a ground station drives launchers), or its aircraft radar mode is on
     * (which only a cockpit has). Both are plain getters on the computer and never throw for a
     * computer that has been unloaded mid-refresh.</p>
     */
    public static boolean podIsAirborne(StabilizerControllerBlockEntity controller) {
        if (controller == null) {
            return false;
        }
        try {
            if (!controller.getAircraftMissileRacks().isEmpty()) {
                return true;
            }
        } catch (RuntimeException | LinkageError ignored) {
            // a computer that cannot be read is treated as a ground station
        }
        try {
            return controller.isAircraftRadarMode();
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    /** The key a crew can write a code of its own under, on the computer. */
    public static final String CODE_OVERRIDE_KEY = "fclasercode";

    /**
     * The laser code of a computer: the one written on it by hand, or - when none
     * is written - the one derived from where it stands, which is unique per rig
     * and needs no setting up at all.
     *
     * <p>Writing the same code on two computers is what lets one of them paint for
     * the other: the spot is only lit while a round of the same code is in the
     * air, and a round takes the code of the computer it was fired from.</p>
     */
    public static int codeFor(StabilizerControllerBlockEntity controller) {
        if (controller == null) {
            return 0;
        }
        try {
            int written = controller.getPersistentData().getInt(CODE_OVERRIDE_KEY);
            if (written > 0) {
                return written;
            }
        } catch (RuntimeException | LinkageError ignored) {
        }
        return codeOf(controller.getBlockPos());
    }

    /** The computer nearest to a point, if one stands within that many blocks. */
    public static StabilizerControllerBlockEntity nearestController(Level level, Vec3 position, double radius) {
        if (level == null || position == null) {
            return null;
        }
        double best = radius * radius;
        StabilizerControllerBlockEntity nearest = null;
        for (StabilizerControllerBlockEntity controller : ControllerTracker.loadedControllers()) {
            try {
                if (controller == null || controller.isRemoved() || controller.getLevel() == null
                        || controller.getLevel().dimension() != level.dimension()) {
                    continue;
                }
                for (Vec3 candidate : new Vec3[] {
                        Vec3.atCenterOf(controller.getBlockPos()),
                        SubLevelPositions.worldCenter(level, controller.getBlockPos())}) {
                    if (candidate == null) {
                        continue;
                    }
                    double distance = candidate.distanceToSqr(position);
                    if (distance < best) {
                        best = distance;
                        nearest = controller;
                    }
                }
            } catch (RuntimeException | LinkageError ignored) {
            }
        }
        return nearest;
    }

    /** Spots by code. Server side only, and tiny: one entry per pod in use. */
    private static final Map<Integer, Spot> SPOTS = new HashMap<>();

    /** Ticks a spot survives without the pod refreshing it. */
    private static final long STALE_TICKS = 10L;

    /** Sampling step of the occlusion walk, in blocks. */
    private static final double SAMPLE_STEP = 0.5D;

    /** Only one pass a tick, and only on the ticks the collection runs. */
    private static long lastPass = Long.MIN_VALUE;

    private LaserSpots() {
    }

    /**
     * Collects what every pod in use is painting. Called from the server tick,
     * twice a tick at most, and only actually reads the pods every other tick:
     * a hand-aimed laser does not need more than 10 Hz.
     */
    public static void serverTick(long gameTime) {
        if (gameTime == lastPass || gameTime % 2L != 0L) {
            return;
        }
        lastPass = gameTime;
        Set<Integer> seen = new HashSet<>();
        for (StabilizerControllerBlockEntity controller : ControllerTracker.loadedControllers()) {
            try {
                if (controller == null || controller.isRemoved() || controller.getLevel() == null) {
                    continue;
                }
                if (!controller.isPodViewEnabled()) {
                    continue;
                }
                Vec3 point = controller.getPodGuidanceTarget();
                if (point == null) {
                    // The pack has no aim point: most often because what the pod is
                    // looking at stands outside the loaded region, where its own
                    // search stops. This mod walks the ray itself so a target out
                    // there can still be painted - and, through the spot's chunk
                    // ticket, be brought into the world for the round to find.
                    point = longRangeSpot(controller);
                }
                if (point == null) {
                    continue;
                }
                if (!finite(point)) {
                    continue;
                }
                int code = codeFor(controller);
                Vec3 camera = controller.getPodCameraPosition();
                PodCameraProbe.report(controller, camera);
                boolean blocked = camera != null && !LaserSeeker.lineOfSight(controller.getLevel(), camera, point);
                SPOTS.put(code, new Spot(code, point, gameTime, blocked,
                        controller.getLevel() instanceof ServerLevel spotLevel ? spotLevel : null,
                        podIsAirborne(controller)));
                seen.add(code);
                // The spot entity 面包学's rounds home on is dropped by
                // PodLaserPointer once a tick; here only the aim is recorded.
            } catch (RuntimeException | LinkageError ignored) {
                // A pod that cannot be read is simply not painting this pass.
            }
        }
        SPOTS.entrySet().removeIf(entry -> !seen.contains(entry.getKey())
                && gameTime - entry.getValue().stamp() > STALE_TICKS);
        // The spots themselves are one-tick entities: nothing has to be swept.
    }

    /**
     * The spot a round with this code may use: its own code when it has one, or
     * the nearest spot of any code when it has none (a round fired without a
     * bound computer listens to whichever pod is painting nearby).
     */
    public static Spot find(int code, Vec3 from, double maxRange) {
        if (from == null) {
            return null;
        }
        Spot best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Spot spot : SPOTS.values()) {
            if (code > 0 && spot.code() != code) {
                continue;
            }
            double distance = spot.position().distanceTo(from);
            if (distance > maxRange || distance >= bestDistance) {
                continue;
            }
            best = spot;
            bestDistance = distance;
        }
        return best;
    }

    /** The code a computer's pod paints under. Stable, and unique per position. */
    public static int codeOf(BlockPos pos) {
        if (pos == null) {
            return 0;
        }
        return (int) (Math.floorMod(pos.asLong(), 9999L) + 1L);
    }

    /**
     * The code of the computer nearest to a point, which is the platform a round
     * that has just been launched belongs to: a laser round homes on the nearest
     * spot, so it is told which pod is its own by being given that platform's
     * code. Answers 0 - "no platform" - when nothing is close enough.
     */
    public static int codeNear(Level level, Vec3 position, double radius) {
        if (level == null || position == null || radius <= 0.0D) {
            return 0;
        }
        double best = radius * radius;
        int code = 0;
        java.util.Set<Integer> codes = new java.util.HashSet<>();
        for (StabilizerControllerBlockEntity controller : ControllerTracker.loadedControllers()) {
            try {
                if (controller == null || controller.isRemoved() || controller.getLevel() == null) {
                    continue;
                }
                if (controller.getLevel().dimension() != level.dimension()) {
                    continue;
                }
                // A controller on a structure keeps its block position in that
                // structure's own space while the round that just left it is in
                // world space, and a round measured against the wrong one matches
                // nothing - which is how vestalihy rounds ended up with no code at
                // all. Both readings are offered and the nearer one wins.
                for (Vec3 candidate : new Vec3[] {
                        Vec3.atCenterOf(controller.getBlockPos()),
                        SubLevelPositions.worldCenter(level, controller.getBlockPos())}) {
                    if (candidate == null) {
                        continue;
                    }
                    double distance = candidate.distanceToSqr(position);
                    if (distance < best) {
                        best = distance;
                        code = codeFor(controller);
                    }
                    codes.add(codeFor(controller));
                }
            } catch (RuntimeException | LinkageError ignored) {
            }
        }
        if (codes.size() > 1) {
            // Two computers within reach means the round cannot be said to belong to
            // either: 0 is "any pod", which every pod serves, and that is far better
            // than picking one and having the pod of the other refuse to light.
            return 0;
        }
        return code;
    }

    /**
     * The code of the computer that holds this rack, so a round fired off a rack
     * listens to the pod its own crew is looking through. Answers 0 - "any pod" -
     * when the rack is not bound, which keeps an unbound rack usable.
     */
    public static int codeForRack(Level level, BlockPos rackPos) {
        if (level == null || rackPos == null) {
            return 0;
        }
        for (StabilizerControllerBlockEntity controller : ControllerTracker.loadedControllers()) {
            try {
                if (controller == null || controller.isRemoved() || controller.getLevel() == null) {
                    continue;
                }
                for (BindingRef bound : controller.getAircraftMissileRacks()) {
                    if (bound == null) {
                        continue;
                    }
                    if (bound.pos().equals(rackPos) || bound.matches(level, rackPos)) {
                        return codeOf(controller.getBlockPos());
                    }
                }
            } catch (RuntimeException | LinkageError ignored) {
            }
        }
        return 0;
    }

    /** One line naming the spots in the air, for the guidance diagnostics. */
    public static String describe() {
        if (SPOTS.isEmpty()) {
            return "-";
        }
        StringBuilder text = new StringBuilder();
        for (Spot spot : SPOTS.values()) {
            if (text.length() > 0) {
                text.append(',');
            }
            text.append(spot.code()).append(spot.blocked() ? "(blocked)" : "")
                    .append(spot.airborne() ? "(air)" : "(ground)")
                    .append('@').append(spot.position().x).append('/')
                    .append(spot.position().y).append('/').append(spot.position().z);
        }
        return text.toString();
    }

    /** How far this mod will paint when the pack itself has no aim point. */
    private static final double LONG_SPOT_RANGE = 1600.0D;

    /** Step of that walk, in blocks. */
    private static final double LONG_SPOT_STEP = 1.0D;

    /**
     * A spot computed by this mod along the pod's own ray, for the case where the
     * pack has no aim point at all.
     *
     * <p>The pod's aim point stops at what the pack can find, and what it can find
     * stops at the loaded region - so a target beyond it is not painted, and the
     * laser rounds that should fly at it have nothing to fly at. This walks the
     * ray from the pod's camera along the direction it is looking, treats chunks
     * that are not loaded as open air rather than as a wall, and stops at the
     * first block that would stop light. A spot placed there is out of the loaded
     * region on purpose: the ticket the spot carries is what loads it.</p>
     */
    private static Vec3 longRangeSpot(StabilizerControllerBlockEntity controller) {
        try {
            Level level = controller.getLevel();
            Vec3 camera = controller.getPodCameraPosition();
            Vec3 look = controller.getPodDesiredWorldDirection();
            if (level == null || camera == null || look == null || look.lengthSqr() < 1.0E-8D) {
                return null;
            }
            Vec3 direction = look.normalize();
            for (double travelled = 4.0D; travelled <= LONG_SPOT_RANGE; travelled += LONG_SPOT_STEP) {
                Vec3 sample = camera.add(direction.scale(travelled));
                BlockPos pos = BlockPos.containing(sample);
                if (!level.isLoaded(pos)) {
                    continue;
                }
                var state = level.getBlockState(pos);
                if (!state.isAir() && !state.getCollisionShape(level, pos).isEmpty()) {
                    return sample;
                }
            }
        } catch (RuntimeException | LinkageError ignored) {
        }
        return null;
    }

    /** The spots standing right now, one per designating pod. */
    public static java.util.Collection<Spot> current() {
        return java.util.List.copyOf(SPOTS.values());
    }

    /** Whether anything is being painted right now. */
    public static boolean anyPainting() {
        return !SPOTS.isEmpty();
    }

    /**
     * Whether a spot is being painted, and can be seen: a spot that is blocked
     * by terrain or smoke counts as not painted, because a round released for it
     * would have nothing to fly to.
     */
    public static boolean painting(int code) {
        for (Spot spot : SPOTS.values()) {
            if (spot.blocked()) {
                continue;
            }
            if (code <= 0 || spot.code() == code) {
                return true;
            }
        }
        return false;
    }

    /** Whether the pod of the computer at that position is painting a visible spot. */
    public static boolean paintingFrom(BlockPos controllerPos) {
        return painting(codeOf(controllerPos));
    }

    private static boolean finite(Vec3 vector) {
        return vector != null
                && Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }
}

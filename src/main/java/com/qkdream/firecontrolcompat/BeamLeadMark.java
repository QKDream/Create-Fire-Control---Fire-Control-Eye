package com.qkdream.firecontrolcompat;

import com.hooya.stabilizedturret.content.controller.ControllerTracker;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.qkdream.firecontrolcompat.entity.BeamRidingMissileEntity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

/**
 * Lead solution handed to the beam-riding round a maid fire control operator
 * releases: instead of riding the designator point at the target itself, the
 * round keeps steering onto the point the target and the round meet at, so a
 * crossing target is still hit.
 *
 * <p>The solution is armed right before the launcher fires and is attached to
 * the next beam round that leaves a launcher, which keeps the plumbing inside
 * the firing tick. Rounds without the mark keep their normal laser riding
 * behaviour, so nothing changes for a player at the trigger.</p>
 */
public final class BeamLeadMark {

    /** Target UUID of the contact the round has to be led onto. */
    public static final String TARGET_KEY = "FireControlEyeLeadTarget";

    /** Fire control computer that owns the contact, packed as a block position. */
    public static final String CONTROLLER_KEY = "FireControlEyeLeadController";

    /** Longest flight time the interception solution looks ahead, in ticks. */
    private static final double MAX_FLIGHT_TICKS = 400.0;

    /** Most ticks the radar hand over position may be carried forward. */
    private static final double MAX_RADAR_LAG_TICKS = 3.0;

    /** Weight of the newest radar reading in the lead velocity, 0..1. */
    private static final double LEAD_VELOCITY_SMOOTHING = 0.5;

    /** Disagreement between two readings, in blocks a tick, that starts the filter over. */
    private static final double LEAD_VELOCITY_RESET = 2.0;

    /** The same disagreement as a share of the newer reading. */
    private static final double LEAD_VELOCITY_RESET_FRACTION = 0.5;

    /** Lead velocity carried on the round, so the filter state survives the call. */
    private static final String VELOCITY_X_KEY = "FireControlEyeLeadVelocityX";
    private static final String VELOCITY_Y_KEY = "FireControlEyeLeadVelocityY";
    private static final String VELOCITY_Z_KEY = "FireControlEyeLeadVelocityZ";
    private static final String VELOCITY_TICK_KEY = "FireControlEyeLeadVelocityTick";

    /** Guidance top speed of a plain beam round, in blocks per tick. */
    public static final double NOMINAL_TOP_SPEED = 34.0;

    /**
     * Range beyond which a straight-flying contact is chased instead of led, in blocks.
     *
     * <p>A lead solution multiplies the target's velocity by a whole flight time, so at long range
     * the meeting point sits far down the target's own track. That is right for something crossing
     * the line and wrong for something already flying straight at or away from the mount: there the
     * meeting point and the target itself are the same shot, and the prediction only adds the
     * track's own noise. Inside this range a crossing contact still gets the lead, because the
     * round has no time to catch up otherwise.</p>
     */
    public static final double TAIL_RANGE_BLOCKS = 60.0D;

    /**
     * Line-of-sight crossing angle, in degrees, under which a contact counts as flying straight
     * along the sight line rather than across it.
     */
    public static final double STRAIGHT_LOS_DEGREES = 30.0D;

    /** Which of the two guidance methods a release is flown with. */
    public enum Mode {
        /** Steer at where the contact is now: a long, straight, closing or opening track. */
        TAIL,
        /** Steer at the meeting point solved from the relative motion. */
        LEAD
    }

    /** The mode's name as it appears in the log line. */
    public static String modeName(Mode mode) {
        return mode == Mode.TAIL ? "tail" : "lead";
    }

    /**
     * Tail chase or lead pursuit for one contact, from where the crew is looking at it.
     *
     * <p>The rule is the user's: a contact <b>further than {@value #TAIL_RANGE_BLOCKS} blocks</b>
     * that is <b>flying along the sight line</b> - straight at the mount or straight away from it,
     * within {@value #STRAIGHT_LOS_DEGREES} degrees - is chased at its current position; anything
     * closer, or anything crossing the sight line, is led onto its meeting point. The angle is the
     * acute angle between the contact's own velocity and the sight line, so a head-on track and a
     * receding one read the same, which is what "直飞向/飞离" means.</p>
     *
     * @param reference where the crew measures from: the mount, or the round already in the air
     * @param target where the contact is now
     * @param targetVelocity how the contact is moving, in blocks per tick
     */
    public static Mode modeFor(Vec3 reference, Vec3 target, Vec3 targetVelocity) {
        if (reference == null || target == null) {
            return Mode.LEAD;
        }
        Vec3 offset = target.subtract(reference);
        double distance = offset.length();
        if (distance <= TAIL_RANGE_BLOCKS) {
            return Mode.LEAD;
        }
        if (targetVelocity == null || !finite(targetVelocity) || targetVelocity.lengthSqr() < 1.0E-8) {
            // A stationary contact has no track to lead and none to chase: the current position is
            // the whole answer either way, and TAIL says so without pretending to solve anything.
            return Mode.TAIL;
        }
        double cosine = Math.abs(offset.normalize().dot(targetVelocity.normalize()));
        double degrees = Math.toDegrees(Math.acos(Math.max(-1.0D, Math.min(1.0D, cosine))));
        return degrees < STRAIGHT_LOS_DEGREES ? Mode.TAIL : Mode.LEAD;
    }

    /** Acute angle between a contact's track and the sight line, in degrees. */
    public static double losAngleDegrees(Vec3 reference, Vec3 target, Vec3 targetVelocity) {
        if (reference == null || target == null
                || targetVelocity == null || !finite(targetVelocity)
                || targetVelocity.lengthSqr() < 1.0E-8) {
            return Double.NaN;
        }
        Vec3 offset = target.subtract(reference);
        if (offset.lengthSqr() < 1.0E-8) {
            return Double.NaN;
        }
        double cosine = Math.abs(offset.normalize().dot(targetVelocity.normalize()));
        return Math.toDegrees(Math.acos(Math.max(-1.0D, Math.min(1.0D, cosine))));
    }

    /** Store for the guidance mode of the rounds in the air; see {@link #reportMode}. */
    public static final String MODE_KEY = "FireControlEyeGuidanceMode";

    /** Tick the mode of this round was last reported on. */
    private static final String MODE_LOGGED_TICK_KEY = "FireControlEyeGuidanceLoggedTick";

    /** Quietest gap between two mode lines of one round, in ticks. */
    private static final int MODE_LOG_GAP_TICKS = 20;

    /**
     * Records the method this round is being flown with, and reports a change immediately.
     *
     * <p>Printed on a change and otherwise once a second, so a long engagement produces a readable
     * trace of which method each round used instead of one line per tick per round. The tick stamp
     * lives on the round rather than in a static map, so a round that is gone takes its state with
     * it.</p>
     */
    public static void reportMode(BeamRidingMissileEntity missile, Mode mode, double distance,
            double angle, UUID target) {
        if (missile == null || mode == null) {
            return;
        }
        try {
            CompoundTag data = missile.getPersistentData();
            String name = modeName(mode);
            boolean changed = !name.equals(data.getString(MODE_KEY));
            data.putString(MODE_KEY, name);
            int tick = missile.tickCount;
            boolean due = !data.contains(MODE_LOGGED_TICK_KEY)
                    || tick - data.getInt(MODE_LOGGED_TICK_KEY) >= MODE_LOG_GAP_TICKS;
            if (!changed && !due) {
                return;
            }
            data.putInt(MODE_LOGGED_TICK_KEY, tick);
            FireControlCompat.LOGGER.info(
                    "maid guidance: mode={} dist={} angle={} target={} round={}",
                    name, String.format(java.util.Locale.ROOT, "%.1f", distance),
                    Double.isFinite(angle) ? String.format(java.util.Locale.ROOT, "%.1f", angle)
                            : "n/a",
                    target, missile.getId());
        } catch (Throwable ignored) {
            // a round whose data cannot be written simply does not report
        }
    }

    /** Rounds flying a lead solution, so the guidance pass can find them again. */
    private static final Map<BeamRidingMissileEntity, Boolean> MARKED =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static UUID pendingTarget;
    private static long pendingController = Long.MIN_VALUE;

    private BeamLeadMark() {
    }

    /**
     * Every round still flying on a lead solution. Weak, so a round that is gone
     * stops being reported without anything having to unregister it, and the
     * list is filtered again on the way out because an entity can be removed
     * between the plain tick that released it and the pass that steers it.
     */
    public static List<BeamRidingMissileEntity> markedRounds() {
        List<BeamRidingMissileEntity> live = new ArrayList<>();
        for (BeamRidingMissileEntity missile : MARKED.keySet()) {
            if (missile != null && !missile.isRemoved() && missile.isAlive()) {
                live.add(missile);
            }
        }
        return live;
    }

    /** Arms the solution the next beam round has to fly. */
    public static synchronized void arm(BlockPos controllerPos, UUID targetId) {
        if (controllerPos == null || targetId == null) {
            clear();
            return;
        }
        pendingController = controllerPos.asLong();
        pendingTarget = targetId;
    }

    public static synchronized void clear() {
        pendingTarget = null;
        pendingController = Long.MIN_VALUE;
    }

    /**
     * Attaches an armed solution to a freshly built round, consuming it either
     * way. The round is also entered in {@link #markedRounds()}, because one that
     * carries a solution has to be flown by that solution even after fire control
     * adopts it: the adoption hands the round to fire controls own beam rider,
     * which flies it down the bearing the mount happens to be on, so a mount that
     * is still slewing - or that is on a cannon solution worked out for a shell
     * rather than for the round - leaves a Mach speed contact behind. Flying onto
     * the meeting point is the whole point of the mark.
     */
    public static synchronized void applyTo(BeamRidingMissileEntity missile) {
        if (missile == null || pendingTarget == null || pendingController == Long.MIN_VALUE) {
            return;
        }
        CompoundTag data = missile.getPersistentData();
        data.putUUID(TARGET_KEY, pendingTarget);
        data.putLong(CONTROLLER_KEY, pendingController);
        MARKED.put(missile, Boolean.TRUE);
        clear();
    }

    /**
     * Interception point for a marked round, or null when the round carries no
     * mark or the contact has left the radar since it was released.
     */
    public static Vec3 leadPoint(BeamRidingMissileEntity missile) {
        if (missile == null) {
            return null;
        }
        CompoundTag data = missile.getPersistentData();
        if (!data.hasUUID(TARGET_KEY) || !data.contains(CONTROLLER_KEY)) {
            return null;
        }
        BlockPos controllerPos = BlockPos.of(data.getLong(CONTROLLER_KEY));
        StabilizerControllerBlockEntity controller =
                ControllerTracker.findLoadedForMissile(missile, controllerPos, null, false);
        if (controller == null) {
            return null;
        }
        StabilizerControllerBlockEntity.RadarContact contact =
                controller.getRadarContact(data.getUUID(TARGET_KEY));
        if (contact == null) {
            return null;
        }
        Vec3 origin = worldPosition(missile);
        Vec3 target = contact.center();
        Vec3 targetVelocity = contact.velocity();
        if (target == null || origin == null) {
            return null;
        }
        if (targetVelocity == null || !finite(targetVelocity)) {
            targetVelocity = Vec3.ZERO;
        }
        targetVelocity = smoothedVelocity(missile, targetVelocity);
        target = liveCenter(controller, target, targetVelocity);
        // Which method this round flies: a long straight track is chased, a crossing or close one
        // is led onto its meeting point (see {@link #modeFor}). The reference is the mount the
        // round came from when it can be measured, so "how far" stays the crew's range rather than
        // shrinking as the round closes.
        Vec3 reference = mountReference(controller, origin);
        Mode mode = modeFor(reference, target, targetVelocity);
        double distance = target.distanceTo(reference);
        UUID targetId = data.getUUID(TARGET_KEY);
        reportMode(missile, mode, distance, losAngleDegrees(reference, target, targetVelocity),
                targetId);
        if (mode == Mode.TAIL) {
            // No prediction: the contact itself is the shot.
            return usableAim(missile, toMissileSpace(missile, target),
                    toMissileSpace(missile, target));
        }
        Vec3 point = missile.interceptionPoint(origin, target, targetVelocity, MAX_FLIGHT_TICKS);
        return usableAim(missile, toMissileSpace(missile, point == null ? target : point),
                toMissileSpace(missile, target));
    }

    /** Where the crew measures from: the mount's aerial, or the round itself as a fallback. */
    private static Vec3 mountReference(StabilizerControllerBlockEntity controller, Vec3 fallback) {
        try {
            Vec3 origin = controller == null ? null : controller.getRadarOrigin();
            if (origin != null && finite(origin)) {
                return origin;
            }
        } catch (Throwable ignored) {
            // a mount that cannot measure its own aerial still has the round to measure from
        }
        return fallback;
    }

    /**
     * The aim point handed to the round, unless flying it would mean turning the
     * round around.
     *
     * <p>A round that has just crossed its meeting point has that point behind
     * its nose, and so does the contact it was led onto. Steering at a point the
     * round is flying away from is what turns a near miss into a round that
     * loops around the contact until it runs out of fuel. A point behind the
     * nose is therefore dropped: the contact centre gets the shot while that is
     * still ahead, and a round with nothing ahead of it at all keeps the course
     * it already has.</p>
     */
    private static Vec3 usableAim(BeamRidingMissileEntity missile, Vec3 meeting, Vec3 contact) {
        Vec3 velocity = missile.getDeltaMovement();
        if (velocity.lengthSqr() < 1.0E-8) {
            return meeting;
        }
        if (aheadOfNose(missile, meeting, velocity)) {
            return meeting;
        }
        return aheadOfNose(missile, contact, velocity) ? contact : null;
    }

    private static boolean aheadOfNose(BeamRidingMissileEntity missile, Vec3 point, Vec3 velocity) {
        return point != null && point.subtract(missile.position()).dot(velocity) > 0.0;
    }

    /**
     * The contact position carried forward to the current tick. The radar list
     * is rebuilt every other tick, so the point handed over here can be that
     * old, and a contact closing at Mach speed covers tens of blocks in the
     * meantime. Solving the meeting point from the stale position places it a
     * tick behind the target and the round reaches it just after the target has
     * gone; carrying it along its own velocity first is what keeps a fast
     * contact inside the fuze.
     */
    private static Vec3 liveCenter(
            StabilizerControllerBlockEntity controller, Vec3 target, Vec3 targetVelocity) {
        if (target == null || targetVelocity == null || targetVelocity.lengthSqr() < 1.0E-8) {
            return target;
        }
        Level level = controller.getLevel();
        long scannedAt = RadarEmitters.scanTick(controller);
        if (level == null || scannedAt == Long.MIN_VALUE) {
            return target;
        }
        double lag = Math.min(MAX_RADAR_LAG_TICKS, Math.max(0.0, (double) (level.getGameTime() - scannedAt)));
        return lag <= 0.0 ? target : target.add(targetVelocity.scale(lag));
    }

    /**
     * Where the round sits in world space. A round released from a launcher that
     * rides a structure lives in that structures own space, while the radar
     * measures its contacts in world space; solving the two against each other is
     * what sent a crew launched round off to a point nobody was flying to.
     */
    private static Vec3 worldPosition(BeamRidingMissileEntity missile) {
        Vec3 position = missile.position();
        if (position == null) {
            return null;
        }
        try {
            Vec3 world = Sable.HELPER.projectOutOfSubLevel(missile.level(), position);
            return world != null && finite(world) ? world : position;
        } catch (Throwable ignored) {
            return position;
        }
    }

    /** World point expressed in the rounds own space, which is the space its steering works in. */
    private static Vec3 toMissileSpace(BeamRidingMissileEntity missile, Vec3 world) {
        if (world == null) {
            return null;
        }
        try {
            SubLevel subLevel = Sable.HELPER.getContaining(missile);
            if (subLevel == null) {
                return world;
            }
            Vector3d local = new Vector3d(world.x, world.y, world.z);
            subLevel.logicalPose().transformPositionInverse(local);
            if (Double.isFinite(local.x) && Double.isFinite(local.y) && Double.isFinite(local.z)) {
                return new Vec3(local.x, local.y, local.z);
            }
        } catch (Throwable ignored) {
        }
        return world;
    }

    /**
     * Range from the round to the contact it was released against, in blocks,
     * or NaN when the round carries no mark or the contact has left the radar.
     * Only used to report how close an engagement actually came, so a miss can
     * be told apart from a fuze that never fired.
     */
    public static double rangeToTarget(BeamRidingMissileEntity missile) {
        if (missile == null) {
            return Double.NaN;
        }
        try {
            CompoundTag data = missile.getPersistentData();
            if (!data.hasUUID(TARGET_KEY) || !data.contains(CONTROLLER_KEY)) {
                return Double.NaN;
            }
            StabilizerControllerBlockEntity controller =
                    ControllerTracker.findLoadedForMissile(missile, BlockPos.of(data.getLong(CONTROLLER_KEY)), null, false);
            StabilizerControllerBlockEntity.RadarContact contact =
                    controller == null ? null : controller.getRadarContact(data.getUUID(TARGET_KEY));
            Vec3 origin = worldPosition(missile);
            if (contact == null || contact.center() == null || origin == null) {
                return Double.NaN;
            }
            return origin.distanceTo(contact.center());
        } catch (Throwable ignored) {
            return Double.NaN;
        }
    }

    /**
     * Length of the contact velocity the lead solution is currently built on, in
     * blocks per tick, or NaN when nothing is stored. Reported by the flight log
     * so a contact whose radar track jumps - which throws the lead point far past
     * the contact and sends the round after a point nobody is flying to - can be
     * told apart from a round that simply lost its track.
     */
    public static double trackedVelocity(BeamRidingMissileEntity missile) {
        if (missile == null) {
            return Double.NaN;
        }
        CompoundTag data = missile.getPersistentData();
        if (!data.contains(VELOCITY_X_KEY)) {
            return Double.NaN;
        }
        return new Vec3(data.getDouble(VELOCITY_X_KEY), data.getDouble(VELOCITY_Y_KEY),
                data.getDouble(VELOCITY_Z_KEY)).length();
    }

    /**
     * Meeting point a beam round released from {@code origin} right now would fly
     * to, or null when the geometry has none. A bound launcher is pointed at this
     * so the round leaves the tube on the collision course instead of spending
     * the first seconds of its flight turning onto it - which is exactly what a
     * contact crossing the sight line does not leave time for.
     *
     * <p>The launcher has no speed ramp to measure, so the point is solved in
     * closed form: with a constant launch speed {@code s} the round meets the
     * contact where {@code |T + Vt - P| = st}, a quadratic in the flight time.
     * The old solver walked that equation by fixed point instead, one pass at a
     * time, and the walk only converges while the contact is slower than the
     * round - the factor each pass is scaled by is the contact speed over the
     * round speed. A contact closing as fast as the round flies leaves a factor
     * above one, so every pass walked the point further along the track and the
     * launcher was aimed at whatever the last pass happened to leave, which on a
     * head on merge is a point behind the round's own nose.</p>
     */
    public static Vec3 launchInterceptPoint(Vec3 origin, Vec3 target, Vec3 targetVelocity, double topSpeed) {
        if (origin == null || target == null) {
            return null;
        }
        Vec3 velocity = targetVelocity == null || !finite(targetVelocity) ? Vec3.ZERO : targetVelocity;
        double speed = topSpeed > 0.0 ? topSpeed : NOMINAL_TOP_SPEED;
        Vec3 offset = target.subtract(origin);
        double quadratic = velocity.lengthSqr() - speed * speed;
        double linear = 2.0 * offset.dot(velocity);
        double constant = offset.lengthSqr();
        double ticks;
        if (Math.abs(quadratic) < 1.0E-6) {
            if (Math.abs(linear) < 1.0E-6) {
                return null;
            }
            ticks = -constant / linear;
        } else {
            double discriminant = linear * linear - 4.0 * quadratic * constant;
            if (discriminant < 0.0) {
                return null;
            }
            double root = Math.sqrt(discriminant);
            double first = (-linear - root) / (2.0 * quadratic);
            double second = (-linear + root) / (2.0 * quadratic);
            ticks = first > 0.0 && (second <= 0.0 || first < second) ? first : second;
        }
        if (!Double.isFinite(ticks) || ticks < 0.0 || ticks > MAX_FLIGHT_TICKS) {
            return null;
        }
        return target.add(velocity.scale(ticks));
    }

    /**
     * The radar velocity the lead is built on, low pass filtered on the round
     * itself so the state survives between the calls the lead solution makes.
     * A two tick difference of the contact centre carries the sampling noise of
     * both samples, and the lead multiplies that noise by the whole flight
     * time; half weight on the newest reading still follows a real manoeuvre
     * within a couple of ticks, which is what a settling launcher needs.
     *
     * <p>The pass only ever damps readings of one track. A reading that
     * disagrees with the carried one by more than a couple of blocks a tick is
     * not that track measured again - it is a contact the radar has just begun
     * reporting at its true speed, a rebuilt track, or a round that has been
     * handed a speed its own flight never flies - and blending it in would
     * keep the lead on the old speed for a dozen ticks, which is long enough
     * to carry a slow contact a hundred blocks past the meeting point. Such a
     * reading is taken as it stands, and the filter starts again from it.</p>
     */
    private static Vec3 smoothedVelocity(BeamRidingMissileEntity missile, Vec3 raw) {
        CompoundTag data = missile.getPersistentData();
        int tick = missile.tickCount;
        if (data.contains(VELOCITY_TICK_KEY) && data.getInt(VELOCITY_TICK_KEY) == tick
                && data.contains(VELOCITY_X_KEY)) {
            return new Vec3(data.getDouble(VELOCITY_X_KEY), data.getDouble(VELOCITY_Y_KEY),
                    data.getDouble(VELOCITY_Z_KEY));
        }
        Vec3 value = raw;
        if (data.contains(VELOCITY_X_KEY)) {
            Vec3 previous = new Vec3(data.getDouble(VELOCITY_X_KEY), data.getDouble(VELOCITY_Y_KEY),
                    data.getDouble(VELOCITY_Z_KEY));
            value = previous.subtract(raw).length()
                    > Math.max(LEAD_VELOCITY_RESET, raw.length() * LEAD_VELOCITY_RESET_FRACTION)
                            ? raw
                            : previous.scale(1.0 - LEAD_VELOCITY_SMOOTHING).add(raw.scale(LEAD_VELOCITY_SMOOTHING));
        }
        if (finite(value)) {
            data.putDouble(VELOCITY_X_KEY, value.x);
            data.putDouble(VELOCITY_Y_KEY, value.y);
            data.putDouble(VELOCITY_Z_KEY, value.z);
            data.putInt(VELOCITY_TICK_KEY, tick);
        }
        return value;
    }

    private static boolean finite(Vec3 vector) {
        return Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }
}

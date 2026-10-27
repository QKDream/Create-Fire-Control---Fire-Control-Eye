package com.qkdream.firecontrolcompat.compat.maid;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.hooya.stabilizedturret.compat.mianbao.MountedHeavyMachineGunCompat;
import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.content.controller.CannonBallisticsCompat;
import com.hooya.stabilizedturret.content.controller.BindingSlot;
import com.hooya.stabilizedturret.content.controller.CannonMountCompat;
import com.hooya.stabilizedturret.content.controller.MianbaoAirDefenseCompat;
import com.hooya.stabilizedturret.content.controller.MianbaoMissileCompat;
import com.hooya.stabilizedturret.content.controller.MianbaoRemoteWeaponStationCompat;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.qkdream.firecontrolcompat.BeamLeadMark;
import com.qkdream.firecontrolcompat.BeamMissileCompat;
import com.qkdream.firecontrolcompat.FireControlCompat;
import com.qkdream.firecontrolcompat.IffRadar;
import com.qkdream.firecontrolcompat.IrSeekerSession;
import com.qkdream.firecontrolcompat.MaidFireControlAccess;
import com.qkdream.firecontrolcompat.SpyglassMarkStore;
import com.qkdream.firecontrolcompat.RadarEmitters;
import com.qkdream.firecontrolcompat.network.ContactTypePayload;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;

/**
 * Crew work of the fire control operator maid.
 *
 * <p>While she sits on the seat that was bound to a fire control computer she
 * takes over the gunner's job: every radar contact whose friend-or-foe answer
 * is not friendly is engaged, incoming ordnance is intercepted first, the
 * bound turret bearings are slewed onto the contact she picked and the bound
 * weapons are released, with the two step infrared release respected and the
 * beam-riding round led onto the interception point.</p>
 *
 * <p>She never touches a friendly contact, and a contact hidden from the radar
 * by the computer's own friend-or-foe and speed gate settings is left alone as
 * well, so the settings a player made on the computer keep their meaning.</p>
 */
final class MaidFireControlOperator {

    /** Seat search and walk retry interval. */
    private static final int SEAT_SCAN_INTERVAL_TICKS = 20;

    /**
     * Interval between two missile releases against the same contact: five
     * seconds. The clock follows the contact rather than the turret, so a tube
     * that has just spent a round on one target is free to take the next one
     * straight away - which is what makes a fresh threat or a second target
     * worth switching to.
     */
    private static final int MISSILE_INTERVAL_TICKS = 100;

    /**
     * How long the guns stay cold after a round left a launcher. Both weapons
     * sit on the same turret, so a shell fired while the missile is still
     * climbing away down the same line of sight is what detonates our own round.
     */
    private static final long GUN_HOLDOFF_TICKS = 60L;

    /** How long the guns have to be quiet before a missile may be released. */
    private static final long GUN_SILENCE_TICKS = 20L;

    /**
     * The same pause against incoming ordnance, cut to a token. A round closing
     * at Mach speed needs the tube now, and the shell that was fired a moment
     * ago is far enough down range after a fifth of a second.
     */
    private static final long ORDNANCE_GUN_SILENCE_TICKS = 4L;

    /** Interval between two infrared seeker trigger presses, in ticks. */
    private static final int TRIGGER_INTERVAL_TICKS = 5;

    /** How long a picked contact is held before the choice is revisited. */
    private static final long TARGET_HOLD_TICKS = 60L;

    /** Aim tolerance for a missile release, in degrees. */
    private static final double MISSILE_AIM_TOLERANCE_DEGREES = 35.0;

    /**
     * Aim tolerance for a missile release against incoming ordnance. A beam
     * round steers itself onto the interception point, so a tube that is still
     * slewing is worth firing: waiting for the mount to settle is what let Mach
     * speed rounds pass while the launcher was swinging onto them.
     */
    private static final double ORDNANCE_AIM_TOLERANCE_DEGREES = 60.0;

    /**
     * Aim tolerance for the gun trigger, in degrees. The barrel is all the
     * trigger knows about, so every degree it sits off the contact is a degree
     * of miss: at half a kilometre a shot released six degrees wide lands fifty
     * metres from the target, which is why the tube has to be properly on the
     * contact before it opens up rather than firing on the swing.
     */
    private static final double GUN_AIM_TOLERANCE_DEGREES = 2.5;

    /**
     * Beyond this the gun holds its fire against incoming ordnance. The shells
     * of a bound cannon cannot reach further, so rounds fired at longer range
     * only fall short and burn the magazine; that work belongs to the missiles.
     */
    private static final double GUN_MAX_RANGE = 600.0;

    /**
     * The same ceiling against a structure. A ship or a building does not have
     * to be caught, it has to be broken, and a missile does that from any
     * distance; the gun is only worth the magazine once the target is close
     * enough for the shells to arrive together.
     */
    private static final double GUN_MAX_RANGE_STRUCTURE = 50.0;

    /**
     * Closing rate, in blocks per second, that still counts as the contact
     * standing still. Far below the speed of anything that moves, so noise in
     * the reading never turns a parked target into a fleeing one.
     */
    private static final double CLOSING_MARGIN = 0.5;

    /** Fastest travel of the mount itself the measurement accepts, in blocks per second. */
    private static final double PLATFORM_SPEED_LIMIT = 200.0;

    /** Angular error the aim request ignores, so a settled turret stays settled. */
    private static final double AIM_DEADBAND_DEGREES = 0.15;

    /**
     * Share of the way to a fresh aim request the mount is moved on each tick.
     *
     * <p>The ask is filtered rather than followed outright. A contact far out is
     * aimed at through a lead that multiplies whatever noise the radar has in
     * the target's speed - a two tick finite difference - by the whole flight
     * time of the round, so the ask itself trembles by a fraction of a degree
     * from tick to tick. Handed straight to the bearings that is a mount that
     * buzzes on a target it is already pointed at, which is what the crew sees
     * as the elevation and traverse drivers shaking against each other. A large
     * correction is still made at full speed, because the blend is only what the
     * step limit is measured against.</p>
     */
    private static final double AIM_FILTER = 0.25;

    /**
     * Ceiling on how far the aim request may swing in one tick. A target crossing
     * the sight line faster than this is followed at the limit instead of making
     * the turret shake between two wildly different directions every tick.
     */
    private static final double AIM_MAX_STEP_DEGREES = 9.0;

    /**
     * Ceiling used while the contact is incoming ordnance. Losing the sight line
     * on a round that crosses at Mach speed costs the whole engagement, so the
     * request is allowed to swing further than the smooth tracking ceiling.
     */
    private static final double ORDNANCE_AIM_STEP_DEGREES = 15.0;

    /** Distance the aim point sits at, so the computer only sees its direction. */
    private static final double AIM_POINT_DISTANCE = 512.0;

    /**
     * Angular speed of the idle traverse, in degrees per tick (0.6 is 12 deg/s).
     * Slow enough to read as a crew scanning its arc, and slow enough that no
     * bearing driver mistakes it for a tracking command.
     */
    private static final double IDLE_SWEEP_RATE_DEGREES = 0.6;

    /** How far the idle traverse swings each way from where it started, in degrees. */
    private static final double IDLE_SWEEP_LIMIT_DEGREES = 35.0;

    /** Rate limit of the idle aim request, in degrees per tick. */
    private static final double IDLE_AIM_STEP_DEGREES = 3.0;

    /**
     * Most ticks the radar hand over position may be carried forward. The sweep
     * runs every other tick, so this only guards against a scan that was missed
     * altogether.
     */
    private static final double RADAR_LAG_TICKS = 3.0;

    /**
     * Angular error below which the aim counts as settled. The stall watch only
     * ever looks at a turret that is genuinely off the contact, so a settled
     * turret is never mistaken for a stuck one. Deliberately looser than the
     * trigger: the tolerance that decides whether a shot is worth taking is not
     * the tolerance that decides whether the contact can be held at all.
     */
    private static final double STALL_ERROR_DEGREES = 6.0;

    /**
     * Stall error used for incoming ordnance. A round crossing at Mach speed
     * never lets the barrel close on it, so the ordinary window would drop the
     * fastest threats as unreachable; ordnance is only written off when the
     * mount is nowhere near the sight line at all.
     */
    private static final double ORDNANCE_STALL_ERROR_DEGREES = 90.0;

    /** Closing of the aim error across one watch window that counts as progress. */
    private static final double STALL_PROGRESS_DEGREES = 0.5;

    /**
     * How long the aim may fail to close on a contact before that contact counts
     * as out of reach. Two seconds is long enough for the slowest slew a player
     * can configure to show movement and short enough that a dead corner of the
     * mount does not soak up the magazine.
     */
    private static final long STALL_TICKS = 40L;

    /** How long a contact dropped as out of reach is left alone before it is tried again. */
    private static final long UNREACHABLE_HOLD_TICKS = 100L;

    /**
     * The same, for ordnance. A round that is already in the air is the one
     * contact that cannot be written off for long: a top attack spends its whole
     * dive inside that window, so five seconds of being skipped is the entire
     * attack. Incoming ordnance is therefore retried once a second.
     */
    private static final long ORDNANCE_UNREACHABLE_HOLD_TICKS = 20L;

    /** Walking speed toward the seat. */
    private static final float WALK_SPEED = 0.6F;

    private static final Map<UUID, State> STATES = new ConcurrentHashMap<>();

    private MaidFireControlOperator() {
    }

    /** Per maid bookkeeping so a post keeps its target and its reload clock. */
    static final class State {
        UUID targetId;
        Vec3 commandedDirection;
        long gunsColdUntilTick = Long.MIN_VALUE;
        long lastGunFireTick = Long.MIN_VALUE;
        long targetUntil = Long.MIN_VALUE;
        /** Per contact reload clock: when each one may take another round. */
        final Map<UUID, Long> volleyReadyTick = new HashMap<>();
        long nextTriggerTick = Long.MIN_VALUE;
        long nextSeatScanTick = Long.MIN_VALUE;
        /** Contacts the bearings could not be brought onto, each until a game tick. */
        final Map<UUID, Long> unreachableUntil = new HashMap<>();
        /** Contact the aim watch runs for, the error the window started at and when. */
        UUID watchTargetId;
        double watchError = Double.MAX_VALUE;
        long watchSinceTick = Long.MIN_VALUE;
        /** Structures her own sweep found, used while the computer has no radar. */
        List<StabilizerControllerBlockEntity.RadarContact> localContacts = List.of();
        /** Centre of each of those on the last sweep, so its speed can be measured. */
        final Map<UUID, Vec3> localCenters = new HashMap<>();
        /** Tick of the last sweep of her own, {@code Long.MIN_VALUE} when she has not run one. */
        long localScanTick = Long.MIN_VALUE;
        /** Tick the idle traverse started on, {@code Long.MIN_VALUE} while she is working. */
        long idleSinceTick = Long.MIN_VALUE;
        /** Bearing the idle traverse swings around, captured when it starts. */
        Vec3 idleBaseDirection;
        /** True while a crew posted ahead of her holds the mount and she waits. */
        boolean standingBy;
        /** True for the manual operator, who only takes ordnance and marked structures. */
        boolean manual;
        /** Where the mount itself sat on the last tick, and on which tick that was. */
        Vec3 platformOrigin;
        long platformOriginTick = Long.MIN_VALUE;
        /** How fast the mount itself is travelling, in blocks per second. */
        Vec3 platformVelocity = Vec3.ZERO;
        /** Who she is, so a target called onto another operator is not hers to take. */
        UUID maidId;
    }

    static boolean isOperator(EntityMaid maid) {
        return isAutoOperator(maid) || isManualOperator(maid);
    }

    /** The operator that works whatever the scope finds. */
    static boolean isAutoOperator(EntityMaid maid) {
        return maid.getTask() != null && TaskFireControlOperator.UID.equals(maid.getTask().getUid());
    }

    /**
     * The operator that only intercepts ordnance, and structures a player
     * called her onto with a scoped spyglass.
     */
    static boolean isManualOperator(EntityMaid maid) {
        return maid.getTask() != null && TaskManualFireControlOperator.UID.equals(maid.getTask().getUid());
    }

    static void tick(ServerLevel level, EntityMaid maid, long gameTime) {
        long started = System.nanoTime();
        String phase = "operate";
        try {
            StabilizerControllerBlockEntity controller = MaidFireControlSeats.boundController(level, maid);
            State state = STATES.computeIfAbsent(maid.getUUID(), id -> new State());
            if (controller == null) {
                phase = "no-post";
                state.standingBy = false;
                state.targetId = null;
                state.commandedDirection = null;
                state.targetUntil = Long.MIN_VALUE;
                endIdle(state);
                walkToSeat(level, maid, state, gameTime);
                return;
            }
            if (!MaidFireControlSeats.holdsCommand(level, maid, controller)) {
                phase = "stand-down";
                standDown(level, controller, state);
                return;
            }
            state.standingBy = false;
            state.manual = isManualOperator(maid);
            state.maidId = maid.getUUID();
            operate(level, maid, controller, state, gameTime);
        } finally {
            firecontrolcompat$recordCost(level, gameTime, System.nanoTime() - started, phase);
        }
    }

    // -------------------------------------------------------------------------------------------
    // Per-tick cost readout
    // -------------------------------------------------------------------------------------------

    /** Quietest gap between two maid cost lines, in ticks: a hundred ticks is five seconds. */
    private static final long COST_WINDOW_TICKS = 100L;

    private static long costWindowAt;
    private static long costWindowNanos;
    private static long costWindowCalls;
    private static long costWindowPeakNanos;
    private static String costWindowWorstPhase = "-";

    /**
     * Reports what the maid's own fire control work costs per tick.
     *
     * <p>"Air defense with a maid lags badly" is a report about this method's callees - the radar
     * list, the friends-and-foes gate, the lead solvers and the launcher hand-off - so one number
     * per five seconds is the only way to tell whether the cost is here at all. The worst single
     * call of the window is reported next to the average because a stutter is a spike, not a
     * mean.</p>
     *
     * <p>The seed is {@code 0} and compared directly; a {@code Long.MIN_VALUE} seed subtracted
     * from game time wraps negative for every positive tick and would silence the line forever.</p>
     */
    private static void firecontrolcompat$recordCost(ServerLevel level, long gameTime, long nanos,
            String phase) {
        costWindowNanos += Math.max(0L, nanos);
        costWindowCalls++;
        if (nanos > costWindowPeakNanos) {
            costWindowPeakNanos = nanos;
            costWindowWorstPhase = phase;
        }
        if (gameTime > 0L ? (costWindowAt != 0L && gameTime - costWindowAt < COST_WINDOW_TICKS)
                : costWindowCalls < COST_WINDOW_TICKS) {
            return;
        }
        long calls = Math.max(1L, costWindowCalls);
        FireControlCompat.LOGGER.info(
                "maid fire control cost: avg_us={} peak_us={} phase={} calls={} maids={}",
                costWindowNanos / 1000L / calls, costWindowPeakNanos / 1000L, costWindowWorstPhase,
                calls, STATES.size());
        costWindowAt = gameTime;
        costWindowNanos = 0L;
        costWindowCalls = 0L;
        costWindowPeakNanos = 0L;
        costWindowWorstPhase = "-";
    }

    /**
     * Waits her turn: a crew posted ahead of her holds the mount, so she lets
     * go of the guns and of every target she was working, and picks the work
     * up again the moment that post is vacated.
     */
    private static void standDown(ServerLevel level, StabilizerControllerBlockEntity controller, State state) {
        state.targetId = null;
        state.commandedDirection = null;
        state.targetUntil = Long.MIN_VALUE;
        state.unreachableUntil.clear();
        endIdle(state);
        if (state.standingBy) {
            return;
        }
        state.standingBy = true;
        releaseWeapons(level, controller);
    }

    static void release(ServerLevel level, EntityMaid maid) {
        State state = STATES.remove(maid.getUUID());
        StabilizerControllerBlockEntity controller = MaidFireControlSeats.boundController(level, maid);
        if (controller == null) {
            return;
        }
        releaseWeapons(level, controller);
        if (state != null) {
            state.targetId = null;
        }
    }

    private static void walkToSeat(ServerLevel level, EntityMaid maid, State state, long gameTime) {
        if (gameTime < state.nextSeatScanTick) {
            return;
        }
        state.nextSeatScanTick = gameTime + SEAT_SCAN_INTERVAL_TICKS;
        MaidFireControlSeats.SeatSpot spot = MaidFireControlSeats.nearestSeat(level, maid);
        if (spot == null) {
            return;
        }
        if (MaidFireControlSeats.inReach(level, maid, spot)) {
            maid.getNavigation().stop();
            MaidFireControlSeats.sit(maid, spot);
            return;
        }
        Vec3 center = spot.worldCenter();
        maid.getNavigation().moveTo(center.x, center.y, center.z, WALK_SPEED);
    }

    private static void operate(
            ServerLevel level,
            EntityMaid maid,
            StabilizerControllerBlockEntity controller,
            State state,
            long gameTime) {
        if (!(controller instanceof MaidFireControlAccess access)) {
            // The accessor mixin is what puts this interface onto the controller. With it
            // missing - the mixin master switch off, or the class never reached - the maid
            // does nothing this tick rather than dying on a cast and taking the server down.
            return;
        }
        samplePlatformVelocity(controller, state, gameTime);
        UUID target = chooseTarget(level, controller, state, gameTime);
        state.targetId = target;
        if (target == null) {
            // Nothing worth shooting at. The mount is still a manned post, so it
            // traverses instead of sitting parked on one bearing; the aim request
            // deliberately keeps its previous value so the swing is continuous.
            releaseWeapons(level, controller);
            access.firecontrolcompat$setRadarLockedSubLevelId(null);
            idleTraverse(controller, access, state, gameTime);
            return;
        }
        endIdle(state);
        StabilizerControllerBlockEntity.RadarContact contact = controller.getRadarContact(target);
        if (contact == null) {
            // The computer only knows the contacts a radar handed it. A target
            // that came from her own structure search has no entry there.
            contact = localContact(state, target);
        }
        if (contact == null) {
            state.commandedDirection = null;
            releaseWeapons(level, controller);
            return;
        }
        boolean ordnance = contact.missile();
        Vec3 desired = desiredDirection(level, controller, contact, gameTime);
        aim(controller, access, state, desired,
                ordnance ? ORDNANCE_AIM_STEP_DEGREES : AIM_MAX_STEP_DEGREES);
        MaidFireControlSeats.lookAt(maid, contact.center());
        if (dropUnreachable(controller, state, gameTime,
                ordnance ? ORDNANCE_STALL_ERROR_DEGREES : STALL_ERROR_DEGREES,
                ordnance ? ORDNANCE_UNREACHABLE_HOLD_TICKS : UNREACHABLE_HOLD_TICKS)) {
            // The contact sits where the mount cannot be brought to bear. Nothing
            // is released at it, and the next tick picks what can be reached.
            state.commandedDirection = null;
            releaseWeapons(level, controller);
            return;
        }
        holdGuns(level, controller, contact, desired, state, gameTime);
        if (gameTime < volleyReadyTick(state, target, gameTime)) {
            return;
        }
        // What takes the shot is decided by the way the contact is moving. One
        // that is opening the range is never caught by a shell, so it belongs to
        // the missile whatever the distance; one that is standing still or coming
        // on belongs to the gun, as far as the calibre bound to the mount can
        // carry - the full range against incoming ordnance, a short one against a
        // structure, which a missile breaks better from further out anyway.
        boolean gunTakesIt = gunCanReach(level, controller, contact, state, gameTime);
        if (gunTakesIt) {
            if (aligned(level, controller, desired, GUN_AIM_TOLERANCE_DEGREES)) {
                return;
            }
            if (!ordnance) {
                // The mount is still slewing onto a structure it can shell. A
                // missile spent here would only mean the gun never gets its shot.
                return;
            }
        }
        // Even across a range change the two never overlap: the trigger has to
        // have been quiet for a moment, and every release silences it again.
        // Ordnance only pays the token pause, because that second is exactly
        // what the incoming round is using to close.
        long silenceTicks = ordnance ? ORDNANCE_GUN_SILENCE_TICKS : GUN_SILENCE_TICKS;
        if (state.lastGunFireTick != Long.MIN_VALUE
                && gameTime - state.lastGunFireTick < silenceTicks) {
            return;
        }
        if (fireVolley(level, controller, contact, desired, state, gameTime)) {
            state.volleyReadyTick.put(contact.subLevelId(), gameTime + MISSILE_INTERVAL_TICKS);
            state.gunsColdUntilTick = gameTime + GUN_HOLDOFF_TICKS;
        }
    }

    /**
     * Where the bound guns should point. Inside gun range the bound cannon's own
     * moving target solution is used, so the shell is led by its own flight time
     * instead of being sent at where the contact happened to be; a contact that
     * is only worth a missile is aimed at directly, because the round steers
     * itself and the turret only has to be roughly on it.
     */
    private static Vec3 desiredDirection(
            ServerLevel level,
            StabilizerControllerBlockEntity controller,
            StabilizerControllerBlockEntity.RadarContact contact,
            long gameTime) {
        Vec3 origin = controller.getMeasuredAimOrigin();
        if (origin == null) {
            origin = controller.getRadarOrigin();
        }
        if (origin == null) {
            return null;
        }
        Vec3 point = aimPoint(controller, contact, gameTime);
        if (point == null) {
            return null;
        }
        if (!withinGunRange(origin, point, gunReach(contact))
                || (contact.missile() && diving(controller, contact))) {
            // A round released now flies to the meeting point, not to the contact,
            // so that is where the tube is pointed: a contact crossing the sight
            // line, the classic beam turn, is otherwise handed a heading the round
            // spends its whole flight correcting and never catches up with.
            //
            // The tube follows the same choice the round will make in the air (see
            // {@link BeamLeadMark#modeFor}): a contact beyond tail range flying straight along the
            // sight line is aimed at where it is, not at a meeting point. A tube pointed down a
            // prediction the round is not going to fly leaves the round correcting a heading it
            // was never given a reason to take.
            Vec3 crew = origin;
            BeamLeadMark.Mode mode = BeamLeadMark.modeFor(crew, point, contact.velocity());
            if (mode == BeamLeadMark.Mode.TAIL) {
                return point.subtract(origin);
            }
            Vec3 meeting = BeamLeadMark.launchInterceptPoint(
                    origin, point, contact.velocity(), BeamLeadMark.NOMINAL_TOP_SPEED);
            return meeting != null ? meeting.subtract(origin) : point.subtract(origin);
        }
        Vec3 lead = cannonLead(level, controller, contact, gameTime);
        return lead != null ? lead : point.subtract(origin);
    }

    /**
     * Where the contact is now. The radar rebuilds its list every other tick and
     * hands over the position sampled on that sweep, so the point a consumer
     * reads can be up to two ticks old. A round closing at Mach speed covers
     * tens of blocks in that time, which is the error the aim, the launch
     * alignment and the shell solution all used to inherit; carrying the contact
     * forward along its own velocity removes it.
     */
    private static Vec3 aimPoint(
            StabilizerControllerBlockEntity controller,
            StabilizerControllerBlockEntity.RadarContact contact,
            long gameTime) {
        Vec3 center = contact.center();
        Vec3 velocity = contact.velocity();
        if (center == null || velocity == null || velocity.lengthSqr() < 1.0E-8) {
            return center;
        }
        long scannedAt = RadarEmitters.scanTick(controller);
        if (scannedAt == Long.MIN_VALUE || gameTime <= scannedAt) {
            return center;
        }
        double lag = Math.min(RADAR_LAG_TICKS, (double) (gameTime - scannedAt));
        return center.add(velocity.scale(lag));
    }

    /** The bound cannon's own lead solution, or null when there is no cannon. */
    private static Vec3 cannonLead(
            ServerLevel level,
            StabilizerControllerBlockEntity controller,
            StabilizerControllerBlockEntity.RadarContact contact,
            long gameTime) {
        for (BindingRef mount : controller.getCannonMountBindings()) {
            try {
                if (!CannonMountCompat.hasAssembledCannon(level, mount)) {
                    continue;
                }
                CannonBallisticsCompat.ShotProfile profile = CannonBallisticsCompat.analyze(level, mount);
                Vec3 live = aimPoint(controller, contact, gameTime);
                CannonBallisticsCompat.MovingTargetSolution solution =
                        CannonBallisticsCompat.solveMovingTarget(profile,
                                live == null ? contact.center() : live, contact.velocity());
                if (solution != null && solution.launchDirection() != null
                        && solution.launchDirection().lengthSqr() > 1.0E-8) {
                    return solution.launchDirection();
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /**
     * Points the bound bearings at the contact. The direction asked for is rate
     * limited and deadbanded: a contact crossing the sight line asks for a very
     * different direction every tick, and a request that swings that far is what
     * makes a turret shake instead of track.
     */
    private static void aim(
            StabilizerControllerBlockEntity controller,
            MaidFireControlAccess access,
            State state,
            Vec3 desired,
            double maxStepDegrees) {
        // The lock follows the contact she is working every tick, even while the
        // bearings are already settled. A launcher's own round rides the computer's
        // lock, so leaving it on the previous contact sent the next round of a
        // salvo after the one that had just been destroyed.
        access.firecontrolcompat$setRadarLockedSubLevelId(state.targetId);
        Vec3 origin = controller.getMeasuredAimOrigin();
        if (origin == null) {
            origin = controller.getRadarOrigin();
        }
        if (origin == null || desired == null || desired.lengthSqr() < 1.0E-8) {
            return;
        }
        Vec3 direction = desired.normalize();
        Vec3 previous = state.commandedDirection;
        if (previous != null) {
            double error = angleDegrees(previous, direction);
            if (error <= AIM_DEADBAND_DEGREES) {
                return;
            }
            Vec3 blended = previous.scale(1.0D - AIM_FILTER).add(direction.scale(AIM_FILTER));
            direction = blended.lengthSqr() < 1.0E-8 ? direction : blended.normalize();
            if (angleDegrees(previous, direction) > maxStepDegrees) {
                direction = rotateTowards(previous, direction, maxStepDegrees);
            }
        }
        state.commandedDirection = direction;
        // Slews the bearings through the computer's own aim pass. The point sits
        // far along the limited direction, so the computer reproduces exactly the
        // direction that was limited here.
        controller.setDesiredWorldTarget(origin.add(direction.scale(AIM_POINT_DISTANCE)));
        access.firecontrolcompat$applyAimDirection();
    }

    /**
     * Turret motion of a post with nothing to shoot at.
     *
     * <p>A manned mount is never parked on one bearing, so while no contact is
     * worth engaging the crew keeps the barrels traversing slowly across their
     * arc. Nothing is released and no lock is taken: the swing rides the same aim
     * pass a live contact does, so the bearings, the view offset and the seat port
     * proxies behave exactly as they do in action, and coming out of it needs no
     * special case the moment a contact appears.</p>
     *
     * <p>The swing is a triangle wave around the direction the mount held when the
     * last contact went away, and it is bounded on purpose: a traverse that walked
     * further every cycle would end up parked against a bearing stop.</p>
     */
    private static void idleTraverse(
            StabilizerControllerBlockEntity controller,
            MaidFireControlAccess access,
            State state,
            long gameTime) {
        if (state.idleSinceTick == Long.MIN_VALUE) {
            state.idleSinceTick = gameTime;
        }
        if (state.idleBaseDirection == null || state.idleBaseDirection.lengthSqr() < 1.0E-8) {
            Vec3 base = controller.getMeasuredAimDirection();
            if (base == null || base.lengthSqr() < 1.0E-8) {
                base = state.commandedDirection;
            }
            if (base == null || base.lengthSqr() < 1.0E-8) {
                // No aim reference yet, so there is nothing to swing the mount to.
                return;
            }
            state.idleBaseDirection = base.normalize();
        }
        double travelled = (double) (gameTime - state.idleSinceTick) * IDLE_SWEEP_RATE_DEGREES;
        double swing = triangleWave(travelled, IDLE_SWEEP_LIMIT_DEGREES);
        aim(controller, access, state,
                rotateAboutY(state.idleBaseDirection, Math.toRadians(swing)),
                IDLE_AIM_STEP_DEGREES);
    }

    /** Ends the idle traverse, so the next one starts from wherever the mount is. */
    private static void endIdle(State state) {
        state.idleSinceTick = Long.MIN_VALUE;
        state.idleBaseDirection = null;
    }

    /** Triangle wave between -{@code limit} and +{@code limit} around zero. */
    private static double triangleWave(double travelled, double limit) {
        if (limit <= 0.0) {
            return 0.0;
        }
        double period = 4.0 * limit;
        double phase = travelled % period;
        if (phase < 0.0) {
            phase += period;
        }
        if (phase <= limit) {
            return phase;
        }
        if (phase <= 3.0 * limit) {
            return 2.0 * limit - phase;
        }
        return phase - period;
    }

    /** Direction turned about the world Y axis, which is what the idle traverse swings on. */
    private static Vec3 rotateAboutY(Vec3 direction, double radians) {
        double cosine = Math.cos(radians);
        double sine = Math.sin(radians);
        Vec3 rotated = new Vec3(
                direction.x * cosine + direction.z * sine,
                direction.y,
                -direction.x * sine + direction.z * cosine);
        return rotated.lengthSqr() < 1.0E-8 ? direction : rotated.normalize();
    }

    /** Angle between two directions, in degrees. */
    private static double angleDegrees(Vec3 from, Vec3 to) {
        Vec3 a = from.normalize();
        Vec3 b = to.normalize();
        return Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, a.dot(b)))));
    }

    /** {@code from} turned toward {@code to} by at most the given angle. */
    private static Vec3 rotateTowards(Vec3 from, Vec3 to, double maxDegrees) {
        Vec3 axis = from.cross(to);
        double sine = axis.length();
        if (sine < 1.0E-8) {
            return to;
        }
        double step = Math.min(Math.atan2(sine, from.dot(to)), Math.toRadians(maxDegrees));
        axis = axis.scale(1.0 / sine);
        double cosine = Math.cos(step);
        double sin = Math.sin(step);
        Vec3 rotated = from.scale(cosine)
                .add(axis.cross(from).scale(sin))
                .add(axis.scale(axis.dot(from) * (1.0 - cosine)));
        Vec3 normalized = rotated.normalize();
        return normalized.lengthSqr() < 1.0E-8 ? to : normalized;
    }

    /**
     * The contacts the crew works from. Normally that is the computer's own radar
     * list. A post without a working radar has none at all - the computer clears
     * its list every tick and never paints anything - so she falls back on her own
     * sweep of the loaded structures inside {@link MaidLocalRadar#SEARCH_RANGE}
     * blocks, which never reports ordnance.
     */
    private static List<StabilizerControllerBlockEntity.RadarContact> contactsFor(
            ServerLevel level,
            StabilizerControllerBlockEntity controller,
            State state,
            long gameTime) {
        try {
            if (!controller.isRadarActive()) {
                return MaidLocalRadar.contacts(level, controller, state, gameTime);
            }
        } catch (Throwable ignored) {
        }
        MaidLocalRadar.forget(state);
        return controller.getRadarContacts();
    }

    /** The contact of her own sweep with that id, or null when it is not one of hers. */
    private static StabilizerControllerBlockEntity.RadarContact localContact(State state, UUID id) {
        if (id == null || state.localContacts == null) {
            return null;
        }
        for (StabilizerControllerBlockEntity.RadarContact contact : state.localContacts) {
            if (id.equals(contact.subLevelId())) {
                return contact;
            }
        }
        return null;
    }

    /**
     * Contact the maid engages: incoming ordnance first, then the one she is
     * already working, then the closest structure. Friendly contacts and contacts
     * the computer itself hides (speed gate included) are never picked, and a
     * target that is still on the scope is not dropped for a fresh one, because
     * swapping back and forth is what makes a turret look jumpy.
     */
    private static UUID chooseTarget(
            ServerLevel level,
            StabilizerControllerBlockEntity controller,
            State state,
            long gameTime) {
        List<StabilizerControllerBlockEntity.RadarContact> contacts =
                contactsFor(level, controller, state, gameTime);
        if (contacts.isEmpty()) {
            state.targetUntil = Long.MIN_VALUE;
            return null;
        }
        if (state.unreachableUntil.size() > 8) {
            state.unreachableUntil.values().removeIf(until -> until <= gameTime);
        }
        Set<String> own = IffRadar.ownChannels(level, controller);
        Vec3 origin = controller.getRadarOrigin();
        StabilizerControllerBlockEntity.RadarContact incoming = null;
        StabilizerControllerBlockEntity.RadarContact cooling = null;
        StabilizerControllerBlockEntity.RadarContact held = null;
        StabilizerControllerBlockEntity.RadarContact nearest = null;
        double incomingDistance = Double.MAX_VALUE;
        double coolingDistance = Double.MAX_VALUE;
        double nearestDistance = Double.MAX_VALUE;
        for (StabilizerControllerBlockEntity.RadarContact contact : contacts) {
            if (!engageable(level, controller, contact, own, state.manual, state.maidId)) {
                continue;
            }
            if (unreachable(state, contact.subLevelId(), gameTime)) {
                continue;
            }
            double distance = origin == null ? 0.0 : contact.center().distanceTo(origin);
            if (contact.subLevelId().equals(state.targetId)) {
                held = contact;
            }
            if (contact.missile()) {
                // Ordnance is intercepted before structures, however far out it is,
                // but only a contact whose own reload clock has run out can take a
                // round. Holding the post while that clock runs is what let a salvo
                // through: the closest round spent the whole five seconds doing
                // nothing and the rest of the salvo arrived unopposed, so a threat
                // that is still cooling is set aside as long as another one may be
                // engaged now.
                if (reloadPending(state, contact.subLevelId(), gameTime)) {
                    if (distance < coolingDistance) {
                        coolingDistance = distance;
                        cooling = contact;
                    }
                } else if (distance < incomingDistance) {
                    incomingDistance = distance;
                    incoming = contact;
                }
            } else if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = contact;
            }
        }
        // A threat that may be engaged now, then the threat already being worked
        // (the gun can still take it), then a threat that is only waiting on its
        // clock, then a structure, then anything else.
        StabilizerControllerBlockEntity.RadarContact chosen;
        if (incoming != null) {
            chosen = incoming;
        } else if (held != null && held.missile()) {
            chosen = held;
        } else if (cooling != null) {
            chosen = cooling;
        } else {
            chosen = held != null ? held : nearest;
        }
        if (chosen == null) {
            state.targetUntil = Long.MIN_VALUE;
            return null;
        }
        state.targetUntil = gameTime + TARGET_HOLD_TICKS;
        return chosen.subLevelId();
    }

    private static boolean engageable(
            Level level,
            StabilizerControllerBlockEntity controller,
            StabilizerControllerBlockEntity.RadarContact contact,
            Set<String> own,
            boolean manual,
            UUID maidId) {
        if (IffRadar.hiddenFromRadar(level, contact, own)) {
            return false;
        }
        // The manual operator stays on ordnance and on the structure a player
        // called her onto through a scope pointed at her; everything else on the
        // scope is left alone. The mark belongs to one operator rather than to
        // the world, so a structure called onto somebody else is not hers to
        // take, however close it is.
        if (manual && !contact.missile()
                && !SpyglassMarkStore.isMarked(level, maidId, contact.subLevelId())) {
            return false;
        }
        byte mark = IffRadar.mark(level, contact.subLevelId(), own);
        return mark != ContactTypePayload.IFF_FRIENDLY;
    }

    /**
     * Releases one missile against the contact. Both launcher families are used,
     * air-defense tubes and anti-tank tubes alike, and every round the mod adds
     * is handled: the infrared round goes through the same two step trigger a
     * player uses, the beam riders are handed the interception point so they lead
     * a crossing target, and the launcher's own rounds ride their normal launch.
     */
    private static boolean fireVolley(
            ServerLevel level,
            StabilizerControllerBlockEntity controller,
            StabilizerControllerBlockEntity.RadarContact contact,
            Vec3 desired,
            State state,
            long gameTime) {
        double tolerance = contact.missile()
                ? ORDNANCE_AIM_TOLERANCE_DEGREES
                : MISSILE_AIM_TOLERANCE_DEGREES;
        // A dive that has climbed above what the mount can still point at is
        // released on the bearing alone. The launcher cannot follow it up there,
        // but a steering round leaves the tube on the arc the launcher is
        // already on and turns onto the intercept itself, and the heat head can
        // still take a contact that far off its axis. Waiting for an alignment
        // that will never come is how a top attack arrives unopposed.
        boolean onTarget = aligned(level, controller, desired, tolerance)
                || (contact.missile() && diving(controller, contact)
                        && onBearing(level, controller, desired, DIVE_BEARING_TOLERANCE_DEGREES));
        if (!onTarget) {
            return false;
        }
        try {
            return releaseVolley(level, controller, contact, state, gameTime);
        } catch (Throwable failure) {
            // A launcher's ammo counter lives in a lazily loaded fire-control class
            // and its mixins are applied on that load. A descriptor that stopped
            // matching the installed fire-control build turns that first touch into
            // a MixinApplyError, which is an Error and would otherwise take the
            // server down the moment a maid fired. Log it once and let her keep
            // working with whatever families still answer.
            firecontrolcompat$reportVolleyFailure(failure);
            return false;
        }
    }

    /** First failure of the launcher hand-off, logged once per session. */
    private static volatile boolean volleyFailureReported;

    private static void firecontrolcompat$reportVolleyFailure(Throwable failure) {
        if (volleyFailureReported) {
            return;
        }
        volleyFailureReported = true;
        FireControlCompat.LOGGER.error(
                "[firecontrolcompat] maid fire control: launcher hand-off threw {}: {} - the volley was skipped instead of crashing the server",
                failure.getClass().getName(), failure.getMessage(), failure);
    }

    /** The release itself: air-defense tubes first, then the anti-tank tubes. */
    private static boolean releaseVolley(
            ServerLevel level,
            StabilizerControllerBlockEntity controller,
            StabilizerControllerBlockEntity.RadarContact contact,
            State state,
            long gameTime) {
        for (BindingRef launcher : controller.getAaMissileLaunchers()) {
            if (MianbaoAirDefenseCompat.getAmmoCount(level, launcher) <= 0) {
                continue;
            }
            return releaseMissile(level, controller, launcher, contact, state, gameTime, true);
        }
        for (BindingRef launcher : controller.getMissileLaunchers()) {
            if (MianbaoMissileCompat.getAmmoCount(level, launcher) <= 0) {
                continue;
            }
            return releaseMissile(level, controller, launcher, contact, state, gameTime, false);
        }
        return false;
    }

    /** One missile release from a single launcher. */
    private static boolean releaseMissile(
            ServerLevel level,
            StabilizerControllerBlockEntity controller,
            BindingRef launcher,
            StabilizerControllerBlockEntity.RadarContact contact,
            State state,
            long gameTime,
            boolean antiAir) {
        boolean infrared = antiAir
                ? BeamMissileCompat.hasIrAntiAirAmmo(level, launcher)
                : BeamMissileCompat.hasIrMissileLauncherAmmo(level, launcher);
        if (infrared) {
            // The two step release belongs to the air-defence family alone. The anti-tank round is
            // released in one shot onto the computer's designation (pod paint, optical lock, radar
            // contact) and climbs over it - it is not an infrared homing missile, so a seeker press
            // for it is both pointless and wrong (see BeamMissileCompat#fireIrAtgm).
            if (antiAir && !pressSeeker(level, controller, launcher, state, gameTime, true)) {
                return false;
            }
            boolean fired = antiAir
                    ? MianbaoAirDefenseCompat.fireOne(level, launcher, controller)
                    : MianbaoMissileCompat.fireOne(level, launcher);
            if (!fired) {
                FireControlCompat.LOGGER.info(
                        "[firecontrolcompat] maid fire control: infrared release failed launcher={}",
                        launcher.pos());
            }
            return fired;
        }
        if (!antiAir && !BeamMissileCompat.hasBeamAmmo(level, launcher)) {
            // A round that needs somebody at the trigger is left in the tube.
            return false;
        }
        BeamLeadMark.arm(controller.getBlockPos(), contact.subLevelId());
        // The computer goes with the shot. Through the two-argument {@code fireOne} the pack sees no
        // computer, refuses the launch, and the fallback round carries no guidance and no ownership
        // mark - which is what let a gunner empty every tube at her own missiles (session log
        // 2026-10-25 15:46: four rounds per rail in five seconds, all of them unmarked).
        boolean fired = antiAir
                ? MianbaoAirDefenseCompat.fireOne(level, launcher, controller)
                : MianbaoMissileCompat.fireOne(level, launcher);
        BeamLeadMark.clear();
        if (!fired) {
            FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] maid fire control: missile release failed launcher={}",
                    launcher.pos());
        }
        return fired;
    }

    /**
     * One trigger press for an infrared launcher. Returns true once the seeker
     * holds a contact and the round may leave the tube, false while the seeker
     * is still watching.
     */
    private static boolean pressSeeker(
            ServerLevel level,
            StabilizerControllerBlockEntity controller,
            BindingRef launcher,
            State state,
            long gameTime,
            boolean antiAir) {
        if (gameTime < state.nextTriggerTick) {
            return false;
        }
        state.nextTriggerTick = gameTime + TRIGGER_INTERVAL_TICKS;
        Vec3 muzzle = antiAir
                ? BeamMissileCompat.antiAirMuzzle(level, launcher.pos())
                : BeamMissileCompat.missileLauncherMuzzle(level, launcher.pos());
        IrSeekerSession.Lock lock = IrSeekerSession.press(
                level, launcher.pos(), controller.getBlockPos(), muzzle,
                BeamMissileCompat.facing(level, launcher.pos()));
        if (lock == null) {
            return false;
        }
        IrSeekerSession.handOver(level, launcher.pos(), lock);
        return true;
    }

    /** Holds or releases the gun trigger on every bound gun station. */
    private static void holdGuns(
            ServerLevel level,
            StabilizerControllerBlockEntity controller,
            StabilizerControllerBlockEntity.RadarContact contact,
            Vec3 desired,
            State state,
            long gameTime) {
        // The same rule the release follows: whichever contact it is, the gun
        // takes it while it is coming on and still inside the gun's reach.
        boolean release = gameTime >= state.gunsColdUntilTick
                && gunCanReach(level, controller, contact, state, gameTime)
                && aligned(level, controller, desired, GUN_AIM_TOLERANCE_DEGREES);
        if (release) {
            state.lastGunFireTick = gameTime;
        }
        for (BindingRef mount : controller.getCannonMountBindings()) {
            CannonMountCompat.setAutomaticFire(level, mount, release);
        }
        if (!release) {
            return;
        }
        for (BindingRef station : controller.getRemoteWeaponStations()) {
            MianbaoRemoteWeaponStationCompat.tickFireControl(level, station, true);
        }
        for (BindingRef mount : controller.getCannonMountBindings()) {
            if (CannonMountCompat.getMountedCannonEntity(level, mount)
                    instanceof PitchOrientedContraptionEntity cannon
                    && MountedHeavyMachineGunCompat.hasAttachments(cannon)) {
                MountedHeavyMachineGunCompat.tickFireControl(cannon, true);
            }
        }
    }

    /** Drops every trigger the maid was holding. */
    private static void releaseWeapons(ServerLevel level, StabilizerControllerBlockEntity controller) {
        for (BindingRef mount : controller.getCannonMountBindings()) {
            CannonMountCompat.setAutomaticFire(level, mount, false);
        }
    }

    /** The point the bound guns fire from, or null when it cannot be measured. */
    private static Vec3 desiredOrigin(StabilizerControllerBlockEntity controller) {
        Vec3 origin = controller.getMeasuredAimOrigin();
        return origin != null ? origin : controller.getRadarOrigin();
    }

    /** Whether the computer has a bound cannon that is actually assembled. */
    private static boolean hasGun(ServerLevel level, StabilizerControllerBlockEntity controller) {
        for (BindingRef mount : controller.getCannonMountBindings()) {
            if (CannonMountCompat.hasAssembledCannon(level, mount)) {
                return true;
            }
        }
        return false;
    }

    /** Whether the point the contact has reached is inside the given reach of the bound cannon. */
    private static boolean withinGunRange(Vec3 origin, Vec3 point, double reach) {
        return origin != null && point != null && point.distanceTo(origin) <= reach;
    }

    /**
     * How steeply a contact has to be coming down before its flight path counts
     * as a dive: the sine of the angle below the horizon, so 0.57 is about
     * thirty-five degrees.
     */
    private static final double DIVE_DESCENT = 0.57D;

    /**
     * How far above the mount a diving contact has to be before the mount counts
     * as under attack: the sine of the elevation of the sight line, so 0.34 is
     * about twenty degrees. Below that the round is falling past a mount it was
     * aimed at from the side, which the gun can still take.
     */
    private static final double DIVE_OVERHEAD = 0.34D;

    /**
     * How far off the contact's bearing a launcher may be and still release a
     * round at a dive. Only the bearing is measured: the elevation is the half
     * of the aim a dive escapes, and the round steers its own way up.
     */
    private static final double DIVE_BEARING_TOLERANCE_DEGREES = 35.0D;

    /** How far the bound gun is worth using against this contact, in blocks. */
    private static double gunReach(StabilizerControllerBlockEntity.RadarContact contact) {
        return contact.missile() ? GUN_MAX_RANGE : GUN_MAX_RANGE_STRUCTURE;
    }

    /**
     * Whether the contact is a top attack: one that comes down on the mount from
     * above rather than crossing it or running in at height.
     *
     * <p>Both halves matter. A round in a dive, judged by its own flight path,
     * and overhead, judged by where it is on the sight line - a missile crossing
     * the far side of the mount on a shallow descent is an ordinary crossing
     * target and stays with the gun.</p>
     */
    private static boolean diving(
            StabilizerControllerBlockEntity controller,
            StabilizerControllerBlockEntity.RadarContact contact) {
        Vec3 velocity = contact.velocity();
        if (velocity == null || velocity.lengthSqr() < 1.0E-8) {
            return false;
        }
        if (-velocity.y / velocity.length() < DIVE_DESCENT) {
            return false;
        }
        Vec3 origin = desiredOrigin(controller);
        Vec3 point = contact.center();
        if (origin == null || point == null) {
            return false;
        }
        Vec3 sight = point.subtract(origin);
        if (sight.lengthSqr() < 1.0E-8) {
            return false;
        }
        return sight.y / sight.length() >= DIVE_OVERHEAD;
    }

    /**
     * Whether the gun is the weapon for this contact right now: a bound cannon
     * that is actually assembled, a contact the bound calibre can already reach,
     * and a contact that is not opening the range. A ship running away is never
     * caught by a shell, and that is the one case where the missile goes even at
     * point blank range.
     */
    private static boolean gunCanReach(
            ServerLevel level,
            StabilizerControllerBlockEntity controller,
            StabilizerControllerBlockEntity.RadarContact contact,
            State state,
            long gameTime) {
        if (!hasGun(level, controller)) {
            return false;
        }
        // A dive is the one geometry the barrel cannot win. The sight line onto a
        // round coming down overhead is close to vertical, and a bearing that has
        // to climb that far spends the whole dive getting there; the shell is then
        // released onto a meeting point the round has already fallen past. The
        // steering rounds have no such trouble - they are launched into the same
        // arc the launcher is already on and turn onto the intercept - so diving
        // ordnance is handed to the tubes outright, however close it is.
        if (contact.missile() && diving(controller, contact)
                && hasLoadedTube(level, controller)
                && !reloadPending(state, contact.subLevelId(), gameTime)) {
            return false;
        }
        Vec3 origin = desiredOrigin(controller);
        if (!withinGunRange(origin, aimPoint(controller, contact, gameTime), gunReach(contact))) {
            return false;
        }
        return closingSpeed(contact, origin, state) >= -CLOSING_MARGIN;
    }

    /**
     * Whether any bound launcher holds a round a dive could be handed to. A
     * mount with nothing in its tubes keeps its dive for the barrel: a shot the
     * barrel can barely take is still worth more than an empty launcher.
     */
    private static boolean hasLoadedTube(
            ServerLevel level,
            StabilizerControllerBlockEntity controller) {
        for (BindingRef launcher : controller.getAaMissileLaunchers()) {
            if (MianbaoAirDefenseCompat.getAmmoCount(level, launcher) > 0) {
                return true;
            }
        }
        for (BindingRef launcher : controller.getMissileLaunchers()) {
            if (MianbaoMissileCompat.getAmmoCount(level, launcher) > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * How fast the contact is coming on, in blocks per second; negative while
     * the range is opening. The mount's own way on is taken out of the contact's
     * velocity, so a hull overhauling a target reads it as closing and a hull
     * running away from one reads it as opening.
     */
    private static double closingSpeed(
            StabilizerControllerBlockEntity.RadarContact contact,
            Vec3 origin,
            State state) {
        Vec3 velocity = contact.velocity();
        Vec3 center = contact.center();
        if (velocity == null || center == null || origin == null) {
            return 0.0;
        }
        Vec3 los = center.subtract(origin);
        if (los.lengthSqr() < 1.0E-8) {
            return 0.0;
        }
        return -velocity.subtract(state.platformVelocity).dot(los.normalize()) * 20.0;
    }

    /**
     * Measures how fast the mount itself is travelling, from how far its radar
     * origin moved since the last tick. A post aboard a moving hull has to know
     * its own way on, or every contact it holds would read as closing at the
     * speed of the ship and the gun would take shots its bearing drivers could
     * never hold. A jump far beyond anything that flies is a rebound or a radar
     * that was bound somewhere else rather than travel, and is dropped so the
     * reading survives it.
     */
    private static void samplePlatformVelocity(
            StabilizerControllerBlockEntity controller,
            State state,
            long gameTime) {
        Vec3 origin = controller.getRadarOrigin();
        if (origin == null || state.platformOriginTick == gameTime) {
            return;
        }
        Vec3 previous = state.platformOrigin;
        long previousTick = state.platformOriginTick;
        state.platformOrigin = origin;
        state.platformOriginTick = gameTime;
        if (previous == null || previousTick == Long.MIN_VALUE || gameTime <= previousTick) {
            return;
        }
        Vec3 measured = origin.subtract(previous).scale(20.0 / (double) (gameTime - previousTick));
        if (measured.lengthSqr() > PLATFORM_SPEED_LIMIT * PLATFORM_SPEED_LIMIT) {
            return;
        }
        state.platformVelocity = state.platformVelocity.scale(0.5).add(measured.scale(0.5));
    }

    /**
     * Whether the bound guns already point where they were asked to. It fails
     * closed on purpose: with no aim reference at all the trigger stays off,
     * because holding it blind is what empties a magazine into thin air.
     */
    private static boolean aligned(
            ServerLevel level,
            StabilizerControllerBlockEntity controller,
            Vec3 desired,
            double toleranceDegrees) {
        if (desired == null || desired.lengthSqr() < 1.0E-8) {
            return false;
        }
        Vec3 direction = aimDirection(level, controller);
        if (direction == null) {
            return false;
        }
        return angleDegrees(direction, desired) <= toleranceDegrees;
    }

    /**
     * Whether the launcher's bearing is on the contact, whatever its elevation.
     *
     * <p>Only ever asked of a dive. Everything else has to be fully on the
     * contact before a round is spent, but a round coming down from above climbs
     * out of the arc the mount can reach as it closes, and a launcher left
     * swinging after it never releases anything at all. The bearing is what the
     * mount can always hold, and what the round needs: it is launched into the
     * arc the launcher is already on and steers itself from there.</p>
     */
    private static boolean onBearing(
            ServerLevel level,
            StabilizerControllerBlockEntity controller,
            Vec3 desired,
            double toleranceDegrees) {
        if (desired == null || desired.lengthSqr() < 1.0E-8) {
            return false;
        }
        Vec3 direction = aimDirection(level, controller);
        if (direction == null) {
            return false;
        }
        Vec3 ask = new Vec3(desired.x, 0.0D, desired.z);
        Vec3 aim = new Vec3(direction.x, 0.0D, direction.z);
        if (ask.lengthSqr() < 1.0E-6 || aim.lengthSqr() < 1.0E-6) {
            // Straight up or straight down: there is no bearing to compare, and
            // a mount in that attitude is pointed at the contact as well as it
            // ever will be.
            return true;
        }
        return angleDegrees(aim, ask) <= toleranceDegrees;
    }

    /** Where the bound guns are actually pointed, or null when that cannot be read. */
    private static Vec3 aimDirection(
            ServerLevel level,
            StabilizerControllerBlockEntity controller) {
        Vec3 direction = controller.getMeasuredAimDirection();
        if (direction == null || direction.lengthSqr() < 1.0E-8) {
            direction = assembledCannonDirection(level, controller);
        }
        if (direction == null || direction.lengthSqr() < 1.0E-8) {
            return null;
        }
        return direction;
    }

    /**
     * Tick the contact may take its next round, {@code Long.MIN_VALUE} when it
     * has none pending. Expired clocks are swept out on the way, so a contact
     * that leaves the scope does not keep an entry alive.
     */
    private static long volleyReadyTick(State state, UUID target, long gameTime) {
        if (state.volleyReadyTick.size() > 8) {
            state.volleyReadyTick.values().removeIf(until -> until <= gameTime);
        }
        Long ready = state.volleyReadyTick.get(target);
        if (ready == null) {
            return Long.MIN_VALUE;
        }
        if (ready <= gameTime) {
            state.volleyReadyTick.remove(target);
            return Long.MIN_VALUE;
        }
        return ready;
    }

    /** Whether the contact is still waiting on its own reload clock. */
    private static boolean reloadPending(State state, UUID target, long gameTime) {
        Long ready = state.volleyReadyTick.get(target);
        return ready != null && ready > gameTime;
    }

    /** Whether the contact was dropped as out of reach and is still on the list. */
    private static boolean unreachable(State state, UUID id, long gameTime) {
        Long until = state.unreachableUntil.get(id);
        if (until == null) {
            return false;
        }
        if (until <= gameTime) {
            state.unreachableUntil.remove(id);
            return false;
        }
        return true;
    }

    /**
     * Drops a contact the bound bearings cannot be brought onto.
     *
     * <p>A target in a dead corner of the mount - past a yaw stop, above the
     * elevation limit, or on a bearing that no longer turns - leaves the barrel
     * parked while every aim request stays well off it. Releasing there throws
     * the shell or the round away and, worse, grinds the bearing against its own
     * stop. The watch therefore measures what matters: whether the aim is closing
     * on the contact at all. The error is sampled when the watch window opens and
     * compared two seconds later; anything that did not come meaningfully closer
     * is treated as out of reach and skipped for a while, so the guns and tubes
     * work on what they can actually reach.</p>
     *
     * <p>Returns true on the tick the contact was dropped.</p>
     */
    private static boolean dropUnreachable(
            StabilizerControllerBlockEntity controller,
            State state,
            long gameTime,
            double stallErrorDegrees,
            long holdTicks) {
        Vec3 asked = state.commandedDirection;
        Vec3 measured = controller.getMeasuredAimDirection();
        if (state.targetId == null
                || asked == null
                || asked.lengthSqr() < 1.0E-8
                || measured == null
                || measured.lengthSqr() < 1.0E-8) {
            resetAimWatch(state);
            return false;
        }
        if (!state.targetId.equals(state.watchTargetId)) {
            startAimWatch(state, gameTime, measured, asked);
            return false;
        }
        double error = angleDegrees(measured, asked);
        if (error <= stallErrorDegrees) {
            // The barrel is on the ask, so there is nothing left to close.
            resetAimWatch(state);
            return false;
        }
        if (gameTime - state.watchSinceTick < STALL_TICKS) {
            return false;
        }
        if (error > state.watchError - STALL_PROGRESS_DEGREES) {
            state.unreachableUntil.put(state.targetId, gameTime + holdTicks);
            resetAimWatch(state);
            return true;
        }
        startAimWatch(state, gameTime, measured, asked);
        return false;
    }

    private static void startAimWatch(
            State state,
            long gameTime,
            Vec3 measured,
            Vec3 asked) {
        state.watchTargetId = state.targetId;
        state.watchError = angleDegrees(measured, asked);
        state.watchSinceTick = gameTime;
    }

    private static void resetAimWatch(State state) {
        state.watchTargetId = null;
        state.watchError = Double.MAX_VALUE;
        state.watchSinceTick = Long.MIN_VALUE;
    }

    /** Direction of the first assembled bound cannon, when the aim pass has no answer. */
    private static Vec3 assembledCannonDirection(
            ServerLevel level,
            StabilizerControllerBlockEntity controller) {
        for (BindingRef mount : controller.getCannonMountBindings()) {
            Vec3 direction = CannonMountCompat.getAssembledCannonDirection(level, mount);
            if (direction != null && direction.lengthSqr() > 1.0E-8) {
                return direction;
            }
        }
        return null;
    }
}

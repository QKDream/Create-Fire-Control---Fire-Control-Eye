package com.qkdream.firecontrolcompat;

import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.qkdream.firecontrolcompat.entity.InfraredMissileEntity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Two step release of the infrared missile: the first trigger press opens the
 * seeker, which watches a narrow field of view out to 1500 blocks for fifteen
 * seconds and takes the contact closest to the middle of that window once it
 * has stayed there for two seconds; the second press launches the round at that
 * contact and spends the ammunition. A seeker that finds nothing simply closes
 * after fifteen seconds and leaves the launcher loaded.
 *
 * <p>Two angles describe the head. The narrow one is its field of view: only a
 * contact inside it can be taken, so the head has to be pointed at what it is
 * meant to shoot. The wide one is the off-axis limit: a contact the head
 * already holds stays held anywhere inside it and is released once it has
 * stayed outside it for a couple of seconds, which is what the big frame on the
 * HUD draws.</p>
 *
 * <p>When the fire control computer holds something on its radar the head turns
 * to look at it, as far as its travel allows: the field of view frame slides onto
 * that contact while the head can reach it and stops on the edge of the off-axis
 * frame while it cannot. The off-axis frame itself never leaves the launcher's
 * own axis, because it is the limit of that travel. A designated contact outranks
 * anything the head finds by itself, and losing the radar lock simply hands the
 * head back to its own scan.</p>
 *
 * <p>A session is owned by the launcher (or rack) that opened it, so a second
 * press only launches from the tube whose seeker is already running.</p>
 *
 * <p>With image lock picked in the fire control computer the head works a
 * narrower trade: it only ever takes a Sable structure, out to 1000 blocks, in
 * daylight under a clear sky. Holding a contact is the same either way - both
 * heads release it once it leaves the off-axis frame. What the image head buys
 * is the round it releases: that one is blind to countermeasures, so a flare
 * cannot pull it off the structure it was fired at.</p>
 */
@EventBusSubscriber(modid = FireControlCompat.MOD_ID)
public final class IrSeekerSession {

    /** How long the seeker keeps watching before it gives up, in ticks (15 s). */
    public static final int SEARCH_TICKS = 300;
    /** How long a contact has to stay inside the frame before the head takes it. */
    public static final int LOCK_DWELL_TICKS = 40;
    /**
     * How long a held contact may stay outside the off-axis frame before the head
     * lets it go, in ticks (2 s). A sweep past the edge of the frame is not an
     * unlock, but a contact that is really gone still drops.
     */
    public static final int LOCK_GRACE_TICKS = 40;
    /** Reach of the pre-launch acquisition, in blocks. */
    public static final double SEARCH_RANGE = 1500.0;
    /** Reach of the image lock head, in blocks. */
    public static final double IMAGE_SEARCH_RANGE = 1000.0;
    /**
     * The head's own field of view, half angle in degrees. Narrowed from the old
     * sixty degree search window, so the head only takes a contact that is held
     * near the launch line: a launcher slaved to the fire control computer is
     * aimed at its target, a hand aimed one has to be pointed at it.
     */
    public static final double SEARCH_HALF_ANGLE_DEGREES = 10.0;
    /** Cosine of the field of view, used to test a contact against it. */
    public static final double SEARCH_HALF_COS = Math.cos(Math.toRadians(SEARCH_HALF_ANGLE_DEGREES));
    /**
     * Off-axis limit of the head, half angle in degrees: a contact the head has
     * already taken is only released once it leaves this cone. The frame the HUD
     * draws at this angle is the frame a lock lives inside.
     */
    public static final double OFF_AXIS_HALF_ANGLE_DEGREES = 30.0;
    /** Cosine of the off-axis limit, used to test a held contact against it. */
    public static final double OFF_AXIS_HALF_COS = Math.cos(Math.toRadians(OFF_AXIS_HALF_ANGLE_DEGREES));
    /**
     * How far along the look direction the field of view frame is drawn while the
     * head cannot reach the contact the radar designated. It matches the distance
     * the HUD itself falls back to, so a frame stuck on the edge of travel is drawn
     * at the same scale as every other.
     */
    private static final double FRAME_ANCHOR_DISTANCE = 50.0;
    /**
     * Ticks between two searches. Each search walks every entity of the world
     * inside the head's range plus every loaded body, which is the single most
     * expensive thing this class does, so it runs twice a second instead of ten
     * times: the keep-frame is two seconds wide and the stage lasts fifteen, so
     * nothing about the lock behaviour changes, only how often the world is
     * walked for it.
     */
    private static final int SCAN_INTERVAL = 10;
    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();
    /** Contacts locked by a trigger press, waiting for the round to leave the tube. */
    private static final Map<String, Pending> HANDOVERS = new ConcurrentHashMap<>();

    private IrSeekerSession() {
    }

    /** The contact a launcher's seeker settled on: one of the three kinds the round can ride. */
    public record Lock(UUID entity, UUID subLevel, UUID shaolib, Vec3 position) {
    }

    /**
     * Handles one trigger press for a launcher. Returns null while the seeker is
     * still watching, and the locked contact when the press has to spend a round.
     *
     * @param controller the fire control computer the launcher answers to, whose
     *                   radar lock the head is slaved to while it holds one
     */
    public static Lock press(ServerLevel level, BlockPos launcher, BlockPos controller, Vec3 localMuzzle,
            Direction facing) {
        if (level == null || launcher == null || localMuzzle == null || facing == null) {
            return null;
        }
        long now = level.getGameTime();
        String key = key(level, launcher);
        Session running = SESSIONS.get(key);
        if (running != null && running.level == level && !running.expired(now)) {
            if (running.lock == null) {
                scan(running, now);
            }
            Lock lock = running.lock;
            if (lock == null) {
                report("search", level, launcher, null,
                        SubLevelPositions.worldPosition(level, localMuzzle), null);
                return null;
            }
            SESSIONS.remove(key);
            // The press that spends the round: the contact and the tube line are both known here,
            // so this is where the off-boresight angle of the shot is decided and printed.
            // The contact's position is already a world point (an entity centre, a structure's
            // bounding box centre, a shaolib round's own position); only the muzzle is stored in the
            // launcher's own space, so only that one is projected.
            report("locked", level, launcher,
                    offBoresightDegrees(running.origin(), running.boresight(), lock.position()),
                    SubLevelPositions.worldPosition(level, localMuzzle),
                    lock.position());
            return lock;
        }
        SubLevel own = ownSubLevel(level, launcher);
        SESSIONS.put(key, new Session(level, launcher, controller, localMuzzle, facing,
                now + SEARCH_TICKS, now + SCAN_INTERVAL, own, ownGroup(level, own)));
        report("search", level, launcher, null,
                SubLevelPositions.worldPosition(level, localMuzzle), null);
        return null;
    }

    /**
     * Parks a locked contact for the round the launcher is about to release. The
     * trigger press runs above the launcher, so the contact travels this way.
     */
    public static void handOver(ServerLevel level, BlockPos launcher, Lock lock) {
        if (level == null || launcher == null || lock == null) {
            return;
        }
        HANDOVERS.put(key(level, launcher), new Pending(lock, level, level.getGameTime() + 40L));
    }

    /** Consumes the parked contact, or null when the launch was not seeker led. */
    public static Lock takeHandOver(ServerLevel level, BlockPos launcher) {
        if (level == null || launcher == null) {
            return null;
        }
        Pending pending = HANDOVERS.remove(key(level, launcher));
        return pending == null ? null : pending.lock();
    }

    /**
     * The one line a two step release prints, one per stage.
     *
     * <p>{@code stage=search} is the press that opened the head, {@code stage=locked} the press that
     * found a contact and parked it for the round, and {@code stage=fire} the launch itself - which
     * is the only stage that carries an off-boresight angle, because it is the only one where the
     * round's own tube line and the contact it was released against are both known.</p>
     *
     * <p>{@code space} says which coordinate space the launcher's block position is stored in:
     * {@code plot} for a launcher standing on a Sable structure (its block position lives in the
     * structure's storage region, tens of millions of blocks from where it is seen), {@code world}
     * otherwise. A muzzle and a target printed twenty million blocks apart is then obviously a
     * space mismatch and not a missing lock.</p>
     */
    public static void report(String stage, Level level, BlockPos rack, Double offBoresight, Vec3 muzzle,
            Vec3 target) {
        try {
            FireControlCompat.LOGGER.info(
                    "ir-seeker: stage={} rack={} offBoresight={} muzzle={} target={} space={}",
                    stage,
                    rack == null ? "-" : rack.toShortString(),
                    offBoresight == null ? "-" : String.format(java.util.Locale.ROOT, "%.1f", offBoresight),
                    muzzle == null ? "-" : String.format(java.util.Locale.ROOT, "%.2f/%.2f/%.2f",
                            muzzle.x, muzzle.y, muzzle.z),
                    target == null ? "-" : String.format(java.util.Locale.ROOT, "%.2f/%.2f/%.2f",
                            target.x, target.y, target.z),
                    spaceName(level, rack));
        } catch (Throwable ignored) {
            // a diagnostic line is never worth a shot
        }
    }

    /** {@code plot} when the block position is stored in a sub-level, {@code world} otherwise. */
    public static String spaceName(Level level, BlockPos pos) {
        if (level == null || pos == null) {
            return "world";
        }
        try {
            return SubLevelPositions.inPlotSpace(level, Vec3.atCenterOf(pos)) ? "plot" : "world";
        } catch (Throwable ignored) {
            return "world";
        }
    }

    /** How far {@code point} sits off the line {@code axis} seen from {@code origin}, in degrees. */
    public static double offBoresightDegrees(Vec3 origin, Vec3 axis, Vec3 point) {
        if (origin == null || axis == null || point == null) {
            return 0.0;
        }
        try {
            Vec3 relative = point.subtract(origin);
            if (relative.lengthSqr() < 1.0E-8 || axis.lengthSqr() < 1.0E-8) {
                return 0.0;
            }
            double dot = relative.normalize().dot(axis.normalize());
            return Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, dot))));
        } catch (Throwable ignored) {
            return 0.0;
        }
    }

    /** The seeker session a launcher is currently running, or null. */
    public static Session active(ServerLevel level, BlockPos launcher) {
        if (level == null || launcher == null) {
            return null;
        }
        Session session = SESSIONS.get(key(level, launcher));
        return session != null && session.level == level && !session.expired(level.getGameTime()) ? session : null;
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (SESSIONS.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Session> entry : SESSIONS.entrySet()) {
            Session session = entry.getValue();
            long now = session.level.getGameTime();
            if (session.expired(now)) {
                SESSIONS.remove(entry.getKey());
            } else if (now >= session.nextScan) {
                scan(session, now);
            }
        }
        HANDOVERS.entrySet().removeIf(entry -> entry.getValue().expired());
    }

    private record Pending(Lock lock, ServerLevel level, long deadline) {

        private boolean expired() {
            return this.level.getGameTime() > this.deadline;
        }
    }

    /** Looks for the contact closest to the middle of the search cone. */
    private static void scan(Session session, long now) {
        session.nextScan = now + SCAN_INTERVAL;
        ServerLevel level = session.level;
        boolean image = imageLockActive(level);
        double range = image ? IMAGE_SEARCH_RANGE : SEARCH_RANGE;
        Vec3 origin = session.origin();
        Vec3 axis = session.axis();
        Vec3 boresight = session.boresight();
        AABB own = session.ownBounds == null ? null : session.ownBounds.inflate(2.0);
        double bestDot = SEARCH_HALF_COS;
        double bestDistance = Double.MAX_VALUE;
        Lock best = null;
        session.lockSeen = false;
        double[] hit = new double[2];
        double[] held = new double[2];

        // An image lock only ever takes a Sable structure; the plain heat head
        // takes hostile ordnance and countermeasures as well.
        if (!image) {
            try {
                AABB query = AABB.ofSize(origin, range * 2.0, range * 2.0, range * 2.0);
                for (Entity entity : level.getEntities((Entity) null, query, InfraredMissileEntity::isSeekerTarget)) {
                    Vec3 centre = entity.getBoundingBox().getCenter();
                    if (own != null && own.contains(centre) || insideOwnGroup(session, centre)) {
                        continue;
                    }
                    // The contact the head already holds is only kept while it stays
                    // inside the off-axis frame; the narrow field of view is what
                    // takes a contact in the first place, never what keeps it.
                    if (session.lock != null && entity.getUUID().equals(session.lock.entity())) {
                        if (score(origin, boresight, centre, range, OFF_AXIS_HALF_COS, held)) {
                            session.lockSeen = true;
                            session.lock = new Lock(entity.getUUID(), null, null, centre);
                        }
                        continue;
                    }
                    if (!score(origin, axis, centre, range, SEARCH_HALF_COS, hit)) {
                        continue;
                    }
                    if (better(hit[0], hit[1], bestDot, bestDistance)) {
                        bestDot = hit[0];
                        bestDistance = hit[1];
                        best = new Lock(entity.getUUID(), null, null, centre);
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        try {
            SubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container != null) {
                for (SubLevel subLevel : container.getAllSubLevels()) {
                    if (subLevel == null || subLevel.isRemoved()) {
                        continue;
                    }
                    UUID id = subLevel.getUniqueId();
                    if (id.equals(session.ownStructure) || session.ownGroup.contains(id)) {
                        continue;
                    }
                    Vec3 centre = subLevel.boundingBox().toMojang().getCenter();
                    if (session.lock != null && id.equals(session.lock.subLevel())) {
                        if (score(origin, boresight, centre, range, OFF_AXIS_HALF_COS, held)) {
                            session.lockSeen = true;
                            session.lock = new Lock(null, id, null, centre);
                        }
                        continue;
                    }
                    if (!score(origin, axis, centre, range, SEARCH_HALF_COS, hit)) {
                        continue;
                    }
                    if (better(hit[0], hit[1], bestDot, bestDistance)) {
                        bestDot = hit[0];
                        bestDistance = hit[1];
                        best = new Lock(null, id, null, centre);
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        if (!image && ShaolibBridge.available()) {
            try {
                String dimension = level.dimension().location().toString();
                for (Object instance : ShaolibBridge.activeInstances()) {
                    if (instance == null || !ShaolibBridge.isTauOrHellfire(instance) || !ShaolibBridge.isAlive(instance)) {
                        continue;
                    }
                    if (!dimension.equals(ShaolibBridge.dimensionId(instance))) {
                        continue;
                    }
                    Vec3 centre = ShaolibBridge.position(instance);
                    if (centre == null) {
                        continue;
                    }
                    UUID id = ShaolibBridge.uuidFor(instance);
                    if (session.lock != null && id != null && id.equals(session.lock.shaolib())) {
                        if (score(origin, boresight, centre, range, OFF_AXIS_HALF_COS, held)) {
                            session.lockSeen = true;
                            session.lock = new Lock(null, null, id, centre);
                        }
                        continue;
                    }
                    if (!score(origin, axis, centre, range, SEARCH_HALF_COS, hit)) {
                        continue;
                    }
                    if (better(hit[0], hit[1], bestDot, bestDistance)) {
                        bestDot = hit[0];
                        bestDistance = hit[1];
                        best = new Lock(null, null, id, centre);
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        // The contact the fire control radar is locked onto outranks anything the
        // head found on its own: that is the one the gunner asked for, and the head
        // is already slaved onto it. While the radar keeps holding that contact the
        // track is fed straight from the radar, so it stays held even though the
        // narrow field of view would never have kept it.
        Lock cued = session.cued();
        if (cued != null && (!image || cued.subLevel() != null)
                && !insideOwnGroup(session, cued.position())
                && score(origin, boresight, cued.position(), range, OFF_AXIS_HALF_COS, held)
                && score(origin, axis, cued.position(), range, SEARCH_HALF_COS, hit)) {
            if (sameContact(cued, session.lock)) {
                session.lockSeen = true;
                session.lock = cued;
                session.lostSince = Long.MIN_VALUE;
                return;
            }
            // A designation that differs from what the head holds wins outright: the
            // head lets go of its own pick and works the cued contact instead. The dwell
            // timer is deliberately left alone here, because clearing it on every scan
            // restarts the two second wait forever and the designation is never taken.
            session.lock = null;
            session.lostSince = Long.MIN_VALUE;
            best = cued;
        }

        holdLock(session, best, now);
    }

    /**
     * Decides what one scan does to the lock. Both heads behave the same here:
     * a contact is released the moment it leaves the off-axis frame, and a fresh
     * contact has to stay inside the field of view for {@link #LOCK_DWELL_TICKS}
     * ticks before the head takes it. What image lock changes is what can be
     * taken and what the released round does about countermeasures.
     */
    private static void holdLock(Session session, Lock best, long now) {
        if (session.lock != null) {
            if (session.lockSeen) {
                session.lostSince = Long.MIN_VALUE;
                if (sameContact(best, session.lock)) {
                    session.lock = best;
                }
                return;
            }
            // A contact that steps outside the off-axis frame keeps its lock for a
            // moment before the head gives up on it.
            if (session.lostSince == Long.MIN_VALUE) {
                session.lostSince = now;
                return;
            }
            if (now - session.lostSince < LOCK_GRACE_TICKS) {
                return;
            }
            session.lock = null;
            session.lostSince = Long.MIN_VALUE;
            session.candidate = null;
            session.candidateSince = 0L;
        }
        if (best == null) {
            session.candidate = null;
            session.candidateSince = 0L;
            return;
        }
        if (!sameContact(best, session.candidate)) {
            session.candidate = best;
            session.candidateSince = now;
            return;
        }
        session.candidate = best;
        if (now - session.candidateSince < LOCK_DWELL_TICKS) {
            return;
        }
        session.lock = best;
        session.lostSince = Long.MIN_VALUE;
        FireControlCompat.LOGGER.info(
                "[firecontrolcompat] ir seeker locked launcher={} target={} pos={} dist={} image={}",
                session.launcher,
                best.entity() != null ? best.entity() : best.subLevel() != null ? best.subLevel() : best.shaolib(),
                best.position(), Math.sqrt(session.origin().distanceToSqr(best.position())),
                imageLockActive(session.level));
    }

    /** Re-reads the position of a locked contact, or null once it is gone for good. */
    private static Vec3 livePosition(ServerLevel level, Lock lock) {
        if (lock == null) {
            return null;
        }
        try {
            if (lock.entity() != null) {
                Entity entity = level.getEntity(lock.entity());
                return entity == null || !entity.isAlive() || entity.isRemoved()
                        ? null : entity.getBoundingBox().getCenter();
            }
            if (lock.subLevel() != null) {
                SubLevelContainer container = SubLevelContainer.getContainer(level);
                SubLevel subLevel = container == null ? null : container.getSubLevel(lock.subLevel());
                return subLevel == null || subLevel.isRemoved()
                        ? null : subLevel.boundingBox().toMojang().getCenter();
            }
            if (lock.shaolib() != null) {
                return ShaolibBridge.position(lock.shaolib());
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Whether both locks point at the same contact. */
    private static boolean sameContact(Lock first, Lock second) {
        return first != null && second != null
                && Objects.equals(first.entity(), second.entity())
                && Objects.equals(first.subLevel(), second.subLevel())
                && Objects.equals(first.shaolib(), second.shaolib());
    }

    /**
     * True while the computer has the head on image lock and the sky allows it.
     * The round the launcher releases while this reads true is told so, and that
     * round ignores countermeasures for the rest of its flight.
     */
    public static boolean imageLockActive(ServerLevel level) {
        return FireControlLeadSettings.imageLockEnabled() && imageLockUsable(level);
    }

    /**
     * Image lock is a daylight sight: it refuses to work at night, in rain or
     * in a thunderstorm, and it never runs in a dimension without a day.
     */
    public static boolean imageLockUsable(ServerLevel level) {
        return level != null && level.isDay() && !level.isRaining() && !level.isThundering();
    }

    /** Line the trigger press prints when it only opened the seeker. */
    public static String searchHint(ServerLevel level) {
        if (!FireControlLeadSettings.imageLockEnabled()) {
            return "红外导引头搜索中…";
        }
        return imageLockUsable(level) ? "图像锁定搜索中…" : "图像锁定：仅限白天晴朗";
    }

    /**
     * True when the point sits inside the cone and the range, filling dot and
     * distance. The cone is passed in, because taking a contact uses the narrow
     * field of view while keeping one uses the off-axis limit.
     */
    private static boolean score(Vec3 origin, Vec3 axis, Vec3 point, double range, double minCos, double[] out) {
        Vec3 relative = point.subtract(origin);
        double distanceSqr = relative.lengthSqr();
        if (distanceSqr > range * range || distanceSqr < 1.0E-8) {
            return false;
        }
        double distance = Math.sqrt(distanceSqr);
        double dot = relative.dot(axis) / distance;
        if (dot < minCos) {
            return false;
        }
        out[0] = dot;
        out[1] = distance;
        return true;
    }

    /** Closest to the middle of the cone wins, the nearer contact breaking a tie. */
    private static boolean better(double dot, double distance, double bestDot, double bestDistance) {
        return dot > bestDot + 1.0E-6 || Math.abs(dot - bestDot) <= 1.0E-6 && distance < bestDistance;
    }

    private static String key(ServerLevel level, BlockPos pos) {
        return level.dimension().location() + "|" + pos.asLong();
    }

    private static Vec3 project(ServerLevel level, Vec3 local) {
        try {
            return Sable.HELPER.projectOutOfSubLevel(level, local);
        } catch (Throwable ignored) {
            return local;
        }
    }

    private static Vec3 axis(ServerLevel level, Vec3 local, Direction facing, Vec3 origin) {
        Vec3 step = new Vec3(facing.getStepX(), facing.getStepY(), facing.getStepZ());
        try {
            Vec3 ahead = Sable.HELPER.projectOutOfSubLevel(level, local.add(step));
            Vec3 direction = ahead.subtract(origin);
            if (direction.lengthSqr() > 1.0E-8) {
                return direction.normalize();
            }
        } catch (Throwable ignored) {
        }
        return step;
    }

    /**
     * The direction {@code degrees} away from {@code from}, in the plane it shares with
     * {@code to}: how far the head gets when the contact it was told to look at sits
     * outside its travel. Both inputs are unit vectors.
     */
    private static Vec3 slew(Vec3 from, Vec3 to, double degrees) {
        Vec3 perpendicular = to.subtract(from.scale(to.dot(from)));
        if (perpendicular.lengthSqr() < 1.0E-8) {
            return from;
        }
        double angle = Math.toRadians(degrees);
        return from.scale(Math.cos(angle)).add(perpendicular.normalize().scale(Math.sin(angle)));
    }

    /** The launching body plus everything welded, jointed or docked to it. */
    private static Set<UUID> ownGroup(ServerLevel level, SubLevel own) {
        if (own == null) {
            return Set.of();
        }
        try {
            return PhysicsGroups.group(level, own.getUniqueId());
        } catch (Throwable ignored) {
            return Set.of(own.getUniqueId());
        }
    }

    /** Whether the point sits inside the firing rig, so the seeker never locks it. */
    private static boolean insideOwnGroup(Session session, Vec3 point) {
        if (session.ownGroup.isEmpty()) {
            return false;
        }
        try {
            for (UUID body : PhysicsGroups.bodiesContaining(session.level, point, 0.0)) {
                if (session.ownGroup.contains(body)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static SubLevel ownSubLevel(ServerLevel level, BlockPos pos) {
        try {
            SubLevel subLevel = Sable.HELPER.getContaining(level, pos);
            return subLevel == null || subLevel.isRemoved() ? null : subLevel;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** One launcher's running seeker. */
    public static final class Session {

        private final ServerLevel level;
        private final BlockPos launcher;
        /**
         * Muzzle in the launcher's own frame. The world-space muzzle and aim are
         * re-projected from it every tick, so a launcher on a turning bearing or a
         * moving ship carries its frames with it instead of leaving them behind at
         * the point the trigger was pressed.
         */
        private final Vec3 localMuzzle;
        private final Direction facing;
        /** Fire control computer this launcher answers to, for its radar lock. */
        private final BlockPos controllerPos;
        /** Contact the fire control radar holds, or null; the head is slaved to it. */
        private Lock cued;
        /** Tick the held contact was first seen outside the off-axis frame, or Long.MIN_VALUE. */
        private long lostSince = Long.MIN_VALUE;
        private Vec3 origin;
        /** Where the launcher itself points: the line the off-axis frame sits on. */
        private Vec3 boresight;
        /** Where the head is looking: the boresight, or the radar contact it turned to. */
        private Vec3 axis;
        /** World point the field of view frame is centred on, or null for the boresight. */
        private Vec3 fovAnchor;
        /** Tick the geometry above was last re-projected on. */
        private long geometryTick = Long.MIN_VALUE;
        private final long expiresAt;
        private final UUID ownStructure;
        private final AABB ownBounds;
        private final Set<UUID> ownGroup;
        private long nextScan;
        /** The contact being taken, and the tick it was first seen. */
        private Lock candidate;
        private long candidateSince;
        /** Set while the scan met the contact the plain heat head is riding. */
        private boolean lockSeen;
        private Lock lock;

        private Session(ServerLevel level, BlockPos launcher, BlockPos controllerPos, Vec3 localMuzzle,
                Direction facing, long expiresAt, long nextScan, SubLevel own, Set<UUID> ownGroup) {
            this.level = level;
            this.launcher = launcher;
            this.controllerPos = controllerPos;
            this.localMuzzle = localMuzzle;
            this.facing = facing;
            this.expiresAt = expiresAt;
            this.nextScan = nextScan;
            this.ownStructure = own == null ? null : own.getUniqueId();
            this.ownBounds = own == null ? null : own.boundingBox().toMojang();
            this.ownGroup = ownGroup;
            this.refreshGeometry();
        }

        /**
         * Re-projects the muzzle, the launcher's own aim and the direction the head is
         * looking into world space, once per tick.
         */
        private void refreshGeometry() {
            long now = this.level.getGameTime();
            if (now == this.geometryTick) {
                return;
            }
            this.geometryTick = now;
            Vec3 projected = project(this.level, this.localMuzzle);
            this.origin = projected;
            this.boresight = IrSeekerSession.axis(this.level, this.localMuzzle, this.facing, projected);
            this.cued = radarLockedContact();
            Vec3 cuedPoint = this.cued == null ? null : this.cued.position();
            Vec3 toCue = cuedPoint == null ? null : cuedPoint.subtract(projected);
            Vec3 look = this.boresight;
            this.fovAnchor = null;
            if (toCue != null && toCue.lengthSqr() > 1.0E-6) {
                Vec3 unit = toCue.normalize();
                if (unit.dot(this.boresight) >= OFF_AXIS_HALF_COS) {
                    // The head can reach the designation, so it looks straight at it and
                    // the frame is drawn on the contact itself while it can be taken.
                    look = unit;
                    this.fovAnchor = toCue.lengthSqr() <= SEARCH_RANGE * SEARCH_RANGE
                            ? cuedPoint
                            : projected.add(look.scale(FRAME_ANCHOR_DISTANCE));
                } else {
                    // Out of travel: the head slews as far as it goes and stops on the
                    // edge of the off-axis frame, still pointed at what the radar wants.
                    look = slew(this.boresight, unit, OFF_AXIS_HALF_ANGLE_DEGREES);
                    this.fovAnchor = projected.add(look.scale(FRAME_ANCHOR_DISTANCE));
                }
            }
            this.axis = look;
        }

        /**
         * The contact the fire control computer holds on its radar, if any. That can
         * be a Sable structure or, through the contact types this mod adds to the
         * radar, a missile; both come back as the same kind of lock the head takes
         * from its own scan. The position is re-read per tick so the slaved frames
         * follow the contact the radar is tracking.
         */
        private Lock radarLockedContact() {
            try {
                if (this.controllerPos == null
                        || !(this.level.getBlockEntity(this.controllerPos)
                                instanceof StabilizerControllerBlockEntity controller)) {
                    return null;
                }
                UUID id = controller.getRadarLockedSubLevelId();
                if (id == null && controller.isAircraftRadarMode()) {
                    id = controller.getAircraftSelectedTargetId();
                }
                if (id == null) {
                    return null;
                }
                StabilizerControllerBlockEntity.RadarContact contact = controller.getRadarContact(id);
                if (contact == null) {
                    return null;
                }
                Lock held = ShaolibBridge.isShaolibId(id)
                        ? new Lock(null, null, id, contact.center())
                        : contact.missile() ? new Lock(id, null, null, contact.center())
                                : new Lock(null, id, null, contact.center());
                Vec3 live = livePosition(this.level, held);
                return live == null ? null : new Lock(held.entity(), held.subLevel(), held.shaolib(), live);
            } catch (Throwable ignored) {
                return null;
            }
        }

        private boolean expired(long now) {
            return now > this.expiresAt;
        }

        /** World-space muzzle the frame is drawn from, re-projected every tick. */
        public Vec3 origin() {
            refreshGeometry();
            return this.origin;
        }

        /**
         * World-space centre line of the seeker: where the head is looking. That is the
         * launcher's own axis until the fire control radar designates a contact, and
         * then the line to that contact held inside the head's travel.
         */
        public Vec3 axis() {
            refreshGeometry();
            return this.axis;
        }

        /**
         * Where the launcher itself points. The off-axis frame is drawn along this line:
         * it is the limit of the head's travel measured from the launcher, so it must
         * not follow the head as the head turns.
         */
        public Vec3 boresight() {
            refreshGeometry();
            return this.boresight;
        }

        /**
         * World point the field of view frame is centred on: the contact the radar
         * designated while the head can reach it, the edge of its travel while it
         * cannot, or null while no designation is being worked - in which case the
         * frame simply follows the launcher's own line.
         */
        public Vec3 fovAnchor() {
            refreshGeometry();
            return this.fovAnchor;
        }

        /** The contact the fire control radar holds, or null while it holds none. */
        public Lock cued() {
            refreshGeometry();
            return this.cued;
        }

        /**
         * Where the locked contact sits right now, or null while nothing is locked
         * yet. The position is re-read on every call rather than once per scan, so
         * the lock frame the HUD draws keeps following a moving contact.
         */
        public Vec3 lockPosition() {
            Lock held = this.lock;
            if (held == null) {
                return null;
            }
            Vec3 live = livePosition(this.level, held);
            return live != null ? live : held.position();
        }

        /** Ticks the seeker still has left. */
        public long ticksLeft(long now) {
            return Math.max(0L, this.expiresAt - now);
        }
    }
}

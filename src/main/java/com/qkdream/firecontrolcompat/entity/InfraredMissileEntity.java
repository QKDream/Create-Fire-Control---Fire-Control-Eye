package com.qkdream.firecontrolcompat.entity;

import com.qkdream.firecontrolcompat.BeamLeadMark;
import com.qkdream.firecontrolcompat.BeamMissileRegistry;
import com.qkdream.firecontrolcompat.FireControlCompat;
import com.qkdream.firecontrolcompat.ShaolibBridge;
import com.qkdream.firecontrolcompat.TargetClassifier;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Infrared-homing proximity missile. No lock is required to launch: after
 * firing the seeker scans a 60 degree cone straight ahead for Sable
 * structures, mianbao / TAOV / vestalihy missiles, CBCMS bombs, rockets and
 * torpedoes, and mianbao countermeasures, then keeps riding the first
 * contact it acquires. Flight data: Mach 1 top speed (340 blocks/s), 45G of
 * overload, the standard warhead and a 5 block proximity fuse of its own.
 *
 * <p>Steering is lead pursuit: the round flies at the point its contact will
 * have reached by the time the round gets there, solved from the velocity the
 * contact is measured to have, so a crossing contact is met rather than
 * chased. The engine keeps running past the acceleration window so the round
 * holds Mach 1 for the whole flight instead of coasting down, and the round
 * lives long enough to cross the distance its launcher can see.</p>
 *
 * <p>A round released on an image lock is told what to hit and is blind to
 * countermeasures from that moment on, so a flare cannot pull it off the
 * structure it was fired at. Everything else about it is unchanged.</p>
 */
public class InfraredMissileEntity extends BeamRidingMissileEntity {

    protected static final double SEEKER_HALF_COS = Math.cos(Math.toRadians(30.0));
    protected static final double SEEKER_RANGE = 200.0;
    protected static final int SEEKER_START_TICK = 2;
    /** Mach 1 = 340 blocks/s = 17 blocks/tick. */
    private static final double IR_GUIDANCE_SPEED = 17.0;
    /** Infrared missile lateral overload rating. */
    private static final double IR_OVERLOAD_G = 45.0;
    /**
     * Proximity fuse range of the infrared missile, in blocks. It keeps a figure
     * of its own instead of following the computer's beam missile range: a round
     * released without a lock has to go off next to whatever the seeker caught,
     * whatever the crew dialled in for the beam riders.
     */
    private static final double IR_PROXIMITY_RADIUS = 5.0;
    /** Warhead strength of the infrared missile. */
    private static final float IR_EXPLOSION_STRENGTH = 12.0F;
    /** How quickly the measured contact velocity follows a change in the contact's motion. */
    private static final double LEAD_VELOCITY_SMOOTHING = 0.35;
    /** A position step larger than this is a teleport or a rebuild, not flight, blocks per tick. */
    private static final double LEAD_MAX_TARGET_SPEED = 6.0;
    /** A measured contact speed below this is noise rather than flight, blocks per tick. */
    private static final double LEAD_DEADBAND_SPEED = 0.06;
    /**
     * Most blocks of lead the solution may add ahead of the contact. A parked
     * contact reports a velocity that is noise around zero, and a long flight
     * time multiplied that noise into an aim point hundreds of blocks off the
     * contact, which is what sent rounds off into empty sky at still targets.
     */
    private static final double LEAD_MAX_LEAD_BLOCKS = 64.0;
    /**
     * Fraction of the range the lead may never exceed. Even a believable reading
     * is only a straight line extrapolation, so the meeting point is kept inside
     * the part of the sky the contact itself is in.
     */
    private static final double LEAD_MAX_LEAD_FRACTION = 0.5;
    /**
     * Ticks of contact travel one velocity reading covers. A contact reports its
     * position as the middle of a bounding box, and that centre jitters by a
     * fraction of a block as the plot box is re-transformed and the physics
     * settles: measured tick against tick that jitter reads as a speed, and every
     * tick of it is multiplied by the whole flight time. Averaging over a handful
     * of ticks leaves real travel untouched and drops the jitter out.
     */
    private static final int LEAD_VELOCITY_WINDOW_TICKS = 4;
    /** Ticks at the start of the flight whose seeker scans are always reported. */
    private static final int SEEKER_REPORT_START_TICKS = 6;
    /** Ticks between two seeker scan reports once the round is in flight. */
    private static final int SEEKER_REPORT_INTERVAL = 40;
    /** Ticks of the flight whose guidance is written out tick by tick. */
    private static final int TRACE_TICKS = 24;
    /** Ticks between the guidance reports of a flight that runs longer. */
    private static final int TRACE_INTERVAL = 40;
    /** Longest flight time the intercept solution looks ahead, in ticks (45 s). */
    private static final double LEAD_MAX_TICKS = 900.0;
    /** Fraction of the top speed the intercept solution assumes while the round is accelerating. */
    private static final double LEAD_CRUISE_FRACTION = 0.75;
    /**
     * Total flight time, in ticks (20 s). At Mach 1 that is several thousand blocks of
     * travel, so every shot inside the seeker range is covered, while a round that never
     * finds anything still clears the sky in twenty seconds instead of flying on and on.
     */
    private static final int IR_LIFETIME_TICKS = 400;
    /**
     * How long a round that already held a contact keeps searching after losing it, in
     * ticks (4 s). Past that it is a dud and detonates instead of wandering off.
     */
    private static final int TRACK_LOST_TICKS = 80;

    private UUID trackedEntityId;
    private UUID trackedSubLevelId;
    private UUID trackedShaolibId;
    /** Where the contact was on the last tick and how fast it is going, for the lead solution. */
    private Vec3 lastTrackPosition;
    private long lastTrackTick = Long.MIN_VALUE;
    private Vec3 trackVelocity = Vec3.ZERO;
    /** Set when the round was released on the launcher's image lock. */
    private boolean imageLocked;
    /** Set once the round has held a contact; losing one after that makes it a dud. */
    private boolean heldTrack;
    /** Tick the round first noticed its contact was gone, or Long.MIN_VALUE. */
    private long trackLostSince = Long.MIN_VALUE;
    /** Set once a failed Sable scan has been reported, so the log is written once. */
    private boolean seekerScanFailureLogged;

    public InfraredMissileEntity(EntityType<? extends AbstractArrow> type, Level level) {
        super(type, level);
    }

    @Override
    protected Item missileItem() {
        return BeamMissileRegistry.INFRARED_MISSILE.get();
    }

    @Override
    protected double guidanceSpeed() {
        return IR_GUIDANCE_SPEED;
    }

    @Override
    protected double guidanceOverloadG() {
        return IR_OVERLOAD_G;
    }

    /**
     * Mach 1 is held for the whole flight. Without this the arrow drag would take
     * the round down to a fraction of its speed within seconds of the engine
     * stopping, which is what made long shots fall short.
     */
    @Override
    protected boolean sustainsCruiseSpeed() {
        return true;
    }

    @Override
    protected int maxLifetimeTicks() {
        return IR_LIFETIME_TICKS;
    }

    @Override
    protected int maxAgeTicks() {
        return IR_LIFETIME_TICKS;
    }

    /**
     * Marks the round as released on the launcher's image lock: it keeps flying at
     * the contact it was handed and ignores countermeasures while it does.
     */
    public void setImageLock(boolean imageLock) {
        this.imageLocked = imageLock;
    }

    /**
     * Proximity fuse range of this round, captured once on the launch tick.
     * Fixed at {@link #IR_PROXIMITY_RADIUS} blocks, so the seeker's catch is
     * detonated on close pass rather than at whatever range the bound computer
     * set for its beam riders.
     */
    @Override
    protected double initialProximityRadius() {
        return IR_PROXIMITY_RADIUS;
    }

    @Override
    protected float explosionStrength() {
        return IR_EXPLOSION_STRENGTH;
    }

    @Override
    protected Vec3 trackedTargetPosition() {
        return currentTrack();
    }

    @Override
    protected Vec3 guidancePoint() {
        Vec3 current = currentTrack();
        if (current != null) {
            this.heldTrack = true;
            this.trackLostSince = Long.MIN_VALUE;
            Vec3 aim = interceptPoint(current);
            if (this.tickCount % 20 == 0) {
                FireControlCompat.LOGGER.info(
                        "[firecontrolcompat] ir missile track tick={} target={} aim={} trackVel={} delta={}",
                        this.tickCount, current, aim, this.trackVelocity, this.getDeltaMovement());
            }
            return aim;
        }
        clearTrack();
        if (this.getPersistentData().getDouble("fadongji1") < SEEKER_START_TICK) {
            return null;
        }
        Vec3 direction = forwardDirection();
        Vec3 acquired = direction == null ? null : acquireTarget(direction);
        if (acquired != null) {
            this.heldTrack = true;
            this.trackLostSince = Long.MIN_VALUE;
            return interceptPoint(acquired);
        }
        // A round that already had a contact and cannot find another one is a dud: it
        // gives up after a few seconds instead of flying on until its fuel runs out.
        if (this.heldTrack) {
            this.giveUpIfLost();
        }
        return null;
    }

    /** Detonates the round once it has searched for a fresh contact long enough. */
    private void giveUpIfLost() {
        if (this.level().isClientSide()) {
            return;
        }
        long now = this.level().getGameTime();
        if (this.trackLostSince == Long.MIN_VALUE) {
            this.trackLostSince = now;
            return;
        }
        if (now - this.trackLostSince >= TRACK_LOST_TICKS) {
            this.detonate(this.position(), "track-lost");
        }
    }

    private Vec3 currentTrack() {
        if (this.trackedEntityId != null) {
            Entity entity = this.level() instanceof ServerLevel serverLevel
                    ? serverLevel.getEntity(this.trackedEntityId) : null;
            if (entity == null || !entity.isAlive() || entity.isRemoved()) {
                this.trackedEntityId = null;
                forgetTrackMotion();
                return null;
            }
            return entity.getBoundingBox().getCenter();
        }
        if (this.trackedSubLevelId != null) {
            SubLevelContainer container = SubLevelContainer.getContainer(this.level());
            SubLevel subLevel = container == null ? null : container.getSubLevel(this.trackedSubLevelId);
            if (subLevel == null || subLevel.isRemoved()) {
                this.trackedSubLevelId = null;
                forgetTrackMotion();
                return null;
            }
            return subLevel.boundingBox().toMojang().getCenter();
        }
        if (this.trackedShaolibId != null) {
            Vec3 position = ShaolibBridge.position(this.trackedShaolibId);
            if (position == null || !ShaolibBridge.isAlive(this.trackedShaolibId)) {
                this.trackedShaolibId = null;
                forgetTrackMotion();
                return null;
            }
            return position;
        }
        return null;
    }

    /**
     * Hands the round the contact the launcher seeker locked before launch, so
     * the round flies at that contact instead of scanning for a new one.
     */
    public void presetSeekerTarget(UUID entity, UUID subLevel, UUID shaolib) {
        this.trackedEntityId = entity;
        this.trackedSubLevelId = subLevel;
        this.trackedShaolibId = shaolib;
        forgetTrackMotion();
    }

    private void clearTrack() {
        this.trackedEntityId = null;
        this.trackedSubLevelId = null;
        this.trackedShaolibId = null;
        forgetTrackMotion();
    }

    /** Drops the measured contact velocity, so a fresh contact starts its own solution. */
    private void forgetTrackMotion() {
        this.lastTrackPosition = null;
        this.lastTrackTick = Long.MIN_VALUE;
        this.trackVelocity = Vec3.ZERO;
    }

    /**
     * Lead pursuit: the round steers at the point the contact will have reached by
     * the time the round gets there, instead of at where the contact is now, so a
     * crossing contact is met head on rather than chased. The contact velocity is
     * measured tick by tick, because an entity, a Sable structure and a Shaolib
     * projectile each report their motion in their own way.
     */
    private Vec3 interceptPoint(Vec3 target) {
        updateTrackVelocity(target);
        if (this.trackVelocity.lengthSqr() < LEAD_DEADBAND_SPEED * LEAD_DEADBAND_SPEED) {
            // A contact that is holding still has no meeting point to solve for:
            // fly at the contact itself.
            return target;
        }
        Vec3 missile = this.position();
        // The round is still accelerating through the first five seconds of its
        // flight, so the current speed understates how far it will get: solved
        // with the current speed alone the round aims short of the meeting point
        // and ends up chasing a crossing contact.
        double speed = Math.max(this.guidanceSpeed() * LEAD_CRUISE_FRACTION, this.getDeltaMovement().length());
        if (!(speed > 1.0E-4) || !Double.isFinite(speed)) {
            return target;
        }
        double flightTime = target.distanceTo(missile) / speed;
        Vec3 aim = target;
        for (int pass = 0; pass < 3; pass++) {
            if (!Double.isFinite(flightTime)) {
                return target;
            }
            flightTime = Math.min(flightTime, LEAD_MAX_TICKS);
            Vec3 lead = this.trackVelocity.scale(flightTime);
            double leadLength = lead.length();
            double maxLead = Math.min(LEAD_MAX_LEAD_BLOCKS, target.distanceTo(missile) * LEAD_MAX_LEAD_FRACTION);
            if (leadLength > maxLead) {
                lead = lead.scale(maxLead / leadLength);
            }
            aim = target.add(lead);
            flightTime = aim.distanceTo(missile) / speed;
        }
        return aim;
    }

    /**
     * Measures the contact velocity over a window of ticks rather than tick by
     * tick, so the jitter of a bounding box centre cannot be read as travel.
     */
    private void updateTrackVelocity(Vec3 target) {
        long now = this.level().getGameTime();
        if (this.lastTrackPosition == null || now <= this.lastTrackTick) {
            this.lastTrackPosition = target;
            this.lastTrackTick = now;
            this.trackVelocity = Vec3.ZERO;
            return;
        }
        long elapsed = now - this.lastTrackTick;
        if (elapsed < LEAD_VELOCITY_WINDOW_TICKS) {
            return;
        }
        Vec3 step = target.subtract(this.lastTrackPosition).scale(1.0 / elapsed);
        this.lastTrackPosition = target;
        this.lastTrackTick = now;
        if (step.length() > LEAD_MAX_TARGET_SPEED) {
            this.trackVelocity = Vec3.ZERO;
            return;
        }
        this.trackVelocity = this.trackVelocity.scale(1.0 - LEAD_VELOCITY_SMOOTHING)
                .add(step.scale(LEAD_VELOCITY_SMOOTHING));
    }

    private Vec3 forwardDirection() {
        Vec3 velocity = this.getDeltaMovement();
        if (velocity.lengthSqr() > 1.0E-4) {
            return velocity.normalize();
        }
        Vec3 look = this.getViewVector(1.0F);
        return look.lengthSqr() > 1.0E-8 ? look.normalize() : null;
    }

    private Vec3 acquireTarget(Vec3 direction) {
        Vec3 origin = this.position();
        double bestDistanceSqr = Double.MAX_VALUE;
        Vec3 best = null;
        UUID bestEntity = null;
        UUID bestSubLevel = null;
        UUID bestShaolib = null;
        int entitiesInRange = 0;
        int subLevelsInRange = 0;
        int ownPlatformSkipped = 0;
        int ownPlatformSize = 0;
        double bestDot = -2.0;
        double bestDistance = -1.0;

        AABB query = AABB.ofSize(origin, SEEKER_RANGE * 2.0, SEEKER_RANGE * 2.0, SEEKER_RANGE * 2.0);
        for (Entity entity : this.level().getEntities(this, query, this::isIrSeekerTarget)) {
            Vec3 center = entity.getBoundingBox().getCenter();
            Vec3 relative = center.subtract(origin);
            double distanceSqr = relative.lengthSqr();
            if (distanceSqr > SEEKER_RANGE * SEEKER_RANGE || distanceSqr < 1.0E-8) {
                continue;
            }
            entitiesInRange++;
            double dot = relative.dot(direction) / Math.sqrt(distanceSqr);
            if (dot < SEEKER_HALF_COS) {
                continue;
            }
            if (distanceSqr < bestDistanceSqr) {
                bestDistanceSqr = distanceSqr;
                bestDot = dot;
                bestDistance = Math.sqrt(distanceSqr);
                best = center;
                bestEntity = entity.getUUID();
                bestSubLevel = null;
                bestShaolib = null;
            }
        }

        try {
            if (this.level() instanceof ServerLevel serverLevel) {
                SubLevelContainer container = SubLevelContainer.getContainer(serverLevel);
                if (container != null) {
                    // The whole connected platform is off limits, not just the one
                    // sub-level the launcher happens to sit in. A crew that has welded
                    // a hull, a turret and a rack into a single body would otherwise
                    // watch a free fired round turn onto a part of its own carrier,
                    // which is exactly what a round flying off at nothing looks like.
                    Set<UUID> ownPlatform = this.launchPlatformGroup();
                    ownPlatformSize = ownPlatform.size();
                    for (SubLevel subLevel : container.getAllSubLevels()) {
                        if (subLevel == null || subLevel.isRemoved()) {
                            continue;
                        }
                        if (ownPlatform.contains(subLevel.getUniqueId())
                                || this.launchSubLevelId != null
                                        && this.launchSubLevelId.equals(subLevel.getUniqueId())) {
                            ownPlatformSkipped++;
                            continue;
                        }
                        AABB box = subLevel.boundingBox().toMojang();
                        AABB launchZone = box.inflate(2.0);
                        if (this.launchSourcePos != null && launchZone.contains(Vec3.atCenterOf(this.launchSourcePos))
                                || this.launchPosition != null && launchZone.contains(this.launchPosition)) {
                            ownPlatformSkipped++;
                            continue;
                        }
                        subLevelsInRange++;
                        Vec3 center = box.getCenter();
                        Vec3 relative = center.subtract(origin);
                        double distanceSqr = relative.lengthSqr();
                        if (distanceSqr > SEEKER_RANGE * SEEKER_RANGE || distanceSqr < 1.0E-8) {
                            continue;
                        }
                        double dot = relative.dot(direction) / Math.sqrt(distanceSqr);
                        if (dot < SEEKER_HALF_COS) {
                            continue;
                        }
                        if (distanceSqr < bestDistanceSqr) {
                            bestDistanceSqr = distanceSqr;
                            bestDot = dot;
                            bestDistance = Math.sqrt(distanceSqr);
                            best = center;
                            bestSubLevel = subLevel.getUniqueId();
                            bestEntity = null;
                            bestShaolib = null;
                        }
                    }
                }
            }
        } catch (Throwable failure) {
            this.reportSeekerScanFailure(failure);
        }
        if (ShaolibBridge.available()) {
            String dimensionId = this.level().dimension().location().toString();
            for (Object instance : ShaolibBridge.activeInstances()) {
                if (instance == null || !ShaolibBridge.isTauOrHellfire(instance) || !ShaolibBridge.isAlive(instance)) {
                    continue;
                }
                if (!dimensionId.equals(ShaolibBridge.dimensionId(instance))) {
                    continue;
                }
                Vec3 center = ShaolibBridge.position(instance);
                if (center == null) {
                    continue;
                }
                Vec3 relative = center.subtract(origin);
                double distanceSqr = relative.lengthSqr();
                if (distanceSqr > SEEKER_RANGE * SEEKER_RANGE || distanceSqr < 1.0E-8) {
                    continue;
                }
                if (relative.dot(direction) / Math.sqrt(distanceSqr) < SEEKER_HALF_COS) {
                    continue;
                }
                if (distanceSqr < bestDistanceSqr) {
                    bestDistanceSqr = distanceSqr;
                    bestDot = relative.dot(direction) / Math.sqrt(distanceSqr);
                    bestDistance = Math.sqrt(distanceSqr);
                    best = center;
                    bestShaolib = ShaolibBridge.uuidFor(instance);
                    bestEntity = null;
                    bestSubLevel = null;
                }
            }
        }

        this.trackedEntityId = bestEntity;
        this.trackedSubLevelId = bestSubLevel;
        this.trackedShaolibId = bestShaolib;
        this.reportSeekerScan(origin, direction, entitiesInRange, subLevelsInRange, ownPlatformSkipped,
                ownPlatformSize, best, bestDot, bestDistance);
        if (best != null) {
            FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] ir missile acquired target entity={} sublevel={} shaolib={} dist={} pos={}",
                    bestEntity, bestSubLevel, bestShaolib, Math.sqrt(bestDistanceSqr), best);
        }
        return best;
    }

    /**
     * One line describing what the seeker saw. A report of a round that flew past
     * everything can then be read straight out of the log: how many contacts were
     * in range, how many were skipped as part of the launching platform, and how
     * far off the nose the best candidate sat.
     */
    private void reportSeekerScan(Vec3 origin, Vec3 direction, int entities, int subLevels, int ownPlatformSkipped,
            int ownPlatformSize, Vec3 best, double bestDot, double bestDistance) {
        if (this.level().isClientSide()
                || this.tickCount > SEEKER_REPORT_START_TICKS && this.tickCount % SEEKER_REPORT_INTERVAL != 0) {
            return;
        }
        FireControlCompat.LOGGER.info(
                "[firecontrolcompat] ir seeker scan tick={} origin={} dir={} entities={} sublevels={} ownPlatform={} ownPlatformSkipped={} best={} dot={} dist={}",
                this.tickCount, origin, direction, entities, subLevels, ownPlatformSize, ownPlatformSkipped, best,
                bestDot, bestDistance);
    }

    /** Writes a Sable scan that threw once, instead of quietly flying straight. */
    private void reportSeekerScanFailure(Throwable failure) {
        if (this.level().isClientSide() || this.seekerScanFailureLogged) {
            return;
        }
        this.seekerScanFailureLogged = true;
        FireControlCompat.LOGGER.warn("[firecontrolcompat] ir seeker sable scan failed", failure);
    }
    /**
     * Writes the flight out tick by tick while a report is being chased: the
     * position, the velocity the round is carrying, the aim it was handed that
     * tick, how far off that aim its velocity sits, and how much of the step it
     * asked for it actually covered. A round that visibly wanders has to be told
     * from one that is already pointed at its contact and got pushed off it.
     */
    @Override
    protected void traceGuidance(Vec3 aim, double engine) {
        if (this.level().isClientSide()
                || this.tickCount > TRACE_TICKS && this.tickCount % TRACE_INTERVAL != 0) {
            return;
        }
        Vec3 velocity = this.getDeltaMovement();
        double speed = velocity.length();
        double distance = aim == null ? -1.0 : aim.distanceTo(this.position());
        double off = -1.0;
        if (aim != null && speed > 1.0E-4 && distance > 1.0E-4) {
            Vec3 toAim = aim.subtract(this.position());
            off = Math.toDegrees(Math.acos(Mth.clamp(toAim.dot(velocity) / (distance * speed), -1.0, 1.0)));
        }
        Vec3 step = this.lastStep;
        double stepOff = -1.0;
        if (speed > 1.0E-4 && step.lengthSqr() > 1.0E-6) {
            stepOff = Math.toDegrees(Math.acos(Mth.clamp(step.dot(velocity) / (step.length() * speed), -1.0, 1.0)));
        }
        FireControlCompat.LOGGER.info(
                "[firecontrolcompat] ir flight tick={} pos={} vel={} speed={} moved={} planned={} step={} stepOff={}deg aim={} dist={} off={}deg inGround={} collided={} aa={} guided={} mark={} inside={}",
                this.tickCount, this.position(), velocity, speed, this.lastStepDistance, this.lastPlannedStep,
                step, stepOff,
                aim, distance, off, this.inGround, this.horizontalCollision || this.verticalCollision,
                this.fireControlAirDefenseGuided(),
                this.getPersistentData().getBoolean("CreateFireControlGuided"),
                this.getPersistentData().hasUUID(BeamLeadMark.TARGET_KEY), containedSubLevel());
    }

    /** Short id of the Sable sub-level this round currently sits inside, if any. */
    private String containedSubLevel() {
        try {
            SubLevel subLevel = Sable.HELPER.getContaining(this);
            return subLevel == null ? "none" : subLevel.getUniqueId().toString().substring(0, 8);
        } catch (Throwable ignored) {
            return "?";
        }
    }

    /**
     * What this round is allowed to take. A round released on an image lock was
     * already told what to hit, so it does not see countermeasures at all and a
     * flare cannot pull it off that contact.
     */
    private boolean isIrSeekerTarget(Entity entity) {
        if (!isSeekerTarget(entity)) {
            return false;
        }
        return !this.imageLocked || !isMianbaoCountermeasure(entity);
    }

    /**
     * Whether the infrared seeker may lock this contact: hostile ordnance and
     * countermeasures, never one of our own rounds. Shared with the pre-launch
     * seeker so the round and its launcher watch the same set of targets.
     */
    public static boolean isSeekerTarget(Entity entity) {
        if (entity == null || !entity.isAlive() || entity.isRemoved()) {
            return false;
        }
        EntityType<?> type = entity.getType();
        // RETIRED(ir-arm-rounds): the airborne infrared round is not registered any more.
        if (type == BeamMissileRegistry.INFRARED_TANSHE.get()) {
            return false;
        }
        return TargetClassifier.isCompatMissile(entity) || isMianbaoCountermeasure(entity);
    }

    static boolean isMianbaoCountermeasure(Entity entity) {
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        if (id == null || !"mianbaos_modernwarfare".equals(id.getNamespace())) {
            return false;
        }
        String path = id.getPath().toLowerCase(Locale.ROOT);
        if (path.contains("countermeasure")) {
            return true;
        }
        for (Class<?> type = entity.getClass(); type != null; type = type.getSuperclass()) {
            if (type.getSimpleName().contains("Countermeasure")) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected boolean countsAsFuseEntity(Entity entity) {
        return super.countsAsFuseEntity(entity) || isMianbaoCountermeasure(entity);
    }
}

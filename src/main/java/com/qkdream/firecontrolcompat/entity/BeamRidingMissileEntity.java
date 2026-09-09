package com.qkdream.firecontrolcompat.entity;

import com.hooya.stabilizedturret.network.MissileRemoteStatePayload;
import com.qkdream.firecontrolcompat.BeamMissileRegistry;
import com.qkdream.firecontrolcompat.FireControlCompat;
import com.qkdream.firecontrolcompat.FireControlLeadSettings;
import com.qkdream.firecontrolcompat.ShaolibBridge;
import com.qkdream.firecontrolcompat.network.MissileTrackPayload;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.mcreator.myfirstmod.entity.Laserpoint4Entity;
import net.mcreator.myfirstmod.init.MianbaosModernwarfareModParticleTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Entity.RemovalReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.ItemSupplier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.Level.ExplosionInteraction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Beam-riding proximity missile for fire-control.
 *
 * <p>Guidance rides the nearest {@link Laserpoint4Entity} spawned by the
 * laser designator. Steering rotates the velocity vector toward the target
 * by at most the turn rate implied by the missile's lateral overload rating,
 * so the flight path stays a smooth curve instead of snapping. The proximity
 * fuse is a port of the autocannon revolution
 * {@code UniversalProximityFuzeItem} and detonates against Sable structures,
 * mianbao / vestalihy / CBCMS entity ammunition and TAOV Shaolib Tau /
 * Hellfire projectiles.</p>
 */
public class BeamRidingMissileEntity extends AbstractArrow implements ItemSupplier {

    public static final double PROXIMITY_RADIUS = 10.0;
    public static final double PROXIMITY_ARM_DISTANCE = 12.0;
    public static final double CONTACT_ARM_DISTANCE = 5.0;
    public static final int MAX_LIFETIME_TICKS = 600;
    /** 5 second powered acceleration phase: speed ramps linearly from launch to guidance top speed. */
    private static final int ACCEL_TICKS = 100;
    private static final double BEAM_SEARCH_RANGE = 800.0;
    private static final double GUIDANCE_SPEED = 34.0;
    /** Beam missile lateral overload rating. */
    private static final double GUIDANCE_OVERLOAD_G = 30.0;
    private static final int STEERING_START_TICK = 2;
    /** Launch boost: for the first 0.5s the missile may turn at least this much per tick (>= 120 degrees total). */
    private static final int BOOST_TURN_TICKS = 10;
    private static final double BOOST_TURN_RADIANS_PER_TICK = Math.toRadians(20.0);
    /** Axial speed cap during the 0.5s boost window: 10 ticks x 0.3 blocks = at most 3 blocks flown. */
    private static final double BOOST_MAX_SPEED = 0.3;

    protected Vec3 launchPosition;
    protected BlockPos launchSourcePos;
    protected UUID launchSubLevelId;
    private double launchSpeed = -1.0;
    /** Vertical (boost) or horizontal (direct acceleration) launch mode snapshot. */
    private boolean verticalLaunch = true;
    /** Proximity fuse range snapshot taken from the fire control computer setting. */
    private double proximityRadius = PROXIMITY_RADIUS;
    private boolean launchSettingsCaptured;

    /** Guidance top speed in blocks per tick. Beam missile: 34 (680 blocks/s, Mach 2). */
    protected double guidanceSpeed() {
        return GUIDANCE_SPEED;
    }

    /** Lateral overload available to the guidance, in G. */
    protected double guidanceOverloadG() {
        return GUIDANCE_OVERLOAD_G;
    }

    /** Proximity fuse range captured once at launch; overridable per missile. */
    protected double initialProximityRadius() {
        return FireControlLeadSettings.beamProximityRange();
    }

    public BeamRidingMissileEntity(EntityType<? extends AbstractArrow> type, Level level) {
        super(type, level);
        this.setPickupItemStack(new ItemStack(this.missileItem()));
    }

    public void markLaunchSource(BlockPos sourcePos) {
        this.launchSourcePos = sourcePos;
    }

    /** Sublevel that contains the launching rack, so the seeker never locks the carrier aircraft. */
    public void markLaunchSubLevel(UUID subLevelId) {
        this.launchSubLevelId = subLevelId;
    }

    /**
     * Missiles must keep ticking even when their section lies outside the
     * entity-ticking radius. Without this, one fired from an aircraft rack
     * parked away from the player is added to a hidden section and never
     * ticks at all, which looks like the missile silently vanishing.
     */
    @Override
    public boolean isAlwaysTicking() {
        return true;
    }

    /**
     * Long-range visibility is driven by fire control's missile remote state
     * proxy, so the native arrow render distance cap (about 64 blocks) must
     * not hide either the tracked entity or the client proxy.
     */
    @Override
    public boolean shouldRenderAtSqrDistance(double distanceSqr) {
        return true;
    }

    protected Item missileItem() {
        return BeamMissileRegistry.BEAM_RIDING_MISSILE.get();
    }

    @Override
    protected ItemStack getDefaultPickupItem() {
        return new ItemStack(this.missileItem());
    }

    @Override
    public ItemStack getItem() {
        return new ItemStack(this.missileItem());
    }

    @Override
    public void tick() {
        // Remote visual proxies are moved exclusively by fire control's
        // MissileLongRangeEffects extrapolation. Self-ticking them applies the
        // velocity a second time, which swings the rendered position off the
        // real flight path and tears the trail into straight lines on turns.
        if (this.level().isClientSide() && this.getPersistentData().getBoolean("CreateFireControlRemoteVisual")) {
            return;
        }
        if (!this.level().isClientSide() && this.getPersistentData().getDouble("fadongji1") <= 2.0) {
            FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] missile tick begin type={} ticks={} pos={} delta={} removed={} added={}",
                    BuiltInRegistries.ENTITY_TYPE.getKey(this.getType()), this.tickCount, this.position(),
                    this.getDeltaMovement(), this.isRemoved(), this.isAddedToLevel());
        }
        if (this.launchPosition == null && !this.level().isClientSide()) {
            this.launchPosition = this.position();
        }
        if (this.launchSpeed < 0.0 && !this.level().isClientSide()) {
            this.launchSpeed = this.getDeltaMovement().length();
        }
        if (!this.launchSettingsCaptured && !this.level().isClientSide()) {
            this.launchSettingsCaptured = true;
            this.verticalLaunch = FireControlLeadSettings.beamVerticalLaunch();
            this.proximityRadius = this.initialProximityRadius();
        }
        // Keep the beam missile crawling during its 0.5s boost window so the
        // whole window covers at most 3 blocks, including the launch tick.
        if (this.hasLaunchBoost() && this.getPersistentData().getDouble("fadongji1") < BOOST_TURN_TICKS) {
            Vec3 boostVelocity = this.getDeltaMovement();
            double boostSpeed = boostVelocity.length();
            if (boostSpeed > BOOST_MAX_SPEED) {
                this.setDeltaMovement(boostVelocity.scale(BOOST_MAX_SPEED / boostSpeed));
            }
        }
        Vec3 beforeTick = this.position();
        Vec3 beforeDelta = this.getDeltaMovement();
        super.tick();

        // Sable's BlockGetter#clip overwrite returns sublevel-local hit
        // coordinates when the travel ray crosses a Sable structure, which
        // teleports the projectile to the structure plot. Recover from any
        // implausible single-tick jump by advancing with our own velocity.
        double maxMoveSqr = Math.max(beforeDelta.lengthSqr() * 4.0, 64.0);
        if (this.position().distanceToSqr(beforeTick) > maxMoveSqr) {
            this.setPos(beforeTick.add(beforeDelta));
            this.setDeltaMovement(beforeDelta);
            this.inGround = false;
        }
        if (this.inGround) {
            FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] missile inGround discard type={} ticks={} pos={}",
                    BuiltInRegistries.ENTITY_TYPE.getKey(this.getType()), this.tickCount, this.position());
            this.discard();
            return;
        }

        double engine = this.getPersistentData().getDouble("fadongji1") + 1.0;
        this.getPersistentData().putDouble("fadongji1", engine);
        this.setNoGravity(true);

        // With fire control present the client proxy trail is the single
        // long-range trail source; native tick particles would double it
        // and their client copy drifts one tick ahead of the real path.
        if (!this.level().isClientSide() && !ModList.get().isLoaded("create_fire_control")) {
            if (engine <= 15.0) {
                spawnTrailParticles((SimpleParticleType) MianbaosModernwarfareModParticleTypes.ROCKETMISSILETRAIL_3.get(),
                        beforeTick, this.position(), 2.0, 10);
                spawnTrailParticles((SimpleParticleType) MianbaosModernwarfareModParticleTypes.NEWSMOKE_2.get(),
                        beforeTick, this.position(), 4.0, 5);
            }
            if (engine <= 150.0) {
                spawnTrailParticles((SimpleParticleType) MianbaosModernwarfareModParticleTypes.COUNTERMEASURETRAIL_1.get(),
                        beforeTick, this.position(), 6.0, 4);
            }
        }

        if (!this.level().isClientSide() && engine <= 6.0
                && this.getType() == BeamMissileRegistry.AIRCRAFT_INFRARED_TANSHE.get()) {
            FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] aircraft ir missile tick={} engine={} pos={} delta={}",
                    this.tickCount, engine, this.position(), this.getDeltaMovement());
        }

        if (this.level().isClientSide()) {
            return;
        }
        if (this.tickCount > 1200 || !this.level().getWorldBorder().isWithinBounds(this.position())
                || this.getY() < this.level().getMinBuildHeight() - 64
                || this.getY() > this.level().getMaxBuildHeight() + 64) {
            FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] missile bounds discard type={} ticks={} pos={}",
                    BuiltInRegistries.ENTITY_TYPE.getKey(this.getType()), this.tickCount, this.position());
            this.discard();
            return;
        }

        if (!this.fireControlAirDefenseGuided() && this.hasLaunchBoost() && engine <= BOOST_TURN_TICKS) {
            Vec3 velocity = this.getDeltaMovement();
            double speed = velocity.length();
            if (speed > BOOST_MAX_SPEED) {
                this.setDeltaMovement(velocity.scale(BOOST_MAX_SPEED / speed));
            }
        } else if (!this.fireControlAirDefenseGuided() && engine <= ACCEL_TICKS) {
            double desired = this.accelerationTargetSpeed(engine);
            Vec3 velocity = this.getDeltaMovement();
            double speed = velocity.length();
            if (speed > 1.0E-4 && speed < desired) {
                this.setDeltaMovement(velocity.scale(desired / speed));
            }
        }

        if (!this.getPersistentData().getBoolean("CreateFireControlGuided")) {
            Vec3 point = this.guidancePoint();
            if (point != null) {
                this.steerTowardPoint(point, engine);
            }
        }

        this.broadcastRemoteState();
        this.broadcastTrackState();

        Vec3 to = this.position();
        Vec3 from = to.subtract(this.getDeltaMovement());
        Vec3 armedFrom = this.clipSegmentPastArmDistance(from, to);
        if (armedFrom != null) {
            ProximityHit hit = this.findProximityTarget(armedFrom, to);
            if (hit != null) {
                this.detonate(hit.position(), hit.cause);
                return;
            }
        }

        if (engine > MAX_LIFETIME_TICKS) {
            this.detonate(to, "lifetime");
            return;
        }
    }

    /** Whether this missile has the 0.5s low-speed high-agility launch boost (beam-riding missile only). */
    protected boolean hasLaunchBoost() {
        return this.getType() == BeamMissileRegistry.BEAMRIDER_TANSHE.get() && this.verticalLaunch;
    }

    /** Whether fire control's air-defense guidance has adopted this missile. */
    protected boolean fireControlAirDefenseGuided() {
        return this.getPersistentData().getBoolean("CreateFireControlAaGuided")
                || this.getPersistentData().getBoolean("CreateFireControlDisplayGuided");
    }

    @Override
    public void remove(RemovalReason reason) {
        if (!this.level().isClientSide() && !this.isRemoved()) {
            FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] missile remove type={} reason={} ticks={} pos={}",
                    BuiltInRegistries.ENTITY_TYPE.getKey(this.getType()), reason, this.tickCount, this.position());
        }
        super.remove(reason);
    }

    @Override
    public void onRemovedFromLevel() {
        if (!this.level().isClientSide()) {
            FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] missile removed-from-level type={} ticks={} pos={}",
                    BuiltInRegistries.ENTITY_TYPE.getKey(this.getType()), this.tickCount, this.position());
        }
        super.onRemovedFromLevel();
    }

    /**
     * Mirrors fire control's {@code MianbaoMissileRemoteSync}: every tick the
     * server streams the world-space position and velocity to nearby players
     * through fire control's own {@link MissileRemoteStatePayload} channel.
     * The fire control client then spawns a no-physics proxy of this entity
     * type, which renders the model, the minimap dot and the trail far beyond
     * the native entity tracking range.
     */
    private void broadcastRemoteState() {
        if (!(this.level() instanceof ServerLevel serverLevel) || !ModList.get().isLoaded("create_fire_control")) {
            return;
        }
        try {
            Vec3 worldPosition = Sable.HELPER.projectOutOfSubLevel(this.level(), this.position());
            Vec3 velocity = this.getDeltaMovement();
            MissileRemoteStatePayload payload = new MissileRemoteStatePayload(
                    this.getUUID(),
                    BuiltInRegistries.ENTITY_TYPE.getKey(this.getType()),
                    worldPosition.x, worldPosition.y, worldPosition.z,
                    velocity.x, velocity.y, velocity.z);
            for (ServerPlayer player : serverLevel.players()) {
                if (player.position().distanceToSqr(worldPosition) <= 4.0E8) {
                    PacketDistributor.sendToPlayer(player, payload);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** Position the seeker is currently tracking, or null when it has no target. */
    protected Vec3 trackedTargetPosition() {
        return null;
    }

    /**
     * Streams the seeker's current target to nearby players so the fire
     * control seat HUD can draw a red square around it. Only missiles with an
     * active track send anything; a lost track simply times out on the client.
     */
    private void broadcastTrackState() {
        if (!(this.level() instanceof ServerLevel serverLevel) || !ModList.get().isLoaded("create_fire_control")) {
            return;
        }
        Vec3 track = this.trackedTargetPosition();
        if (track == null) {
            return;
        }
        try {
            Vec3 worldTrack = Sable.HELPER.projectOutOfSubLevel(this.level(), track);
            Vec3 worldMissile = Sable.HELPER.projectOutOfSubLevel(this.level(), this.position());
            MissileTrackPayload payload = new MissileTrackPayload(this.getUUID(), worldTrack);
            for (ServerPlayer player : serverLevel.players()) {
                if (player.position().distanceToSqr(worldMissile) <= 4.0E8) {
                    PacketDistributor.sendToPlayer(player, payload);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Beam source for the steering law. Ground missiles ride the nearest
     * laser designator point; the air-launched variant rides the electro
     * optical pod beam.
     */
    protected Vec3 guidancePoint() {
        List<Laserpoint4Entity> points = this.level()
                .getEntitiesOfClass(Laserpoint4Entity.class,
                        AABB.ofSize(this.position(), BEAM_SEARCH_RANGE, BEAM_SEARCH_RANGE, BEAM_SEARCH_RANGE), e -> true);
        Laserpoint4Entity target = null;
        double bestDistanceSqr = Double.MAX_VALUE;
        for (Laserpoint4Entity point : points) {
            double distanceSqr = point.distanceToSqr(this);
            if (distanceSqr < bestDistanceSqr) {
                bestDistanceSqr = distanceSqr;
                target = point;
            }
        }
        return target == null ? null : target.position();
    }

    private void steerTowardPoint(Vec3 target, double engine) {
        Vec3 velocity = this.getDeltaMovement();
        double speed = velocity.length();
        double dx = target.x - this.getX();
        double dy = target.y - this.getY();
        double dz = target.z - this.getZ();
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance < 1.0E-4 || speed < 1.0E-4) {
            return;
        }
        double cosine = Mth.clamp((dx * velocity.x + dy * velocity.y + dz * velocity.z) / (distance * speed), -1.0, 1.0);
        double angle = Math.acos(cosine);
        if (engine <= STEERING_START_TICK || angle <= 1.0E-4) {
            return;
        }
        // Overload G means a lateral acceleration of G * 9.8 blocks/s^2, which
        // caps the per-tick velocity rotation at (G * 9.8 / 400) / speed
        // radians. The velocity direction is slerped toward the target by at
        // most that angle, so hard target passes become smooth arcs.
        double maxTurnRadians = this.guidanceOverloadG() * 9.8 / 400.0 / speed;
        if (this.hasLaunchBoost() && engine <= BOOST_TURN_TICKS) {
            maxTurnRadians = Math.max(maxTurnRadians, BOOST_TURN_RADIANS_PER_TICK);
        }
        double fraction = Math.min(1.0, maxTurnRadians / angle);
        double sinAngle = Math.sin(angle);
        double fromWeight = Math.sin((1.0 - fraction) * angle) / sinAngle;
        double toWeight = Math.sin(fraction * angle) / sinAngle;
        Vec3 velocityDirection = velocity.scale(1.0 / speed);
        Vec3 targetDirection = new Vec3(dx / distance, dy / distance, dz / distance);
        Vec3 newDirection = velocityDirection.scale(fromWeight).add(targetDirection.scale(toWeight));
        this.setDeltaMovement(newDirection.normalize().scale(speed));
    }

    /** Speed the missile should have after {@code engine} ticks of its 5 second acceleration phase. */
    private double accelerationTargetSpeed(double engine) {
        double topSpeed = this.guidanceSpeed();
        if (this.hasLaunchBoost()) {
            // After the boost window the ramp starts from the crawl speed.
            double rampTicks = ACCEL_TICKS - BOOST_TURN_TICKS;
            double progress = Math.min(1.0, (engine - BOOST_TURN_TICKS) / rampTicks);
            return BOOST_MAX_SPEED + (topSpeed - BOOST_MAX_SPEED) * progress;
        }
        double start = this.launchSpeed > 0.0 ? this.launchSpeed : topSpeed;
        if (engine >= ACCEL_TICKS) {
            return topSpeed;
        }
        return start + (topSpeed - start) * engine / ACCEL_TICKS;
    }

    /** Scatters trail particles along the segment the missile covered this tick so fast missiles leave a solid trail. */
    private void spawnTrailParticles(SimpleParticleType particle, Vec3 from, Vec3 to, double gap, int maxPoints) {
        double distance = from.distanceTo(to);
        int points = (int) Math.min(maxPoints, Math.max(1.0, distance / gap));
        for (int i = 1; i <= points; i++) {
            double t = (double) i / (double) points;
            this.level().addParticle(particle,
                    Mth.lerp(t, from.x, to.x),
                    Mth.lerp(t, from.y, to.y),
                    Mth.lerp(t, from.z, to.z),
                    Mth.nextDouble(this.getRandom(), -0.5, 0.5),
                    Mth.nextDouble(this.getRandom(), -0.5, 0.5),
                    Mth.nextDouble(this.getRandom(), -0.5, 0.5));
        }
    }

    @Override
    protected void onHitEntity(EntityHitResult result) {
        if (this.plausibleHit(result.getLocation()) && this.contactArmed()) {
            this.detonate(this.position(), "contact-entity");
        }
    }

    @Override
    protected void onHitBlock(BlockHitResult result) {
        if (this.plausibleHit(result.getLocation()) && this.contactArmed()) {
            this.detonate(this.position(), "contact-block");
        }
    }

    /**
     * Sable hands arrows sublevel-local hit coordinates for structure blocks,
     * which land far away from the real travel segment. Only detonate on hits
     * that actually lie on this tick's movement path.
     */
    private boolean plausibleHit(Vec3 hitLocation) {
        Vec3 from = this.position();
        Vec3 to = from.add(this.getDeltaMovement());
        return closestPointOnSegment(from, to, hitLocation).distanceToSqr(hitLocation) <= 4.0;
    }

    public void detonate(Vec3 position, String cause) {
        if (this.level().isClientSide()) {
            return;
        }
        FireControlCompat.LOGGER.info("[firecontrolcompat] beam missile detonated cause={} pos={} delta={} ticks={}",
                cause, position, this.getDeltaMovement(), this.tickCount);
        Level level = this.level();
        level.playSound(null, BlockPos.containing(position),
                (SoundEvent) BuiltInRegistries.SOUND_EVENT.get(ResourceLocation.parse("mianbaos_modernwarfare:missile_explode")),
                SoundSource.NEUTRAL, 8.0F, (float) Mth.nextDouble(RandomSource.create(), 0.8, 1.0));
        level.playSound(null, BlockPos.containing(position),
                (SoundEvent) BuiltInRegistries.SOUND_EVENT.get(ResourceLocation.parse("mianbaos_modernwarfare:explode_far_sound3")),
                SoundSource.NEUTRAL, 32.0F, (float) Mth.nextDouble(RandomSource.create(), 0.7, 1.0));
        if (level instanceof ServerLevel serverLevel) {
            serverLevel.explode(null, position.x, position.y, position.z,
                    this.explosionStrength(), ExplosionInteraction.BLOCK);
        }
        this.discard();
    }

    /** Explosion strength used when this missile detonates. */
    protected float explosionStrength() {
        return 12.0F;
    }

    private ProximityHit findProximityTarget(Vec3 segStart, Vec3 segEnd) {
        Vec3 target;
        // Fire control's AA guidance owns the Sable structure fuse; the local
        // fuse keeps detonating on entity / Shaolib munitions only.
        if (!this.fireControlAirDefenseGuided()) {
            target = this.findSableTarget(segStart, segEnd);
            if (target != null) {
                return new ProximityHit(target, "sable");
            }
        }
        target = this.findEntityTarget(segStart, segEnd);
        if (target != null) {
            return new ProximityHit(target, "entity");
        }
        target = this.findShaolibTarget(segStart, segEnd);
        return target != null ? new ProximityHit(target, "shaolib") : null;
    }

    private boolean contactArmed() {
        return this.launchPosition != null
                && this.position().distanceToSqr(this.launchPosition) >= CONTACT_ARM_DISTANCE * CONTACT_ARM_DISTANCE;
    }

    /** Trims the travel segment so the fuze never scans closer than the arm distance to the launcher. */
    private Vec3 clipSegmentPastArmDistance(Vec3 segStart, Vec3 segEnd) {
        if (this.launchPosition == null) {
            return null;
        }
        double armSqr = PROXIMITY_ARM_DISTANCE * PROXIMITY_ARM_DISTANCE;
        Vec3 startOffset = segStart.subtract(this.launchPosition);
        if (startOffset.lengthSqr() >= armSqr) {
            return segStart;
        }
        Vec3 direction = segEnd.subtract(segStart);
        double a = direction.lengthSqr();
        double b = 2.0 * startOffset.dot(direction);
        double c = startOffset.lengthSqr() - armSqr;
        if (a < 1.0E-8) {
            return null;
        }
        double discriminant = b * b - 4.0 * a * c;
        if (discriminant < 0.0) {
            return null;
        }
        double t = (-b + Math.sqrt(discriminant)) / (2.0 * a);
        if (t < 0.0 || t > 1.0) {
            return null;
        }
        return segStart.add(direction.scale(t));
    }

    private record ProximityHit(Vec3 position, String cause) {
    }

    private Vec3 findSableTarget(Vec3 segStart, Vec3 segEnd) {
        if (!(this.level() instanceof ServerLevel serverLevel)) {
            return null;
        }
        try {
            ServerSubLevelContainer container = SubLevelContainer.getContainer(serverLevel);
            if (container == null) {
                return null;
            }
            for (SubLevel subLevel : container.getAllSubLevels()) {
                if (subLevel == null || subLevel.isRemoved()) {
                    continue;
                }
                AABB box = subLevel.boundingBox().toMojang();
                AABB launchZone = box.inflate(2.0);
                if (this.launchSourcePos != null && launchZone.contains(Vec3.atCenterOf(this.launchSourcePos))
                        || this.launchPosition != null && launchZone.contains(this.launchPosition)) {
                    continue;
                }
            if (segmentDistanceSqrToBox(segStart, segEnd, box) <= this.proximityRadius * this.proximityRadius) {
                    return closestPointOnAABB(box, this.position());
                }
            }
        } catch (Throwable t) {
            FireControlCompat.LOGGER.debug("[firecontrolcompat] sable proximity scan failed", t);
        }
        return null;
    }

    private Vec3 findEntityTarget(Vec3 segStart, Vec3 segEnd) {
        AABB searchBox = new AABB(segStart, segEnd).inflate(Math.max(this.proximityRadius, 4.0));
        double bestAlongSqr = Double.MAX_VALUE;
        Vec3 bestPosition = null;
        for (Entity entity : this.level().getEntities(this, searchBox, e -> e != this && !e.isRemoved())) {
            if (entity.getType() == BeamMissileRegistry.BEAMRIDER_TANSHE.get()
                    || entity.getType() == BeamMissileRegistry.INFRARED_TANSHE.get()
                    || entity.getType() == BeamMissileRegistry.AIRCRAFT_INFRARED_TANSHE.get()
                    || !this.countsAsFuseEntity(entity)) {
                continue;
            }
            Vec3 missileEnd = entity.position();
            Vec3 missileStart = missileEnd.subtract(entity.getDeltaMovement());
            Vec3 closestOnShell = closestPointBetweenSegments(segStart, segEnd, missileStart, missileEnd);
            Vec3 closestOnMissile = closestPointOnSegment(missileStart, missileEnd, closestOnShell);
            if (closestOnShell.distanceToSqr(closestOnMissile) <= this.proximityRadius * this.proximityRadius) {
                double alongSqr = closestOnShell.distanceToSqr(segStart);
                if (alongSqr < bestAlongSqr) {
                    bestAlongSqr = alongSqr;
                    bestPosition = closestOnShell;
                }
            }
        }
        return bestPosition;
    }

    private Vec3 findShaolibTarget(Vec3 segStart, Vec3 segEnd) {
        if (!ShaolibBridge.available()) {
            return null;
        }
        String dimensionId = this.level().dimension().location().toString();
        double bestAlongSqr = Double.MAX_VALUE;
        Vec3 bestPosition = null;
        for (Object instance : ShaolibBridge.activeInstances()) {
            if (instance == null || !ShaolibBridge.isTauOrHellfire(instance) || !ShaolibBridge.isAlive(instance)) {
                continue;
            }
            if (!dimensionId.equals(ShaolibBridge.dimensionId(instance))) {
                continue;
            }
            Vec3 missileEnd = ShaolibBridge.position(instance);
            if (missileEnd == null) {
                continue;
            }
            Vec3 missileStart = missileEnd.subtract(ShaolibBridge.velocity(instance));
            Vec3 closestOnShell = closestPointBetweenSegments(segStart, segEnd, missileStart, missileEnd);
            Vec3 closestOnMissile = closestPointOnSegment(missileStart, missileEnd, closestOnShell);
            if (closestOnShell.distanceToSqr(closestOnMissile) <= this.proximityRadius * this.proximityRadius) {
                double alongSqr = closestOnShell.distanceToSqr(segStart);
                if (alongSqr < bestAlongSqr) {
                    bestAlongSqr = alongSqr;
                    bestPosition = closestOnShell;
                }
            }
        }
        return bestPosition;
    }

    /** Whether the proximity fuze should detonate against the given entity. */
    protected boolean countsAsFuseEntity(Entity entity) {
        return isMissileEntity(entity);
    }

    protected static boolean isMissileEntity(Entity entity) {
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        String namespace = id == null ? "" : id.getNamespace();
        String key = id == null ? "" : id.getPath();
        if ("vestalihy".equals(namespace)) {
            return "ptur".equals(key) || "tow".equals(key) || "ptur_jet".equals(key) || "malytka".equals(key);
        }
        String lower = (namespace + ":" + key).toLowerCase(Locale.ROOT);
        boolean mianbao = lower.startsWith("mianbaos_modernwarfare:") || lower.contains("tanshe");
        if (mianbao) {
            return key.contains("missile") || key.contains("rocket") || key.startsWith("agm_") || key.startsWith("jdam");
        }
        if ("cbcmoreshells".equals(namespace)) {
            return key.contains("torpedo") || key.contains("rocket") || key.contains("depth_charge")
                    || key.contains("depthcharge") || key.contains("bomb") || key.contains("missile");
        }
        return key.contains("missile") || key.contains("rocket");
    }

    private static Vec3 closestPointBetweenSegments(Vec3 a1, Vec3 a2, Vec3 b1, Vec3 b2) {
        Vec3 p = closestPointOnSegment(a1, a2, b1);
        for (int i = 0; i < 4; i++) {
            Vec3 q = closestPointOnSegment(b1, b2, p);
            p = closestPointOnSegment(a1, a2, q);
        }
        return p;
    }

    private static double segmentDistanceSqrToBox(Vec3 segStart, Vec3 segEnd, AABB box) {
        Vec3 p = closestPointOnSegment(segStart, segEnd, box.getCenter());
        for (int i = 0; i < 3; i++) {
            Vec3 q = closestPointOnAABB(box, p);
            p = closestPointOnSegment(segStart, segEnd, q);
        }
        Vec3 q = closestPointOnAABB(box, p);
        return p.distanceToSqr(q);
    }

    private static Vec3 closestPointOnSegment(Vec3 a, Vec3 b, Vec3 p) {
        Vec3 ab = b.subtract(a);
        double lengthSqr = ab.lengthSqr();
        double t = lengthSqr < 1.0E-8 ? 0.0 : Mth.clamp(p.subtract(a).dot(ab) / lengthSqr, 0.0, 1.0);
        return a.add(ab.scale(t));
    }

    private static Vec3 closestPointOnAABB(AABB box, Vec3 p) {
        return new Vec3(
                Mth.clamp(p.x, box.minX, box.maxX),
                Mth.clamp(p.y, box.minY, box.maxY),
                Mth.clamp(p.z, box.minZ, box.maxZ));
    }
}

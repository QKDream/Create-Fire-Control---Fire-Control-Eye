package com.qkdream.firecontrolcompat.entity;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import com.hooya.stabilizedturret.content.controller.MianbaoMissileCompat;
import com.qkdream.firecontrolcompat.BeamLeadMark;
import com.qkdream.firecontrolcompat.BeamMissileRegistry;
import com.qkdream.firecontrolcompat.FireControlCompat;
import com.qkdream.firecontrolcompat.FireControlLeadSettings;
import com.qkdream.firecontrolcompat.PhysicsGroups;
import com.qkdream.firecontrolcompat.ShaolibBridge;
import com.qkdream.firecontrolcompat.SonicPulse;
import com.qkdream.firecontrolcompat.iff.IffOwnership;
import com.qkdream.firecontrolcompat.missile.InFlightRounds;
import com.qkdream.firecontrolcompat.network.MissileTrackPayload;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.UUID;
import net.mcreator.myfirstmod.entity.Laserpoint4Entity;
import net.mcreator.myfirstmod.init.MianbaosModernwarfareModParticleTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
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
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.PacketDistributor;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

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
public class BeamRidingMissileEntity extends AbstractArrow implements ItemSupplier, GeoEntity {

    /**
     * Whether this round is sitting on a rail rather than flying.
     *
     * <p>Synched, because both sides must agree: the server pins it, and the client must stop the
     * flight tick and the flight-vector rendering. Without a synched flag the client sees an
     * ordinary flying round and spins it on the rail - {@code getPersistentData()} never leaves the
     * server, which is exactly why the earlier fix looked like it did nothing.</p>
     */
    private static final net.minecraft.network.syncher.EntityDataAccessor<Boolean> FC_MOUNTED =
            net.minecraft.network.syncher.SynchedEntityData.defineId(
                    BeamRidingMissileEntity.class,
                    net.minecraft.network.syncher.EntityDataSerializers.BOOLEAN);

    /** Marks this round as rail-mounted (or back to flying). */
    public void firecontrolcompat$setMounted(boolean mounted) {
        this.getEntityData().set(FC_MOUNTED, mounted);
    }

    /** Whether the round is rail-mounted; valid on both sides. */
    public boolean firecontrolcompat$isMounted() {
        return this.getEntityData().get(FC_MOUNTED);
    }

    private final AnimatableInstanceCache geckolibCache = GeckoLibUtil.createInstanceCache(this);

    public static final double PROXIMITY_RADIUS = 10.0;
    public static final double PROXIMITY_ARM_DISTANCE = 12.0;
    public static final double CONTACT_ARM_DISTANCE = 5.0;

    /**
     * Distance a round with a safety fuse has to <b>fly</b> before any of its fuzes may fire, in
     * blocks. Measured as distance travelled, not as the straight line to the launcher.
     *
     * <p>The two heavy anti-air rounds are launched from the deck of a ship and from batteries
     * packed together with their own rounds, and the reports were always the same shape: a
     * super-heavy round going off in the middle of its own launcher, or being judged by the
     * interception sweep while it was still in the tube's neighbourhood. Forty blocks is long enough
     * for the round to be clear of the hull and of the pad, and short enough that a genuine
     * self-defence shot is not disarmed.</p>
     */
    public static final double SAFETY_FUSE_DISTANCE = 40.0;

    /** Persistent-data key of the last arming state printed, so a flip is reported once. */
    private static final String SAFETY_ARMED_TAG = "firecontrolcompat:safety_armed";

    /** Persistent-data key of the safety-fuse report counter. */
    private static final String SAFETY_LOG_TAG = "firecontrolcompat:safety_log";

    /** Quietest gap between two safety-fuse lines, in calls. */
    private static final int SAFETY_LOG_EVERY = 20;
    public static final int MAX_LIFETIME_TICKS = 600;
    /** Hard age limit for the bounds cleanup, in ticks. */
    private static final int AGE_LIMIT_TICKS = 1200;
    /** 5 second powered acceleration phase: speed ramps linearly from launch to guidance top speed. */
    private static final int ACCEL_TICKS = 100;
    private static final double BEAM_SEARCH_RANGE = 800.0;
    private static final double GUIDANCE_SPEED = 34.0;
    /** Beam missile lateral overload rating. */
    private static final double GUIDANCE_OVERLOAD_G = 30.0;
    private static final int STEERING_START_TICK = 2;
    /** Distance to the steering point inside which the endgame overload applies. */
    private static final double TERMINAL_STEER_DISTANCE = 320.0;
    /** Turning authority available in the endgame, as a multiple of the rated overload. */
    private static final double TERMINAL_OVERLOAD_FACTOR = 8.0;
    /** Relative speed that needs no extra fuze range, in blocks per tick. */
    private static final double FUSE_SPEED_FREE = 6.0;
    /** Extra fuze range per block per tick of relative speed. */
    private static final double FUSE_SPEED_BONUS_PER_TICK = 0.6;
    /** Bisection passes the meeting point solver refines a crossing tick with. */
    private static final int INTERCEPT_BISECTIONS = 24;
    /** Most extra fuze range a fast merge may claim, in blocks. */
    private static final double FUSE_SPEED_BONUS_MAX = 20.0;
    /** Heading error above which the wide steering allowance applies, in radians. */
    private static final double HARD_TURN_ANGLE = Math.toRadians(20.0);
    /** Speed a round has just after leaving a horizontal launcher, in blocks per tick. */
    private static final double FRESH_LAUNCH_SPEED = 2.0;
    /** Launch boost: for the first 0.5s the missile may turn at least this much per tick (>= 120 degrees total). */
    private static final int BOOST_TURN_TICKS = 10;
    private static final double BOOST_TURN_RADIANS_PER_TICK = Math.toRadians(20.0);
    /** Axial speed cap during the 0.5s boost window: 10 ticks x 0.3 blocks = at most 3 blocks flown. */
    private static final double BOOST_MAX_SPEED = 0.3;
    /**
     * Safety bubble around the launcher's own structure. Building the launch
     * position from the first tick is not always reliable when Sable moves a
     * freshly spawned projectile out of its plot, so the fuze additionally
     * treats the hull itself as off limits.
     */
    private static final double LAUNCH_CLEARANCE = 3.0;
    /** How far around the launch point neighbouring bodies are still counted as the same platform. */
    private static final double LAUNCH_NEIGHBOURHOOD = 6.0;
    /**
     * Rail safety: the structure fuze stays cold for the first second of the
     * flight, so a salvo cannot cook off against whatever hull happens to be
     * beside the launcher.
     */
    private static final int STRUCTURE_FUSE_GRACE_TICKS = 20;
    /**
     * Ticks of settle time before the structure fuze may arm early. Sable moves
     * a freshly spawned projectile between plots on the first ticks, and the
     * launch position is only trustworthy once that is over.
     */
    private static final int STRUCTURE_FUSE_MIN_TICKS = 3;
    /**
     * Distance from the launch point at which the structure fuze arms early, in
     * blocks. Twice the fuze arm distance: a round that far from its rail can no
     * longer cook off against the hull it left, so a contact sixty blocks out is
     * a fair target from there on. The old fixed one second delay denied exactly
     * that engagement - the round crossed the contact while the fuze was still
     * cold, sailed past it and only detonated after turning back around.
     */
    private static final double STRUCTURE_FUSE_CLEAR_DISTANCE = 2.0 * PROXIMITY_ARM_DISTANCE;

    protected Vec3 launchPosition;
    protected BlockPos launchSourcePos;
    protected UUID launchSubLevelId;
    private double launchSpeed = -1.0;
    /** Proximity fuse range snapshot taken from the fire control computer setting. */
    private double proximityRadius = PROXIMITY_RADIUS;
    private boolean launchSettingsCaptured;

    /** Where the safety fuse last measured from, so the block count is a distance travelled. */
    private Vec3 safetyFuseLast;
    /** Blocks this round has flown since it left the tube (see {@link #SAFETY_FUSE_DISTANCE}). */
    private double safetyFuseDistance;
    /** Tick the last distance step was taken on, so both tick paths cannot count the same step. */
    private int safetyFuseTick = Integer.MIN_VALUE;
    /** Cached per tick: the missile is still on or inside the platform that launched it. */
    private int launcherClearanceTick = Integer.MIN_VALUE;
    private boolean launcherClearance;
    /**
     * Where the proximity fuze last swept up to. The sweep has to start there
     * rather than one step back from the current position, so a round that
     * something else moved between two of our ticks is still swept over the
     * whole path it covered.
     */
    private Vec3 lastFuzePosition;

    /** Cached per tick: every physics body that belongs to the launching platform. */
    private int motherBodyTick = Integer.MIN_VALUE;
    private Set<UUID> motherBodyCache = Set.of();
    /** Distance the round actually covered in its last movement step, in blocks. */
    protected double lastStepDistance;
    /** Displacement the round actually covered on the last tick, in world blocks. */
    protected Vec3 lastStep = Vec3.ZERO;
    /** Distance that step asked for, in blocks, before collisions had their say. */
    protected double lastPlannedStep;

    /** Guidance top speed in blocks per tick. Beam missile: 34 (680 blocks/s, Mach 2). */
    protected double guidanceSpeed() {
        return GUIDANCE_SPEED;
    }

    /** Lateral overload available to the guidance, in G. */
    protected double guidanceOverloadG() {
        return GUIDANCE_OVERLOAD_G;
    }

    /**
     * Whether the round keeps thrusting past the acceleration window to hold its
     * top speed. Arrows shed one percent of their speed every tick, so a round
     * that only accelerates for five seconds coasts down to a crawl on a long
     * shot, while one that sustains cruise speed holds its rated Mach number all
     * the way out.
     */
    protected boolean sustainsCruiseSpeed() {
        return false;
    }

    /** Flight time after which the round gives up and detonates, in ticks. */
    protected int maxLifetimeTicks() {
        return MAX_LIFETIME_TICKS;
    }

    /** Hard age limit in ticks, after which the round is discarded wherever it is. */
    protected int maxAgeTicks() {
        return AGE_LIMIT_TICKS;
    }

    /** Proximity fuse range captured once at launch; overridable per missile. */
    protected double initialProximityRadius() {
        return FireControlLeadSettings.beamProximityRange();
    }

    public BeamRidingMissileEntity(EntityType<? extends AbstractArrow> type, Level level) {
        super(type, level);
        this.setPickupItemStack(new ItemStack(this.missileItem()));
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        // The flight pose is applied by the renderer; there are no skeletal animations.
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geckolibCache;
    }

    /** Game tick this round last ran its own tick, so a steady tick can be told from a skipped one. */
    private static final String OWN_TICK_KEY = "FireControlEyeOwnTick";

    /** Own ticks between two flight reports of a guided round. */
    private static final int FLIGHT_REPORT_TICKS = 20;

    /**
     * Reports this round's own tick to fire control's guided-missile pass. That
     * pass moves any guided round whose own tick did not run, and it decides
     * that from the marker a round leaves behind: this round never wrote one, so
     * the pass advanced it a second time on every tick. The round then covered
     * twice the distance its guidance commanded, which doubles the turn radius
     * it needs and sends it past a contact it should have met, and the proximity
     * fuze only ever got to look at the last of the two steps, so the other half
     * of the flight path was never checked at all.
     *
     * <p>The report is only left once the round ticked the game tick before as
     * well. A round whose own tick the level skips - which happens while a fresh
     * projectile is still being moved out of a structure plot - then keeps the
     * fallback advance instead of standing still where it was last ticked.</p>
     */
    private void firecontrolcompat$reportNativeTick() {
        CompoundTag data = this.getPersistentData();
        long now = this.level().getGameTime();
        long previous = data.getLong(OWN_TICK_KEY);
        data.putLong(OWN_TICK_KEY, now);
        if (!data.getBoolean("CreateFireControlGuided")) {
            return;
        }
        // The marker is left on every tick this round ran itself, never held back
        // after a gap. Fire control's fallback advance fires in the very game tick
        // a round skipped its own tick, so a stale marker buys nothing there; what
        // it does buy is a second move in a tick the round already flew, and that
        // doubles the ground speed it covers between two steers - the round then
        // crosses the aim point in half the time the guidance solved for and sails
        // straight past the contact it was led onto.
        MianbaoMissileCompat.noteNativeMissileTick(this);
        if (this.tickCount > 0 && this.tickCount % FLIGHT_REPORT_TICKS == 0) {
            FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] beam flight ticks={} gt={} gap={} guided={} aa={} mark={} pos={} speed={} leadVel={} range={}",
                    this.tickCount, now, now - previous, data.getBoolean("CreateFireControlGuided"),
                    this.fireControlAirDefenseGuided(), data.hasUUID(BeamLeadMark.TARGET_KEY),
                    this.position(), this.getDeltaMovement().length(),
                    BeamLeadMark.trackedVelocity(this), BeamLeadMark.rangeToTarget(this));
        }
    }

    public void markLaunchSource(BlockPos sourcePos) {
        this.launchSourcePos = sourcePos;
    }

    /**
     * The rack this round was released from, or null when it was not launched off a block.
     *
     * <p>Read by {@code MathIntercept} to answer "are these two rounds off the same launcher?".
     * That question is what keeps a salvo from fuzing on itself: two rounds of one rack leave the
     * same block, so they are one platform and are never each other's interceptor.</p>
     */
    public BlockPos firecontrolcompat$launchSource() {
        return this.launchSourcePos;
    }

    /**
     * The Sable structure this round was released from, or null when it was not launched off one.
     *
     * <p>The same platform question as {@link #firecontrolcompat$launchSource()}, for a launcher
     * that rides a hull: its rack position is in the plot's stored coordinates, which two different
     * plots can share, while the structure id cannot.</p>
     */
    public UUID firecontrolcompat$launchSubLevel() {
        return this.launchSubLevelId;
    }

    /** Sublevel that contains the launching rack, so the seeker never locks the carrier aircraft. */
    public void markLaunchSubLevel(UUID subLevelId) {
        this.launchSubLevelId = subLevelId;
        IffOwnership.remember(this, subLevelId);
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

    /**
     * The round this missile picks up as, and the item it is drawn from.
     *
     * <p>The beam-riding round this base class used to be registered as is retired, so there is no
     * pickup item of its own any more; an arrow stands in for it. Every live round overrides this
     * with its own item, which is why an arrow is never handed to a player.</p>
     */
    protected Item missileItem() {
        return net.minecraft.world.item.Items.ARROW;
    }

    @Override
    protected ItemStack getDefaultPickupItem() {
        return new ItemStack(this.missileItem());
    }

    @Override
    public ItemStack getItem() {
        return new ItemStack(this.missileItem());
    }

    /**
     * Turns the model onto the round's own flight vector once per tick.
     *
     * <p>Guidance only rewrites the delta movement, so without this the synced
     * rotation keeps whatever {@code shoot()} stored at launch and the missile
     * visibly flies sideways through every turn (the vertical launch turn in
     * particular). The previous rotation is bookkept by hand so the renderer's
     * partial-tick interpolation stays smooth, and the yaw is unwrapped so a
     * turn across the 180 degree seam never makes the model spin the long way
     * round.</p>
     */
    private void firecontrolcompat$faceVelocity() {
        if (this.level().isClientSide) {
            return;
        }
        Vec3 velocity = this.getDeltaMovement();
        if (velocity.lengthSqr() < 1.0E-6) {
            return;
        }
        double horizontal = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        float yaw = (float) (Mth.atan2(-velocity.x, velocity.z) * (180.0 / Math.PI));
        float pitch = (float) (-Mth.atan2(velocity.y, horizontal) * (180.0 / Math.PI));
        float previousYaw = this.getYRot();
        this.yRotO = previousYaw;
        this.xRotO = this.getXRot();
        this.setYRot(previousYaw + Mth.wrapDegrees(yaw - previousYaw));
        this.setXRot(Mth.clamp(pitch, -90.0F, 90.0F));
    }

    @Override
    protected void defineSynchedData(net.minecraft.network.syncher.SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(FC_MOUNTED, false);
    }

    public void tick() {
        if (this.firecontrolcompat$isMounted()) {
            // On a rail, not in flight: the rack holds it in place and nothing else runs. A round
            // sitting on a pylon must not guide, fuse, age or spend its motor.
            return;
        }
        SonicPulse.projectile(this);
        // Remote visual proxies are moved exclusively by fire control's
        // MissileLongRangeEffects extrapolation. Self-ticking them applies the
        // velocity a second time, which swings the rendered position off the
        // real flight path and tears the trail into straight lines on turns.
        if (this.level().isClientSide() && this.getPersistentData().getBoolean("CreateFireControlRemoteVisual")) {
            return;
        }
        if (!this.level().isClientSide()) {
            this.firecontrolcompat$reportNativeTick();
            // In-flight registry, so the interception sweep can find a round that is a real entity
            // (fire control's own proximity fuze only knows its own mathematical table, and this
            // round is not in it). See {@code InFlightRounds}.
            InFlightRounds.track(this);
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
            // Vertical launch is gone: the beam rider always uses the horizontal
            // (direct acceleration) profile, so there is no per-computer mode to read.
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
        this.lastPlannedStep = beforeDelta.length();
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
        this.lastStepDistance = this.position().distanceTo(beforeTick);
        this.lastStep = this.position().subtract(beforeTick);
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
        this.firecontrolcompat$faceVelocity();

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
        // The plume a round carries for its whole flight, on the server and for every player in
        // sight of it. The branch above is fire-control-absent only, and the client fallback
        // ignores anything within thirty-two blocks, so between the two of them a round released
        // near the crew had no flame and no smoke at all. Only the infrared anti-tank round asks
        // for this one; the heavy and Neva rounds already get the pack's proxy trail.
        if (!this.level().isClientSide() && this.level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            try {
                com.qkdream.firecontrolcompat.missile.MissileTrailParticles.emit(serverLevel, this);
            } catch (Throwable failure) {
                FireControlCompat.LOGGER.info("[firecontrolcompat] ir-atgm trail: tick emit failed: {}",
                        String.valueOf(failure));
            }
        }

        // RETIRED(ir-arm-rounds): the airborne infrared round's engine stall probe stood here. The
        // round is 面包学's own from its next version on, so this mod has nothing to watch on it.

        if (this.level().isClientSide()) {
            return;
        }
        if (this.tickCount > this.maxAgeTicks() || !this.level().getWorldBorder().isWithinBounds(this.position())
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
        } else if (!this.fireControlAirDefenseGuided()
                && (engine <= ACCEL_TICKS || this.sustainsCruiseSpeed())) {
            double desired = this.accelerationTargetSpeed(engine);
            Vec3 velocity = this.getDeltaMovement();
            double speed = velocity.length();
            if (speed > 1.0E-4 && speed < desired) {
                this.setDeltaMovement(velocity.scale(desired / speed));
            }
        }

        if (!this.getPersistentData().getBoolean("CreateFireControlGuided")) {
            // A crew released round is flown onto the point where it meets its
            // contact; rounds without the mark keep riding the beam point.
            Vec3 point = this.firecontrolcompat$leadPoint();
            if (point == null) {
                point = this.guidancePoint();
            }
            if (point != null) {
                this.steerTowardPoint(point, engine);
            }
            this.traceGuidance(point, engine);
        }

        this.broadcastRemoteState();
        this.broadcastTrackState();

        this.firecontrolcompat$accumulateFlight();
        Vec3 to = this.position();
        Vec3 from = this.firecontrolcompat$fuzeFrom(to);
        this.lastFuzePosition = to;
        if (!this.firecontrolcompat$armed()) {
            // Safety fuse: inside the arming run the fuze is cold, so nothing can be detonated -
            // not the hull the round just left, not the rounds on the neighbouring pylons, not the
            // ground within forty blocks of the rail.
            reportSafetyFuse(this, "fuse");
        } else {
            Vec3 armedFrom = this.stillOnLauncher() ? null : this.clipSegmentPastArmDistance(from, to);
            if (armedFrom != null) {
                ProximityHit hit = this.findProximityTarget(armedFrom, to);
                if (hit != null) {
                    this.detonate(hit.position(), hit.cause);
                    return;
                }
            }
        }

        if (engine > this.maxLifetimeTicks()) {
            this.detonate(to, "lifetime");
            return;
        }
    }

    /** Engine age of a round flown mathematically, kept apart from the pack's own counter. */
    private static final String MATH_AGE_TAG = "firecontrolcompat:math_age";

    /**
     * One tick of guidance for a round that fire control is flying as a record.
     *
     * <p>A mathematically flown round <b>never joins the level</b>: {@code
     * MathematicalMissileManager.capture} cancels the join event, so neither the level nor this
     * class's own {@link #tick()} ever runs for it. That is exactly what the user asked for - the
     * round exists as a position and a velocity on the server, and only its model is drawn, on the
     * client - but it also takes the guidance away, because the guidance lived in {@code tick()}.</p>
     *
     * <p>So this is the same body with the parts that need a real entity removed: no {@code
     * super.tick()} (the pack's {@code MathematicalMissileManager.advance} moves the round, sweeps
     * its path for terrain, structures and entities, and detonates it on impact), no in-ground
     * discard (a record has no ground state), and no engine tag of the pack's (that curve is
     * parked, see {@code MathGuidedMissiles}). What stays is the launch snapshot, the acceleration
     * ramp, the steering, the orientation and the proximity fuse - everything that decides where
     * the round goes and what it kills.</p>
     *
     * @param root the level the round is filed under; it is where the proximity fuse looks
     */
    public void firecontrolcompat$mathTick(net.minecraft.server.level.ServerLevel root) {
        if (root == null || this.firecontrolcompat$isMounted()) {
            return;
        }
        if (this.launchPosition == null) {
            this.launchPosition = this.position();
        }
        if (this.launchSpeed < 0.0) {
            this.launchSpeed = this.getDeltaMovement().length();
        }
        if (!this.launchSettingsCaptured) {
            this.launchSettingsCaptured = true;
            this.proximityRadius = this.initialProximityRadius();
        }
        this.setNoGravity(true);
        double engine = this.getPersistentData().getDouble(MATH_AGE_TAG) + 1.0;
        this.getPersistentData().putDouble(MATH_AGE_TAG, engine);

        // The pack's advance applies its own drag each tick, so the ramp has to be re-asserted
        // here or the round coasts down instead of accelerating to its top speed.
        if (this.sustainsCruiseSpeed() || engine <= ACCEL_TICKS) {
            double desired = this.accelerationTargetSpeed(engine);
            Vec3 velocity = this.getDeltaMovement();
            double speed = velocity.length();
            if (speed > 1.0E-4 && speed < desired) {
                this.setDeltaMovement(velocity.scale(desired / speed));
            }
        }

        Vec3 point = this.firecontrolcompat$leadPoint();
        if (point == null) {
            point = this.guidancePoint();
        }
        if (point != null) {
            this.steerTowardPoint(point, engine);
        }
        this.traceGuidance(point, engine);
        this.firecontrolcompat$faceVelocity();

        Vec3 to = this.position();
        this.lastPlannedStep = this.getDeltaMovement().length();
        // The mathematical tick is the only tick a captured round has, so the flight distance the
        // safety fuse counts is accumulated here as well as in tick(). Both calls are guarded by the
        // round's tick number, so one flight step is never counted twice.
        this.firecontrolcompat$accumulateFlight();
        Vec3 from = this.firecontrolcompat$fuzeFrom(to);
        this.lastFuzePosition = to;
        if (!this.firecontrolcompat$armed()) {
            reportSafetyFuse(this, "fuse");
        } else {
            Vec3 armedFrom = this.stillOnLauncher() ? null : this.clipSegmentPastArmDistance(from, to);
            if (armedFrom != null) {
                ProximityHit hit = this.findProximityTarget(armedFrom, to);
                if (hit != null) {
                    this.detonate(hit.position(), hit.cause);
                    return;
                }
            }
        }
        if (engine > this.maxLifetimeTicks()) {
            this.detonate(to, "lifetime");
        }
    }

    /** Whether this missile has the 0.5s low-speed high-agility launch boost (beam-riding missile only). */
    protected boolean hasLaunchBoost() {
        // The beam-riding round this class was registered as is retired, and the launch mode it
        // used to read now lives on the pack's own beam rider (see OrdinaryAaLaunchModeMixin).
        // The launcher-family rounds that still fly override this with their own answer.
        return false;
    }

    /** Whether fire control's air-defense guidance has adopted this missile. */
    protected boolean fireControlAirDefenseGuided() {
        return this.getPersistentData().getBoolean("CreateFireControlAaGuided")
                || this.getPersistentData().getBoolean("CreateFireControlDisplayGuided");
    }

    @Override
    public void remove(RemovalReason reason) {
        InFlightRounds.untrack(this);
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
     * through fire control's own {@code MissileRemoteStatePayload} channel.
     * The fire control client then spawns a no-physics proxy of this entity
     * type, which renders the model, the minimap dot and the trail far beyond
     * the native entity tracking range.
     */
    /**
     * Fire control changed {@code MissileRemoteStatePayload} from 8 to 14 record
     * components in 0.7.8. Both shapes are resolved once by reflection so one
     * build keeps working whichever version the pack ships.
     */
    private static final String REMOTE_STATE_CLASS =
            "com.hooya.stabilizedturret.network.MissileRemoteStatePayload";
    private static final int REMOTE_STATE_ARGUMENTS = 14;
    private static final java.lang.reflect.Constructor<?> REMOTE_STATE_CONSTRUCTOR =
            resolveRemoteStateConstructor();

    private static java.lang.reflect.Constructor<?> resolveRemoteStateConstructor() {
        try {
            Class<?> payload = Class.forName(REMOTE_STATE_CLASS);
            java.lang.reflect.Constructor<?> best = null;
            for (java.lang.reflect.Constructor<?> candidate : payload.getConstructors()) {
                int count = candidate.getParameterCount();
                if (count != 8 && count != REMOTE_STATE_ARGUMENTS) {
                    continue;
                }
                if (best == null || count > best.getParameterCount()) {
                    best = candidate;
                }
            }
            if (best != null) {
                best.setAccessible(true);
            }
            return best;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Feeds the remote-state channel from outside {@link #tick()}.
     *
     * <p>Called by {@code MathGuidedMissiles} for a round the pack is flying mathematically: that
     * round never joins the level, so its tick never runs, and without this call the long-range
     * channel would be silent for exactly the rounds the player watches at range.</p>
     */
    public void firecontrolcompat$broadcastRemoteState() {
        this.broadcastRemoteState();
    }

    private void broadcastRemoteState() {
        if (!(this.level() instanceof ServerLevel serverLevel) || !ModList.get().isLoaded("create_fire_control")) {
            return;
        }
        try {
            Vec3 worldPosition = Sable.HELPER.projectOutOfSubLevel(this.level(), this.position());
            Vec3 velocity = this.getDeltaMovement();
            // The airframe axis this rotation is about is +Y, not +Z: every consumer of
            // MissileRemoteStatePayload in fire control maps model space +Y onto the flight vector
            // (MianbaoMissileRemoteSync#broadcast, MianbaoBallisticMissileRemoteVisualMixin
            // #orientRemoteProxy and ClientMissileRenderer#renderNativeModel all build it as
            // `new Quaternionf().rotationTo(new Vector3f(0, 1, 0), direction)`), and the renderer
            // that draws this mod's airframes lays the -Z nose onto +Y before applying it. Sent with
            // +Z here, a proxy's nose came out a right angle off its flight path - which is what
            // "the round still flies sideways" looks like while the tracked round is drawn right.
            Quaternionf rotation = velocity.lengthSqr() < 1.0E-8
                    ? new Quaternionf()
                    : new Quaternionf().rotationTo(new Vector3f(0.0F, 1.0F, 0.0F),
                            new Vector3f((float) velocity.x, (float) velocity.y, (float) velocity.z).normalize());
            if (REMOTE_STATE_CONSTRUCTOR == null) {
                return;
            }
            ResourceLocation entityKey = BuiltInRegistries.ENTITY_TYPE.getKey(this.getType());
            Object payload = REMOTE_STATE_CONSTRUCTOR.getParameterCount() == REMOTE_STATE_ARGUMENTS
                    ? REMOTE_STATE_CONSTRUCTOR.newInstance(
                            this.getUUID(), entityKey,
                            worldPosition.x, worldPosition.y, worldPosition.z,
                            velocity.x, velocity.y, velocity.z,
                            rotation.x, rotation.y, rotation.z, rotation.w,
                            false,
                            serverLevel.getGameTime())
                    : REMOTE_STATE_CONSTRUCTOR.newInstance(
                            this.getUUID(), entityKey,
                            worldPosition.x, worldPosition.y, worldPosition.z,
                            velocity.x, velocity.y, velocity.z);
            int sent = 0;
            double nearest = Double.MAX_VALUE;
            for (ServerPlayer player : serverLevel.players()) {
                double distance = Math.sqrt(player.position().distanceToSqr(worldPosition));
                if (distance * distance <= 4.0E8) {
                    PacketDistributor.sendToPlayer(player, (CustomPacketPayload) payload);
                    sent++;
                    nearest = Math.min(nearest, distance);
                }
            }
            // Which channel is carrying this round to a watching client, and how far away the
            // nearest one is. `via=proxy` is where the client's handover puts the picture: past the
            // fog wall the tracked round is invisible whatever it is drawn with, so fire control's
            // proxy - this very payload - answers for the round there, and the fog-free mark draws
            // it. `ir-atgm render: .. proxy=` on the client is the authoritative reading; this line
            // is the server's side of the same decision. Throttled by a per-round counter, never by
            // a subtraction from Long.MIN_VALUE.
            CompoundTag remoteData = this.getPersistentData();
            int remoteTicks = remoteData.getInt(REMOTE_LOG_TICKS_TAG) + 1;
            remoteData.putInt(REMOTE_LOG_TICKS_TAG, remoteTicks);
            if (remoteTicks % REMOTE_LOG_INTERVAL == 0) {
                double seen = nearest == Double.MAX_VALUE ? -1.0D : nearest;
                FireControlCompat.LOGGER.info(
                        "{}ir-atgm remote: sent={} dist={} via={} type={} uuid={} pos={}",
                        "[firecontrolcompat] ", sent,
                        String.format(java.util.Locale.ROOT, "%.1f", seen),
                        seen > com.qkdream.firecontrolcompat.IrAtgm.PROXY_HANDOVER_FLOOR ? "proxy" : "entity",
                        BuiltInRegistries.ENTITY_TYPE.getKey(this.getType()),
                        this.getUUID(), worldPosition);
            }
        } catch (Throwable ignored) {
        }
    }

    /** Ticks counted for the {@code ir-atgm remote:} line. */
    private static final String REMOTE_LOG_TICKS_TAG = "firecontrolcompat:remote_log_ticks";

    /** One {@code ir-atgm remote:} line a second. */
    private static final int REMOTE_LOG_INTERVAL = 20;

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

    /** Interception point handed over by a crew release, or null without a mark. */
    private Vec3 firecontrolcompat$leadPoint() {
        return BeamLeadMark.leadPoint(this);
    }

    /**
     * Hook for a round that wants its own guidance decision written into the
     * log: where it is, what it was told to fly at and how far off that aim its
     * velocity sits. The default does nothing, so ordinary rounds stay quiet.
     */
    protected void traceGuidance(Vec3 aim, double engine) {
    }

    /**
     * Steers a round fire control has adopted back onto the meeting point it was
     * released for.
     *
     * <p>Ownership of a crew released round is split: fire control adopts it for
     * the chunk ticket and the long range proxy, and then flies it as a beam
     * rider - down the bearing the mount is on, corrected onto that line by a
     * fixed lateral gain and a four degree per tick turn limit. That is a fine
     * way to fly a round at a target somebody is tracking by hand, and a poor way
     * to fly one at a contact closing on the mount at Mach speed: the mount is
     * always a few degrees behind a crossing contact, the round is launched into
     * the same lag, and the turn limit is nowhere near enough to catch up, so the
     * round sails past a target it was aimed straight at.</p>
     *
     * <p>The interception point solves the collision triangle instead, which is
     * what the round actually needs, so a round that carries one is steered here
     * after fire control has had its say. Fire control keeps everything else it
     * does with the round.</p>
     */
    public void firecontrolcompat$steerAdoptedLead() {
        if (this.level().isClientSide()) {
            return;
        }
        if (!this.getPersistentData().getBoolean("CreateFireControlGuided")) {
            // Rounds fire control has not adopted are already flown by their own tick.
            return;
        }
        Vec3 point = this.firecontrolcompat$leadPoint();
        if (point == null) {
            return;
        }
        this.steerTowardPoint(point, this.getPersistentData().getDouble("fadongji1") + 1.0);
    }

    /**
     * Point this round has to fly to in order to meet a contact, in the same
     * space as {@code origin}, or {@code target} itself when the geometry has no
     * meeting point inside the flight window.
     *
     * <p>The meeting point is where the round's reach and the contact's track
     * cross: the earliest time at which the distance the round covers along its
     * own speed schedule - the launch crawl, the five second ramp and the cruise
     * speed it settles at - equals the distance to where the contact will be by
     * then. The lead solution used to walk that equation by fixed point, one
     * pass at a time, and the walk only converges while the contact is slower
     * than the round: the factor a pass is scaled by is the contact speed over
     * the round speed, so a contact closing as fast as the round flies walks the
     * point further every pass and never settles. That is the head on merge, and
     * it is also the endgame of every interception: the point the last pass left
     * behind sits behind the round's own nose, the round turns around for it and
     * the contact it was about to fly through is never reached.</p>
     *
     * <p>The schedule is therefore walked once, tick by tick, until the reach
     * passes the distance to the contact's position at that tick, and the
     * crossing tick itself is found by bisection inside it - the answer is the
     * earliest crossing rather than a whole tick early or late.</p>
     */
    public Vec3 interceptionPoint(Vec3 origin, Vec3 target, Vec3 targetVelocity, double capTicks) {
        if (origin == null || target == null) {
            return null;
        }
        Vec3 contactVelocity = targetVelocity == null || !finite(targetVelocity) ? Vec3.ZERO : targetVelocity;
        double topSpeed = this.guidanceSpeed();
        if (!(topSpeed > 1.0E-4) || !Double.isFinite(topSpeed)) {
            topSpeed = 1.0E-4;
        }
        double window = capTicks > 0.0 && Double.isFinite(capTicks) ? capTicks : this.maxLifetimeTicks();
        int cap = (int) Math.floor(Math.min(window, Math.max(1, this.maxLifetimeTicks())));
        if (cap < 1) {
            return target;
        }
        double speed = this.getDeltaMovement().length();
        if (!(speed > 1.0E-4) || !Double.isFinite(speed)) {
            speed = topSpeed;
        }
        boolean guided = this.fireControlAirDefenseGuided();
        int engine = (int) Math.floor(this.getPersistentData().getDouble("fadongji1"));
        double covered = 0.0;
        for (int step = 1; step <= cap; step++) {
            int at = engine + step;
            if (!guided && this.hasLaunchBoost() && at <= BOOST_TURN_TICKS) {
                speed = Math.min(speed, BOOST_MAX_SPEED);
            } else if (!guided && (at <= ACCEL_TICKS || this.sustainsCruiseSpeed())) {
                speed = Math.max(speed, this.accelerationTargetSpeed(at));
            } else {
                speed *= 0.99;
            }
            double reached = covered + speed;
            double distance = target.add(contactVelocity.scale(step)).subtract(origin).length();
            if (!Double.isFinite(distance)) {
                return target;
            }
            if (reached >= distance) {
                double low = step - 1.0;
                double high = step;
                for (int pass = 0; pass < INTERCEPT_BISECTIONS; pass++) {
                    double middle = 0.5 * (low + high);
                    double reach = covered + speed * (middle - (step - 1.0));
                    double aimDistance = target.add(contactVelocity.scale(middle)).subtract(origin).length();
                    if (reach >= aimDistance) {
                        high = middle;
                    } else {
                        low = middle;
                    }
                }
                return target.add(contactVelocity.scale(high));
            }
            covered = reached;
        }
        return target;
    }

    /**
     * Ticks this round still needs to cross {@code distance} blocks. The walk
     * follows the very speed schedule the round flies - the launch crawl, the
     * five second ramp and the cruise speed it settles at - because a solution
     * built on the top speed alone meets a crossing target a third of the way
     * early and lets it walk out of the fuze.
     */
    public double flightTicksFor(double distance) {
        if (!(distance > 0.0) || !Double.isFinite(distance)) {
            return 0.0;
        }
        double topSpeed = this.guidanceSpeed();
        boolean guided = this.fireControlAirDefenseGuided();
        int engine = (int) Math.floor(this.getPersistentData().getDouble("fadongji1"));
        double speed = this.getDeltaMovement().length();
        if (!(speed > 1.0E-4) || !Double.isFinite(speed)) {
            speed = topSpeed;
        }
        int horizon = Math.max(20, Math.min(this.maxAgeTicks(), this.maxLifetimeTicks()));
        double covered = 0.0;
        for (int step = 1; step <= horizon; step++) {
            int at = engine + step;
            if (!guided && this.hasLaunchBoost() && at <= BOOST_TURN_TICKS) {
                speed = Math.min(speed, BOOST_MAX_SPEED);
            } else if (!guided && (at <= ACCEL_TICKS || this.sustainsCruiseSpeed())) {
                speed = Math.max(speed, this.accelerationTargetSpeed(at));
            } else {
                speed *= 0.99;
            }
            covered += speed;
            if (covered >= distance) {
                return step;
            }
        }
        return distance / Math.max(topSpeed, 1.0E-3);
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
        if (distance <= TERMINAL_STEER_DISTANCE || angle >= HARD_TURN_ANGLE) {
            // Wide authority. At Mach two the rated overload only swings the
            // round a degree or so per tick, a turn radius of about a kilometre,
            // so neither the endgame correction nor swinging onto the meeting
            // point of a target that is crossing the sight line can come from the
            // rated figure alone: a contact manoeuvring across the line, the
            // classic beam turn, simply walks out of the fuze.
            maxTurnRadians *= TERMINAL_OVERLOAD_FACTOR;
        }
        if (this.hasLaunchBoost() && engine <= BOOST_TURN_TICKS) {
            maxTurnRadians = Math.max(maxTurnRadians, BOOST_TURN_RADIANS_PER_TICK);
        }
        // The infrared anti-tank round is capped by its own leg rate on top of the overload law.
        // That law is written for a round doing seventeen blocks a tick; at the one to two blocks a
        // tick this round flies it allows tens of degrees a tick, which is what turned a leg change
        // into a corner in the sky. The cap only ever tightens the limit - the overload law and the
        // wide-authority factor above still apply - and it answers a non-positive number for every
        // other round, so no other missile's steering changes.
        double legLimit = com.qkdream.firecontrolcompat.IrAtgm.turnLimitDegrees(this);
        if (legLimit > 0.0D) {
            maxTurnRadians = Math.min(maxTurnRadians, Math.toRadians(legLimit));
        }
        double fraction = Math.min(1.0, maxTurnRadians / angle);
        double sinAngle = Math.sin(angle);
        double fromWeight = Math.sin((1.0 - fraction) * angle) / sinAngle;
        double toWeight = Math.sin(fraction * angle) / sinAngle;
        Vec3 velocityDirection = velocity.scale(1.0 / speed);
        Vec3 targetDirection = new Vec3(dx / distance, dy / distance, dz / distance);
        Vec3 newDirection = velocityDirection.scale(fromWeight).add(targetDirection.scale(toWeight)).normalize();
        this.setDeltaMovement(newDirection.scale(speed));
        if (legLimit > 0.0D) {
            com.qkdream.firecontrolcompat.IrAtgm.reportSteer(this, velocityDirection, newDirection,
                    Math.toDegrees(angle), legLimit);
        }
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

    /**
     * Claims the one detonation a round is allowed.
     *
     * <p>A round can be asked to go off several times for one kill: the interception sweep bursts
     * it, the burst's own explosion is seen by the cook-off pass which bursts it again, and a fuze
     * that queued a lifetime detonation can still reach it. Each of those used to run a full
     * warhead - which reads as a round that explodes twice, and (since the sweep can judge the
     * same round every tick) as one that never gets out of its own launch tube.</p>
     *
     * <p>The flag is per round and per level, and the round is taken out of the in-flight registry
     * here, before any blast: the registry is what the sweep walks as threats, so a round that is
     * going off must not be a threat for one more pass.</p>
     *
     * @return {@code true} for the first caller only
     */
    protected final boolean firecontrolcompat$beginDetonation() {
        if (this.firecontrolcompat$detonated || this.isRemoved()) {
            return false;
        }
        this.firecontrolcompat$detonated = true;
        InFlightRounds.markSpent(this);
        InFlightRounds.untrack(this);
        return true;
    }

    /** Set by {@link #firecontrolcompat$beginDetonation()}; a second detonation is a no-op. */
    private boolean firecontrolcompat$detonated;

    public void detonate(Vec3 position, String cause) {
        if (this.level().isClientSide()) {
            return;
        }
        if (!this.firecontrolcompat$beginDetonation()) {
            FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] beam missile detonate ignored reason=already-spent cause={} ticks={} type={}",
                    cause, this.tickCount, BuiltInRegistries.ENTITY_TYPE.getKey(this.getType()));
            return;
        }
        FireControlCompat.LOGGER.info("[firecontrolcompat] beam missile detonated cause={} pos={} delta={} ticks={} range={}",
                cause, position, this.getDeltaMovement(), this.tickCount, BeamLeadMark.rangeToTarget(this));
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
        if (!this.fireControlAirDefenseGuided() && this.structureFuseLive()) {
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

    /**
     * Whether the structure fuze may look for a contact this tick. The launch
     * platform, its neighbouring bodies and the launch zone are excluded by
     * {@link #findSableTarget} itself, and the round is additionally held cold
     * while it is still inside its own platform, so arming as soon as it is
     * clear of the rail cannot detonate a salvo on its own hull. The one second
     * figure stays as a fallback for a round whose platform never resolves.
     */
    private boolean structureFuseLive() {
        if (this.tickCount >= STRUCTURE_FUSE_GRACE_TICKS) {
            return true;
        }
        if (this.tickCount < STRUCTURE_FUSE_MIN_TICKS || this.stillOnLauncher()) {
            return false;
        }
        return this.launchPosition == null
                || this.position().distanceToSqr(this.launchPosition)
                        >= STRUCTURE_FUSE_CLEAR_DISTANCE * STRUCTURE_FUSE_CLEAR_DISTANCE;
    }

    /**
     * Whether this round carries the safety fuse at all.
     *
     * <p>Only the two heavy anti-air rounds do ({@link HeavyAirDefenseMissileEntity} and
     * {@link NevaAirDefenseMissileEntity}); every other round of this mod keeps its old behaviour to
     * the block, because a short-range round with a forty block arming run would simply never
     * detonate.</p>
     */
    protected boolean hasSafetyFuse() {
        return false;
    }

    /**
     * Adds the distance this round covered since the last call. Called once per tick from both tick
     * paths - the entity one and the mathematical one.
     *
     * <p>The guard is the tick number, not a boolean: a mathematical round's tick is
     * {@code MathematicalMissileManager.advance}, and this mod calls into the round both before that
     * advance and (from the drive loop) around it, so "same tick, already counted" is what keeps one
     * step from being counted twice. A step longer than {@link #SAFETY_FUSE_JUMP} is a teleport (a
     * payload snap, a Sable projection), not flight, and is dropped rather than credited.</p>
     */
    public void firecontrolcompat$accumulateFlight() {
        if (!this.hasSafetyFuse() || this.level() == null || this.level().isClientSide()) {
            return;
        }
        int tick = this.tickCount;
        if (tick == this.safetyFuseTick) {
            return;
        }
        this.safetyFuseTick = tick;
        Vec3 now = this.position();
        if (now == null) {
            return;
        }
        if (this.safetyFuseLast != null) {
            double step = now.distanceTo(this.safetyFuseLast);
            if (step > 1.0E-6D && step <= SAFETY_FUSE_JUMP) {
                this.safetyFuseDistance += step;
            }
        }
        this.safetyFuseLast = now;
    }

    /** Blocks this round has flown, cumulatively. Zero on a round without a safety fuse. */
    public double firecontrolcompat$flightDistance() {
        return this.safetyFuseDistance;
    }

    /**
     * Whether this round's fuzes may fire: false while it is still inside its arming run.
     *
     * <p>Answers true on the client and for every round without a safety fuse. The distance is only
     * ever accumulated server-side (a client round is a position snapshot), and a client that
     * believed its rounds were permanently unarmed would suppress the cosmetic side of a contact
     * detonation for no reason.</p>
     */
    public boolean firecontrolcompat$armed() {
        if (!this.hasSafetyFuse() || this.level() == null || this.level().isClientSide()) {
            return true;
        }
        return this.safetyFuseDistance >= SAFETY_FUSE_DISTANCE;
    }

    /** Longest single step that still counts as flight rather than a teleport, in blocks. */
    private static final double SAFETY_FUSE_JUMP = 64.0;

    /**
     * The safety fuse's answer for any entity, for the code that does not own the round.
     *
     * @return true when the entity may be detonated or judged (everything without a safety fuse
     *         answers true)
     */
    public static boolean safetyFuseArmed(Entity entity) {
        if (safetyFuseKind(entity) == null) {
            return true;
        }
        return entity instanceof BeamRidingMissileEntity round && round.firecontrolcompat$armed();
    }

    /** {@code heavy} or {@code neva} for the two rounds that carry the fuse, null otherwise. */
    public static String safetyFuseKind(Entity entity) {
        if (entity instanceof HeavyAirDefenseMissileEntity) {
            return "heavy";
        }
        if (entity instanceof NevaAirDefenseMissileEntity) {
            return "neva";
        }
        return null;
    }

    /**
     * One line saying which premature detonation the safety fuse just refused.
     *
     * <p>Printed on the arming flip and then once every {@link #SAFETY_LOG_EVERY} reports, because a
     * round inside its arming run is asked about every tick by both the interception sweep and the
     * cook-off pass. Deliberately counted, not timed: the seed is a counter compared with {@code %} -
     * never a subtraction from {@code Long.MIN_VALUE}, which wraps and silences the line forever.</p>
     */
    public static void reportSafetyFuse(Entity entity, String skip) {
        String kind = safetyFuseKind(entity);
        if (kind == null || !(entity instanceof BeamRidingMissileEntity round)) {
            return;
        }
        if (entity.level() == null || entity.level().isClientSide()) {
            // The arming run is a server-side fact; a client copy has no distance to report.
            return;
        }
        try {
            boolean armed = round.firecontrolcompat$armed();
            net.minecraft.nbt.CompoundTag data = entity.getPersistentData();
            boolean seen = data.contains(SAFETY_ARMED_TAG);
            boolean wasArmed = data.getBoolean(SAFETY_ARMED_TAG);
            int count = data.getInt(SAFETY_LOG_TAG) + 1;
            data.putInt(SAFETY_LOG_TAG, count);
            data.putBoolean(SAFETY_ARMED_TAG, armed);
            if (seen && armed == wasArmed && Integer.remainderUnsigned(count, SAFETY_LOG_EVERY) != 0) {
                return;
            }
            FireControlCompat.LOGGER.info(
                    "safety fuse: type={} dist={} armed={} skip={} at={}",
                    kind,
                    String.format(java.util.Locale.ROOT, "%.1f", round.firecontrolcompat$flightDistance()),
                    armed,
                    armed ? "armed" : skip,
                    String.format(java.util.Locale.ROOT, "%.1f,%.1f,%.1f",
                            entity.getX(), entity.getY(), entity.getZ()));
        } catch (Throwable ignored) {
            // a diagnostic line never costs a shot
        }
    }

    private boolean contactArmed() {
        return this.firecontrolcompat$armed()
                && this.launchPosition != null
                && this.position().distanceToSqr(this.launchPosition) >= CONTACT_ARM_DISTANCE * CONTACT_ARM_DISTANCE
                && !this.stillOnLauncher();
    }

    /**
     * Every physics body that makes up the launching platform: the body that
     * fired, everything connected or welded to it (any bearing, joint, coupler
     * or welded blueprint piece) and every body the launch point sat inside.
     * A ship built from several bodies is therefore a single mother body.
     */
    /**
     * Sub-levels that make up the platform this round was released from. Fire
     * control's own seeker and the round's seeker share it so neither of them can
     * turn a free fired round onto a part of its own carrier.
     */
    protected Set<UUID> launchPlatformGroup() {
        return this.motherBody();
    }

    private Set<UUID> motherBody() {
        if (this.motherBodyTick == this.tickCount) {
            return this.motherBodyCache;
        }
        Set<UUID> body = Set.of();
        try {
            Level level = this.level();
            if (this.launchSubLevelId != null || this.launchPosition != null) {
                body = PhysicsGroups.motherBody(
                        level, this.launchSubLevelId, this.launchPosition, LAUNCH_NEIGHBOURHOOD);
            }
        } catch (Throwable ignored) {
        }
        this.motherBodyTick = this.tickCount;
        this.motherBodyCache = body;
        return body;
    }

    /**
     * Whether the missile is still sitting on or inside its own platform. Both
     * fuzes stay silent there: a round that detonates while leaving the rail
     * damages its own launcher, and a freshly spawned projectile whose launch
     * position was captured after Sable moved it out of the plot would
     * otherwise arm in the middle of its own hull.
     */
    private boolean stillOnLauncher() {
        if (this.launcherClearanceTick == this.tickCount) {
            return this.launcherClearance;
        }
        boolean inside = false;
        try {
            Level level = this.level();
            Vec3 position = this.position();
            for (UUID bodyId : this.motherBody()) {
                SubLevel body = PhysicsGroups.resolve(level, bodyId);
                if (body != null && body.boundingBox().toMojang().inflate(LAUNCH_CLEARANCE).contains(position)) {
                    inside = true;
                    break;
                }
            }
        } catch (Throwable ignored) {
        }
        this.launcherClearanceTick = this.tickCount;
        this.launcherClearance = inside;
        return inside;
    }

    /**
     * Start of the path the proximity fuze has to sweep this tick: everything
     * the round moved since the last sweep, not just the step its own tick
     * applied. Anything that moves the round between two of our ticks leaves a
     * gap the old segment never covered - fire control's guided-missile pass
     * advanced it a second time on every tick, and a structure relocating a
     * fresh projectile between plots does the same - so a fast round flew
     * through its contact with the fuze looking the other way, which is why a
     * merge could end without a detonation at all. A gap far wider than the
     * round can fly in one tick is a teleport rather than travel, so that one
     * alone falls back to the single step.
     */
    private Vec3 firecontrolcompat$fuzeFrom(Vec3 to) {
        Vec3 step = to.subtract(this.getDeltaMovement());
        Vec3 last = this.lastFuzePosition;
        if (last == null) {
            return step;
        }
        double limit = Math.max(4.0 * this.getDeltaMovement().length(), 96.0);
        return last.distanceTo(to) <= limit ? last : step;
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
            Set<UUID> ownPlatform = this.motherBody();
            for (SubLevel subLevel : container.getAllSubLevels()) {
                if (subLevel == null || subLevel.isRemoved()) {
                    continue;
                }
                if (ownPlatform.contains(subLevel.getUniqueId())) {
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
        // Wide enough for the largest bubble proximityRadiusFor can hand back, not
        // just the dialled in one: the bonus for a fast merge is worth up to
        // FUSE_SPEED_BONUS_MAX blocks, and a search box that only reaches the base
        // range never fetches a contact that far out, so the bonus could only ever
        // apply along the flight path and never across it - which is exactly the
        // merge it was written for.
        AABB searchBox = new AABB(segStart, segEnd)
                .inflate(Math.max(this.proximityRadius + FUSE_SPEED_BONUS_MAX, 4.0));
        double bestAlongSqr = Double.MAX_VALUE;
        Vec3 bestPosition = null;
        for (Entity entity : this.level().getEntities(this, searchBox, e -> e != this && !e.isRemoved())) {
            // RETIRED(ir-arm-rounds): only the ground infrared round is ours to ignore a fuse for.
            if (entity.getType() == BeamMissileRegistry.INFRARED_TANSHE.get()
                    || !this.countsAsFuseEntity(entity)) {
                continue;
            }
            Vec3 missileEnd = entity.position();
            Vec3 missileStart = missileEnd.subtract(entity.getDeltaMovement());
            Vec3 closestOnShell = closestPointBetweenSegments(segStart, segEnd, missileStart, missileEnd);
            Vec3 closestOnMissile = closestPointOnSegment(missileStart, missileEnd, closestOnShell);
            double radius = this.proximityRadiusFor(this.relativeSpeed(entity.getDeltaMovement()));
            if (closestOnShell.distanceToSqr(closestOnMissile) <= radius * radius) {
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
            double radius = this.proximityRadiusFor(this.relativeSpeed(ShaolibBridge.velocity(instance)));
            if (closestOnShell.distanceToSqr(closestOnMissile) <= radius * radius) {
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

    /**
     * Fuze range for a merge at the given relative speed, in blocks per tick.
     * A fixed bubble is sized for a slow contact: a fast one changes the whole
     * geometry between two ticks, so the miss the guidance leaves behind is
     * proportionally wider and a ten block bubble lets the two pass each other.
     * The relative speed drives the bonus rather than the closing rate, because
     * the worst case is not a head on merge but a target crossing the line of
     * sight, the classic beam turn: there the closing rate is nearly zero while
     * the geometry still changes by tens of blocks per tick.
     */
    private double proximityRadiusFor(double relativeSpeed) {
        if (!(relativeSpeed > FUSE_SPEED_FREE)) {
            return this.proximityRadius;
        }
        double bonus = Math.min(FUSE_SPEED_BONUS_MAX,
                (relativeSpeed - FUSE_SPEED_FREE) * FUSE_SPEED_BONUS_PER_TICK);
        return this.proximityRadius + Math.max(0.0, bonus);
    }

    /** Speed of this round relative to the other one, in blocks per tick. */
    private double relativeSpeed(Vec3 targetVelocity) {
        Vec3 own = this.getDeltaMovement();
        if (targetVelocity == null) {
            return own.length();
        }
        return targetVelocity.subtract(own).length();
    }

    /**
     * Ticks a round fresh off the rail needs to cross the given distance,
     * following the schedule it will really fly: the launch ramp, the five
     * second acceleration and the slow bleed off after it. Used to point a
     * launcher at the meeting point a round released now would fly to, so the
     * tube does not hand the round a heading it has to spend its whole flight
     * correcting.
     */
    public static double freshFlightTicks(double distance, double topSpeed) {
        if (!(distance > 0.0) || !Double.isFinite(distance) || !(topSpeed > 0.0)) {
            return 0.0;
        }
        double covered = 0.0;
        for (int step = 1; step <= ACCEL_TICKS * 4; step++) {
            double speed = step >= ACCEL_TICKS
                    ? topSpeed * Math.pow(0.99, step - ACCEL_TICKS)
                    : FRESH_LAUNCH_SPEED + (topSpeed - FRESH_LAUNCH_SPEED) * step / (double) ACCEL_TICKS;
            covered += Math.max(speed, 0.0);
            if (covered >= distance) {
                return step;
            }
        }
        return distance / topSpeed;
    }

    /** True while every component of the vector is a real number. */
    private static boolean finite(Vec3 vector) {
        return Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
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

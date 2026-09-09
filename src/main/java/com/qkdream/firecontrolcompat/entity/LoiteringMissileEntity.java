package com.qkdream.firecontrolcompat.entity;

import com.hooya.stabilizedturret.content.controller.MianbaoOpticalMissileHandler;
import com.hooya.stabilizedturret.content.controller.MianbaoMissileCompat;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.qkdream.firecontrolcompat.BeamMissileRegistry;
import com.qkdream.firecontrolcompat.FireControlCompat;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Entity.RemovalReason;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Small loitering munition carried by the ATGM launcher family. Flight,
 * television camera and steering are owned by fire control's own optical
 * missile handler ({@code MianbaoOpticalMissileHandler}) - the same code
 * that flies the television-guided missile - so the speed curve, view
 * enter/exit and input pipeline behave identically. When no fire control
 * computer owns the launcher the missile flies straight with the same
 * speed curve on its own. Flight time is capped at 10 seconds.
 */
public class LoiteringMissileEntity extends BeamRidingMissileEntity {

    /** Top speed in blocks per tick (120 blocks/s, slower than the television missile). */
    public static final double TOP_SPEED = 6.0;
    /** Flight time: 10 seconds. */
    public static final int LIFETIME_TICKS = 200;
    /** Acceleration ramp: 7.5 seconds to top speed. */
    private static final int SPEED_RAMP_TICKS = 150;

    private static final Set<LoiteringMissileEntity> ACTIVE =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private Vec3 direction = new Vec3(0.0, 1.0, 0.0);
    private double initialSpeed = 3.0;
    private boolean nativeGuided;

    public LoiteringMissileEntity(EntityType<? extends AbstractArrow> type, Level level) {
        super(type, level);
        ACTIVE.add(this);
    }

    @Override
    protected Item missileItem() {
        return BeamMissileRegistry.LOITERING_MUNITION.get();
    }

    /** The native television handler drives the speed ramp; the base acceleration branch must stay out of the way. */
    @Override
    protected double guidanceSpeed() {
        return 0.0;
    }

    @Override
    protected float explosionStrength() {
        return 4.8F;
    }

    /** Television munitions detonate on contact, not on a proximity fuze. */
    @Override
    protected double initialProximityRadius() {
        return 1.0;
    }

    /** Steering is handled by fire control's optical missile handler. */
    @Override
    protected Vec3 guidancePoint() {
        return null;
    }

    /** Captures the world-space launch profile before the entity is added to the level. */
    public void setLaunchProfile(Vec3 launchDirection, double launchSpeed) {
        if (launchDirection != null && launchDirection.lengthSqr() > 1.0E-8) {
            this.direction = launchDirection.normalize();
        }
        this.initialSpeed = Math.max(0.1, launchSpeed);
    }

    /**
     * Finds the fire control computer that owns this launcher and hands the
     * missile to fire control's native television guidance. Mirrors
     * {@code ControllerTracker} through reflection to avoid a compile-time
     * dependency on its private loaded set.
     */
    public void bindNearestController(Level level, BlockPos launcherPos) {
        try {
            Class<?> trackerType = Class.forName("com.hooya.stabilizedturret.content.controller.ControllerTracker");
            Field field = trackerType.getDeclaredField("LOADED");
            field.setAccessible(true);
            Object loaded = field.get(null);
            if (!(loaded instanceof java.util.Set<?> controllers)) {
                return;
            }
            StabilizerControllerBlockEntity best = null;
            double bestDistanceSqr = Double.MAX_VALUE;
            for (Object entry : controllers) {
                if (!(entry instanceof StabilizerControllerBlockEntity candidate) || candidate.isRemoved()
                        || candidate.getLevel() == null) {
                    continue;
                }
                if (candidate.getLevel().getServer() != level.getServer()
                        || !candidate.getLevel().dimension().equals(level.dimension())) {
                    continue;
                }
                boolean ownsLauncher = false;
                for (Object ref : candidate.getMissileLaunchers()) {
                    if (ref instanceof com.hooya.stabilizedturret.content.controller.BindingRef binding
                            && binding.matches(level, launcherPos)) {
                        ownsLauncher = true;
                        break;
                    }
                }
                if (!ownsLauncher) {
                    for (Object ref : candidate.getAaMissileLaunchers()) {
                        if (ref instanceof com.hooya.stabilizedturret.content.controller.BindingRef binding
                                && binding.matches(level, launcherPos)) {
                            ownsLauncher = true;
                            break;
                        }
                    }
                }
                if (!ownsLauncher) {
                    continue;
                }
                double distanceSqr = candidate.getBlockPos().getCenter()
                        .distanceToSqr(Vec3.atCenterOf(launcherPos));
                if (distanceSqr < bestDistanceSqr) {
                    bestDistanceSqr = distanceSqr;
                    best = candidate;
                }
            }
            if (best != null) {
                if (MianbaoOpticalMissileHandler.hasActiveMissile(best)) {
                    this.discard();
                    return;
                }
                this.adoptNativeTelevisionGuidance(best);
            }
        } catch (Throwable throwable) {
            FireControlCompat.LOGGER.debug("[firecontrolcompat] loitering controller lookup unavailable", throwable);
        }
    }

    /**
     * Registers this missile with fire control's optical missile handler
     * exactly like a launched television-guided missile: the handler owns the
     * speed ramp, the camera toggle and the steering input from here on.
     */
    private void adoptNativeTelevisionGuidance(StabilizerControllerBlockEntity controller) {
        try {
            Field activeField = MianbaoOpticalMissileHandler.class.getDeclaredField("ACTIVE");
            activeField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<Entity, Object> active = (Map<Entity, Object>) activeField.get(null);
            Class<?> stateClass = Class.forName(
                    "com.hooya.stabilizedturret.content.controller.MianbaoOpticalMissileHandler$State");
            Constructor<?> constructor = stateClass.getDeclaredConstructor(
                    StabilizerControllerBlockEntity.class, Vec3.class, double.class);
            constructor.setAccessible(true);
            Object state = constructor.newInstance(controller, this.direction, this.initialSpeed);
            synchronized (active) {
                active.put(this, state);
            }
            this.getPersistentData().putBoolean("CreateFireControlOpticalGuided", true);
            this.getPersistentData().putBoolean("CreateFireControlAircraftGuided", true);
            this.getPersistentData().putInt("CreateFireControlSafetyTicks", 10);
            this.getPersistentData().putBoolean("锁定模式", false);
            this.getPersistentData().putBoolean("坐标模式", false);
            this.getPersistentData().putBoolean("jamed", true);
            this.setNoGravity(true);
            this.nativeGuided = true;
            FireControlCompat.LOGGER.info("[firecontrolcompat] loitering missile joined native television guidance controller={}",
                    controller.getBlockPos());
        } catch (Throwable throwable) {
            FireControlCompat.LOGGER.error("[firecontrolcompat] loitering missile failed to join television guidance", throwable);
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide()) {
            return;
        }
        double engine = this.getPersistentData().getDouble("fadongji1");
        if (!this.nativeGuided) {
            double progress = Mth.clamp(engine / (double) SPEED_RAMP_TICKS, 0.0, 1.0);
            double speed = Mth.lerp(progress, this.initialSpeed, TOP_SPEED);
            Vec3 velocity = this.direction.scale(Math.max(0.0, speed));
            this.setDeltaMovement(velocity);
            float yaw = (float) Math.toDegrees(Mth.atan2(this.direction.x, this.direction.z));
            float pitch = (float) Math.toDegrees(Mth.atan2(this.direction.y, this.direction.horizontalDistance()));
            this.setYRot(yaw);
            this.setXRot(pitch);
            this.yRotO = yaw;
            this.xRotO = pitch;
            this.hasImpulse = true;
        }
        if (engine >= LIFETIME_TICKS) {
            this.detonate(this.position(), "lifetime");
        }
    }

    @Override
    public void remove(RemovalReason reason) {
        ACTIVE.remove(this);
        super.remove(reason);
    }

    /**
     * The native television handler drives every optical missile toward
     * 8.5075 blocks per tick. Called at the end of the handler's server tick,
     * this rescales adopted loitering munitions onto their own slower speed
     * curve while keeping the handler's steering direction intact.
     */
    public static void scaleGuidedVelocity() {
        synchronized (ACTIVE) {
            for (LoiteringMissileEntity missile : ACTIVE) {
                if (missile == null || missile.isRemoved() || !missile.isAlive() || missile.level().isClientSide()) {
                    continue;
                }
                if (!missile.getPersistentData().getBoolean("CreateFireControlOpticalGuided")) {
                    continue;
                }
                Vec3 velocity = MianbaoMissileCompat.getWorldVelocity(missile);
                double speed = velocity.length();
                if (speed < 1.0E-8) {
                    continue;
                }
                double engine = missile.getPersistentData().getDouble("fadongji1");
                double progress = Mth.clamp(engine / (double) SPEED_RAMP_TICKS, 0.0, 1.0);
                double desired = Mth.lerp(progress, missile.initialSpeed, TOP_SPEED);
                MianbaoMissileCompat.setWorldVelocity(missile, velocity.scale(Math.max(0.05, desired / speed)));
            }
        }
    }
}

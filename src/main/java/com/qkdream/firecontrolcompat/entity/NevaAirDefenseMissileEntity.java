package com.qkdream.firecontrolcompat.entity;

import com.hooya.stabilizedturret.content.controller.ControllerTracker;
import com.hooya.stabilizedturret.content.controller.MianbaoMissileCompat;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.qkdream.firecontrolcompat.BeamMissileRegistry;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity.RemovalReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * The Neva (S-125) super-heavy air-defense missile. The launcher block hands
 * it straight up and fire control's AA guidance adopts it through the
 * {@code MianbaoAirDefenseCompat} mixin exactly like the heavy missile:
 * controller or connected display owns lock, launch and the Sable proximity
 * fuse, while this entity only carries the ammunition fuse and contact
 * detonation.
 *
 * <p>The fire-control guidance caps every AA missile at 13 degrees of steer
 * per tick (about 32G at Mach 2). The post-guidance pass below rescales the
 * velocity onto the Mach 2 curve and adds up to another 7.5 degrees per tick
 * toward the radar contact, lifting the terminal lateral load to the rated
 * 50G. Warhead strength is the ground cruise missile x4 (48), above the heavy
 * missile's 36.</p>
 */
public class NevaAirDefenseMissileEntity extends BeamRidingMissileEntity {

    /** 2 Mach top speed in blocks per tick. */
    public static final double TOP_SPEED = 34.03;
    /** Fastest per-tick speed multiplication of the post-guidance ramp. */
    public static final double SPEED_SCALE = 1.499;
    /** Extra steer per tick beyond fire control's own 13 degrees. */
    private static final double EXTRA_STEER_DEGREES = 7.5;

    private static final Set<NevaAirDefenseMissileEntity> ACTIVE =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    public NevaAirDefenseMissileEntity(EntityType<? extends AbstractArrow> type, Level level) {
        super(type, level);
        ACTIVE.add(this);
    }

    /**
     * Runs after fire control's AA guidance has steered for the tick. Adopted
     * Neva missiles are rescaled onto their own Mach 2 acceleration curve and
     * receive the extra 50G steering toward the radar contact, both without
     * touching the handler's steering direction more than the extra cap.
     */
    public static void scaleGuidedVelocity() {
        synchronized (ACTIVE) {
            for (NevaAirDefenseMissileEntity missile : ACTIVE) {
                if (missile == null || missile.isRemoved() || !missile.isAlive() || missile.level().isClientSide()) {
                    continue;
                }
                boolean adopted = missile.getPersistentData().getBoolean("CreateFireControlAaGuided")
                        || missile.getPersistentData().getBoolean("CreateFireControlDisplayGuided");
                if (!adopted) {
                    continue;
                }
                Vec3 velocity = MianbaoMissileCompat.getWorldVelocity(missile);
                double speed = velocity.length();
                if (speed < 1.0E-8) {
                    continue;
                }
                double factor = Math.min(SPEED_SCALE, TOP_SPEED / speed);
                Vec3 scaled = velocity.scale(factor);
                Vec3 target = missile.firecontrolcompat$aaTargetCenter();
                if (target != null) {
                    Vec3 position = MianbaoMissileCompat.getWorldPosition(missile);
                    Vec3 lineOfSight = target.subtract(position);
                    if (lineOfSight.lengthSqr() >= 1.0E-8) {
                        Vec3 direction = scaled.normalize();
                        Vec3 desired = lineOfSight.normalize();
                        double angle = Math.acos(Mth.clamp(direction.dot(desired), -1.0, 1.0));
                        if (angle > 1.0E-6) {
                            double cap = Math.toRadians(EXTRA_STEER_DEGREES);
                            double blend = Math.min(cap, angle) / angle;
                            Vec3 blended = direction.scale(1.0 - blend).add(desired.scale(blend));
                            if (blended.lengthSqr() >= 1.0E-8) {
                                scaled = blended.normalize().scale(scaled.length());
                            }
                        }
                    }
                }
                MianbaoMissileCompat.setWorldVelocity(missile, scaled);
            }
        }
    }

    /** Center of the radar contact the firing computer locked, or null. */
    private Vec3 firecontrolcompat$aaTargetCenter() {
        CompoundTag data = this.getPersistentData();
        if (!data.getBoolean("CreateFireControlAaGuided") || !data.hasUUID("CreateFireControlAaTarget")) {
            return null;
        }
        try {
            UUID target = data.getUUID("CreateFireControlAaTarget");
            BlockPos controllerPos = BlockPos.of(data.getLong("CreateFireControlAaController"));
            boolean exact = data.getBoolean("CreateFireControlAaControllerSubLevelKnown");
            UUID subLevelId = data.hasUUID("CreateFireControlAaControllerSubLevel")
                    ? data.getUUID("CreateFireControlAaControllerSubLevel") : null;
            StabilizerControllerBlockEntity controller =
                    ControllerTracker.findLoadedForMissile(this, controllerPos, subLevelId, exact);
            if (controller == null) {
                return null;
            }
            StabilizerControllerBlockEntity.RadarContact contact = controller.getRadarContact(target);
            return contact == null ? null : contact.center();
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Override
    protected Item missileItem() {
        return BeamMissileRegistry.NEVA_MISSILE.get();
    }

    /** Fire control's AA guidance drives the velocity; never ride a laser beam. */
    @Override
    protected Vec3 guidancePoint() {
        return null;
    }

    @Override
    protected double guidanceSpeed() {
        return TOP_SPEED;
    }

    /** Ground cruise missile warhead x4. */
    @Override
    protected float explosionStrength() {
        return 48.0F;
    }

    /**
     * The heavy round carries the safety fuse: nothing detonates it until it has flown
     * {@link #SAFETY_FUSE_DISTANCE} blocks (see BeamRidingMissileEntity#firecontrolcompat\).
     */
    @Override
    protected boolean hasSafetyFuse() {
        return true;
    }

    @Override
    protected boolean hasLaunchBoost() {
        return false;
    }

    @Override
    public void remove(RemovalReason reason) {
        ACTIVE.remove(this);
        super.remove(reason);
    }
}

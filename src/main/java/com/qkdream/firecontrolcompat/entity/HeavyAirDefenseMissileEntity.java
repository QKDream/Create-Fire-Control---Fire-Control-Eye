package com.qkdream.firecontrolcompat.entity;

import com.hooya.stabilizedturret.content.controller.MianbaoMissileCompat;
import com.qkdream.firecontrolcompat.BeamMissileRegistry;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.world.entity.Entity.RemovalReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Heavy air-defense missile carried by the mianbao vertical launch silo.
 *
 * <p>Fire control's {@code MianbaoAirDefenseGuidanceHandler} /
 * {@code AutoDefenseMissileGuidance} adopt this entity (through the
 * {@code MianbaoAirDefenseCompat} mixin) and drive lock, launch, speed and
 * the Sable proximity fuse; the base entity only keeps its own ammunition
 * fuse (mianbao / vestalihy / CBCMS / TAOV munitions) and contact
 * detonation while the fire-control path owns the rest. Warhead strength is
 * the ground cruise missile x2 (36), matching {@code nativeExplosionStrength
 * 12 x 3} used by fire-control detonations.</p>
 */
public class HeavyAirDefenseMissileEntity extends BeamRidingMissileEntity {

    /** 3 Mach top speed in blocks per tick. */
    public static final double TOP_SPEED = 51.0;
    /** Fire control's own AA guidance caps at Mach 2; this closes the gap. */
    public static final double SPEED_SCALE = TOP_SPEED / 34.03;

    private static final Set<HeavyAirDefenseMissileEntity> ACTIVE =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    public HeavyAirDefenseMissileEntity(EntityType<? extends AbstractArrow> type, Level level) {
        super(type, level);
        ACTIVE.add(this);
    }

    /**
     * Fire control's AA guidance caps its missiles at 34.03 blocks per tick
     * (Mach 2). The handlers run after entity ticks, so their return paths
     * rescale every adopted heavy missile back onto its 3 Mach acceleration
     * curve without touching the steering direction.
     */
    public static void scaleGuidedVelocity() {
        synchronized (ACTIVE) {
            for (HeavyAirDefenseMissileEntity missile : ACTIVE) {
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
                MianbaoMissileCompat.setWorldVelocity(missile, velocity.scale(factor));
            }
        }
    }

    @Override
    protected Item missileItem() {
        return BeamMissileRegistry.HEAVY_AIR_DEFENSE_MISSILE.get();
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

    /** Ground cruise missile warhead x2. */
    @Override
    protected float explosionStrength() {
        return 36.0F;
    }

    @Override
    protected boolean hasLaunchBoost() {
        return false;
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide()) {
            return;
        }
        Vec3 velocity = this.getDeltaMovement();
        if (velocity.lengthSqr() > 1.0E-8) {
            float yaw = (float) Math.toDegrees(Mth.atan2(velocity.x, velocity.z));
            float pitch = (float) Math.toDegrees(Mth.atan2(velocity.y, velocity.horizontalDistance()));
            this.setYRot(yaw);
            this.setXRot(pitch);
            this.yRotO = yaw;
            this.xRotO = pitch;
            this.hasImpulse = true;
        }
    }

    @Override
    public void remove(RemovalReason reason) {
        ACTIVE.remove(this);
        super.remove(reason);
    }
}
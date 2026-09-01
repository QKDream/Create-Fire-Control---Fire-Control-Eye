package com.qkdream.firecontrolcompat;

import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.verr1.synaxis.foundation.physics.PhysicsBodyView;
import com.verr1.taov.weapons.content.gun.GunBlockEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

/**
 * Bridge between fire-control and TAOV's gun (taov_weapons:gun). The gun is a
 * synaxis physics-body autocannon whose launch direction is the block facing
 * transformed by the body pose; it exposes redstone / plant "fire" inputs and
 * ammo-chain control offsets but no yaw/pitch actuator. This bridge reports
 * muzzle position and aim direction, drives a separate fire-control-only
 * barrel deflection (see {@link TaovGunAimControl}), and toggles firing via
 * {@code setPlantFireActive}.
 *
 * <p>All TAOV/synaxis references are guarded by {@link #isGun(BlockEntity)}
 * name checks, so this class is safe to load without TAOV installed.
 */
public final class TaovGunBridge {

    public static final String GUN_BLOCK_ENTITY = "com.verr1.taov.weapons.content.gun.GunBlockEntity";
    public static final double MAX_DEFLECTION = 4.0;
    public static final double MIN_FRONT_COMPONENT = 1.0E-3;

    private TaovGunBridge() {
    }

    public static boolean isGun(BlockEntity blockEntity) {
        return blockEntity != null && GUN_BLOCK_ENTITY.equals(blockEntity.getClass().getName());
    }

    public static GunBlockEntity resolveGun(Level level, BindingRef ref) {
        if (level == null || ref == null) {
            return null;
        }
        BlockEntity mount = ref.resolve(level);
        if (!isGun(mount)) {
            return null;
        }
        try {
            return (GunBlockEntity) mount;
        } catch (Throwable t) {
            FireControlCompat.LOGGER.debug("[firecontrolcompat] Taov gun resolve failed at {}", ref.pos(), t);
            return null;
        }
    }

    /** World position of the gun muzzle, or null when unavailable. */
    public static Vec3 muzzleWorld(GunBlockEntity gun) {
        if (gun == null) {
            return null;
        }
        try {
            PhysicsBodyView view = gun.readSelfPhysics();
            if (view == null) {
                return null;
            }
            Vector3d muzzle = view.modelToWorldPosition(gun.muzzlePositionModel(), new Vector3d());
            if (muzzle != null && Double.isFinite(muzzle.x) && Double.isFinite(muzzle.y) && Double.isFinite(muzzle.z)) {
                return new Vec3(muzzle.x, muzzle.y, muzzle.z);
            }
        } catch (Throwable t) {
            FireControlCompat.LOGGER.debug("[firecontrolcompat] Taov gun muzzle failed for {}", gun, t);
        }
        Level level = gun.getLevel();
        return level == null ? null : Vec3.atCenterOf(gun.getBlockPos());
    }

    /** World position of the gun block center (body transformed), or null. */
    public static Vec3 centerWorld(GunBlockEntity gun) {
        if (gun == null) {
            return null;
        }
        try {
            Vector3d center = gun.position();
            if (center != null && Double.isFinite(center.x) && Double.isFinite(center.y) && Double.isFinite(center.z)) {
                return new Vec3(center.x, center.y, center.z);
            }
        } catch (Throwable t) {
            FireControlCompat.LOGGER.debug("[firecontrolcompat] Taov gun center failed for {}", gun, t);
        }
        Level level = gun.getLevel();
        return level == null ? null : Vec3.atCenterOf(gun.getBlockPos());
    }

    /**
     * Current world launch direction, including an active fire-control
     * deflection when present. Never null unless the gun is broken.
     */
    public static Vec3 aimDirection(GunBlockEntity gun) {
        if (gun == null) {
            return null;
        }
        try {
            Vec3 deflected = gun instanceof TaovGunAimControl control ? control.firecontrolcompat$deflectedDirection() : null;
            if (deflected != null && deflected.lengthSqr() > 1.0E-8) {
                return deflected.normalize();
            }
            PhysicsBodyView view = gun.readSelfPhysics();
            Vector3d world = view.bodyToWorld().transformDirection(new Vector3d(gun.frontLocal()), new Vector3d());
            if (world.lengthSquared() < 1.0E-8) {
                world.set(0.0, 0.0, 1.0);
            }
            return new Vec3(world.x, world.y, world.z).normalize();
        } catch (Throwable t) {
            FireControlCompat.LOGGER.debug("[firecontrolcompat] Taov gun direction failed for {}", gun, t);
            return null;
        }
    }

    /**
     * World direction for the tangent deflection
     * {@code front + left * h + up * v} through the gun's physics body.
     */
    public static Vector3d deflectDirection(GunBlockEntity gun, double horizontal, double vertical) {
        Vector3d local = new Vector3d(gun.frontLocal())
                .add(new Vector3d(gun.leftLocal()).mul(horizontal))
                .add(new Vector3d(gun.upLocal()).mul(vertical));
        Vector3d world = gun.readSelfPhysics().bodyToWorld().transformDirection(local, new Vector3d());
        if (world.lengthSquared() < 1.0E-8) {
            world.set(0.0, 0.0, 1.0);
        }
        return world;
    }

    /**
     * Solves tangent deflection offsets for the desired world direction and
     * pushes them into the gun through the aim-control duck. Elevation /
     * depression are degrees like the CBC mount path; the gun's vertical
     * tangent is clamped to tan(limit) so the fire-control configured limits
     * are respected.
     */
    public static boolean aimAtDirection(GunBlockEntity gun, Vec3 worldDirection, double depressionDegrees, double elevationDegrees) {
        if (gun == null || worldDirection == null || worldDirection.lengthSqr() < 1.0E-8) {
            return false;
        }
        if (!Double.isFinite(worldDirection.x) || !Double.isFinite(worldDirection.y) || !Double.isFinite(worldDirection.z)) {
            return false;
        }
        if (!(gun instanceof TaovGunAimControl control)) {
            return false;
        }
        try {
            PhysicsBodyView view = gun.readSelfPhysics();
            Vector3d dir = new Vector3d(worldDirection.x, worldDirection.y, worldDirection.z).normalize();
            Vector3d front = view.bodyToWorld().transformDirection(new Vector3d(gun.frontLocal()), new Vector3d());
            Vector3d left = view.bodyToWorld().transformDirection(new Vector3d(gun.leftLocal()), new Vector3d());
            Vector3d up = view.bodyToWorld().transformDirection(new Vector3d(gun.upLocal()), new Vector3d());
            double frontComponent = dir.dot(front);
            double horizontal = dir.dot(left);
            double vertical = dir.dot(up);
            double h = frontComponent > MIN_FRONT_COMPONENT
                    ? horizontal / frontComponent
                    : Math.signum(horizontal) * MAX_DEFLECTION;
            double v = frontComponent > MIN_FRONT_COMPONENT
                    ? vertical / frontComponent
                    : Math.signum(vertical) * MAX_DEFLECTION;
            h = Mth.clamp(h, -MAX_DEFLECTION, MAX_DEFLECTION);
            double maxDown = Math.tan(Math.toRadians(Mth.clamp(depressionDegrees, 0.0, 90.0)));
            double maxUp = Math.tan(Math.toRadians(Mth.clamp(elevationDegrees, 0.0, 90.0)));
            v = Mth.clamp(v, -Math.min(MAX_DEFLECTION, maxDown), Math.min(MAX_DEFLECTION, maxUp));
            control.firecontrolcompat$setAim(h, v);
            return true;
        } catch (Throwable t) {
            FireControlCompat.LOGGER.debug("[firecontrolcompat] Taov gun aim failed for {}", gun, t);
            return false;
        }
    }
}


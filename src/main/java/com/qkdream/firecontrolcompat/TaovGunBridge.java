package com.qkdream.firecontrolcompat;

import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.verr1.synaxis.foundation.physics.PhysicsBodyView;
import com.verr1.taov.weapons.content.gun.GunBlockEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

/**
 * Bridge between fire-control and TAOV's gun (taov_weapons:gun). The gun is a
 * synaxis physics-body autocannon whose launch direction is the block facing
 * transformed by the body pose; it exposes redstone / plant "fire" inputs.
 * This bridge reports muzzle position and aim direction and toggles firing
 * via {@code setPlantFireActive}.
 *
 * <p>All TAOV/synaxis references are guarded by {@link #isGun(BlockEntity)}
 * name checks, so this class is safe to load without TAOV installed.
 */
public final class TaovGunBridge {

    public static final String GUN_BLOCK_ENTITY = "com.verr1.taov.weapons.content.gun.GunBlockEntity";

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

    /** Current world launch direction. Never null unless the gun is broken. */
    public static Vec3 aimDirection(GunBlockEntity gun) {
        if (gun == null) {
            return null;
        }
        try {
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
}


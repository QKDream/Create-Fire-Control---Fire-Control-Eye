package com.qkdream.firecontrolcompat;

import com.verr1.taov.weapons.content.hellfire.HellFireDirectorBlockEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

/**
 * Bridge to the TAOV Hellfire director through its public synaxis methods.
 * The director is a synaxis physics-body block; its world position and front
 * direction come from synaxis {@code OnPhysicsBodyBlockEntity#position()} /
 * {@code #front()}. Presence checks stay name based so this mod compiles and
 * runs even without taov_weapons / synaxis installed.
 */
public final class HellfireBridge {

    private static final String DIRECTOR_BLOCK_ID = "taov_weapons:hell_fire_director";
    private static final String DIRECTOR_BLOCK_ENTITY = "com.verr1.taov.weapons.content.hellfire.HellFireDirectorBlockEntity";

    private HellfireBridge() {
    }

    public static boolean isDirector(BlockState state) {
        if (state == null) {
            return false;
        }
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return id != null && DIRECTOR_BLOCK_ID.equals(id.toString());
    }

    public static boolean isDirector(BlockEntity blockEntity) {
        return blockEntity != null && DIRECTOR_BLOCK_ENTITY.equals(blockEntity.getClass().getName());
    }

    /** World position of the director body, or null when unavailable. */
    public static Vec3 position(BlockEntity blockEntity) {
        if (!isDirector(blockEntity)) {
            return null;
        }
        try {
            Vector3d value = ((HellFireDirectorBlockEntity) blockEntity).position();
            return value == null ? null : new Vec3(value.x, value.y, value.z);
        } catch (Throwable t) {
            FireControlCompat.LOGGER.debug("[firecontrolcompat] Hellfire position failed for {}", blockEntity, t);
            return null;
        }
    }

    /**
     * Normalized world front direction of the director body, using the same
     * (0,0,1) fallback the director itself uses when its front is degenerate.
     */
    public static Vec3 aimDirection(BlockEntity blockEntity) {
        if (!isDirector(blockEntity)) {
            return null;
        }
        try {
            Vector3d front = ((HellFireDirectorBlockEntity) blockEntity).front();
            if (front == null) {
                return null;
            }
            Vec3 direction = new Vec3(front.x, front.y, front.z);
            if (direction.lengthSqr() < 1.0E-8) {
                direction = new Vec3(0.0, 0.0, 1.0);
            }
            return direction.normalize();
        } catch (Throwable t) {
            FireControlCompat.LOGGER.debug("[firecontrolcompat] Hellfire direction failed for {}", blockEntity, t);
            return null;
        }
    }
}

package com.qkdream.firecontrolcompat;

import java.lang.reflect.Method;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

/**
 * Reflection bridge to the TAOV Hellfire director. The director is a synaxis
 * physics-body block; its world position and front direction come from
 * synaxis {@code OnPhysicsBodyBlockEntity#position()} / {@code #front()}.
 * Everything here is reflective so this mod compiles and runs even without
 * taov_weapons / synaxis installed.
 */
public final class HellfireBridge {

    private static final String DIRECTOR_BLOCK_ID = "taov_weapons:hell_fire_director";
    private static final String DIRECTOR_BLOCK_ENTITY = "com.verr1.taov.weapons.content.hellfire.HellFireDirectorBlockEntity";

    private static volatile Method positionMethod;
    private static volatile Method frontMethod;

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
        Vector3d value = invoke(blockEntity, positionMethod(), "position");
        return value == null ? null : new Vec3(value.x, value.y, value.z);
    }

    /**
     * Normalized world front direction of the director body, using the same
     * (0,0,1) fallback the director itself uses when its front is degenerate.
     */
    public static Vec3 aimDirection(BlockEntity blockEntity) {
        Vector3d front = invoke(blockEntity, frontMethod(), "front");
        if (front == null) {
            return null;
        }
        Vec3 direction = new Vec3(front.x, front.y, front.z);
        if (direction.lengthSqr() < 1.0E-8) {
            direction = new Vec3(0.0, 0.0, 1.0);
        }
        return direction.normalize();
    }

    private static Vector3d invoke(BlockEntity target, Method method, String name) {
        if (target == null || method == null) {
            return null;
        }
        try {
            Object result = method.invoke(target);
            return result instanceof Vector3d vector ? vector : null;
        } catch (Throwable t) {
            FireControlCompat.LOGGER.debug("[firecontrolcompat] Hellfire bridge {} failed for {}", name, target, t);
            return null;
        }
    }

    private static Method positionMethod() {
        Method method = positionMethod;
        if (method == null) {
            positionMethod = method = findPublicMethod("position");
        }
        return method;
    }

    private static Method frontMethod() {
        Method method = frontMethod;
        if (method == null) {
            frontMethod = method = findPublicMethod("front");
        }
        return method;
    }

    private static Method findPublicMethod(String name) {
        try {
            Class<?> type = Class.forName(DIRECTOR_BLOCK_ENTITY);
            return type.getMethod(name);
        } catch (Throwable t) {
            FireControlCompat.LOGGER.debug("[firecontrolcompat] Hellfire bridge unavailable for {}: {}", name, t.toString());
            return null;
        }
    }
}

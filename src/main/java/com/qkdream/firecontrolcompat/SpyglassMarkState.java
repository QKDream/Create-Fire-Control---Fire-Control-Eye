package com.qkdream.firecontrolcompat;

import java.util.UUID;

import net.minecraft.world.phys.Vec3;

/**
 * The mark this client is showing a frame around.
 *
 * <p>Held apart from the renderer so the network handler can fill it in without
 * touching client classes, the same way the radar call sign is kept.</p>
 */
public final class SpyglassMarkState {

    private static volatile boolean active;
    private static volatile UUID structureId;
    private static volatile Vec3 point;

    private SpyglassMarkState() {
    }

    /** Replaces the shown mark, or clears it when {@code active} is false. */
    public static void apply(boolean active, UUID structureId, Vec3 point) {
        SpyglassMarkState.active = active && point != null;
        SpyglassMarkState.structureId = SpyglassMarkState.active ? structureId : null;
        SpyglassMarkState.point = SpyglassMarkState.active ? point : null;
    }

    public static boolean isActive() {
        return active && point != null;
    }

    /** The structure the mark belongs to, when the server named one. */
    public static UUID structureId() {
        return structureId;
    }

    /** Where the sight line met the structure. */
    public static Vec3 point() {
        return point;
    }
}
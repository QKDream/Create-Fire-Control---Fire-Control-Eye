package com.qkdream.firecontrolcompat;

import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

/**
 * The point a fire control computer has been told to strike by the airborne
 * strike terminal: a fixed place on the ground, kept on the computer itself, so
 * it survives a restart and is there for every round that leaves a rack the
 * computer holds.
 */
public final class StrikePoints {

    private static final String SET = "firecontrolcompat:StrikeSet";
    private static final String X = "firecontrolcompat:StrikeX";
    private static final String Y = "firecontrolcompat:StrikeY";
    private static final String Z = "firecontrolcompat:StrikeZ";

    private StrikePoints() {
    }

    /** Leaves a point on the computer. */
    public static void set(BlockEntity controller, Vec3 point) {
        if (controller == null || point == null) {
            return;
        }
        CompoundTag data = controller.getPersistentData();
        data.putBoolean(SET, true);
        data.putDouble(X, point.x);
        data.putDouble(Y, point.y);
        data.putDouble(Z, point.z);
        controller.setChanged();
    }

    /** Takes the point off the computer. */
    public static void clear(BlockEntity controller) {
        if (controller == null) {
            return;
        }
        CompoundTag data = controller.getPersistentData();
        data.remove(SET);
        data.remove(X);
        data.remove(Y);
        data.remove(Z);
        controller.setChanged();
    }

    /** The point the computer holds, or null while it holds none. */
    public static Vec3 pointOf(BlockEntity controller) {
        if (controller == null) {
            return null;
        }
        CompoundTag data = controller.getPersistentData();
        if (!data.getBoolean(SET)) {
            return null;
        }
        return new Vec3(data.getDouble(X), data.getDouble(Y), data.getDouble(Z));
    }

    /** The same, for a computer that is known. */
    public static Vec3 pointOf(StabilizerControllerBlockEntity controller) {
        return pointOf((BlockEntity) controller);
    }

    /** Whether the computer has been given a point to strike. */
    public static boolean isSet(BlockEntity controller) {
        return controller != null && controller.getPersistentData().getBoolean(SET);
    }
}

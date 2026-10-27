package com.qkdream.firecontrolcompat;

import com.hooya.stabilizedturret.content.controller.GuidedMissileMode;

import net.minecraft.world.entity.Entity;

/**
 * Asks fire control how a missile is being flown.
 *
 * <p>Since 0.9.3 a guided missile may be flown by the pack's own math flight instead of by an entity
 * that ticks: a flight record is advanced on the server and the client only draws the model. When
 * that is the case, this mod must not keep its own copy of the same round on screen or in the
 * tracking pass, or the shot appears twice and drifts out of step with the record.</p>
 *
 * <p>Read through one guarded call so a pack that does not carry the question answers "no" instead of
 * throwing.</p>
 */
public final class NativeMissileMode {

    private NativeMissileMode() {
    }

    /** True when fire control is flying this round mathematically, so this mod has nothing to add. */
    public static boolean mathematical(Entity entity) {
        if (entity == null) {
            return false;
        }
        try {
            return GuidedMissileMode.mathematical(entity);
        } catch (Throwable ignored) {
            return false;
        }
    }
}

package com.qkdream.firecontrolcompat;

import net.minecraft.world.phys.Vec3;

/**
 * Duck interface implemented onto TAOV's gun block entity so the fire-control
 * computer can steer the barrel directly (pod control path). The gun itself
 * has no yaw/pitch actuator: its launch direction is fixed along the block
 * facing transformed by its synaxis physics body. Fire control drives a
 * separate deflection stored here instead of touching the gun's own
 * horizontal/vertical offset state, which only shapes the ammo chain and
 * defaults to a non-zero horizontal value in vanilla TAOV.
 */
public interface TaovGunAimControl {

    /** Stores a deflection request; {@code horizontal}/{@code vertical} are tangent offsets in [-4, 4]. */
    void firecontrolcompat$setAim(double horizontalTangent, double verticalTangent);

    /**
     * Current world launch direction including active deflection, or
     * {@code null} when no fire-control deflection is currently active.
     */
    Vec3 firecontrolcompat$deflectedDirection();
}

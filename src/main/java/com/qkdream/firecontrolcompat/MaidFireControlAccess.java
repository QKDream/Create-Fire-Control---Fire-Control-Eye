package com.qkdream.firecontrolcompat;

import java.util.UUID;

/**
 * The two fire control computer internals the maid crew has to reach: the radar
 * lock it selects and the aim pass that slews the bound bearings. The mixin on
 * the controller implements this interface, so a cast from the block entity is
 * the only coupling.
 *
 * <p>It lives outside the maid package on purpose: the mixin that implements it
 * belongs to the fire control side, so it must compile even on a pack that has
 * no maid mod installed.</p>
 */
public interface MaidFireControlAccess {

    /** Selects (or clears, with null) the radar lock of the computer. */
    void firecontrolcompat$setRadarLockedSubLevelId(UUID id);

    /** Runs the computer's own aim pass for the desired world target. */
    void firecontrolcompat$applyAimDirection();
}

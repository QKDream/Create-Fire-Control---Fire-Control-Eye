package com.qkdream.firecontrolcompat.mixin;

import net.minecraft.world.entity.projectile.AbstractArrow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reaches the bow's own "stuck in the ground" flag.
 *
 * <p>面包学's cruise missile discards itself on the first tick that flag is set, so a round
 * that comes out of a rail already touching the ground is destroyed before anyone sees it -
 * no burst, no crater, just gone. The flag is written by the arrow when it lands, and nothing
 * public clears it, so it is reached here to lift a round that never had a chance to fly.</p>
 */
@Mixin(AbstractArrow.class)
public interface ArrowInGroundAccess {

    /** The flag the arrow keeps for "this one is stuck". */
    @Accessor("inGround")
    boolean firecontrolcompat$isInGround();

    /** Sets the flag the arrow keeps for "this one is stuck". */
    @Accessor("inGround")
    void firecontrolcompat$setInGround(boolean inGround);
}

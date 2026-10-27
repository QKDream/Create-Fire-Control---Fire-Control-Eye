package com.qkdream.firecontrolcompat;

import java.util.Set;
import net.minecraft.world.level.block.Block;

/**
 * The one thing this mod needs from a block entity type: to widen the blocks it
 * will accept.
 *
 * <p>It lives here rather than beside the mixin that implements it because a
 * mixin class may never be named by ordinary code. Casting a type to the mixin
 * interface crossed that line - the pack's own classloading then refused it and
 * the whole setup event failed, which is what the crash log of an ordinary
 * session showed. The mixin implements this interface, and the runtime casts to
 * this one.</p>
 */
public interface BlockEntityTypeAccess {

    /** Replaces the set of blocks this type accepts. */
    void firecontrolcompat$setValidBlocks(Set<Block> validBlocks);
}

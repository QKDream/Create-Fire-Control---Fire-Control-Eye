package com.qkdream.firecontrolcompat.compat.copycats;

import com.copycatsplus.copycats.foundation.copycat.multistate.MultiStateCopycatBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The block entity the part-read tiles use.
 *
 * <p>It is the camouflage mod's own multi state entity, so the material and the
 * parts a board or a half layer is dressed in are read, written and drawn by
 * that mod's code; only the entity type is this mod's, which is what makes the
 * tiles a shape of their own instead of a reuse of the ones already in the
 * game.</p>
 *
 * <p>The type is taken from the register rather than handed to the constructor,
 * because the game builds a block entity from its position and state alone.</p>
 */
public class SilencerBlockEntity extends MultiStateCopycatBlockEntity {

    public SilencerBlockEntity(BlockPos pos, BlockState state) {
        super(SilencerBlocks.blockEntity(), pos, state);
    }
}

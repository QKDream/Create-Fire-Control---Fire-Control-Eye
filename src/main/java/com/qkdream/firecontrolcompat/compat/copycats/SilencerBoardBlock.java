package com.qkdream.firecontrolcompat.compat.copycats;

import com.copycatsplus.copycats.content.copycat.board.CopycatBoardBlock;
import com.copycatsplus.copycats.foundation.copycat.multistate.MultiStateCopycatBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour.Properties;

/**
 * A board that swallows sound. Everything else - the six sides, the material,
 * the wrenching - is the camouflage mod's own board, unchanged.
 */
public class SilencerBoardBlock extends CopycatBoardBlock {

    public SilencerBoardBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntityType<? extends MultiStateCopycatBlockEntity> getBlockEntityType() {
        return SilencerBlocks.blockEntity();
    }
}

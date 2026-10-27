package com.qkdream.firecontrolcompat.compat.copycats;

import com.copycatsplus.copycats.foundation.copycat.CCCopycatBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The block entity of the layer shaped tile: the camouflage mod's plain copycat
 * entity, which holds the one material a single piece block is dressed in,
 * tied to this mod's own entity type for the same reason as the part-read one.
 */
public class SilencerLayerBlockEntity extends CCCopycatBlockEntity {

    public SilencerLayerBlockEntity(BlockPos pos, BlockState state) {
        super(SilencerBlocks.layerBlockEntity(), pos, state);
    }
}

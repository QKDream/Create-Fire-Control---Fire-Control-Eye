package com.qkdream.firecontrolcompat.compat.copycats;

import com.copycatsplus.copycats.content.copycat.layer.CopycatLayerBlock;
import com.copycatsplus.copycats.foundation.copycat.CCCopycatBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour.Properties;

/**
 * A sheet of layers that swallows sound. The stacking, the facing and the
 * sneak-wrenching away of one layer at a time are the camouflage mod's own.
 */
public class SilencerLayerBlock extends CopycatLayerBlock {

    public SilencerLayerBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntityType<? extends CCCopycatBlockEntity> getBlockEntityType() {
        return SilencerBlocks.layerBlockEntity();
    }
}

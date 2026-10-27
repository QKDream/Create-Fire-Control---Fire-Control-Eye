package com.qkdream.firecontrolcompat.compat.copycats;

import com.copycatsplus.copycats.CCBlocks;
import com.copycatsplus.copycats.content.copycat.half_layer.CopycatHalfLayerBlock;
import com.copycatsplus.copycats.foundation.copycat.multistate.IMultiStateCopycatBlockEntity;
import com.copycatsplus.copycats.foundation.copycat.multistate.MultiStateCopycatBlockEntity;
import com.copycatsplus.copycats.utility.BlockUtils;
import com.simibubi.create.content.contraptions.StructureTransform;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour.Properties;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The half layer as a silencer tile.
 *
 * <p>Its two halves are measured by a helper that reads its own block out of
 * the state before it can work out where they sat, and this is not that block,
 * so both the state and the material it is dressed in are handed over as that
 * block's kind of state for the sum and read back into this one afterwards. A
 * shape the helper still cannot make sense of keeps its place rather than
 * taking the world down with it.</p>
 */
public class SilencerHalfLayerBlock extends CopycatHalfLayerBlock {

    public SilencerHalfLayerBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntityType<? extends MultiStateCopycatBlockEntity> getBlockEntityType() {
        return SilencerBlocks.blockEntity();
    }

    @Override
    public BlockState transform(BlockState state, StructureTransform transform) {
        try {
            BlockState asCopycat = asCopycat(state);
            BlockState moved = CopycatHalfLayerBlock.fromTransformableState(asCopycat,
                    CopycatHalfLayerBlock.toTransformableState(asCopycat).transform(transform));
            return BlockUtils.tryCopyProperties(moved, defaultBlockState());
        } catch (Throwable ignored) {
            return state;
        }
    }

    @Override
    public void transformStorage(BlockState state, IMultiStateCopycatBlockEntity be, StructureTransform transform) {
        try {
            BlockState asCopycat = asCopycat(state);
            BlockState moved = CopycatHalfLayerBlock.fromTransformableState(asCopycat,
                    CopycatHalfLayerBlock.toTransformableState(asCopycat).untransform(transform));
            CopycatHalfLayerBlock.fromTransformableStorage(moved, be,
                    CopycatHalfLayerBlock.toTransformableStorage(moved, be).transform(transform));
        } catch (Throwable ignored) {
        }
    }

    /** The same tile, read through the block the transform helpers expect. */
    private static BlockState asCopycat(BlockState state) {
        return BlockUtils.tryCopyProperties(state, CCBlocks.COPYCAT_HALF_LAYER.get().defaultBlockState());
    }
}

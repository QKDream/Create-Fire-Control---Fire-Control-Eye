package com.qkdream.firecontrolcompat.sonar;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The man-portable set: a tripod a squad can put down anywhere.
 *
 * <p>It answers to nobody - no fire control computer, no wiring - and sweeps a
 * window of its own: fifty blocks out through a ninety degree gate, once every
 * three seconds, marking whatever the water is carrying inside it. The marks
 * are drawn in the world for a few seconds, so a source can be walked up on
 * rather than read off a screen.</p>
 */
public class ManportableSonarBlock extends HorizontalDirectionalBlock implements EntityBlock {

    public static final MapCodec<ManportableSonarBlock> CODEC = simpleCodec(ManportableSonarBlock::new);

    public ManportableSonarBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ManportableSonarBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide ? null : ManportableSonarBlockEntity::serverTick;
    }

    /** Right-clicking the set reports what it is holding on to right now. */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof ManportableSonarBlockEntity set)) {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide) {
            int marks = set.contacts().size();
            player.displayClientMessage(marks == 0
                    ? Component.translatable("message.firecontrolcompat.sonar.manportable_quiet")
                    : Component.translatable("message.firecontrolcompat.sonar.manportable_marks", marks), true);
        }
        return InteractionResult.SUCCESS;
    }
}

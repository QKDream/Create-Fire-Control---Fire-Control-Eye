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
 * The sonar set itself. Mount it on a hull, point it with the wrench, and wire
 * it to a fire control computer: whatever the water is carrying inside its
 * window shows up on the set's picture.
 *
 * <p>The dish listens every way out of the box. Turning it to a bearing and
 * narrowing the window is done on the fire control screen, so a set that has
 * not been wired to anything yet still hears the whole horizon.</p>
 */
public class SonarBlock extends HorizontalDirectionalBlock implements EntityBlock {

    public static final MapCodec<SonarBlock> CODEC = simpleCodec(SonarBlock::new);

    public SonarBlock(Properties properties) {
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
        return new SonarBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide ? null : SonarBlockEntity::serverTick;
    }

    /** Right-clicking the dish reports who it answers to. */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof SonarBlockEntity sonar)) {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide) {
            player.displayClientMessage(
                    Component.translatable(sonar.isBound()
                            ? "message.firecontrolcompat.sonar.status_bound"
                            : "message.firecontrolcompat.sonar.status_unbound"),
                    true);
        }
        return InteractionResult.SUCCESS;
    }
}

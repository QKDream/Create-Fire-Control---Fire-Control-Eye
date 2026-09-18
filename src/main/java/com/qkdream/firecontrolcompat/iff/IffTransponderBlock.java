package com.qkdream.firecontrolcompat.iff;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Identification friend-or-foe transponder. Right click to open the frequency
 * band screen; wrench it onto a fire control computer to link the two.
 */
public class IffTransponderBlock extends BaseEntityBlock {

    public static final MapCodec<IffTransponderBlock> CODEC = simpleCodec(IffTransponderBlock::new);

    /**
     * Outline and collision of the block model: a 4/16 base plate covering the
     * whole footprint plus the 22.5 degree panel leaning over its northern
     * half, which reaches 7.8/16 and 10.84/16 towards south. The union is used
     * so players no longer bump into an invisible full cube.
     */
    private static final VoxelShape SHAPE = Shapes.or(
            Shapes.box(0.0, 0.0, 0.0, 1.0, 4.0 / 16.0, 1.0),
            Shapes.box(0.0, 0.0, 0.0, 1.0, 7.8 / 16.0, 10.84 / 16.0));

    public IffTransponderBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected VoxelShape getCollisionShape(
            BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new IffTransponderBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(
            BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof IffTransponderBlockEntity transponder) {
            player.openMenu(transponder);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof IffTransponderBlockEntity transponder) {
            transponder.dropContents();
        }
        return super.playerWillDestroy(level, pos, state, player);
    }
}
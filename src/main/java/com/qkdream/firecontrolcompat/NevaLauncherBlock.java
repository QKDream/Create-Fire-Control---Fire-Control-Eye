package com.qkdream.firecontrolcompat;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The Neva (S-125) super-heavy air-defense missile as a placed block: the
 * missile itself stands on the ground, the fire control computer binds it
 * like an air-defense launcher and firing it launches the missile from the
 * block.
 */
public class NevaLauncherBlock extends Block implements EntityBlock {

    public NevaLauncherBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new NevaLauncherBlockEntity(pos, state);
    }

    @Override
    public InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                            BlockHitResult hit) {
        if (!level.isClientSide()) {
            player.displayClientMessage(
                    Component.translatable("message.firecontrolcompat.neva_ready"), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }
}

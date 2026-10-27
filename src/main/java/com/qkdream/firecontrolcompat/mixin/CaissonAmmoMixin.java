package com.qkdream.firecontrolcompat.mixin;

import com.qkdream.firecontrolcompat.crate.AmmoCrateBlockEntity;
import com.qkdream.firecontrolcompat.crate.AmmoCrateInteractions;
import net.mcreator.myfirstmod.block.CaissonBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Turns 面包学's ammunition box into the ammunition store of this mod.
 *
 * <p>The box keeps its looks and its place in the build palette; what is added
 * is the block entity behind it: eight rounds of one 面包学 / TAOV missile type,
 * stored with a right click, taken back with an empty hand, and taken out by a
 * maid carrying the loading order. Dropping the rounds when the box is broken
 * keeps them from vanishing with it.</p>
 */
@Mixin(CaissonBlock.class)
public abstract class CaissonAmmoMixin implements EntityBlock {

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new AmmoCrateBlockEntity(pos, state);
    }

    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hit) {
        ItemInteractionResult stored = AmmoCrateInteractions.store(stack, level, pos, player);
        return stored == ItemInteractionResult.SUCCESS
                ? stored
                : ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hit) {
        InteractionResult taken = AmmoCrateInteractions.take(level, pos, player);
        return taken == InteractionResult.SUCCESS ? taken : InteractionResult.PASS;
    }

    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            AmmoCrateInteractions.dropContents(level, pos);
        }
    }
}

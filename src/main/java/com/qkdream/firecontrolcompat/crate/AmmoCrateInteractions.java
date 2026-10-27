package com.qkdream.firecontrolcompat.crate;

import com.qkdream.firecontrolcompat.AmmoClipboardItem;
import com.qkdream.firecontrolcompat.AmmoLoadingCompat;
import com.qkdream.firecontrolcompat.SubLevelPositions;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * What right clicking an ammunition store does.
 *
 * <p>The store itself lives in 面包学's ammunition box, so this is shared code
 * the block mixes in rather than a block of its own: right click holding a
 * 面包学 / TAOV / 火控之眼 missile stores it (eight rounds, one type), right
 * click with an empty hand takes one round back out, while a hand holding the
 * loading table binds the store instead of spending a round.</p>
 */
public final class AmmoCrateInteractions {

    private AmmoCrateInteractions() {
    }

    /** Stores the held round; a block that does not care keeps its own behaviour. */
    public static ItemInteractionResult store(ItemStack stack, Level level, BlockPos pos, Player player) {
        AmmoCrateBlockEntity crate = crate(level, pos);
        if (crate == null || stack.isEmpty() || !AmmoLoadingCompat.isMissileAmmo(stack)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (level.isClientSide()) {
            return ItemInteractionResult.SUCCESS;
        }
        int accepted = crate.insert(stack);
        if (accepted <= 0) {
            player.displayClientMessage(Component.literal(crate.isEmpty()
                    ? "弹药箱只接受面包学、TAOV或火控之眼的导弹"
                    : "弹药箱已满，或里面装的是另一种弹药"), true);
            return ItemInteractionResult.SUCCESS;
        }
        if (!player.getAbilities().instabuild) {
            stack.shrink(accepted);
        }
        player.displayClientMessage(Component.literal("弹药箱：" + crate.storedType().getHoverName().getString()
                + " " + crate.count() + "/" + AmmoCrateBlockEntity.CAPACITY), true);
        return ItemInteractionResult.SUCCESS;
    }

    /** Takes a single round out, or says why it could not. */
    public static InteractionResult take(Level level, BlockPos pos, Player player) {
        AmmoCrateBlockEntity crate = crate(level, pos);
        if (crate == null) {
            return InteractionResult.PASS;
        }
        // The loading table sits in the very hand that right clicks and needs
        // this same click to record the store, so the order wins the click and
        // nothing is taken out while an order is being written.
        if (player != null && AmmoClipboardItem.isClipboard(player.getMainHandItem())) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (crate.isEmpty()) {
            player.displayClientMessage(Component.literal("弹药箱是空的"), true);
            return InteractionResult.SUCCESS;
        }
        ItemStack out = crate.extractOne();
        if (!out.isEmpty() && !player.getInventory().add(out)) {
            player.drop(out, false);
        }
        player.displayClientMessage(Component.literal("取出：" + out.getHoverName().getString()
                + "（剩余 " + crate.count() + "/" + AmmoCrateBlockEntity.CAPACITY + "）"), true);
        return InteractionResult.SUCCESS;
    }

    /** Drops the rounds still inside; used when the store is broken. */
    public static void dropContents(Level level, BlockPos pos) {
        if (level != null && !level.isClientSide()
                && SubLevelPositions.blockEntity(level, pos) instanceof AmmoCrateBlockEntity crate) {
            crate.dropContents();
        }
    }

    private static AmmoCrateBlockEntity crate(Level level, BlockPos pos) {
        return SubLevelPositions.blockEntity(level, pos) instanceof AmmoCrateBlockEntity crate ? crate : null;
    }
}

package com.qkdream.firecontrolcompat.mixin;

import com.cainiao1053.cbcmoreshells.blocks.ammo_rack.AmmoRackBlock;
import com.qkdream.firecontrolcompat.WeaponHudLinker;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The TAOV HUD-linker binds through {@code Item#useOn}, but the CBCMS ammo
 * rack consumes every main-hand click on its front face in
 * {@code Block#useItemOn} (insert/extract) before the held item runs. While
 * the player is holding the linker, let the click pass through so the rack
 * can be bound as a weapon-HUD source instead of receiving the linker as
 * ammunition.
 */
@Mixin(AmmoRackBlock.class)
public abstract class CbcAmmoRackLinkerUseMixin {

    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
    private void firecontrolcompat$allowLinkerBinding(
            ItemStack stack,
            BlockState state,
            Level level,
            BlockPos pos,
            Player player,
            InteractionHand hand,
            BlockHitResult hit,
            CallbackInfoReturnable<ItemInteractionResult> cir) {
        if (WeaponHudLinker.isLinker(player.getMainHandItem())) {
            cir.setReturnValue(ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION);
        }
    }
}

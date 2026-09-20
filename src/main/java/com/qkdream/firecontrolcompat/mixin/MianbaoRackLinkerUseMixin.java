package com.qkdream.firecontrolcompat.mixin;

import com.qkdream.firecontrolcompat.WeaponHudLinker;
import net.mcreator.myfirstmod.block.RackBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
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
 * The mianbao weapon rack consumes the main-hand click while loading, which
 * stops the TAOV HUD-linker from binding through {@code Item#useOn}.
 *
 * <p>The rack moved its entry point between mod versions: 2.5.1 handled the
 * click in {@code useWithoutItem}, 2.5.2 in {@code useItemOn}. Patching a
 * method a target class does not have aborts mixin application and breaks mod
 * loading, so both hooks are declared with {@code require = 0}: whichever one
 * exists wins and a future rename cannot take the whole game down again.</p>
 */
@Mixin(RackBlock.class)
public abstract class MianbaoRackLinkerUseMixin {

    @Inject(method = "useWithoutItem", at = @At("HEAD"), cancellable = true, require = 0)
    private void firecontrolcompat$allowLinkerBindingLegacy(
            BlockState state,
            Level level,
            BlockPos pos,
            Player player,
            BlockHitResult hit,
            CallbackInfoReturnable<InteractionResult> cir) {
        if (WeaponHudLinker.isLinker(player.getMainHandItem())) {
            cir.setReturnValue(InteractionResult.PASS);
        }
    }

    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true, require = 0)
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

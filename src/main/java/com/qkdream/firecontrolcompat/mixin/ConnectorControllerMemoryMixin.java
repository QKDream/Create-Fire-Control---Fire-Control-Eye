package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlock;
import com.qkdream.firecontrolcompat.VestalihyBridge;
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
 * Lets vestalihy's optics connector name a fire control computer the way it names
 * an optics module: used on the computer, the connector files that computer away
 * on itself, and the next use of the connector on a launcher hands the launcher
 * to that computer. Clicking the computer with the connector while sneaking
 * forgets it again.
 *
 * <p>The click is consumed, so the connector behaves like the wiring tool it is
 * and wiring two devices never opens the fire control screen on the way. An empty
 * hand still opens it, and the connector is only recognised by its registry name,
 * so nothing here needs vestalihy to be installed.</p>
 */
@Mixin(StabilizerControllerBlock.class)
public abstract class ConnectorControllerMemoryMixin {

    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
    private void firecontrolcompat$rememberController(
            ItemStack stack,
            BlockState state,
            Level level,
            BlockPos pos,
            Player player,
            InteractionHand hand,
            BlockHitResult hit,
            CallbackInfoReturnable<ItemInteractionResult> cir) {
        if (!VestalihyBridge.isOpticsConnector(stack)) {
            return;
        }
        VestalihyBridge.rememberController(player, level, stack, pos);
        cir.setReturnValue(ItemInteractionResult.sidedSuccess(level.isClientSide));
    }
}

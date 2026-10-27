package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.qkdream.firecontrolcompat.SeatPriority;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Free look is a steering right, so a relief post does not hold it while the
 * posts in front of it are manned. The aim session handler asks this to decide
 * whether the player's view drives the mount, and the pack's own seat check
 * cannot tell the posts of a chain apart.
 */
@Mixin(StabilizerControllerBlockEntity.class)
public abstract class FreeLookSeatPriorityMixin {

    @Inject(method = "isFreeLookHeld", at = @At("RETURN"), cancellable = true)
    private void firecontrolcompat$reliefPostCannotFreeLook(ServerPlayer player, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && !SeatPriority.maySteer(player)) {
            cir.setReturnValue(false);
        }
    }
}
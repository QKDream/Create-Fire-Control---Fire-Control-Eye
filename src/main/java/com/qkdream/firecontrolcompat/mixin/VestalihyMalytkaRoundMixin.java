package com.qkdream.firecontrolcompat.mixin;

import com.qkdream.firecontrolcompat.VestalihyBridge;
import com.vestalihy.entity.MalytkaEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets the 9M14 answer to the player at the fire control computer.
 *
 * <p>The round asks its vestalihy scope who is steering it, which is the player
 * looking through that scope. When the round was released by a fire control
 * computer, though, nobody is behind a scope: the player is sitting at the
 * computer, so the round also accepts the operator of the computer that owns
 * the launcher it came from. Everything else about its keyboard steering is
 * untouched.</p>
 */
@Mixin(MalytkaEntity.class)
public abstract class VestalihyMalytkaRoundMixin {

    @Inject(method = "isGuidedBy", at = @At("RETURN"), cancellable = true)
    private void firecontrolcompat$steerFromFireControlSeat(Player player, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()
                && VestalihyBridge.isFireControlSteerable((MalytkaEntity) (Object) this, player)) {
            cir.setReturnValue(true);
        }
    }
}

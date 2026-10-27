package com.qkdream.firecontrolcompat.mixin;

import com.qkdream.firecontrolcompat.VestalihyBridge;
import com.vestalihy.entity.MalytkaLauncherEntity;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The 9M14 tube handles its own clicks instead of inheriting the shared tube
 * handler, so it needs the connector binding of its own. Only the binding is
 * added here; the round it fires keeps its keyboard steering, because the
 * 9M14 is not one of the rounds the fire control computer flies. The round is
 * told which launcher it left, so the player sitting at the computer that owns
 * that launcher is the one whose keyboard steering it obeys.
 */
@Mixin(MalytkaLauncherEntity.class)
public abstract class VestalihyMalytkaLauncherMixin {

    @Inject(method = "interact", at = @At("HEAD"))
    private void firecontrolcompat$bindToFireControl(Player player, InteractionHand hand,
            CallbackInfoReturnable<InteractionResult> cir) {
        VestalihyBridge.tryBindWithConnector(player, (MalytkaLauncherEntity) (Object) this);
    }

    @Inject(method = "launch", at = @At("RETURN"))
    private void firecontrolcompat$rememberFiredRound(CallbackInfoReturnable<Boolean> cir) {
        if (Boolean.TRUE.equals(cir.getReturnValue())) {
            VestalihyBridge.markSteerableRounds((Entity) (Object) this);
        }
    }
}

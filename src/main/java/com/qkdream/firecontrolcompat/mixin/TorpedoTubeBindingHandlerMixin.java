package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.content.controller.StabilizerBindingHandler;
import com.qkdream.firecontrolcompat.underwater.TorpedoTubeBinding;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets the wrench wire a torpedo tube to a fire control computer before the
 * computer's own binding flow sees the click, which would otherwise turn the
 * tube down - a tube is not one of the modules that flow knows about. Optional,
 * so a renamed handler in a later release costs the wrench flow rather than the
 * whole game.
 */
@Mixin(StabilizerBindingHandler.class)
public abstract class TorpedoTubeBindingHandlerMixin {

    @Inject(method = "onRightClickBlock", at = @At("HEAD"), cancellable = true, require = 0)
    private static void firecontrolcompat$torpedoTubeBinding(
            PlayerInteractEvent.RightClickBlock event, CallbackInfo ci) {
        if (TorpedoTubeBinding.handle(event)) {
            ci.cancel();
        }
    }
}

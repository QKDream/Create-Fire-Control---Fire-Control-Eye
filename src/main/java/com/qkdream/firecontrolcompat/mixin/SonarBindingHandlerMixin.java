package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.content.controller.StabilizerBindingHandler;
import com.qkdream.firecontrolcompat.sonar.SonarBinding;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets the wrench wire a sonar set to a fire control computer before the
 * computer's own binding flow sees the click. Optional, so a renamed handler in
 * a later fire control release costs the wrench flow rather than the whole game.
 */
@Mixin(StabilizerBindingHandler.class)
public abstract class SonarBindingHandlerMixin {

    @Inject(method = "onRightClickBlock", at = @At("HEAD"), cancellable = true, require = 0)
    private static void firecontrolcompat$sonarBinding(
            PlayerInteractEvent.RightClickBlock event, CallbackInfo ci) {
        if (SonarBinding.handle(event)) {
            ci.cancel();
        }
    }
}

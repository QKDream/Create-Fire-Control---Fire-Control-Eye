package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.content.controller.MianbaoOpticalMissileHandler;
import com.qkdream.firecontrolcompat.entity.LoiteringMissileEntity;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The native television guidance ramps every optical missile to 8.5075
 * blocks per tick. Once it has finished steering for the tick, adopted
 * loitering munitions are rescaled onto their own slower speed curve.
 */
@Mixin(MianbaoOpticalMissileHandler.class)
public abstract class LoiteringSpeedMixin {

    @Inject(method = "tick", at = @At("RETURN"))
    private static void firecontrolcompat$rescaleLoiteringMunitions(ServerTickEvent.Post event, CallbackInfo ci) {
        LoiteringMissileEntity.scaleGuidedVelocity();
    }
}

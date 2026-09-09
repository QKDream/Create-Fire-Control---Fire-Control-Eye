package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.content.display.AutoDefenseMissileGuidance;
import com.qkdream.firecontrolcompat.entity.HeavyAirDefenseMissileEntity;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The connected display interceptor guidance caps every missile at 34.03
 * blocks per tick. Once it has finished steering for the tick, heavy
 * missiles are rescaled onto their own 3 Mach acceleration curve.
 */
@Mixin(AutoDefenseMissileGuidance.class)
public abstract class HeavyAirDefenseDisplaySpeedMixin {

    @Inject(method = "onTick", at = @At("RETURN"))
    private static void firecontrolcompat$rescaleHeavyMissiles(ServerTickEvent.Post event, CallbackInfo ci) {
        HeavyAirDefenseMissileEntity.scaleGuidedVelocity();
    }
}
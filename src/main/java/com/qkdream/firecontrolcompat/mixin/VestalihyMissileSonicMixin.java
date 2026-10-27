package com.qkdream.firecontrolcompat.mixin;

import com.qkdream.firecontrolcompat.SonicPulse;
import com.vestalihy.entity.PturEntity;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets the sculk network hear vestalihy's missiles fly.
 *
 * <p>Malytka, TOW and the rest all sit on this one guided round, so a single
 * tick hook covers the family: the launch on the first tick, the flight after
 * it, and the swim when the round is a torpedo.</p>
 */
@Mixin(PturEntity.class)
public abstract class VestalihyMissileSonicMixin {

    @Inject(method = "tick", at = @At("HEAD"), require = 0)
    private void firecontrolcompat$munitionNoise(CallbackInfo ci) {
        try {
            SonicPulse.projectile((Entity) (Object) this);
        } catch (Throwable ignored) {
        }
    }
}

package com.qkdream.firecontrolcompat.mixin;

import com.qkdream.firecontrolcompat.underwater.AirdroppedTorpedoRack;
import net.mcreator.myfirstmod.procedures.High_explosive_visual_guided_torpedo_goProcedure;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fires the air-dropped acoustic torpedo off 面包学's rail.
 *
 * <p>The rail fires through their own visual guided launch, so the shot is taken
 * over at its head: a rail carrying our round lets the acoustic one go and the
 * empty rack is left behind; every other rail - and the visual round standing in
 * the world - keeps the mod's own launch.</p>
 */
@Mixin(High_explosive_visual_guided_torpedo_goProcedure.class)
public abstract class AirdroppedTorpedoRackFireMixin {

    @Inject(method = "execute", at = @At("HEAD"), cancellable = true)
    private static void firecontrolcompat$fireAirdroppedTorpedo(
            LevelAccessor world, double x, double y, double z, CallbackInfo ci) {
        if (AirdroppedTorpedoRack.tryFire(world, x, y, z)) {
            ci.cancel();
        }
    }
}

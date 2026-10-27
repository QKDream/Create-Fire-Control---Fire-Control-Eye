package com.qkdream.firecontrolcompat.mixin;

import com.qkdream.firecontrolcompat.underwater.AirdroppedTorpedoRack;
import net.mcreator.myfirstmod.procedures.RackreloadProcedure;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hangs the air-dropped acoustic torpedo on the aircraft weapon rack. */
@Mixin(RackreloadProcedure.class)
public abstract class AirdroppedTorpedoRackLoadMixin {

    @Inject(method = "execute", at = @At("HEAD"), cancellable = true)
    private static void firecontrolcompat$loadAirdroppedTorpedo(
            LevelAccessor world, double x, double y, double z, Entity entity, CallbackInfo ci) {
        if (AirdroppedTorpedoRack.tryLoad(world, x, y, z, entity)) {
            ci.cancel();
        }
    }
}

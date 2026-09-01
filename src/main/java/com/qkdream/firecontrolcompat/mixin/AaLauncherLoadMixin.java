package com.qkdream.firecontrolcompat.mixin;

import com.qkdream.firecontrolcompat.BeamMissileCompat;
import net.mcreator.myfirstmod.procedures.AntiairmissilelauncherreloadProcedure;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Loads the beam-riding missile into the AA missile launcher (capacity 4). */
@Mixin(AntiairmissilelauncherreloadProcedure.class)
public abstract class AaLauncherLoadMixin {

    @Inject(method = "execute", at = @At("HEAD"), cancellable = true)
    private static void firecontrolcompat$loadBeamMissile(
            LevelAccessor world, double x, double y, double z, Entity entity, CallbackInfo ci) {
        if (BeamMissileCompat.tryLoadAntiAir(world, x, y, z, entity, 4.0)) {
            ci.cancel();
        }
    }
}

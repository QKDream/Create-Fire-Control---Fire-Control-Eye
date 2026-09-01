package com.qkdream.firecontrolcompat.mixin;

import com.qkdream.firecontrolcompat.BeamMissileCompat;
import net.mcreator.myfirstmod.procedures.AntiairmissilelaunchergoProcedure;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Fires the beam-riding missile from the AA missile launcher. */
@Mixin(AntiairmissilelaunchergoProcedure.class)
public abstract class AaLauncherFireMixin {

    @Inject(method = "execute", at = @At("HEAD"), cancellable = true)
    private static void firecontrolcompat$fireBeamMissile(
            LevelAccessor world, double x, double y, double z, CallbackInfo ci) {
        if (BeamMissileCompat.tryFireAntiAir(world, x, y, z, 2.6, 6.0F)) {
            ci.cancel();
        }
    }
}

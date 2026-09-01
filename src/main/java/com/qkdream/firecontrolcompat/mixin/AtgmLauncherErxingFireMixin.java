package com.qkdream.firecontrolcompat.mixin;

import com.qkdream.firecontrolcompat.BeamMissileCompat;
import net.mcreator.myfirstmod.procedures.AntitankmissilelaunchererxingWORKProcedure;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Fires the beam-riding missile from the two-barrel ATGM launcher. */
@Mixin(AntitankmissilelaunchererxingWORKProcedure.class)
public abstract class AtgmLauncherErxingFireMixin {

    @Inject(method = "execute", at = @At("HEAD"), cancellable = true)
    private static void firecontrolcompat$fireBeamMissile(
            LevelAccessor world, double x, double y, double z, CallbackInfo ci) {
        if (BeamMissileCompat.tryFireAtgm(world, x, y, z, false)) {
            ci.cancel();
        }
    }
}

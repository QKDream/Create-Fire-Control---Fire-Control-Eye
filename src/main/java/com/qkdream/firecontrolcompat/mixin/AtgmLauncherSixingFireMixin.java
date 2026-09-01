package com.qkdream.firecontrolcompat.mixin;

import com.qkdream.firecontrolcompat.BeamMissileCompat;
import net.mcreator.myfirstmod.procedures.Antitankmissilelauncher_sixing_fireProcedure;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Fires the beam-riding missile from the four-barrel ATGM launcher. */
@Mixin(Antitankmissilelauncher_sixing_fireProcedure.class)
public abstract class AtgmLauncherSixingFireMixin {

    @Inject(method = "execute", at = @At("HEAD"), cancellable = true)
    private static void firecontrolcompat$fireBeamMissile(
            LevelAccessor world, double x, double y, double z, CallbackInfo ci) {
        if (BeamMissileCompat.tryFireAtgm(world, x, y, z, false)) {
            ci.cancel();
        }
    }
}

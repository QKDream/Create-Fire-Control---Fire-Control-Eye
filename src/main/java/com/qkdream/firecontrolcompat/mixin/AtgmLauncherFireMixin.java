package com.qkdream.firecontrolcompat.mixin;

import com.qkdream.firecontrolcompat.BeamMissileCompat;
import net.mcreator.myfirstmod.procedures.AntitankMissilelaunchergoProcedure;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Fires the beam-riding missile from the ATGM launcher. */
@Mixin(AntitankMissilelaunchergoProcedure.class)
public abstract class AtgmLauncherFireMixin {

    @Inject(method = "execute", at = @At("HEAD"), cancellable = true)
    private static void firecontrolcompat$fireBeamMissile(
            LevelAccessor world, double x, double y, double z, CallbackInfo ci) {
        if (BeamMissileCompat.tryFireAtgm(world, x, y, z, true)) {
            ci.cancel();
        }
    }
}

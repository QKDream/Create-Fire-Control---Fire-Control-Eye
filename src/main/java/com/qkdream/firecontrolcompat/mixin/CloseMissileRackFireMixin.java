package com.qkdream.firecontrolcompat.mixin;

import com.qkdream.firecontrolcompat.BeamMissileCompat;
import net.mcreator.myfirstmod.procedures.CloseMissile1rackgoProcedure;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fires the aircraft infrared missile from the close-missile rack. When the
 * rack carries our missile (marked in its block entity data) the native
 * close-missile spawn is replaced by the infrared missile; native close
 * missiles keep their original behaviour.
 */
@Mixin(CloseMissile1rackgoProcedure.class)
public abstract class CloseMissileRackFireMixin {

    @Inject(method = "execute", at = @At("HEAD"), cancellable = true)
    private static void firecontrolcompat$fireInfraredMissile(
            LevelAccessor world, double x, double y, double z, CallbackInfo ci) {
        if (BeamMissileCompat.tryFireRack(world, x, y, z)) {
            ci.cancel();
        }
    }
}
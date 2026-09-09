package com.qkdream.firecontrolcompat.mixin;

import com.qkdream.firecontrolcompat.HeavyAirDefenseCompat;
import net.mcreator.myfirstmod.procedures.GroundmissilelaunchergoProcedure;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Fires the heavy air-defense missile from the mianbao vertical silo. */
@Mixin(GroundmissilelaunchergoProcedure.class)
public abstract class GroundMissileLauncherFireMixin {

    @Inject(method = "execute", at = @At("HEAD"), cancellable = true)
    private static void firecontrolcompat$fireHeavyMissile(
            LevelAccessor world, double x, double y, double z, CallbackInfo ci) {
        if (HeavyAirDefenseCompat.tryLaunchSilo(world, x, y, z)) {
            ci.cancel();
        }
    }
}
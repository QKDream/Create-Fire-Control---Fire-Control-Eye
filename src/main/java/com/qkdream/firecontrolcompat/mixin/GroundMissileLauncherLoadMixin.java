package com.qkdream.firecontrolcompat.mixin;

import com.qkdream.firecontrolcompat.HeavyAirDefenseCompat;
import net.mcreator.myfirstmod.procedures.GroundmissilelauncherreloadProcedure;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Loads the heavy air-defense missile into the mianbao vertical silo. */
@Mixin(GroundmissilelauncherreloadProcedure.class)
public abstract class GroundMissileLauncherLoadMixin {

    @Inject(method = "execute", at = @At("HEAD"), cancellable = true)
    private static void firecontrolcompat$loadHeavyMissile(
            LevelAccessor world, double x, double y, double z, Entity entity, CallbackInfo ci) {
        if (HeavyAirDefenseCompat.tryLoadSilo(world, x, y, z, entity)) {
            ci.cancel();
        }
    }
}
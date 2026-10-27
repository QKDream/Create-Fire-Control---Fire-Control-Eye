package com.qkdream.firecontrolcompat.mixin;

import com.qkdream.firecontrolcompat.underwater.AcousticTorpedoMianbaoTube;
import net.mcreator.myfirstmod.procedures.Torpedo_launcher_fireProcedure;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Fires the acoustic torpedo out of 面包学's torpedo tube. */
@Mixin(Torpedo_launcher_fireProcedure.class)
public abstract class MianbaoTorpedoLauncherFireMixin {

    @Inject(method = "execute", at = @At("HEAD"), cancellable = true)
    private static void firecontrolcompat$fireAcousticTorpedo(
            LevelAccessor world, double x, double y, double z, BlockState blockstate, CallbackInfo ci) {
        if (AcousticTorpedoMianbaoTube.tryFire(world, x, y, z, blockstate)) {
            ci.cancel();
        }
    }
}

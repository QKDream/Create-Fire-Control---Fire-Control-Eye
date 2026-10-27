package com.qkdream.firecontrolcompat.mixin;

import com.qkdream.firecontrolcompat.underwater.AcousticTorpedoMianbaoTube;
import net.mcreator.myfirstmod.procedures.Torpedo_launcher_reloadProcedure;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Loads the acoustic torpedo into 面包学's torpedo tube. */
@Mixin(Torpedo_launcher_reloadProcedure.class)
public abstract class MianbaoTorpedoLauncherLoadMixin {

    @Inject(method = "execute", at = @At("HEAD"), cancellable = true)
    private static void firecontrolcompat$loadAcousticTorpedo(
            LevelAccessor world, double x, double y, double z, BlockState blockstate, Entity entity, CallbackInfo ci) {
        if (AcousticTorpedoMianbaoTube.tryLoad(world, x, y, z, blockstate, entity)) {
            ci.cancel();
        }
    }
}

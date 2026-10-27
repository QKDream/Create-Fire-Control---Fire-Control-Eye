package com.qkdream.firecontrolcompat.mixin;

import com.qkdream.firecontrolcompat.underwater.TorpedoDecoyLauncher;
import net.mcreator.myfirstmod.procedures.AntimangrenadechangeProcedure;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Loads the decoy into 面包学's anti-infantry grenade launcher.
 *
 * <p>The launcher's own reload only knows its own grenade, and it is the one
 * place that fills the drum, so a decoy going in has to be taken here: the same
 * click, the same round up on the drum, and the round noted as one of ours so
 * the throw that follows puts a decoy in the air.</p>
 */
@Mixin(AntimangrenadechangeProcedure.class)
public abstract class MianbaoGrenadeDecoyLoadMixin {

    @Inject(method = "execute", at = @At("HEAD"), cancellable = true)
    private static void firecontrolcompat$loadDecoy(
            LevelAccessor world, double x, double y, double z, Entity entity, CallbackInfo ci) {
        if (TorpedoDecoyLauncher.tryLoad(world, x, y, z, entity)) {
            ci.cancel();
        }
    }
}

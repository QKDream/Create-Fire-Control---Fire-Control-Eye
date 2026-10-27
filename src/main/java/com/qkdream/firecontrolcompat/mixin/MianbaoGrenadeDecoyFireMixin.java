package com.qkdream.firecontrolcompat.mixin;

import com.qkdream.firecontrolcompat.underwater.TorpedoDecoyLauncher;
import net.mcreator.myfirstmod.procedures.AntimangrenadeFireProcedure;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Throws the decoy out of 面包学's anti-infantry grenade launcher.
 *
 * <p>The launcher only counts the rounds in its drum and never writes down what
 * kind they are, so a drum that has one of ours in it is answered here instead:
 * the same bang, the same lob, the same round off the drum, and the decoy where
 * the launcher's own grenade would have gone. A drum holding nothing of ours
 * falls straight through to the launcher.</p>
 */
@Mixin(AntimangrenadeFireProcedure.class)
public abstract class MianbaoGrenadeDecoyFireMixin {

    @Inject(method = "execute", at = @At("HEAD"), cancellable = true)
    private static void firecontrolcompat$fireDecoy(
            LevelAccessor world, double x, double y, double z, CallbackInfo ci) {
        if (TorpedoDecoyLauncher.tryFire(world, x, y, z)) {
            ci.cancel();
        }
    }
}

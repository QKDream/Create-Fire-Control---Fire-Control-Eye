package com.qkdream.firecontrolcompat.mixin;

import com.qkdream.firecontrolcompat.BeamMissileCompat;
import net.mcreator.myfirstmod.procedures.Mixed_missile_launcher_fireProcedure;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Fires the beam-riding missile from the mixed missile launcher. */
@Mixin(Mixed_missile_launcher_fireProcedure.class)
public abstract class MixedLauncherFireMixin {

    /**
     * The 2.6.5 procedure carries the launcher's block state as a fifth
     * argument: {@code execute(LevelAccessor, double, double, double,
     * BlockState)}. Handing mixin a four-argument handler aborts the class with
     * {@code InvalidInjectionException} the first time the mixed launcher fires,
     * which is a lazy load and therefore invisible until a player tries it.
     */
    @Inject(method = "execute", at = @At("HEAD"), cancellable = true)
    private static void firecontrolcompat$fireBeamMissile(
            LevelAccessor world, double x, double y, double z, BlockState state, CallbackInfo ci) {
        if (BeamMissileCompat.tryFireMixed(world, x, y, z)) {
            ci.cancel();
        }
    }
}

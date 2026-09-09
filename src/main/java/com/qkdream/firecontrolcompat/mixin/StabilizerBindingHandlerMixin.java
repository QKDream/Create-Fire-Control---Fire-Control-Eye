package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.content.controller.BindingSlot;
import com.hooya.stabilizedturret.content.controller.StabilizerBindingHandler;
import com.qkdream.firecontrolcompat.HeavyAirDefenseCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Binds the vertical silo to fire control computers as an air-defense
 * missile launcher, so the computer can lock and fire the heavy missile
 * through its normal AA pipeline.
 */
@Mixin(StabilizerBindingHandler.class)
public abstract class StabilizerBindingHandlerMixin {

    @Inject(method = "detectBindingSlot", at = @At("RETURN"), cancellable = true)
    private static void firecontrolcompat$siloAsAaLauncher(Level level, BlockPos pos, CallbackInfoReturnable<BindingSlot> cir) {
        if (cir.getReturnValue() == null && HeavyAirDefenseCompat.isSiloHead(level.getBlockState(pos))) {
            cir.setReturnValue(BindingSlot.AA_MISSILE_LAUNCHER);
        }
    }
}
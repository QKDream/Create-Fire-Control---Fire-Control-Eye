package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.content.controller.BindingSlot;
import com.hooya.stabilizedturret.content.controller.StabilizerBindingHandler;
import com.qkdream.firecontrolcompat.BombRackCompat;
import com.qkdream.firecontrolcompat.HeavyAirDefenseCompat;
import com.qkdream.firecontrolcompat.NevaLaunchCompat;
import com.qkdream.firecontrolcompat.SubLevelPositions;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Binds the vertical silo and the bomb racks to fire control computers.
 *
 * <p>The silo answers as an air-defense missile launcher, so the computer can
 * lock and fire the heavy missile through its normal AA pipeline. The bomb racks
 * answer as aircraft missile racks, which is the slot the computer's air-to-
 * ground branch holds: bound there, they are selected with the air-to-ground
 * line, counted with it and released with the fire key.</p>
 */
@Mixin(StabilizerBindingHandler.class)
public abstract class StabilizerBindingHandlerMixin {

    @Inject(method = "detectBindingSlot", at = @At("RETURN"), cancellable = true)
    private static void firecontrolcompat$siloAsAaLauncher(Level level, BlockPos pos, CallbackInfoReturnable<BindingSlot> cir) {
        if (cir.getReturnValue() == null
                && HeavyAirDefenseCompat.isSiloHead(SubLevelPositions.blockState(level, pos))) {
            cir.setReturnValue(BindingSlot.AA_MISSILE_LAUNCHER);
        }
        if (cir.getReturnValue() == null
                && NevaLaunchCompat.isNevaLauncher(SubLevelPositions.blockState(level, pos))) {
            cir.setReturnValue(BindingSlot.AA_MISSILE_LAUNCHER);
        }
        if (cir.getReturnValue() == null
                && BombRackCompat.isBombRack(SubLevelPositions.blockState(level, pos))) {
            cir.setReturnValue(BindingSlot.AIRCRAFT_MISSILE_RACK);
        }
    }
}

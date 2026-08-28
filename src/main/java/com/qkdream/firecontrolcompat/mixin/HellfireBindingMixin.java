package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.content.controller.BindingSlot;
import com.hooya.stabilizedturret.content.controller.StabilizerBindingHandler;
import com.qkdream.firecontrolcompat.HellfireBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets the TAOV Hellfire director be bound to the fire-control computer as a
 * laser designator, so it participates in the head-aim loop exactly like the
 * mianbaos laser designator does.
 */
@Mixin(StabilizerBindingHandler.class)
public abstract class HellfireBindingMixin {

    @Inject(method = "detectBindingSlot", at = @At("HEAD"), cancellable = true)
    private static void firecontrolcompat$detectHellfireDirector(Level level, BlockPos pos, CallbackInfoReturnable<BindingSlot> cir) {
        if (level != null && pos != null && HellfireBridge.isDirector(level.getBlockState(pos))) {
            cir.setReturnValue(BindingSlot.LASER_DESIGNATOR);
        }
    }
}

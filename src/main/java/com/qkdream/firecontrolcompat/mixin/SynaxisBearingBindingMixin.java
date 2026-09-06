package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.content.controller.BindingSlot;
import com.hooya.stabilizedturret.content.controller.StabilizerBindingHandler;
import com.qkdream.firecontrolcompat.SynaxisBearingBridge;
import com.verr1.synaxis.content.blocks.motor.AbstractDynamicMotorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes Synaxis joint / revolute motors bindable to the fire control computer
 * and the connected display: vertical-axis motors default to YAW (direction
 * bearing) and horizontal-axis motors to PITCH (elevation bearing), mirroring
 * how swivel bearings are classified by their rotation axis.
 */
@Mixin(StabilizerBindingHandler.class)
public abstract class SynaxisBearingBindingMixin {

    @Inject(method = "detectBindingSlot", at = @At("HEAD"), cancellable = true)
    private static void firecontrolcompat$detectSynaxisMotor(
            Level level, BlockPos pos, CallbackInfoReturnable<BindingSlot> cir) {
        if (level == null || pos == null) {
            return;
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity instanceof AbstractDynamicMotorBlockEntity motor) {
            cir.setReturnValue(SynaxisBearingBridge.defaultSlot(motor));
        }
    }
}

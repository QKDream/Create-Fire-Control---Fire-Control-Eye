package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.content.display.ConnectedDisplayBlockEntity;
import com.qkdream.firecontrolcompat.SynaxisBearingBridge;
import com.verr1.synaxis.content.blocks.motor.AbstractDynamicMotorBlockEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mirrors the controller-side Synaxis hooks for the connected display, so
 * manual point indication, tracked-contact aiming and automatic defense all
 * slew Synaxis motor turrets exactly like swivel bearing turrets.
 */
@Mixin(ConnectedDisplayBlockEntity.class)
public abstract class ConnectedDisplaySynaxisBearingMixin {

    @Shadow
    private double yawSpeed;

    @Shadow
    private double pitchSpeed;

    @Shadow
    private double depressionLimit;

    @Shadow
    private double elevationLimit;

    @Shadow
    private boolean yawReversed;

    @Shadow
    private boolean pitchReversed;

    @Inject(method = "commandBearing", at = @At("HEAD"), cancellable = true)
    private void firecontrolcompat$synaxisDisplayCommand(
            BindingRef ref, boolean yaw, Vec3 measured, Vec3 requested, CallbackInfo ci) {
        Level level = ((BlockEntity) (Object) this).getLevel();
        if (level == null) {
            return;
        }
        AbstractDynamicMotorBlockEntity motor = SynaxisBearingBridge.resolveMotor(level, ref);
        if (motor == null) {
            return;
        }
        double speed = yaw ? this.yawSpeed : this.pitchSpeed;
        double errorScale = (yaw ? this.yawReversed : this.pitchReversed) ? -1.0 : 1.0;
        double depression = yaw ? 180.0 : this.depressionLimit;
        double elevation = yaw ? 180.0 : this.elevationLimit;
        if (SynaxisBearingBridge.commandAim(
                motor, yaw, measured, requested, speed, errorScale, depression, elevation)) {
            ci.cancel();
        }
    }

    @Inject(method = "bearingChildDistance", at = @At("HEAD"), cancellable = true)
    private void firecontrolcompat$synaxisDisplayChildDistance(
            BindingRef device, BindingRef bearingRef, CallbackInfoReturnable<Integer> cir) {
        Level level = ((BlockEntity) (Object) this).getLevel();
        if (level == null) {
            return;
        }
        AbstractDynamicMotorBlockEntity motor = SynaxisBearingBridge.resolveMotor(level, bearingRef);
        if (motor != null) {
            cir.setReturnValue(
                    SynaxisBearingBridge.childDistance(level, motor, device == null ? null : device.subLevelId()));
        }
    }
}

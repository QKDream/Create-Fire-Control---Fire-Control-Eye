package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.qkdream.firecontrolcompat.SynaxisBearingBridge;
import com.verr1.synaxis.content.blocks.motor.AbstractDynamicMotorBlockEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Routes the fire control computer's bearing aim commands to Synaxis motors
 * so a yaw/pitch pair of dynamic motors behaves like a bound swivel bearing
 * pair: target acquisition, TWS locks, head-aim and display-driven slewing
 * all work through the vanilla aim pipeline. The fire control offset is
 * ignored for motors (the bridge never reads it) so it keeps applying only
 * to real swivel bearings, as requested.
 */
@Mixin(StabilizerControllerBlockEntity.class)
public abstract class ControllerSynaxisBearingMixin {

    @Inject(
            method = {
                    "commandBearing",
                    "(Lcom/hooya/stabilizedturret/content/controller/BindingRef;ZDLnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;DDDLnet/minecraft/world/phys/Vec3;)V"
            },
            at = @At("HEAD"),
            cancellable = true)
    private void firecontrolcompat$synaxisCommandBearing(
            BindingRef ref,
            boolean yaw,
            double speedDegreesPerSecond,
            Vec3 measuredWorldDirection,
            Vec3 requestedWorldDirection,
            double errorScale,
            double depressionLimit,
            double elevationLimit,
            Vec3 localOffset,
            CallbackInfo ci) {
        Level level = ((BlockEntity) (Object) this).getLevel();
        if (level == null) {
            return;
        }
        AbstractDynamicMotorBlockEntity motor = SynaxisBearingBridge.resolveMotor(level, ref);
        if (motor == null) {
            return;
        }
        if (SynaxisBearingBridge.commandAim(
                motor,
                yaw,
                measuredWorldDirection,
                requestedWorldDirection,
                speedDegreesPerSecond,
                errorScale,
                depressionLimit,
                elevationLimit)) {
            ci.cancel();
        }
    }

    @Inject(method = "bearingChildDistance", at = @At("HEAD"), cancellable = true)
    private void firecontrolcompat$synaxisChildDistance(
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

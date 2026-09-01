package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.content.controller.BindingSlot;
import com.hooya.stabilizedturret.content.controller.StabilizerBindingHandler;
import com.qkdream.firecontrolcompat.TaovGunBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Exposes TAOV's gun as a CANNON_MOUNT binding target. Both the fire-control
 * computer (StabilizerBindingHandler) and the connected display
 * (AutoDefenseDisplayBindingHandler) funnel their binding scans through
 * {@code detectBindingSlot}, so this one hook makes the gun bindable on
 * both, exactly like an assembled CBC cannon mount.
 */
@Mixin(StabilizerBindingHandler.class)
public abstract class TaovGunBindingMixin {

    @Inject(method = "detectBindingSlot", at = @At("HEAD"), cancellable = true)
    private static void firecontrolcompat$detectTaovGun(Level level, BlockPos pos, CallbackInfoReturnable<BindingSlot> cir) {
        if (level != null && pos != null && TaovGunBridge.isGun(level.getBlockEntity(pos))) {
            cir.setReturnValue(BindingSlot.CANNON_MOUNT);
        }
    }
}

package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.content.controller.BindingSlot;
import com.hooya.stabilizedturret.content.display.ConnectedDisplayBlockEntity;
import com.qkdream.firecontrolcompat.IffRadar;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The same invalidation as {@link IffControllerBindingMixin}, for a connected display.
 *
 * <p>{@code IffIdentification#channelsForStructure} collects channels from the computers <b>and</b>
 * the displays standing on a structure, so a display that gains or loses a transponder binding
 * changes the marking the radar gives that structure exactly as a computer does. Its own write
 * paths are separate methods, so they are hooked separately.</p>
 */
@Mixin(ConnectedDisplayBlockEntity.class)
public abstract class IffDisplayBindingMixin {

    @Inject(method = "bind", at = @At("RETURN"))
    private void firecontrolcompat$bound(BindingSlot slot, BindingRef target, CallbackInfoReturnable<Boolean> cir) {
        try {
            IffRadar.bump("display-bind");
        } catch (Throwable ignored) {
            // at worst the TTL window covers it
        }
    }

    @Inject(method = "invalidateBindingsAt", at = @At("RETURN"))
    private static void firecontrolcompat$invalidated(Level targetLevel, BlockPos targetPos, CallbackInfo ci) {
        try {
            IffRadar.bump("display-binding-invalidated");
        } catch (Throwable ignored) {
            // at worst the TTL window covers it
        }
    }

    @Inject(method = "removeInvalidBindings", at = @At("RETURN"))
    private void firecontrolcompat$bindingCount(CallbackInfo ci) {
        try {
            ConnectedDisplayBlockEntity self = (ConnectedDisplayBlockEntity) (Object) this;
            IffRadar.noteBindingCount(self, self.getIffTransponderBindings().size());
        } catch (Throwable ignored) {
            // a lost safety net still leaves the TTL window
        }
    }
}

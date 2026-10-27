package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.content.controller.BindingSlot;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.qkdream.firecontrolcompat.IffRadar;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Invalidates every cached friend-or-foe marking when a fire control computer's bindings move.
 *
 * <p>A marking is answered from the channels of the transponders <b>bound</b> to the computers and
 * displays standing on a structure ({@code IffIdentification#channelsForStructure}), so binding a
 * transponder to a computer, unbinding it, or losing the binding because the block is gone all
 * change the answer of every contact that structure appears as - without changing the target's own
 * channel. All four write paths are hooked here:</p>
 *
 * <ul>
 *   <li>{@code bind(BindingSlot, BindingRef)} - an explicit bind, including the transponder slot;</li>
 *   <li>{@code unbindDevice(BindingSlot, int, BlockPos)} - an explicit unbind;</li>
 *   <li>{@code invalidateBindingsAt(Level, BlockPos)} - the block was broken or replaced;</li>
 *   <li>{@code removeInvalidBindings()} - the tickly safety net. It is <b>not</b> a write point on
 *       its own (it runs every tick and usually removes nothing), so it only bumps when the number
 *       of IFF bindings actually differs from the last time it ran. That is what catches a binding
 *       that died while its chunk was unloaded, which no explicit unbind ever reported.</li>
 * </ul>
 */
@Mixin(StabilizerControllerBlockEntity.class)
public abstract class IffControllerBindingMixin {

    @Inject(method = "bind", at = @At("RETURN"))
    private void firecontrolcompat$bound(BindingSlot slot, BindingRef target, CallbackInfo ci) {
        try {
            IffRadar.bump("controller-bind");
        } catch (Throwable ignored) {
            // at worst the TTL window covers it
        }
    }

    @Inject(method = "unbindDevice", at = @At("RETURN"))
    private void firecontrolcompat$unbound(BindingSlot slot, int index, BlockPos expectedPos,
            CallbackInfoReturnable<Boolean> cir) {
        try {
            IffRadar.bump("controller-unbind");
        } catch (Throwable ignored) {
            // at worst the TTL window covers it
        }
    }

    @Inject(method = "invalidateBindingsAt", at = @At("RETURN"))
    private void firecontrolcompat$invalidated(Level targetLevel, BlockPos targetPos, CallbackInfo ci) {
        try {
            IffRadar.bump("controller-binding-invalidated");
        } catch (Throwable ignored) {
            // at worst the TTL window covers it
        }
    }

    @Inject(method = "removeInvalidBindings", at = @At("RETURN"))
    private void firecontrolcompat$bindingCount(CallbackInfo ci) {
        try {
            StabilizerControllerBlockEntity self = (StabilizerControllerBlockEntity) (Object) this;
            IffRadar.noteBindingCount(self, self.getIffTransponderBindings().size());
        } catch (Throwable ignored) {
            // a lost safety net still leaves the TTL window
        }
    }
}

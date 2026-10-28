package com.qkdream.firecontrolcompat.mixin;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.qkdream.firecontrolcompat.blueprint.capture.CaptureExport;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hands out a recording consumer while a frame capture is open.
 *
 * <p>{@code MultiBufferSource.BufferSource#getBuffer(RenderType)} is the single door every part of the
 * level renderer goes through - blocks, block entities, entities, particles alike - so wrapping here
 * is what makes a capture mode possible at all: nothing has to know which renderer is drawing.</p>
 *
 * <p>{@code require = 0} and a try/catch: if a future version renames the method the capture mode
 * simply records nothing (and says so in the log), rather than failing the game at load.</p>
 */
@Mixin(MultiBufferSource.BufferSource.class)
public abstract class CaptureBufferSourceMixin {

    @Inject(method = "getBuffer", at = @At("RETURN"), require = 0)
    private void firecontrolcompat$captureBuffer(RenderType type,
            CallbackInfoReturnable<VertexConsumer> cir) {
        try {
            VertexConsumer consumer = cir.getReturnValue();
            if (consumer != null && CaptureExport.isRecording()) {
                cir.setReturnValue(CaptureExport.wrap(consumer, type));
            }
        } catch (Throwable ignored) {
            // recording is never allowed to break a frame
        }
    }
}

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
 *
 * <p><b>2026-10-29 - this hook is one of two doors now.</b> A session where a capture window armed,
 * stayed open for two seconds, and recorded {@code groups seen=[]} proved that
 * "nothing reached the recording consumer" and "the hook is not in the class" look identical in the
 * log. Two things were added, neither of which changes this injection:</p>
 *
 * <ul>
 *   <li>{@code CaptureExport.arm()} now asks the target class itself whether this callback is
 *       present ({@code CaptureHooks}, reflection over the injected
 *       {@code handler$…$firecontrolcompat$captureBuffer} method) and prints
 *       {@code capture-hook: present=<true|false> target=MultiBufferSource$BufferSource#getBuffer}
 *       - the line this mode was missing;</li>
 *   <li>{@code RecordingBufferSource} plus the entity / block-entity dispatcher
 *       {@code @ModifyVariable} hooks ({@code CaptureScopeEntityMixin},
 *       {@code CaptureBlockEntityMixin}) record through a second, independent door, so a capture
 *       still fills with block-entity and entity geometry when this one is absent. Both doors can be
 *       open at once: {@code CaptureExport.wrap} passes an existing recorder straight back, so
 *       nothing is recorded twice.</li>
 * </ul>
 *
 * <p>The target class and the method were re-verified with {@code javap} on the 1.21.1 client jar:
 * {@code public VertexConsumer getBuffer(RenderType)} exists on
 * {@code net.minecraft.client.renderer.MultiBufferSource$BufferSource} and has no overload, so a
 * rename is the only way this hook can stop resolving.</p>
 */
@Mixin(MultiBufferSource.BufferSource.class)
public abstract class CaptureBufferSourceMixin {

    @Inject(method = "getBuffer", at = @At("RETURN"), require = 0)
    private void firecontrolcompat$captureBuffer(RenderType type,
            CallbackInfoReturnable<VertexConsumer> cir) {
        try {
            VertexConsumer consumer = cir.getReturnValue();
            if (consumer != null && CaptureExport.isRecording()) {
                cir.setReturnValue(CaptureExport.wrap(consumer, type, "getBuffer"));
            }
        } catch (Throwable ignored) {
            // recording is never allowed to break a frame
        }
    }
}

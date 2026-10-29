package com.qkdream.firecontrolcompat.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.qkdream.firecontrolcompat.blueprint.capture.RecordingBufferSource;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Replaces the buffer source a <b>block entity</b> is drawn with while a frame capture is open.
 *
 * <h2>Why block entities need their own door</h2>
 *
 * <p>The frame capture's original hook is a single injection inside
 * {@code MultiBufferSource$BufferSource#getBuffer}. When that hook is absent - and a {@code require=0}
 * hook logs nothing when it is - the window records nothing, which is the report this exists for
 * ("capture 帧捕获一帧都没录到"). The block-entity dispatcher is the second door: its
 * {@code MultiBufferSource} argument is replaced for the duration of the draw, so every
 * {@code getBuffer} the renderer makes is recorded even without the class-level hook.</p>
 *
 * <p>Block entities are also <b>where the geometry the per-block export cannot see lives</b>:
 * tracks, tyres, bearings, contraption parts and every moving structure draw here, from a renderer
 * that expects a live level. That is the whole reason a frame capture was wanted next to the
 * per-block bake.</p>
 *
 * <h2>Safety</h2>
 *
 * <p>javap on the 1.21.1 client jar gives
 * {@code render(BlockEntity, float, PoseStack, MultiBufferSource)} - one method, argument slots
 * {@code this} 0, {@code BlockEntity} 1, {@code float} 2, {@code PoseStack} 3,
 * {@code MultiBufferSource} 4 - so {@code index = 4} with {@code argsOnly = true} is exact. Wrapped in
 * {@code require = 0} plus a try/catch, and inert outside a capture window
 * ({@link RecordingBufferSource#wrap} returns its argument unchanged), so ordinary frames pay one
 * boolean test per block entity.</p>
 *
 * <p>Client only: {@code BlockEntityRenderDispatcher} does not exist on a dedicated server. The gate
 * lives in {@code FireControlCompatMixinPlugin#CLIENT_ONLY_MIXINS}.</p>
 */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class CaptureBlockEntityMixin {

    /** The full descriptor, javap-verified against 1.21.1. */
    private static final String RENDER =
            "render(Lnet/minecraft/world/level/block/entity/BlockEntity;F"
                    + "Lcom/mojang/blaze3d/vertex/PoseStack;"
                    + "Lnet/minecraft/client/renderer/MultiBufferSource;)V";

    @ModifyVariable(method = RENDER, at = @At("HEAD"), argsOnly = true, index = 4, require = 0)
    private MultiBufferSource firecontrolcompat$captureSource(MultiBufferSource buffer) {
        try {
            return RecordingBufferSource.wrap(buffer);
        } catch (Throwable ignored) {
            return buffer;
        }
    }
}

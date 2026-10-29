package com.qkdream.firecontrolcompat.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.qkdream.firecontrolcompat.blueprint.capture.CaptureScope;
import com.qkdream.firecontrolcompat.blueprint.capture.RecordingBufferSource;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Brackets an entity's own draw with an owner scope, so the vertices it writes can be attributed.
 *
 * <p>{@code EntityRenderDispatcher#render} is the one call every entity renderer goes through, and
 * the level renderer's buffer source is the one the vertices are written to - which is also the
 * source the frame capture wraps ({@code CaptureBufferSourceMixin}). Opening a scope here and
 * closing it on return therefore tags exactly the quads that entity drew, with no cooperation from
 * any renderer.</p>
 *
 * <p>It does nothing unless a capture is open: the first thing both handlers do is one boolean
 * test, so ordinary frames are untouched. {@code require = 0} plus a try/catch, like every other
 * hook into a target this mod does not own: a renamed method means the capture simply records
 * untagged geometry, and says so in its summary, rather than failing the game at load.</p>
 *
 * <p>Client only: {@code EntityRenderDispatcher} does not exist on a dedicated server.</p>
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class CaptureScopeEntityMixin {

    /**
     * The full descriptor, javap-verified against 1.21.1:
     * {@code render(Entity,double,double,double,float,float,PoseStack,MultiBufferSource,int)V}.
     *
     * <p>Written out rather than left as a bare name because a selector that resolves but whose
     * shape does not match the handler still aborts the class on first load - {@code require = 0}
     * only covers "found nothing", not "found it, wrong shape". With the descriptor spelled out, a
     * version that changes the signature skips the hook instead.</p>
     */
    private static final String RENDER =
        "render(Lnet/minecraft/world/entity/Entity;DDDFFLcom/mojang/blaze3d/vertex/PoseStack;"
            + "Lnet/minecraft/client/renderer/MultiBufferSource;I)V";

    @Inject(method = RENDER, at = @At("HEAD"), require = 0)
    private void firecontrolcompat$beginCaptureScope(Entity entity, double x, double y, double z,
            float yaw, float partialTicks, PoseStack poseStack, MultiBufferSource buffer,
            int packedLight, CallbackInfo ci) {
        try {
            if (CaptureScope.isCapturing()) {
                CaptureScope.beginEntity(entity);
            }
        } catch (Throwable ignored) {
            // a diagnostic is never allowed to break a frame
        }
    }

    @Inject(method = RENDER, at = @At("RETURN"), require = 0)
    private void firecontrolcompat$endCaptureScope(Entity entity, double x, double y, double z,
            float yaw, float partialTicks, PoseStack poseStack, MultiBufferSource buffer,
            int packedLight, CallbackInfo ci) {
        try {
            CaptureScope.endEntity();
        } catch (Throwable ignored) {
            // as above
        }
    }

    /**
     * Swaps the buffer source this entity is drawn with for one that records.
     *
     * <h2>Why the argument and not the class</h2>
     *
     * <p>The full-frame capture first relied on a single hook inside
     * {@code MultiBufferSource$BufferSource#getBuffer} ({@code CaptureBufferSourceMixin}). A session
     * proved that when that hook is not in the class, the window records nothing and no line says
     * why. This handler is a second, independent door on a method this mod already hooks
     * successfully (the two scopes above): the {@code MultiBufferSource} argument itself is replaced
     * for the duration of the draw, so every {@code getBuffer} the entity renderer makes is recorded
     * without the class-level hook being involved at all.</p>
     *
     * <p>It is safe to have both doors open: {@link RecordingBufferSource} forwards to the real
     * source, so when the class hook <em>is</em> present the consumer it returns is already a
     * recorder and is passed straight back instead of being wrapped a second time.</p>
     *
     * <p>Index 11 is the argument's slot in this exact descriptor (javap-verified: {@code this} 0,
     * {@code Entity} 1, the three {@code double}s at 2/4/6, {@code float}s at 8/9, {@code PoseStack}
     * 10, {@code MultiBufferSource} 11, {@code int} 12). {@code argsOnly = true} keeps the hook to the
     * arguments of the method, and {@code require = 0} plus the try/catch mean a version that changes
     * the shape only loses the fallback door.</p>
     */
    @ModifyVariable(method = RENDER, at = @At("HEAD"), argsOnly = true, index = 11, require = 0)
    private MultiBufferSource firecontrolcompat$captureSource(MultiBufferSource buffer) {
        try {
            return RecordingBufferSource.wrap(buffer);
        } catch (Throwable ignored) {
            return buffer;
        }
    }
}

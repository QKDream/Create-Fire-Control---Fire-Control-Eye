package com.qkdream.firecontrolcompat.blueprint.capture;

import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

/**
 * A buffer source that records whatever is drawn through it, and forwards everything.
 *
 * <h2>Why a second door was needed</h2>
 *
 * <p>The frame capture originally wrapped {@code MultiBufferSource$BufferSource#getBuffer} alone.
 * That is the door every renderer goes through, so it is the right place - but it is one mixin away
 * from recording nothing at all, and a {@code require = 0} hook that does not apply says nothing
 * about it. This class is the second, independent door: the entity and block-entity dispatchers have
 * their {@code MultiBufferSource} <b>argument</b> replaced for the duration of a draw (a
 * {@code @ModifyVariable} on a method this mod already hooks successfully), so block entities and
 * entities are recorded even when the {@code getBuffer} hook is absent.</p>
 *
 * <h2>Why both doors can be open at once</h2>
 *
 * <p>When both work, the dispatcher wrapper's {@code delegate.getBuffer(...)} already comes back as
 * a recorder, and {@link CaptureExport#wrap} hands an existing recorder straight back rather than
 * wrapping it a second time. So the two paths cannot double the file; the log says which one was
 * reached first ({@code capture: hook hit via=}).</p>
 *
 * <p>Nothing here is used unless a capture window is open -
 * {@link #wrap(MultiBufferSource)} returns its argument unchanged otherwise - so ordinary frames pay
 * one boolean test per entity and block entity.</p>
 */
public final class RecordingBufferSource implements MultiBufferSource {

    private final MultiBufferSource delegate;

    private RecordingBufferSource(MultiBufferSource delegate) {
        this.delegate = delegate;
    }

    /**
     * Wraps the source a renderer was handed, or returns it unchanged.
     *
     * <p>Returns the original outside a capture window, when it is null, and when it is already one
     * of ours: replacing a source the caller still holds must never cost the frame anything.</p>
     */
    public static MultiBufferSource wrap(MultiBufferSource original) {
        try {
            if (original == null || !CaptureExport.isRecording()
                    || original instanceof RecordingBufferSource) {
                return original;
            }
            return new RecordingBufferSource(original);
        } catch (Throwable ignored) {
            // a recording aid is never allowed to break a frame
            return original;
        }
    }

    @Override
    public VertexConsumer getBuffer(RenderType type) {
        VertexConsumer consumer = delegate.getBuffer(type);
        try {
            return CaptureExport.wrap(consumer, type, "dispatcher");
        } catch (Throwable ignored) {
            return consumer;
        }
    }
}

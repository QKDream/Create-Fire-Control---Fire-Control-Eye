package com.qkdream.firecontrolcompat.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.qkdream.firecontrolcompat.client.CompatBvr;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Draws a mathematical round of this mod through <b>one</b> of fire control's two long-range
 * passes, not both.
 *
 * <h2>The two passes, and why exactly one is right</h2>
 *
 * <p>A round fire control flies mathematically is published twice, by two different subsystems:</p>
 *
 * <ul>
 *   <li>{@code MathematicalMissilePayload} -&gt; {@code MathematicalMissileVisuals}: a client-side
 *       model entity drawn in {@code AFTER_LEVEL} through {@code EntityRenderDispatcher}. It pushes
 *       <b>no</b> fog and rebuilds <b>no</b> projection, so a round drawn by this pass past the fog
 *       wall is painted in the fog colour - invisible. This is the pass our rounds used to be on,
 *       and it is why a thousand-block shot produced nothing to look at.</li>
 *   <li>{@code MissileRemoteStatePayload} -&gt; {@code MissileLongRangeEffects} -&gt;
 *       {@code ClientMissileRenderer#renderOrdinaryAa}: drawn with the fog pushed out to a million
 *       blocks and the projection rebuilt to reach the round, with the foreground line-of-sight test
 *       applied first. That is the pass that can actually show it.</li>
 * </ul>
 *
 * <p>Both would draw our round, so both together is two airframes for one round - the "phantom
 * with the trail pointing where it used to fly" defect, back through a different door. This hook
 * drops our rounds from the first pass; the second pass then owns them, at every distance, with the
 * fog and the far plane already dealt with.</p>
 *
 * <p>Nothing else changes: the pack's own rounds (and its bombs, its countermeasures and its
 * television visuals) are still drawn by {@code MathematicalMissileVisuals} exactly as before,
 * because the skip asks a class question - is this a round of ours - and answers no for them.</p>
 */
@Mixin(targets = "com.hooya.stabilizedturret.client.MathematicalMissileVisuals")
public abstract class MathVisualSkipMixin {

    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;"
                            + "render(Lnet/minecraft/world/entity/Entity;DDDFFLcom/mojang/blaze3d/vertex/PoseStack;"
                            + "Lnet/minecraft/client/renderer/MultiBufferSource;I)V"
            ),
            require = 0
    )
    private static void firecontrolcompat$oneLongRangePass(
            EntityRenderDispatcher dispatcher, Entity entity,
            double x, double y, double z, float yaw, float partial,
            PoseStack pose, MultiBufferSource buffer, int light) {
        try {
            if (CompatBvr.available() && CompatBvr.isBvrRound(entity)) {
                // Drawn by ClientMissileRenderer#renderOrdinaryAa on the remote-state channel.
                return;
            }
        } catch (Throwable ignored) {
            // a failed check keeps the pack's own drawing rather than losing the round
        }
        dispatcher.render(entity, x, y, z, yaw, partial, pose, buffer, light);
    }
}

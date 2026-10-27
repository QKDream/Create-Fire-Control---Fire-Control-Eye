package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.client.ClientAimState;
import com.hooya.stabilizedturret.client.ThirdPersonAimHudRenderer;
import com.qkdream.firecontrolcompat.client.LeadArrowRenderer;
import com.qkdream.firecontrolcompat.client.OpticalLockFollowState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces the connected green square with a corner-bracket reticle while
 * the optical lock holds a main-world block, so the gunner can tell a block
 * lock apart from a live-target lock at a glance.
 */
@Mixin(ThirdPersonAimHudRenderer.class)
public abstract class OpticalBlockLockReticleMixin {

    private static final int BRACKET_COLOR = 0xFFFFB300;
    private static final int CROSS_COLOR = 0xFFFFFFFF;

    @Inject(method = "renderOpticalMarkers", at = @At("HEAD"), cancellable = true)
    private static void firecontrolcompat$blockLockReticle(
            GuiGraphics graphics,
            Minecraft minecraft,
            int width,
            int height,
            float partialTick,
            int aimX,
            int aimY,
            CallbackInfo ci) {
        if (!OpticalLockFollowState.isBlockLockActive()
                || !ClientAimState.isOpticalLockActive()
                || ClientAimState.isOpticalLockSearching()) {
            return;
        }
        Vec3 point = ClientAimState.getOpticalLockDisplayPoint(partialTick);
        int[] screen = LeadArrowRenderer.projectWorldPoint(minecraft, point, width, height, partialTick);
        if (screen == null) {
            return;
        }
        int centreX = screen[0];
        int centreY = screen[1];
        int half = 12;
        int arm = 6;
        int thickness = 2;
        graphics.fill(centreX - half, centreY - half, centreX - half + arm, centreY - half + thickness, BRACKET_COLOR);
        graphics.fill(centreX - half, centreY - half, centreX - half + thickness, centreY - half + arm, BRACKET_COLOR);
        graphics.fill(centreX + half - arm, centreY - half, centreX + half, centreY - half + thickness, BRACKET_COLOR);
        graphics.fill(centreX + half - thickness, centreY - half, centreX + half, centreY - half + arm, BRACKET_COLOR);
        graphics.fill(centreX - half, centreY + half - thickness, centreX - half + arm, centreY + half, BRACKET_COLOR);
        graphics.fill(centreX - half, centreY + half - arm, centreX - half + thickness, centreY + half, BRACKET_COLOR);
        graphics.fill(centreX + half - arm, centreY + half - thickness, centreX + half, centreY + half, BRACKET_COLOR);
        graphics.fill(centreX + half - thickness, centreY + half - arm, centreX + half, centreY + half, BRACKET_COLOR);
        graphics.fill(centreX - 1, centreY - 5, centreX + 1, centreY + 5, CROSS_COLOR);
        graphics.fill(centreX - 5, centreY - 1, centreX + 5, centreY + 1, CROSS_COLOR);
        ci.cancel();
    }
}

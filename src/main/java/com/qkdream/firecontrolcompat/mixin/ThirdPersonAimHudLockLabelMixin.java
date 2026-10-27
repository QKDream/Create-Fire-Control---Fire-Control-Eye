package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.client.ClientAimState;
import com.hooya.stabilizedturret.client.ThirdPersonAimHudRenderer;
import com.qkdream.firecontrolcompat.client.LeadArrowRenderer;
import com.qkdream.firecontrolcompat.client.LockFrameHud;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the lead indicator at the centre of the optical lock box.
 *
 * <p>Fire control 0.9.3 draws the optical lock through a package private frame class instead of the
 * old corner and connected squares, so the box is left entirely to the pack and this hook only adds
 * the arrow: the optical lock's point is projected with the pack's own projection and the arrow is
 * drawn there. The target category is drawn by the radar HUD alone, so an optical lock and a radar
 * lock never stack two labels.</p>
 */
@Mixin(ThirdPersonAimHudRenderer.class)
public abstract class ThirdPersonAimHudLockLabelMixin {

    @Inject(method = "renderOpticalMarkers", at = @At("TAIL"), require = 0)
    private static void firecontrolcompat$drawLeadAtLock(
            GuiGraphics graphics, Minecraft minecraft, int mouseX, int mouseY, float partialTick,
            int width, int height, CallbackInfo ci) {
        try {
            if (!ClientAimState.isOpticalLockActive()) {
                return;
            }
            int[] point = new int[2];
            if (!LockFrameHud.projectOpticalLock(minecraft, point)) {
                return;
            }
            Vec3 aim = ClientAimState.getOpticalLockDisplayPoint(1.0F);
            if (aim == null) {
                return;
            }
            Vec3 velocity = LeadArrowRenderer.velocityFor(aim);
            if (velocity == null) {
                return;
            }
            LeadArrowRenderer.draw(graphics, point[0], point[1], minecraft,
                    minecraft.getTimer().getGameTimeDeltaTicks(), aim, velocity);
        } catch (Throwable ignored) {
            // A missing lead must never take the HUD down.
        }
    }
}

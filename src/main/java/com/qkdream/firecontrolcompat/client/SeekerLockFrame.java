package com.qkdream.firecontrolcompat.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.phys.Vec3;

/**
 * The small lock frame that squares up the contact an infrared seeker has taken.
 *
 * <p>Apart from the rest of the seeker HUD on purpose. The report was that this frame vanished, and
 * it vanished because its drawing was entangled with the launch flow - a mixin that also pressed the
 * seeker was removed, and while it never drew the square itself, the state it was gated on went with
 * it. Nothing here knows how a round is fired: it is handed a locked position and whether a head is
 * watching, and it draws or it does not.</p>
 *
 * <p>Air defence only. The infrared anti-tank round locks a point the gunner chose and is fired in one
 * press; it has no watching phase, so it has no frame, and asking for one must never draw anything.</p>
 */
public final class SeekerLockFrame {

    /** Half the side of the square, in GUI pixels. */
    private static final int HALF_SIDE = 8;
    /** Red, so it is unmistakable against the sky. */
    private static final int LOCK_RED = 0xFFFF3030;

    private SeekerLockFrame() {
    }

    /**
     * Draw the square around a locked contact.
     *
     * @param graphics    the GUI context
     * @param minecraft   the client
     * @param locked      the contact, or null when the head has not taken one
     * @param searching   whether a head is watching at all
     * @param antiAir     whether the selected round is the air-defence one; false draws nothing
     * @param partialTick the frame's partial tick, for projecting a moving target smoothly
     * @return whether a square was actually drawn, for the once-a-second log line
     */
    public static boolean draw(GuiGraphics graphics, Minecraft minecraft, Vec3 locked,
                               boolean searching, boolean antiAir, float partialTick) {
        if (!antiAir || !searching || locked == null || minecraft == null || minecraft.level == null) {
            return false;
        }
        int[] point = LeadArrowRenderer.projectWorldPoint(minecraft, locked,
                                                          graphics.guiWidth(), graphics.guiHeight(),
                                                          partialTick);
        if (point == null) {
            // Behind the camera, or off screen: the contact is still locked, there is just nothing
            // to draw at this moment.
            return false;
        }
        graphics.fill(point[0] - HALF_SIDE, point[1] - HALF_SIDE,
                      point[0] + HALF_SIDE, point[1] - HALF_SIDE + 1, LOCK_RED);
        graphics.fill(point[0] - HALF_SIDE, point[1] + HALF_SIDE - 1,
                      point[0] + HALF_SIDE, point[1] + HALF_SIDE, LOCK_RED);
        graphics.fill(point[0] - HALF_SIDE, point[1] - HALF_SIDE,
                      point[0] - HALF_SIDE + 1, point[1] + HALF_SIDE, LOCK_RED);
        graphics.fill(point[0] + HALF_SIDE - 1, point[1] - HALF_SIDE,
                      point[0] + HALF_SIDE, point[1] + HALF_SIDE, LOCK_RED);
        return true;
    }
}

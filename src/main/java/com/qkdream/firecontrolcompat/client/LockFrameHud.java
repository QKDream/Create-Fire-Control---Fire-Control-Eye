package com.qkdream.firecontrolcompat.client;

import java.lang.reflect.Method;

import com.hooya.stabilizedturret.client.ClientAimState;
import com.hooya.stabilizedturret.client.ClientRadarState;
import com.hooya.stabilizedturret.client.RadarHudRenderer;
import com.hooya.stabilizedturret.client.ThirdPersonAimHudRenderer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.phys.Vec3;

/**
 * Where this mod's own line sits relative to fire control's lock frame.
 *
 * <p>Fire control 0.9.3 draws its lock box and read-out through classes that are package private
 * ({@code TargetLockFrame} and its {@code ScreenPoint}), so they cannot be named from this mod at
 * all. What is used instead is the renderers' own projection: the contact's screen point is asked
 * for through reflection, and the line is placed above a box of the frame's own size.</p>
 *
 * <p>The read-out lives below the box - distance and closing rate at +43 to the right, height above
 * ground centred at +41, motion indicator centred at +84, all in a space scaled by
 * {@code guiHeight / 1440} - and the frame sprite is 76 px tall. A line at {@code box bottom + 12},
 * which is where this mod used to draw, lands straight on the height line; above the box is free.</p>
 */
public final class LockFrameHud {

    private static Method radarProject;
    private static Method opticalProject;
    private static Method pointX;
    private static Method pointY;
    private static boolean looked;

    private LockFrameHud() {
    }

    /** Half the frame sprite for this GUI height (76 px at 1440). */
    public static int halfSize(int guiHeight) {
        return Math.max(6, Math.round(38.0F * (guiHeight / 1440.0F)));
    }

    /** Y for this mod's line: clear of the sprite, above it. */
    public static int labelY(int centerY, int guiHeight) {
        return centerY - halfSize(guiHeight) - Math.max(10, Math.round(6.0F * (guiHeight / 1440.0F)) + 6);
    }

    /**
     * Projects the radar contact the frame is locked on.
     *
     * @return true when a point was filled into {@code out} ({@code out[0] = x}, {@code out[1] = y})
     */
    public static boolean projectRadarTarget(Minecraft minecraft, int guiHeight, int[] out) {
        try {
            init();
            if (radarProject == null || pointX == null) {
                return false;
            }
            Vec3 centre = firecontrolcompat$lockedContactCentre();
            if (centre == null) {
                return false;
            }
            Object point = radarProject.invoke(null, minecraft, centre,
                    minecraft.getWindow().getGuiScaledWidth(), guiHeight, 1.0F);
            if (point == null) {
                return false;
            }
            out[0] = (int) pointX.invoke(point);
            out[1] = (int) pointY.invoke(point);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Projects the point an optical lock is holding, for the lead arrow. */
    public static boolean projectOpticalLock(Minecraft minecraft, int[] out) {
        try {
            init();
            if (opticalProject == null || pointX == null) {
                return false;
            }
            Vec3 point = ClientAimState.getOpticalLockDisplayPoint(1.0F);
            if (point == null) {
                return false;
            }
            Object screen = opticalProject.invoke(null, minecraft, point,
                    minecraft.getWindow().getGuiScaledWidth(),
                    minecraft.getWindow().getGuiScaledHeight(), 1.0F);
            if (screen == null) {
                return false;
            }
            out[0] = (int) pointX.invoke(screen);
            out[1] = (int) pointY.invoke(screen);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Draws this mod's category line above the radar lock frame, if anything is locked. */
    public static void drawRadarLabel(GuiGraphics graphics, Minecraft minecraft, int guiHeight) {
        try {
            int[] point = new int[2];
            if (!projectRadarTarget(minecraft, guiHeight, point)) {
                return;
            }
            net.minecraft.network.chat.Component label =
                    LockLabelText.groundLockComponent(ClientRadarState.getLockedTarget());
            if (label == null) {
                label = LockLabelText.component(ClientRadarState.getSelectedTarget());
            }
            if (label == null) {
                return;
            }
            graphics.drawCenteredString(minecraft.font, label, point[0],
                    labelY(point[1], guiHeight), LockLabelText.LOCK_GREEN);
        } catch (Throwable ignored) {
            // A missing category must never take the HUD down.
        }
    }

    private static Vec3 firecontrolcompat$lockedContactCentre() {
        java.util.UUID locked = ClientRadarState.getLockedTarget();
        java.util.UUID selected = ClientRadarState.getSelectedTarget();
        for (ClientRadarState.Contact contact : ClientRadarState.getContacts()) {
            if (locked != null && contact.id().equals(locked)) {
                return contact.center();
            }
        }
        for (ClientRadarState.Contact contact : ClientRadarState.getContacts()) {
            if (selected != null && contact.id().equals(selected)) {
                return contact.center();
            }
        }
        return null;
    }

    private static void init() {
        if (!looked) {
            looked = true;
            try {
                radarProject = RadarHudRenderer.class.getDeclaredMethod("project", Minecraft.class,
                        Vec3.class, int.class, int.class, float.class);
                radarProject.setAccessible(true);
                opticalProject = ThirdPersonAimHudRenderer.class.getDeclaredMethod("projectToScreen",
                        Minecraft.class, Vec3.class, int.class, int.class, float.class);
                opticalProject.setAccessible(true);
                Class<?> pointType = radarProject.getReturnType();
                pointX = pointType.getDeclaredMethod("x");
                pointX.setAccessible(true);
                pointY = pointType.getDeclaredMethod("y");
                pointY.setAccessible(true);
            } catch (Throwable ignored) {
                radarProject = null;
                opticalProject = null;
            }
        }
    }
}

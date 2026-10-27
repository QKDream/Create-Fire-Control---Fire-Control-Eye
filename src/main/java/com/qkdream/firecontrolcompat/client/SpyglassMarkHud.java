package com.qkdream.firecontrolcompat.client;

import com.hooya.stabilizedturret.client.ClientRadarState;
import com.hooya.stabilizedturret.mixin.client.GameRendererAccessor;
import com.qkdream.firecontrolcompat.FireControlCompat;
import com.qkdream.firecontrolcompat.SpyglassMarkRay;
import com.qkdream.firecontrolcompat.SpyglassMarkState;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import org.joml.Vector3f;

/**
 * The two frames a scope puts on the screen: the one that says a structure is
 * there to be picked, and the one that says an operator has been sent onto it.
 *
 * <p>The frame over the crosshair is walked locally so it appears the moment
 * the sight line finds a hull rather than when a click lands - without it a
 * gunner has no way of knowing whether the scope is looking at a structure or
 * at empty sky, and marking looks like it does nothing whichever it is. The
 * frame on the target is only drawn from what the server confirmed, because the
 * server owns which operators a mark went to.</p>
 */
@EventBusSubscriber(modid = FireControlCompat.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class SpyglassMarkHud {

    /** Same green the optical lock frame is drawn in. */
    private static final int FRAME_COLOR = -16711936;

    /** Colour of the frame that says a structure is under the crosshair. */
    private static final int PICK_COLOR = -4980737;

    /** Half side used when the target's own bounds are not on the client. */
    private static final int FALLBACK_HALF = 28;

    /** Half side of the frame drawn over the crosshair. */
    private static final int PICK_HALF = 16;

    /** Ticks between two walks of the sight line. */
    private static final long SCAN_INTERVAL_TICKS = 3L;

    private static long scannedTick = Long.MIN_VALUE;
    private static SpyglassMarkRay.Hit pick;

    private SpyglassMarkHud() {
    }

    /** A mark from the last world has nothing to point at in this one. */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        SpyglassMarkState.apply(false, null, null);
        pick = null;
        scannedTick = Long.MIN_VALUE;
    }

    @SubscribeEvent
    public static void render(RenderGuiEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        GuiGraphics graphics = event.getGuiGraphics();
        float partialTicks = event.getPartialTick().getGameTimeDeltaTicks();
        if (minecraft.player == null || minecraft.options.hideGui || minecraft.screen != null
                || minecraft.getOverlay() != null) {
            return;
        }
        updatePick(minecraft);
        drawPick(graphics, minecraft);
        drawMark(graphics, minecraft, partialTicks);
    }

    /** Walks the sight line now and then while a scope is being held to the eye. */
    private static void updatePick(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null || !scoped(player)) {
            pick = null;
            return;
        }
        long gameTime = minecraft.level.getGameTime();
        if (gameTime == scannedTick) {
            return;
        }
        scannedTick = gameTime;
        if (gameTime % SCAN_INTERVAL_TICKS != 0L) {
            return;
        }
        pick = SpyglassMarkRay.find(minecraft.level, player.getEyePosition(), player.getLookAngle(),
                player.position(), SpyglassMarkRay.REACH).hit();
    }

    private static boolean scoped(LocalPlayer player) {
        return player.isUsingItem() && player.getUseItem().is(Items.SPYGLASS);
    }

    /** Frame over the crosshair naming what the scope has found. */
    private static void drawPick(GuiGraphics graphics, Minecraft minecraft) {
        SpyglassMarkRay.Hit hit = pick;
        if (hit == null || minecraft.player == null) {
            return;
        }
        int centreX = graphics.guiWidth() / 2;
        int centreY = graphics.guiHeight() / 2;
        drawCornerFrame(graphics, centreX, centreY, PICK_HALF, PICK_COLOR);
        double range = minecraft.player.getEyePosition().distanceTo(hit.point());
        String label = String.format(Locale.ROOT, "SABLE 结构  %.0f 米", range);
        graphics.drawCenteredString(minecraft.font, label, centreX, centreY + PICK_HALF + 6, PICK_COLOR);
    }

    /** Frame around the structure an operator has been sent onto. */
    private static void drawMark(GuiGraphics graphics, Minecraft minecraft, float partialTicks) {
        if (!SpyglassMarkState.isActive()) {
            return;
        }
        Vec3 point = SpyglassMarkState.point();
        AABB bounds = boundsOf(SpyglassMarkState.structureId());
        if (bounds != null) {
            point = bounds.getCenter();
        }
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        int[] centre = project(minecraft, point, width, height, partialTicks);
        if (centre == null) {
            return;
        }
        int half = FALLBACK_HALF;
        if (bounds != null) {
            int[] size = projectedSize(minecraft, bounds, height, partialTicks);
            if (size != null) {
                half = Math.max(7, (Math.max(size[0], size[1]) + 1) / 2 + 2);
            }
        }
        drawConnectedSquare(graphics, centre[0], centre[1], Math.max(2, Math.round(half * 0.5F)));
    }

    /**
     * The box the marked structure occupies in the world right now.
     *
     * <p>The radar only knows a structure while it is being swept, and a mark
     * outlives the sweep, so the hull itself is asked when the radar has nothing:
     * it is on this side too, and a frame that stays where the mark was made is
     * wrong the moment the target moves - which is the one thing a ship does.</p>
     */
    private static AABB boundsOf(UUID structureId) {
        if (structureId == null) {
            return null;
        }
        List<ClientRadarState.Contact> contacts = ClientRadarState.getContacts();
        if (contacts != null) {
            for (ClientRadarState.Contact contact : contacts) {
                if (structureId.equals(contact.id()) && contact.bounds() != null) {
                    return contact.bounds();
                }
            }
        }
        return SpyglassMarkRay.bounds(Minecraft.getInstance().level, structureId);
    }

    private static int[] project(Minecraft minecraft, Vec3 position, int width, int height, float partialTicks) {
        Camera camera = minecraft.gameRenderer.getMainCamera();
        Vec3 offset = position.subtract(camera.getPosition());
        Vector3f look = camera.getLookVector();
        Vector3f left = camera.getLeftVector();
        Vector3f up = camera.getUpVector();
        Vec3 lookVector = new Vec3(look.x, look.y, look.z);
        double forward = offset.dot(lookVector);
        if (forward <= 0.05D) {
            return null;
        }
        double scale = pixelScale(minecraft, camera, height, partialTicks);
        int x = width / 2 + (int) Math.round(-offset.dot(new Vec3(left.x, left.y, left.z)) * scale / forward);
        int y = height / 2 + (int) Math.round(-offset.dot(new Vec3(up.x, up.y, up.z)) * scale / forward);
        return new int[] {x, y};
    }

    /** Screen width and height the box covers, or null when it is not in front. */
    private static int[] projectedSize(Minecraft minecraft, AABB bounds, int height, float partialTicks) {
        Camera camera = minecraft.gameRenderer.getMainCamera();
        Vector3f look = camera.getLookVector();
        Vector3f left = camera.getLeftVector();
        Vector3f up = camera.getUpVector();
        Vec3 lookVector = new Vec3(look.x, look.y, look.z);
        Vec3 leftVector = new Vec3(left.x, left.y, left.z);
        Vec3 upVector = new Vec3(up.x, up.y, up.z);
        double scale = pixelScale(minecraft, camera, height, partialTicks);
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        int corners = 0;
        double[] xs = {bounds.minX, bounds.maxX};
        double[] ys = {bounds.minY, bounds.maxY};
        double[] zs = {bounds.minZ, bounds.maxZ};
        for (double cornerX : xs) {
            for (double cornerY : ys) {
                for (double cornerZ : zs) {
                    Vec3 offset = new Vec3(cornerX, cornerY, cornerZ).subtract(camera.getPosition());
                    double forward = offset.dot(lookVector);
                    if (forward <= 0.05D) {
                        continue;
                    }
                    double screenX = -offset.dot(leftVector) * scale / forward;
                    double screenY = -offset.dot(upVector) * scale / forward;
                    minX = Math.min(minX, screenX);
                    maxX = Math.max(maxX, screenX);
                    minY = Math.min(minY, screenY);
                    maxY = Math.max(maxY, screenY);
                    corners++;
                }
            }
        }
        if (corners == 0) {
            return null;
        }
        return new int[] {(int) Math.ceil(maxX - minX), (int) Math.ceil(maxY - minY)};
    }

    private static double pixelScale(Minecraft minecraft, Camera camera, int height, float partialTicks) {
        double fov = ((GameRendererAccessor) minecraft.gameRenderer)
                .stabilizedTurret$getFov(camera, partialTicks, true);
        return (height * 0.5D) / Math.tan(0.01745329238474369D * fov * 0.5D);
    }

    private static void drawConnectedSquare(GuiGraphics graphics, int centreX, int centreY, int halfSide) {
        int thickness = Mth.clamp(Math.round(halfSide * 0.06F), 1, 4);
        int minX = centreX - halfSide;
        int minY = centreY - halfSide;
        int maxX = centreX + halfSide;
        int maxY = centreY + halfSide;
        graphics.fill(minX, minY, maxX + 1, minY + thickness, FRAME_COLOR);
        graphics.fill(minX, maxY - thickness + 1, maxX + 1, maxY + 1, FRAME_COLOR);
        graphics.fill(minX, minY + thickness, minX + thickness, maxY - thickness + 1, FRAME_COLOR);
        graphics.fill(maxX - thickness + 1, minY + thickness, maxX + 1, maxY - thickness + 1, FRAME_COLOR);
    }

    private static void drawCornerFrame(GuiGraphics graphics, int centreX, int centreY, int half, int color) {
        int thickness = Mth.clamp(Math.round(half * 0.08F), 1, 3);
        int arm = Math.max(4, half / 2);
        int left = centreX - half;
        int right = centreX + half;
        int top = centreY - half;
        int bottom = centreY + half;
        graphics.fill(left, top, left + arm, top + thickness, color);
        graphics.fill(left, top, left + thickness, top + arm, color);
        graphics.fill(right - arm, top, right, top + thickness, color);
        graphics.fill(right - thickness, top, right, top + arm, color);
        graphics.fill(left, bottom - thickness, left + arm, bottom, color);
        graphics.fill(left, bottom - arm, left + thickness, bottom, color);
        graphics.fill(right - arm, bottom - thickness, right, bottom, color);
        graphics.fill(right - thickness, bottom - arm, right, bottom, color);
    }
}

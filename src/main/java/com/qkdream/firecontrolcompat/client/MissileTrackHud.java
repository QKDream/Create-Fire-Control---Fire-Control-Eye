package com.qkdream.firecontrolcompat.client;

import com.hooya.stabilizedturret.client.ClientAimState;
import com.hooya.stabilizedturret.mixin.client.GameRendererAccessor;
import com.qkdream.firecontrolcompat.BeamMissileRegistry;
import com.qkdream.firecontrolcompat.FireControlCompat;
import com.qkdream.firecontrolcompat.network.MissileTrackPayload;
import com.qkdream.firecontrolcompat.network.SeekerHudPayload;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * Draws a red lock square around the target the infrared missile seeker is
 * currently tracking, while the player sits in the fire-control-bound seat.
 */
@EventBusSubscriber(modid = FireControlCompat.MOD_ID, value = Dist.CLIENT)
public final class MissileTrackHud {

    private static final int LOCK_RED = -295829640;
    private static final int HALF_SIDE = 7;
    private static long diagnosticTick = -1L;

    private MissileTrackHud() {
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void render(RenderGuiEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.screen != null || minecraft.options.hideGui) {
            return;
        }
        if (!ClientAimState.isSeatedAtBoundController()) {
            return;
        }
        GuiGraphics graphics = event.getGuiGraphics();
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        float partialTick = event.getPartialTick().getGameTimeDeltaTicks();
        boolean ground = SeekerHudPayload.isGroundIrReady();
        boolean aircraft = SeekerHudPayload.isAircraftIrSelected();
        boolean fresh = SeekerHudPayload.isFresh(System.currentTimeMillis());
        boolean showGround = ground && ClientAimState.isScoped();
        boolean showAircraft = aircraft && !ground;
        boolean inFlight = hasInFlightInfraredMissile(minecraft);
        if (diagnosticTick != minecraft.level.getGameTime() && minecraft.level.getGameTime() % 40L == 0L) {
            diagnosticTick = minecraft.level.getGameTime();
            FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] seeker hud render seated={} fresh={} ground={} aircraft={} scoped={} inFlight={}",
                    ClientAimState.isSeatedAtBoundController(), fresh, ground, aircraft,
                    ClientAimState.isScoped(), inFlight);
        }
        if (!inFlight && fresh && (showGround || showAircraft)) {
            Vec3 origin = SeekerHudPayload.launchOrigin();
            Vec3 direction = SeekerHudPayload.launchDirection();
            if (origin != null && direction != null) {
                int[] anchor = LeadArrowRenderer.projectWorldPoint(
                        minecraft, origin.add(direction.scale(50.0)), width, height, partialTick);
                if (anchor != null) {
                    drawSeekerRangeFrame(graphics, minecraft, anchor[0], anchor[1], height, partialTick);
                }
            }
        }
        Map<UUID, Vec3> targets = MissileTrackPayload.freshTargets(minecraft.level.getGameTime());
        if (targets.isEmpty()) {
            return;
        }
        for (Vec3 target : targets.values()) {
            int[] point = LeadArrowRenderer.projectWorldPoint(minecraft, target, width, height, partialTick);
            if (point == null) {
                continue;
            }
            drawSquare(graphics, point[0], point[1], HALF_SIDE, LOCK_RED);
        }
    }

    private static boolean hasInFlightInfraredMissile(Minecraft minecraft) {
        for (Entity entity : minecraft.level.entitiesForRendering()) {
            EntityType<?> type = entity.getType();
            if (type == BeamMissileRegistry.INFRARED_TANSHE.get()
                    || type == BeamMissileRegistry.AIRCRAFT_INFRARED_TANSHE.get()) {
                return true;
            }
        }
        return false;
    }

    /** Corner frame matching the seeker's 60 degree search cone, anchored on the launch direction. */
    private static void drawSeekerRangeFrame(
            GuiGraphics graphics, Minecraft minecraft, int centreX, int centreY, int height, float partialTick) {
        Camera camera = minecraft.gameRenderer.getMainCamera();
        double fov = ((GameRendererAccessor) minecraft.gameRenderer).stabilizedTurret$getFov(camera, partialTick, true);
        double focal = (height * 0.5) / Math.tan(Math.toRadians(fov) * 0.5);
        double halfCone = focal * Math.tan(Math.toRadians(30.0));
        int half = (int) Math.min(Math.round(halfCone), height / 2);
        int minX = centreX - half;
        int minY = centreY - half;
        int maxX = centreX + half;
        int maxY = centreY + half;
        int arm = Math.max(5, Math.min(14, Math.min(maxX - minX, maxY - minY) / 4));
        graphics.fill(minX, minY, minX + 1, minY + arm, LOCK_RED);
        graphics.fill(minX, minY, minX + arm, minY + 1, LOCK_RED);
        graphics.fill(maxX - 1, minY, maxX, minY + arm, LOCK_RED);
        graphics.fill(maxX - arm, minY, maxX, minY + 1, LOCK_RED);
        graphics.fill(minX, maxY - arm, minX + 1, maxY, LOCK_RED);
        graphics.fill(minX, maxY - 1, minX + arm, maxY, LOCK_RED);
        graphics.fill(maxX - 1, maxY - arm, maxX, maxY, LOCK_RED);
        graphics.fill(maxX - arm, maxY - 1, maxX, maxY, LOCK_RED);
    }

    private static void drawSquare(GuiGraphics graphics, int centreX, int centreY, int halfSide, int color) {
        int minX = centreX - halfSide;
        int minY = centreY - halfSide;
        int maxX = centreX + halfSide;
        int maxY = centreY + halfSide;
        graphics.fill(minX, minY, maxX + 1, minY + 1, color);
        graphics.fill(minX, maxY, maxX + 1, maxY + 1, color);
        graphics.fill(minX, minY + 1, minX + 1, maxY, color);
        graphics.fill(maxX, minY + 1, maxX + 1, maxY, color);
    }
}

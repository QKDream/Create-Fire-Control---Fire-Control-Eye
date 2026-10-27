package com.qkdream.firecontrolcompat.client;

import com.hooya.stabilizedturret.client.ClientAimState;
import com.hooya.stabilizedturret.mixin.client.GameRendererAccessor;
import com.qkdream.firecontrolcompat.BeamMissileRegistry;
import com.qkdream.firecontrolcompat.FireControlCompat;
import com.qkdream.firecontrolcompat.IrSeekerSession;
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
 * Draws the infrared seeker HUD for the player in the fire-control-bound seat:
 * the off-axis frame the lock lives inside, the small field of view frame a
 * contact has to be inside before it is taken, the lock frame that follows the
 * contact the head settled on and the lock squares of rounds already in flight.
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
        boolean searching = SeekerHudPayload.isSearchActive();
        boolean fresh = SeekerHudPayload.isFresh(System.currentTimeMillis());
        // The frames are drawn in every camera view, not only down the sight: a
        // gunner aiming from third person needs to see where the head is pointed
        // just as much as one looking through the scope, and without it the narrow
        // field of view is impossible to line up.
        boolean showGround = ground;
        boolean showAircraft = aircraft && !ground;
        boolean inFlight = hasInFlightInfraredMissile(minecraft);
        if (diagnosticTick != minecraft.level.getGameTime() && minecraft.level.getGameTime() % 40L == 0L) {
            diagnosticTick = minecraft.level.getGameTime();
            FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] seeker hud render seated={} fresh={} ground={} aircraft={} scoped={} inFlight={} searching={} lock={}",
                    ClientAimState.isSeatedAtBoundController(), fresh, ground, aircraft,
                    ClientAimState.isScoped(), inFlight, searching, SeekerHudPayload.lockedTargetPosition());
        }
        Vec3 locked = searching ? SeekerHudPayload.lockedTargetPosition() : null;
        if (!inFlight && fresh && (showGround || showAircraft || searching)) {
            Vec3 origin = SeekerHudPayload.launchOrigin();
            Vec3 direction = SeekerHudPayload.launchDirection();
            // The two frames are no longer the same window. The off-axis frame is the
            // limit of the head's travel measured from the launcher, so it stays on the
            // launcher's own line and travels with the launcher. The field of view frame
            // is the head itself: it slides onto the contact the fire control radar
            // designated while the head can reach it, and stops at the edge of travel
            // while it cannot. It is only drawn while the head is still looking, because
            // once a contact is taken the lock frame has to be read without it.
            Vec3 boresightPoint = origin == null || direction == null ? null : origin.add(direction.scale(50.0));
            Vec3 lookPoint = SeekerHudPayload.frameAnchorPoint();
            if (boresightPoint != null) {
                int[] anchor = LeadArrowRenderer.projectWorldPoint(
                        minecraft, boresightPoint, width, height, partialTick);
                if (anchor != null) {
                    drawConeFrame(graphics, minecraft, anchor[0], anchor[1], height, partialTick,
                            IrSeekerSession.OFF_AXIS_HALF_ANGLE_DEGREES, 14);
                }
            }
            if (locked == null) {
                if (lookPoint == null) {
                    lookPoint = boresightPoint;
                }
                int[] anchor = lookPoint == null ? null
                        : LeadArrowRenderer.projectWorldPoint(minecraft, lookPoint, width, height, partialTick);
                if (anchor != null) {
                    drawConeFrame(graphics, minecraft, anchor[0], anchor[1], height, partialTick,
                            IrSeekerSession.SEARCH_HALF_ANGLE_DEGREES, 6);
                }
            }
        }
        if (locked != null) {
            int[] point = LeadArrowRenderer.projectWorldPoint(minecraft, locked, width, height, partialTick);
            if (point != null) {
                drawSquare(graphics, point[0], point[1], HALF_SIDE, LOCK_RED);
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
            // RETIRED(ir-arm-rounds): the airborne infrared round is not registered any more.
            if (type == BeamMissileRegistry.INFRARED_TANSHE.get()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Corner frame of a cone around the launch line: the half angle is the same one
     * the server tests contacts against, so the frame on screen is exactly the
     * window the seeker works in.
     */
    private static void drawConeFrame(GuiGraphics graphics, Minecraft minecraft, int centreX, int centreY,
            int height, float partialTick, double halfAngleDegrees, int maxArm) {
        Camera camera = minecraft.gameRenderer.getMainCamera();
        double fov = ((GameRendererAccessor) minecraft.gameRenderer).stabilizedTurret$getFov(camera, partialTick, true);
        double focal = (height * 0.5) / Math.tan(Math.toRadians(fov) * 0.5);
        double halfCone = focal * Math.tan(Math.toRadians(halfAngleDegrees));
        int half = (int) Math.max(3L, Math.min(Math.round(halfCone), height / 2));
        int minX = centreX - half;
        int minY = centreY - half;
        int maxX = centreX + half;
        int maxY = centreY + half;
        int arm = Math.max(3, Math.min(maxArm, Math.min(maxX - minX, maxY - minY) / 4));
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

package com.qkdream.firecontrolcompat.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.qkdream.firecontrolcompat.FireControlCompat;
import com.qkdream.firecontrolcompat.IrAtgm;
import com.qkdream.firecontrolcompat.entity.BeamRidingMissileEntity;
import java.util.Locale;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/**
 * The mark that makes a round visible at ranges where the round itself <b>cannot</b> be.
 *
 * <h2>Why the round is invisible at long range (measured, not guessed)</h2>
 *
 * <p>The tracking range is not the problem any more. The five entity types were moved to
 * {@code clientTrackingRange(64)} (1024 blocks) and the user's own log proves the change took
 * effect: {@code ir-atgm render: .. dist=611.3 limit=1024 draws=1} - the server sent the round, the
 * client had it, and {@code EntityRenderer#render} ran at six hundred blocks. It was still not
 * visible, because of two things no renderer can argue with:</p>
 *
 * <ol>
 *   <li><b>Fog.</b> The client's {@code renderDistance} is <b>10 chunks = 160 blocks</b>
 *       ({@code options.txt}). Entity and item rendering go through the entity shaders, and those are
 *       fogged: {@code rendertype_entity_translucent.fsh} ends in
 *       {@code linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor)} (vanilla asset, read
 *       out of {@code client-1.21.1-..-extra.jar}). Past the fog end the round is drawn in
 *       <b>exactly the fog colour</b>, which is the sky: invisible, at any size.</li>
 *   <li><b>The far plane.</b> {@code GameRenderer#getDepthFar()} is {@code renderDistance * 4} =
 *       <b>640 blocks</b> at that render distance, so a round at the far end of a thousand block
 *       shot is <b>clipped out of the frustum altogether</b> - there is no pixel to paint it on.</li>
 * </ol>
 *
 * <p>So the long range representation cannot be an entity at all, no matter which object the proxy
 * channel puts there. It is drawn here instead, with {@link GameRenderer#getPositionColorShader}:
 * that shader's JSON declares only {@code ModelViewMat / ProjMat / ColorModulator} and its fragment
 * stage is {@code fragColor = vertexColor * ColorModulator} - <b>no fog uniforms, no fog call</b>
 * (vanilla asset, checked the same way). A quad drawn with it is unaffected by fog.</p>
 *
 * <h2>What is drawn</h2>
 *
 * <p>One camera-facing mark per round that is being drawn past {@link #NEAR_LIMIT}: a small warm
 * halo with a hot core, sized so it keeps a <b>constant angular size</b> ({@link #ANGULAR_SIZE}
 * radians, about five pixels at seventy degrees of field of view) instead of shrinking into
 * nothing. The mark is placed <b>along the same view ray</b> as the round but pulled inside the far
 * plane when the round is beyond it ({@link #FAR_PLANE_MARGIN}), so the perspective divide lands it
 * on exactly the pixel the round is at, while the geometry being painted stays inside the clip
 * volume. Its world size is scaled by the same factor, so pulling it closer does not make it bigger
 * on screen.</p>
 *
 * <p>Which object a mark answers for is decided by {@link MissileVisualSupport#shouldSuppressNative}
 * - the same handover the renderer uses - so a round still produces <b>exactly one</b> object and
 * one mark, whether that object is the tracked round or fire control's remote proxy.</p>
 *
 * <p>The stage is {@link RenderLevelStageEvent.Stage#AFTER_LEVEL}: the depth buffer is complete, so
 * terrain in front of the round still hides the mark, exactly as it hides the round.</p>
 */
@EventBusSubscriber(modid = FireControlCompat.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class MissileFarMarker {

    /**
     * Distance from which the mark is drawn, in blocks.
     *
     * <p>Inside it the airframe itself is big enough to see (1.575 blocks long, so about twenty
     * pixels at this range) and the fog has barely started; drawing a mark on top of it would only
     * double the brightness of a round the player can already follow. The same number is the floor
     * of the entity/proxy handover in {@link MissileVisualSupport#horizon}.</p>
     */
    public static final double NEAR_LIMIT = IrAtgm.PROXY_HANDOVER_FLOOR;

    /** Angular size of one mark, in radians: about five pixels at seventy degrees and 1080p. */
    private static final double ANGULAR_SIZE = 0.0065D;

    /** Smallest the mark is ever drawn, in blocks, so a round just past the limit still reads. */
    private static final double MIN_SIZE = 1.9D;

    /** How much of the far plane the mark may use, so it is never clipped by it. */
    private static final double FAR_PLANE_MARGIN = 0.9D;

    /** Marks per frame. A salvo of anti-air rounds must not turn into a wall of quads. */
    private static final int MAX_MARKERS = 32;

    /** Frames between two report lines (about a second at sixty frames). */
    private static final int REPORT_FRAMES = 120;

    private static final double[] MARK_X = new double[MAX_MARKERS];
    private static final double[] MARK_Y = new double[MAX_MARKERS];
    private static final double[] MARK_Z = new double[MAX_MARKERS];
    private static final double[] MARK_ANGULAR = new double[MAX_MARKERS];
    private static final double[] MARK_DRAWN = new double[MAX_MARKERS];

    /** Frames counted since the last report; never a subtraction from {@code Long.MIN_VALUE}. */
    private static int framesSinceReport;

    private MissileFarMarker() {
    }

    /** How many chunks the client really renders, which is what the fog is measured in. */
    public static int renderDistanceChunks() {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft != null && minecraft.options != null) {
                return Math.max(1, minecraft.options.getEffectiveRenderDistance());
            }
        } catch (Throwable ignored) {
            // fall through to the conservative figure
        }
        return 8;
    }

    /** Where the terrain fog has swallowed everything, in blocks ({@code renderDistance * 16}). */
    public static double fogReach() {
        return renderDistanceChunks() * 16.0D;
    }

    /** Whether a round this far away is drawn as a mark rather than by its own renderer. */
    public static boolean covers(double distance) {
        return distance >= NEAR_LIMIT;
    }

    /**
     * The angular size a mark for a round this far away has, in radians.
     *
     * <p>Constant past {@link #MIN_SIZE}, so the mark never shrinks below a few pixels however far
     * the round gets - which is the whole request ("even if it is small").</p>
     */
    public static double angularSize(double distance) {
        if (distance < 1.0E-4D) {
            return ANGULAR_SIZE;
        }
        return Math.max(MIN_SIZE, distance * ANGULAR_SIZE) / distance;
    }

    /** That angular size as pixels at seventy degrees of field of view on a 1080p screen. */
    public static double pixels(double radians) {
        return Math.toDegrees(radians) * 1080.0D / 70.0D;
    }

    /**
     * Where the drawn picture changes from the tracked round to fire control's remote proxy, in
     * blocks: the fog wall, with {@link #NEAR_LIMIT} as its floor.
     *
     * <p>Inside the fog the tracked round is exact and visible, so it is the picture. Past the fog
     * nothing of either object can be seen and only this mark is, so the proxy - which exists at
     * every distance, because the payload is broadcast to everyone within twenty thousand blocks -
     * is the honest one to answer for the round. {@link MissileVisualSupport#horizon} takes the
     * smaller of this and the entity type's own tracking range.</p>
     */
    public static double handoverDistance() {
        return Math.max(NEAR_LIMIT, fogReach());
    }

    @SubscribeEvent
    public static void onRender(RenderLevelStageEvent event) {
        /*
         * TEMP-DISABLED(use-pack-bvr): the mark is this mod's own answer to fog and the far plane,
         * written before it was known that fire control has a complete beyond-visual-range chain of
         * its own. It is switched off whenever that chain is present, because:
         *
         *   - the chain draws the round itself (ClientMissileRenderer#renderOrdinaryAa ->
         *     EntityRenderDispatcher -> this mod's renderer), not a dot, with the fog pushed away
         *     and the projection rebuilt to reach it;
         *   - a mark plus that drawing would be two pictures of one round, and a mark over a round
         *     that is genuinely drawn is exactly the double image the "phantom" report was.
         *
         * Kept, and still wired, for the case CompatBvr#available() reports false: an older pack
         * without the chain, or a future one that moved those classes. In that case nothing else
         * makes a round visible past the fog wall, so the marker is the answer.
         */
        if (CompatBvr.available()) {
            return;
        }
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null || minecraft.gameRenderer == null) {
            return;
        }
        Camera camera = event.getCamera();
        if (camera == null) {
            return;
        }
        try {
            collect(event, minecraft, camera.getPosition());
        } catch (Throwable failure) {
            // A mark is never worth a crash - but a silent failure here would read as "still
            // invisible", so it is said out loud, throttled by the same frame counter.
            framesSinceReport++;
            if (framesSinceReport >= REPORT_FRAMES) {
                framesSinceReport = 0;
                FireControlCompat.LOGGER.info(
                        "[firecontrolcompat] ir-atgm far marker: paint failed: {}",
                        String.valueOf(failure));
            }
        }
    }

    /**
     * Picks the rounds a mark is owed for, then paints them.
     *
     * <p>Collected first and painted second: the shared {@code Tesselator} builder must not be left
     * midway through a batch, so nothing is begun until there is at least one quad to add to it.</p>
     */
    private static void collect(RenderLevelStageEvent event, Minecraft minecraft, Vec3 eye) {
        float depthFar = 256.0F;
        try {
            depthFar = minecraft.gameRenderer.getDepthFar();
        } catch (Throwable ignored) {
            // keep the conservative figure
        }
        int rounds = 0;
        int count = 0;
        double nearest = Double.MAX_VALUE;
        double farthest = 0.0D;
        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (!(entity instanceof BeamRidingMissileEntity missile)) {
                continue;
            }
            // A round still on its rail is not in flight: the launcher's own model already shows it,
            // and a mark over a parked launcher two hundred blocks away is noise.
            if (missile.firecontrolcompat$isMounted()) {
                continue;
            }
            rounds++;
            // Same handover the renderers use: exactly one object per round, so exactly one mark.
            if (MissileVisualSupport.shouldSuppressNative(entity)) {
                continue;
            }
            Vec3 at = entity.position();
            double distance = at.distanceTo(eye);
            if (!covers(distance) || count >= MAX_MARKERS) {
                continue;
            }
            double size = Math.max(MIN_SIZE, distance * ANGULAR_SIZE);
            double drawn = Math.min(distance, Math.max(NEAR_LIMIT, depthFar * FAR_PLANE_MARGIN));
            double scale = distance < 1.0E-4D ? 1.0D : drawn / distance;
            MARK_X[count] = eye.x + (at.x - eye.x) * scale;
            MARK_Y[count] = eye.y + (at.y - eye.y) * scale;
            MARK_Z[count] = eye.z + (at.z - eye.z) * scale;
            MARK_ANGULAR[count] = size * scale;
            MARK_DRAWN[count] = drawn;
            count++;
            nearest = Math.min(nearest, distance);
            farthest = Math.max(farthest, distance);
        }
        if (count <= 0) {
            report(rounds, 0, 0.0D, 0.0D, 0.0D, depthFar);
            return;
        }
        paint(event, count);
        // The angular size of the nearest mark is the one worth reporting: it is the one most likely
        // to have collapsed into a pixel, and it is what the mark's floor is there to prevent.
        double angularOfNearest = Math.max(MIN_SIZE, nearest * ANGULAR_SIZE) / nearest;
        report(rounds, count, nearest, farthest, angularOfNearest, depthFar);
    }

    /** The quads themselves: a warm halo and a hot core, both facing the camera. */
    private static void paint(RenderLevelStageEvent event, int count) {
        RenderSystem.getModelViewStack().pushMatrix().identity();
        RenderSystem.applyModelViewMatrix();
        try {
            BufferBuilder buffer = Tesselator.getInstance()
                    .begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            if (buffer == null) {
                return;
            }
            PoseStack pose = new PoseStack();
            pose.mulPose(event.getModelViewMatrix());
            Vec3 eye = event.getCamera().getPosition();
            for (int index = 0; index < count; index++) {
                pose.pushPose();
                pose.translate(MARK_X[index] - eye.x, MARK_Y[index] - eye.y, MARK_Z[index] - eye.z);
                Matrix4f matrix = pose.last().pose();
                float half = (float) (MARK_ANGULAR[index] * 0.5D);
                quad(buffer, matrix, half * 2.2F, 1.0F, 0.60F, 0.18F, 0.30F);
                quad(buffer, matrix, half, 1.0F, 0.96F, 0.80F, 0.95F);
                pose.popPose();
            }
            flush(buffer);
        } finally {
            RenderSystem.getModelViewStack().popMatrix();
            RenderSystem.applyModelViewMatrix();
        }
    }

    private static void quad(BufferBuilder buffer, Matrix4f matrix, float half,
                             float red, float green, float blue, float alpha) {
        buffer.addVertex(matrix, -half, -half, 0.0F).setColor(red, green, blue, alpha);
        buffer.addVertex(matrix, half, -half, 0.0F).setColor(red, green, blue, alpha);
        buffer.addVertex(matrix, half, half, 0.0F).setColor(red, green, blue, alpha);
        buffer.addVertex(matrix, -half, half, 0.0F).setColor(red, green, blue, alpha);
    }

    /**
     * Paints the batch.
     *
     * <p>{@link GameRenderer#getPositionColorShader} is the whole trick: a shader with no fog
     * uniforms cannot fog what it draws. Depth test is left on so terrain still hides a mark that is
     * behind it, which is the same rule the round itself follows.</p>
     */
    private static void flush(BufferBuilder buffer) {
        MeshData mesh = buffer.build();
        if (mesh == null) {
            return;
        }
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        // Depth test on (terrain still hides a mark behind it), depth writes off (this pass runs
        // last, and a mark must not leave anything for whatever is drawn after it).
        RenderSystem.depthMask(false);
        try {
            BufferUploader.drawWithShader(mesh);
        } finally {
            RenderSystem.depthMask(true);
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
        }
    }

    /**
     * One line a second while marks are being drawn, so the far picture can be read off the log:
     * {@code marks=0} with rounds in the air means the handover is putting them somewhere else,
     * and a {@code px} that has collapsed means the angular floor is not being applied.
     */
    private static void report(int rounds, int marks, double nearest, double farthest,
                               double angular, double depthFar) {
        framesSinceReport++;
        if (marks <= 0 || framesSinceReport < REPORT_FRAMES) {
            return;
        }
        framesSinceReport = 0;
        double degrees = Math.toDegrees(angular);
        FireControlCompat.LOGGER.info(
                "[firecontrolcompat] ir-atgm far marker: rounds={} marks={} nearest={} farthest={}"
                        + " angular={}deg px={} limit={} rd={} fog={} farplane={} shader=position_color(fog-free)",
                rounds, marks,
                String.format(Locale.ROOT, "%.1f", nearest),
                String.format(Locale.ROOT, "%.1f", farthest),
                String.format(Locale.ROOT, "%.3f", degrees),
                String.format(Locale.ROOT, "%.1f", degrees * 1080.0D / 70.0D),
                String.format(Locale.ROOT, "%.0f", IrAtgm.LOCK_RANGE),
                renderDistanceChunks(),
                String.format(Locale.ROOT, "%.0f", fogReach()),
                String.format(Locale.ROOT, "%.0f", depthFar));
    }
}

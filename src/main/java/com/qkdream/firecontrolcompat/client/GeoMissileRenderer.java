package com.qkdream.firecontrolcompat.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.hooya.stabilizedturret.content.controller.MianbaoBallisticMissileCompat;
import com.qkdream.firecontrolcompat.FireControlCompat;
import com.qkdream.firecontrolcompat.entity.BeamRidingMissileEntity;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * Shared GeckoLib renderer for the missile entities whose Blockbench models
 * lie along Z. Every frame the nose is laid onto the flight vector, keeping
 * the long-range trail and the native-model suppression of the previous
 * renderers. Models with the nose authored towards -Z use the vanilla mob
 * rotation pair, while the newer Blockbench exports that carry the nose
 * towards +Z flip both axes so the nose still ends up on the flight vector.
 *
 * <h2>"Only the trail, no airframe" (2026-10-26)</h2>
 *
 * <p>The heavy and the Neva round are drawn only by fire control's detached
 * long-range proxy since they went mathematical: the round itself never joins a
 * level, so the level's own entity pass never sees it. That proxy is rendered
 * through {@code EntityRenderDispatcher} by
 * {@code ClientMissileRenderer#renderOrdinaryAa}, and a GeckoLib renderer that
 * cannot find its baked geometry answers that call with <b>nothing at all</b> -
 * silently, without an exception and without a log line. The trail still
 * appears, because it is emitted by this class before the model is drawn, which
 * is exactly the user's report: a plume with no airframe.</p>
 *
 * <p>So the two questions that could not be answered from a log are answered
 * here, per type, once and then once a second: was the round a detached proxy,
 * and did its geometry bake. {@code baked=false} names the culprit outright,
 * and the round is then drawn as a wireframe silhouette instead of vanishing -
 * a visible stand-in that makes the defect obvious while proving the renderer
 * itself is reached.</p>
 */
public abstract class GeoMissileRenderer<T extends BeamRidingMissileEntity> extends GeoEntityRenderer<T> {

    private final boolean nosePlusZ;

    /** Uniform render scale; 1 means as authored. */
    protected final float renderScale;

    protected GeoMissileRenderer(EntityRendererProvider.Context context, GeoModel<T> model) {
        this(context, model, false);
    }

    protected GeoMissileRenderer(EntityRendererProvider.Context context, GeoModel<T> model, boolean nosePlusZ) {
        this(context, model, nosePlusZ, 1.0F);
    }

    protected GeoMissileRenderer(EntityRendererProvider.Context context, GeoModel<T> model, boolean nosePlusZ,
            float renderScale) {
        super(context, model);
        this.nosePlusZ = nosePlusZ;
        this.renderScale = renderScale;
    }

    @Override
    public void render(T entity, float entityYaw, float partialTicks, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight) {
        if (MissileVisualSupport.shouldSuppressNative(entity)) {
            return;
        }
        MissileVisualSupport.spawnLongRangeTrail(entity);
        // Past the far plane fire control supplies a buffer source pinned to its own texture; this
        // answers one that keeps the round's own texture instead (see MissileVisualSupport#bufferFor).
        MultiBufferSource target = MissileVisualSupport.bufferFor(entity, buffer, getTextureLocation(entity));
        boolean baked = firecontrolcompat$hasGeometry(entity);
        if (baked) {
            super.render(entity, entityYaw, partialTicks, poseStack, target, packedLight);
        } else {
            firecontrolcompat$wireframe(poseStack, target);
        }
        firecontrolcompat$report(entity, baked);
    }

    /**
     * Whether GeckoLib actually has this round's geometry.
     *
     * <p>This is the one question that turns "no airframe" from a guess into a verdict: a model
     * resource that is absent, unparsable or not reloaded answers {@code null} here, and GeckoLib's
     * own draw call then returns without drawing anything and without complaining.</p>
     */
    private boolean firecontrolcompat$hasGeometry(T entity) {
        try {
            GeoModel<T> model = getGeoModel();
            ResourceLocation resource = model.getModelResource(entity);
            return resource != null && model.getBakedModel(resource) != null;
        } catch (Throwable failure) {
            FireControlCompat.LOGGER.info("ir-atgm render: geometry probe failed type={} reason={}",
                    firecontrolcompat$typeName(entity), String.valueOf(failure));
            return false;
        }
    }

    /**
     * What is drawn when the geometry is not there: the round's own silhouette.
     *
     * <p>Twelve edges of a long thin box along the model's nose axis, in the round's own colour
     * family. It is deliberately not a substitute airframe - it is a marker that says "this round
     * is here, its model is not", so a missing bake can never hide a round completely again.</p>
     */
    private void firecontrolcompat$wireframe(PoseStack poseStack, MultiBufferSource buffer) {
        try {
            VertexConsumer lines = buffer.getBuffer(RenderType.lines());
            Matrix4f matrix = poseStack.last().pose();
            float zNose = this.nosePlusZ ? 1.25F : -1.25F;
            float zTail = -zNose;
            float w = 0.16F;
            float[][] edges = new float[][]{
                    {-w, -w, zTail, w, -w, zTail},
                    {w, -w, zTail, w, w, zTail},
                    {w, w, zTail, -w, w, zTail},
                    {-w, w, zTail, -w, -w, zTail},
                    {-w, -w, zNose, w, -w, zNose},
                    {w, -w, zNose, w, w, zNose},
                    {w, w, zNose, -w, w, zNose},
                    {-w, w, zNose, -w, -w, zNose},
                    {-w, -w, zTail, -w, -w, zNose},
                    {w, -w, zTail, w, -w, zNose},
                    {w, w, zTail, w, w, zNose},
                    {-w, w, zTail, -w, w, zNose},
            };
            for (float[] edge : edges) {
                lines.addVertex(matrix, edge[0], edge[1], edge[2]).setColor(255, 196, 90, 220)
                        .setNormal(poseStack.last(), 0.0F, 0.0F, 1.0F);
                lines.addVertex(matrix, edge[3], edge[4], edge[5]).setColor(255, 196, 90, 220)
                        .setNormal(poseStack.last(), 0.0F, 0.0F, 1.0F);
            }
        } catch (Throwable failure) {
            FireControlCompat.LOGGER.info("ir-atgm render: wireframe fallback failed: {}",
                    String.valueOf(failure));
        }
    }

    /** Draw counter per type, and the throttle so one round cannot fill the log. */
    private static final Map<String, Integer> DRAWS_PER_TYPE = new ConcurrentHashMap<>();

    private static long lastDrawLog;

    /**
     * One line a second per type: is this round drawn, is it a proxy, and does it have geometry.
     *
     * <pre>ir-atgm render: type=&lt;path&gt; mode=&lt;entity|proxy&gt; channel=&lt;pack|own&gt; dist=&lt;n&gt; draws=&lt;n&gt; baked=&lt;true|false&gt; geo=&lt;path&gt; renderer=&lt;class&gt;</pre>
     */
    private void firecontrolcompat$report(T entity, boolean baked) {
        String type = firecontrolcompat$typeName(entity);
        DRAWS_PER_TYPE.merge(type, 1, Integer::sum);
        long now;
        double distance = -1.0D;
        try {
            Minecraft minecraft = Minecraft.getInstance();
            now = minecraft.level == null ? 0L : minecraft.level.getGameTime();
            if (minecraft.gameRenderer != null && minecraft.gameRenderer.getMainCamera() != null) {
                distance = Math.sqrt(minecraft.gameRenderer.getMainCamera().getPosition()
                        .distanceToSqr(entity.position()));
            }
        } catch (Throwable ignored) {
            now = 0L;
        }
        if (lastDrawLog != 0L && now - lastDrawLog < 20L) {
            return;
        }
        lastDrawLog = now;
        int draws = DRAWS_PER_TYPE.getOrDefault(type, 0);
        DRAWS_PER_TYPE.clear();
        ResourceLocation geo = null;
        try {
            geo = getGeoModel().getModelResource(entity);
        } catch (Throwable ignored) {
        }
        // When the geometry is missing the line also says why: is the model in GeckoLib's own
        // baked table at all (the cache field), and what did the probe answer (the reason
        // field). Without those two a baked=false is indistinguishable from a model that was
        // never registered, and the wrong half gets chased - which is what put a box airframe on
        // screen in the first place.
        String cache = "-";
        String reason = "-";
        if (!baked) {
            try {
                cache = String.valueOf(software.bernie.geckolib.cache.GeckoLibCache
                        .getBakedModels().containsKey(geo));
            } catch (Throwable failure) {
                cache = "unreadable:" + failure.getClass().getSimpleName();
            }
            try {
                GeoModel<T> model = getGeoModel();
                reason = model.getBakedModel(geo) == null ? "getBakedModel=null" : "baked-now";
            } catch (Throwable failure) {
                reason = "threw:" + failure.getClass().getSimpleName() + ":" + failure.getMessage();
            }
        }
        FireControlCompat.LOGGER.info(
                "ir-atgm render: type={} mode={} channel={} dist={} draws={} baked={} geo={}"
                        + " cache={} reason={} renderer={} scale={}",
                type,
                MissileVisualSupport.isRemoteVisual(entity) ? "proxy" : "entity",
                CompatBvr.available() ? "pack" : "own",
                distance < 0.0D ? "-" : String.format(java.util.Locale.ROOT, "%.1f", distance),
                draws, baked, geo == null ? "-" : geo.toString(), cache, reason,
                getClass().getSimpleName(),
                // GeckoLib's own multiplier, not just the field this class keeps: the Neva renderer
                // halves nothing and doubles nothing here - it calls withScale(2.8), which lands in
                // scaleWidth. Printing the field alone reported 1.00 for a round drawn at 2.8.
                String.format(java.util.Locale.ROOT, "%.2f", this.scaleWidth * this.renderScale));
    }

    private static String firecontrolcompat$typeName(BeamRidingMissileEntity entity) {
        try {
            ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
            return key == null ? "-" : key.getPath();
        } catch (Throwable ignored) {
            return "-";
        }
    }

    /**
     * The box the game culls this round by, grown with the render scale.
     *
     * <p>A round drawn larger than authored sticks out of the box the entity reports, and the front
     * of it is then culled away while the rest stays - which is what a missing nose looks like. The
     * box is inflated by the same factor the model is drawn at.</p>
     *
     * <p>The mobile models are longer than the 0.6-block entity box as well (the heavy round is
     * three blocks, the Neva nearly that at 2.8x), so the renderer answers "visible" outright the
     * way the infrared anti-tank renderer does. Only ever applied to this mod's own rounds: a
     * vanilla entity that happened to be rendered by this class does not exist.</p>
     */
    @Override
    public boolean shouldRender(T entity, net.minecraft.client.renderer.culling.Frustum frustum,
            double camX, double camY, double camZ) {
        return true;
    }

    /**
     * The pose for one round: rail, remote quaternion, or the mob rotation pair.
     */
    @Override
    protected void applyRotations(T entity, PoseStack poseStack, float ageInTicks,
                                  float rotationYaw, float partialTick, float pivotY) {
        // Fire control renders far-away compat missiles through a remote
        // visual proxy whose orientation arrives as a quaternion that maps
        // model-space +Y onto the flight vector. Honor it first so the proxy
        // no longer flies sideways; our models carry the nose towards -Z, so
        // the +90 degree X turn rotates the nose onto +Y before the pose.
        // A round sitting on a rail is not flying: it must not be laid onto a flight vector, and
        // it must not lerp between anything. Its yaw is the rail's own, set by the rack every tick,
        // so it is drawn from that and nothing else - which is what stops it spinning in place.
        if (entity instanceof com.qkdream.firecontrolcompat.entity.BeamRidingMissileEntity missile
                && missile.firecontrolcompat$isMounted()) {
            // Lying along the rail, nose out: the round is turned to face the way the rack points,
            // and the extra half turn lays the tail towards the mount instead of the nose.
            poseStack.mulPose(Axis.YP.rotationDegrees(-entity.getYRot() + 180.0F));
            if (!this.nosePlusZ) {
                poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
            }
            if (this.renderScale != 1.0F) {
                poseStack.scale(this.renderScale, this.renderScale, this.renderScale);
            }
            return;
        }
        Quaternionf remote = MianbaoBallisticMissileCompat.remoteRenderRotation(entity);
        if (remote != null) {
            poseStack.mulPose(remote);
            poseStack.mulPose(Axis.XP.rotationDegrees(90.0F));
            return;
        }
        // The entities store their rotation in the mob convention (positive
        // pitch flies down, positive yaw turns west - see
        // firecontrolcompat$faceVelocity). A nose towards -Z model takes the
        // vanilla-style 180 - yaw turn with the pitch sign flipped; a nose
        // towards +Z model is the mirror of that pair, so it uses -yaw and
        // +pitch instead of flying tail first.
        float yaw = Mth.lerp(partialTick, entity.yRotO, entity.getYRot());
        float pitch = Mth.lerp(partialTick, entity.xRotO, entity.getXRot());
        if (this.nosePlusZ) {
            poseStack.mulPose(Axis.YP.rotationDegrees(-yaw));
            poseStack.mulPose(Axis.XP.rotationDegrees(pitch));
        } else {
            poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - yaw));
            poseStack.mulPose(Axis.XP.rotationDegrees(-pitch));
        }
    }
}

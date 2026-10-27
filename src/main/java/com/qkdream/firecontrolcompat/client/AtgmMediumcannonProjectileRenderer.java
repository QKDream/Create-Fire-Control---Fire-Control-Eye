package com.qkdream.firecontrolcompat.client;

import java.util.HashMap;
import java.util.Map;

import org.joml.Matrix4f;
import org.joml.Quaternionf;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import riftyboi.cbcmodernwarfare.munitions.medium_cannon.AbstractMediumcannonProjectile;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import rbasamoyai.createbigcannons.CreateBigCannons;

/**
 * Renders the gun launched missile in its own orientation: the Blockbench model
 * points up (+Y) with its tail at the bottom, so the mesh is turned until the
 * nose follows the flight direction and then re-centred on the entity. This
 * avoids relying on CBC's facing matrix, which expects a model that points
 * along -Z.
 *
 * <p>A round fitted with a tracer tip gets the same camera facing glow CBC
 * Modern Warfare draws for its own medium cannon rounds, so the rounds this
 * renderer draws are no exception to the tracer.</p>
 */
public class AtgmMediumcannonProjectileRenderer<T extends AbstractMediumcannonProjectile> extends EntityRenderer<T> {

    private static final float MODEL_CENTER_X = 8.1F;
    private static final float MODEL_CENTER_Y = 21.3F;
    private static final float MODEL_CENTER_Z = 8.05F;
    private static final float UNITS_PER_BLOCK = 16.0F;

    /** Tracer glow frames shipped by Create Big Cannons. */
    private static final String TRACER_TEXTURE = "textures/entity/tracer_glow%d.png";

    public AtgmMediumcannonProjectileRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    /** The last round seen on each entity, for when the entity stops carrying one. */
    private static final Map<Integer, ItemStack> LAST_ITEM = new HashMap<>();

    /** Rounds kept as the fallback for an entity that never carried one at all. */
    private static final Map<Class<?>, ItemStack> TYPE_ITEM = new HashMap<>();

    /**
     * The round to draw this entity as.
     *
     * <p>The model is drawn from the round the entity carries, and an entity that
     * carries none used to be skipped entirely: a shell rammed into a cannon has no
     * round on it, so it flew invisible, and a missile that dropped its round part way
     * through the flight stopped being drawn from that point on. The last round seen on
     * the entity is remembered, and an entity that never had one is drawn as the round
     * its type is loaded with.</p>
     */
    private ItemStack compat$roundOf(T entity) {
        ItemStack carried = entity.getItem();
        if (carried != null && !carried.isEmpty()) {
            LAST_ITEM.put(entity.getId(), carried.copy());
            if (LAST_ITEM.size() > 256) {
                LAST_ITEM.clear();
            }
            return carried;
        }
        ItemStack remembered = LAST_ITEM.get(entity.getId());
        if (remembered != null) {
            return remembered;
        }
        return TYPE_ITEM.computeIfAbsent(entity.getClass(), type -> {
            if (entity instanceof com.qkdream.firecontrolcompat.compat.cbcmodernwarfare.entity
                    .SensorFuzedMediumcannonProjectile) {
                return com.qkdream.firecontrolcompat.compat.cbcmodernwarfare.CbcModernWarfareCompat
                        .SFM_MEDIUMCANNON_ROUND.get().getDefaultInstance();
            }
            if (entity instanceof com.qkdream.firecontrolcompat.compat.cbcmodernwarfare.entity
                    .AtgmMediumcannonProjectile) {
                return com.qkdream.firecontrolcompat.compat.cbcmodernwarfare.CbcModernWarfareCompat
                        .ATGM_MEDIUMCANNON_ROUND.get().getDefaultInstance();
            }
            return ItemStack.EMPTY;
        });
    }

    @Override
    public void render(T entity, float entityYaw, float partialTicks,
            PoseStack poseStack, MultiBufferSource buffers, int packedLight) {
        ItemStack item = this.compat$roundOf(entity);
        if (item.isEmpty()) {
            return;
        }
        Vec3 direction = entity.getOrientation();
        if (direction == null || direction.lengthSqr() < 1.0E-6) {
            direction = entity.getDeltaMovement();
        }
        if (direction.lengthSqr() < 1.0E-6) {
            direction = new Vec3(0.0, -1.0, 0.0);
        }
        direction = direction.normalize();

        poseStack.pushPose();
        poseStack.mulPose(new Quaternionf().rotationTo(0.0F, 1.0F, 0.0F,
                (float) direction.x, (float) direction.y, (float) direction.z));
        poseStack.translate(-MODEL_CENTER_X / UNITS_PER_BLOCK, -MODEL_CENTER_Y / UNITS_PER_BLOCK,
                -MODEL_CENTER_Z / UNITS_PER_BLOCK);
        Minecraft.getInstance().getItemRenderer().renderStatic(item, ItemDisplayContext.NONE,
                LightTexture.FULL_BRIGHT, 0, poseStack, buffers, entity.level(), 0);
        poseStack.popPose();

        if (entity.isTracer()) {
            renderTracer(entity, poseStack, buffers);
        }

        super.render(entity, entityYaw, partialTicks, poseStack, buffers, packedLight);
    }

    /** Glowing sprite hung in front of the round, always camera facing. */
    private void renderTracer(T entity, PoseStack poseStack, MultiBufferSource buffers) {
        int frame = (int) ((entity.getId() + entity.level().getGameTime()) % 4L);
        ResourceLocation texture = CreateBigCannons.resource(String.format(TRACER_TEXTURE, frame));
        VertexConsumer consumer = buffers.getBuffer(RenderType.entityCutoutNoCull(texture));
        poseStack.pushPose();
        poseStack.mulPose(this.entityRenderDispatcher.cameraOrientation());
        Matrix4f pose = poseStack.last().pose();
        vertex(consumer, pose, -0.5F, -0.5F, 0, 1);
        vertex(consumer, pose, -0.5F, 0.5F, 0, 0);
        vertex(consumer, pose, 0.5F, 0.5F, 1, 0);
        vertex(consumer, pose, 0.5F, -0.5F, 1, 1);
        poseStack.popPose();
    }

    private static void vertex(VertexConsumer consumer, Matrix4f pose, float x, float y, int u, int v) {
        consumer.addVertex(pose, x, y, 0.0F)
                .setColor(255, 255, 255, 255)
                .setUv((float) u, (float) v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightTexture.FULL_BRIGHT)
                .setNormal(0.0F, 1.0F, 0.0F);
    }

    @Override
    public boolean shouldRender(T entity, Frustum frustum, double camX, double camY, double camZ) {
        return entity.isTracer() || super.shouldRender(entity, frustum, camX, camY, camZ);
    }

    @Override
    public ResourceLocation getTextureLocation(T entity) {
        return null;
    }
}
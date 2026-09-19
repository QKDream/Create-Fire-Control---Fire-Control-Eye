package com.qkdream.firecontrolcompat.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.qkdream.firecontrolcompat.entity.HeavyAirDefenseMissileEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;

/**
 * Renders the heavy air-defense missile through the vanilla item rendering
 * pipeline (same approach as {@link LoiteringMissileRenderer}): the item model
 * parents the user-supplied Blockbench model, so every texture is stitched into
 * the block atlas and can never show up as a missing/purple model.
 *
 * The supplied model is authored standing up (nose towards +Y, tail towards -Y,
 * centred on the block centre in all three axes), so the pose rotates the model
 * +Y axis onto the entity's flight vector instead of using the yaw/pitch form
 * the old mianbao cruise missile model needed.
 */
public class HeavyAaMissileRenderer extends EntityRenderer<HeavyAirDefenseMissileEntity> {

    private final ItemRenderer itemRenderer;

    public HeavyAaMissileRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.itemRenderer = context.getItemRenderer();
    }

    @Override
    public void render(HeavyAirDefenseMissileEntity entity, float entityYaw, float partialTicks,
                       PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        if (MissileVisualSupport.shouldSuppressNative(entity)) {
            return;
        }
        MissileVisualSupport.spawnLongRangeTrail(entity);
        poseStack.pushPose();
        float yaw = Mth.lerp(partialTicks, entity.yRotO, entity.getYRot());
        float pitch = Mth.lerp(partialTicks, entity.xRotO, entity.getXRot());
        poseStack.mulPose(Axis.YP.rotationDegrees(-yaw));
        poseStack.mulPose(Axis.XP.rotationDegrees(90.0F + pitch));
        this.itemRenderer.renderStatic(entity.getItem(), ItemDisplayContext.NONE, packedLight,
                OverlayTexture.NO_OVERLAY, poseStack, buffer, entity.level(), entity.getId());
        poseStack.popPose();
        super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight);
    }

    @Override
    public ResourceLocation getTextureLocation(HeavyAirDefenseMissileEntity entity) {
        return TextureAtlas.LOCATION_BLOCKS;
    }
}
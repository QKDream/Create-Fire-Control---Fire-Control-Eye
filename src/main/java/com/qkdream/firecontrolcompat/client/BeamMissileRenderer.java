package com.qkdream.firecontrolcompat.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.qkdream.firecontrolcompat.entity.BeamRidingMissileEntity;
import net.mcreator.myfirstmod.client.model.Model单兵防空导弹实体;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * Renders the beam-riding missile with the mianbao arsenal man-portable
 * air-defense missile model and texture.
 */
public class BeamMissileRenderer extends EntityRenderer<BeamRidingMissileEntity> {

    private static final ResourceLocation TEXTURE = ResourceLocation.parse(
            "mianbaos_modernwarfare:textures/entities/dan_bing_fang_kong_dao_dan_shi_ti_.png");

    private final Model单兵防空导弹实体 model;

    public BeamMissileRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.model = new Model单兵防空导弹实体(context.bakeLayer(Model单兵防空导弹实体.LAYER_LOCATION));
    }

    @Override
    public void render(BeamRidingMissileEntity entity, float entityYaw, float partialTicks,
                       PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        if (MissileVisualSupport.shouldSuppressNative(entity)) {
            return;
        }
        MissileVisualSupport.spawnLongRangeTrail(entity);
        VertexConsumer vertexConsumer = buffer.getBuffer(RenderType.entityCutout(this.getTextureLocation(entity)));
        poseStack.pushPose();
        poseStack.mulPose(Axis.YP.rotationDegrees(Mth.lerp(partialTicks, entity.yRotO, entity.getYRot()) - 90.0F));
        poseStack.mulPose(Axis.ZP.rotationDegrees(90.0F + Mth.lerp(partialTicks, entity.xRotO, entity.getXRot())));
        this.model.renderToBuffer(poseStack, vertexConsumer, packedLight, OverlayTexture.NO_OVERLAY, -1);
        poseStack.popPose();
        super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight);
    }

    @Override
    public ResourceLocation getTextureLocation(BeamRidingMissileEntity entity) {
        return TEXTURE;
    }
}

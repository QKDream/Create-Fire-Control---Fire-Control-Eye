package com.qkdream.firecontrolcompat.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.qkdream.firecontrolcompat.entity.LoiteringMissileEntity;
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
 * Renders the loitering munition through the vanilla item rendering pipeline,
 * exactly like mianbao arsenal's television-guided missile renderer: the item
 * model (models/item/loitering_munition.json, parenting the user-supplied
 * Blockbench model) is baked by the standard model bakery, so the texture is
 * stitched into the block atlas and can never go missing or purple-black.
 */
public class LoiteringMissileRenderer extends EntityRenderer<LoiteringMissileEntity> {

    private final ItemRenderer itemRenderer;

    public LoiteringMissileRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.itemRenderer = context.getItemRenderer();
    }

    @Override
    public void render(LoiteringMissileEntity entity, float entityYaw, float partialTicks,
                       PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        if (MissileVisualSupport.shouldSuppressNative(entity)) {
            return;
        }
        MissileVisualSupport.spawnLongRangeTrail(entity);
        poseStack.pushPose();
        float yaw = Mth.lerp(partialTicks, entity.yRotO, entity.getYRot());
        float pitch = Mth.lerp(partialTicks, entity.xRotO, entity.getXRot());
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F + yaw));
        poseStack.mulPose(Axis.XP.rotationDegrees(-pitch));
        this.itemRenderer.renderStatic(entity.getItem(), ItemDisplayContext.NONE, packedLight,
                OverlayTexture.NO_OVERLAY, poseStack, buffer, entity.level(), entity.getId());
        poseStack.popPose();
        super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight);
    }

    @Override
    public ResourceLocation getTextureLocation(LoiteringMissileEntity entity) {
        return TextureAtlas.LOCATION_BLOCKS;
    }

}

package com.qkdream.firecontrolcompat.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.qkdream.firecontrolcompat.compat.cbcmodernwarfare.entity.SensorFuzedSubmunition;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * Draws a sensor fuzed submunition as its own model, turning slowly about the
 * vertical axis on the way down. The mesh sits centred on x/z eight and hovers
 * just above the entity origin, so it is re-centred before it is spun.
 */
public class SensorFuzedSubmunitionRenderer extends EntityRenderer<SensorFuzedSubmunition> {

    private static final float MODEL_CENTER_X = 8.0F;
    private static final float MODEL_CENTER_Y = 1.4F;
    private static final float MODEL_CENTER_Z = 8.0F;
    private static final float UNITS_PER_BLOCK = 16.0F;

    /** Degrees of yaw added per tick, which is the slow spin of the dart. */
    private static final float SPIN_DEGREES_PER_TICK = 9.0F;

    public SensorFuzedSubmunitionRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(SensorFuzedSubmunition entity, float entityYaw, float partialTicks,
            PoseStack poseStack, MultiBufferSource buffers, int packedLight) {
        ItemStack item = entity.getItem();
        if (item.isEmpty()) {
            return;
        }
        poseStack.pushPose();
        poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(
                (entity.tickCount + partialTicks) * SPIN_DEGREES_PER_TICK));
        poseStack.translate(-MODEL_CENTER_X / UNITS_PER_BLOCK, -MODEL_CENTER_Y / UNITS_PER_BLOCK,
                -MODEL_CENTER_Z / UNITS_PER_BLOCK);
        Minecraft.getInstance().getItemRenderer().renderStatic(item, ItemDisplayContext.NONE,
                LightTexture.FULL_BRIGHT, 0, poseStack, buffers, entity.level(), 0);
        poseStack.popPose();

        super.render(entity, entityYaw, partialTicks, poseStack, buffers, packedLight);
    }

    @Override
    public ResourceLocation getTextureLocation(SensorFuzedSubmunition entity) {
        return null;
    }
}
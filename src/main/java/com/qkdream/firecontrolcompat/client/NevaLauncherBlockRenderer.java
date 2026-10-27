package com.qkdream.firecontrolcompat.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.qkdream.firecontrolcompat.FireControlCompat;
import com.qkdream.firecontrolcompat.NevaLauncherBlockEntity;
import com.qkdream.firecontrolcompat.client.model.NevaLauncherGeoModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

/**
 * Block renderer of the Neva launcher. The Blockbench missile is authored
 * lying along Z with its nose towards -Z and the tail fins towards +Z. The
 * pose below rotates it +90 degrees about X so the nose points up, then
 * lifts the missile so the tail fins rest at the foot of the block. The
 * launcher block itself has no model: the placed block is the missile model
 * alone, like the ground cruise missile of the modern-warfare mod.
 */
public class NevaLauncherBlockRenderer extends GeoBlockRenderer<NevaLauncherBlockEntity> {

    private static boolean loggedFirstRender = false;

    public NevaLauncherBlockRenderer() {
        super(new NevaLauncherGeoModel());
    }

    public NevaLauncherBlockRenderer(BlockEntityRendererProvider.Context context) {
        this();
    }

    @Override
    public void render(NevaLauncherBlockEntity blockEntity, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight, int packedOverlay) {
        if (!loggedFirstRender) {
            loggedFirstRender = true;
            FireControlCompat.LOGGER.info("[firecontrolcompat] Neva launcher block renderer invoked at {}", blockEntity.getBlockPos());
        }
        poseStack.pushPose();
        poseStack.mulPose(Axis.XP.rotationDegrees(90.0F));
        poseStack.scale(2.8F, 2.8F, 2.8F);
        poseStack.translate(-0.2F, 0.0F, -1.74F);
        super.render(blockEntity, partialTick, poseStack, buffer, packedLight, packedOverlay);
        poseStack.popPose();
    }

    @Override
    protected void rotateBlock(Direction facing, PoseStack poseStack) {
        // The orientation is applied in render(); keep the facing rotation off.
    }

    @Override
    protected Direction getFacing(NevaLauncherBlockEntity animatable) {
        return Direction.NORTH;
    }

    @Override
    public AABB getRenderBoundingBox(NevaLauncherBlockEntity blockEntity) {
        BlockPos pos = blockEntity.getBlockPos();
        return new AABB(pos).inflate(0.5).expandTowards(0.0, 4.2, 0.0);
    }

    @Override
    public boolean shouldRenderOffScreen(NevaLauncherBlockEntity blockEntity) {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 256;
    }
}

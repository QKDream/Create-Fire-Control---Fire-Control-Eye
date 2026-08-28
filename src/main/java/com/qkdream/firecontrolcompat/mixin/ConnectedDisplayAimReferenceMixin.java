package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.content.controller.MianbaoMissileCompat;
import com.hooya.stabilizedturret.content.display.ConnectedDisplayBlockEntity;
import com.qkdream.firecontrolcompat.HellfireBridge;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Makes a bound TAOV Hellfire director produce a valid aim reference inside
 * the connected display's automatic attack loop. The display hardcodes the
 * mianbao laser designator check, so without this the director falls out of
 * {@code aimReferences} and the yaw/pitch bearings it is attached to never
 * move; with it the director reports its body position and front direction
 * exactly like a mianbao designator.
 */
@Mixin(ConnectedDisplayBlockEntity.class)
public abstract class ConnectedDisplayAimReferenceMixin {

    @Redirect(
            method = "measureAimReference(Lcom/hooya/stabilizedturret/content/controller/BindingRef;)Lcom/hooya/stabilizedturret/content/display/ConnectedDisplayBlockEntity$AimReference;",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/hooya/stabilizedturret/content/controller/MianbaoMissileCompat;isDesignator(Lnet/minecraft/world/level/block/state/BlockState;)Z"
            )
    )
    private boolean firecontrolcompat$isAimDesignator(BlockState state) {
        return MianbaoMissileCompat.isDesignator(state) || HellfireBridge.isDirector(state);
    }

    @Redirect(
            method = "measureAimReference(Lcom/hooya/stabilizedturret/content/controller/BindingRef;)Lcom/hooya/stabilizedturret/content/display/ConnectedDisplayBlockEntity$AimReference;",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/hooya/stabilizedturret/content/controller/MianbaoMissileCompat;getDesignatorOrigin(Lnet/minecraft/world/level/Level;Lcom/hooya/stabilizedturret/content/controller/BindingRef;)Lnet/minecraft/world/phys/Vec3;"
            )
    )
    private Vec3 firecontrolcompat$designatorOrigin(Level level, BindingRef ref) {
        BlockEntity blockEntity = level == null ? null : level.getBlockEntity(ref.pos());
        if (HellfireBridge.isDirector(blockEntity)) {
            Vec3 position = HellfireBridge.position(blockEntity);
            if (position != null) {
                return position;
            }
        }
        return MianbaoMissileCompat.getDesignatorOrigin(level, ref);
    }

    @Redirect(
            method = "measureAimReference(Lcom/hooya/stabilizedturret/content/controller/BindingRef;)Lcom/hooya/stabilizedturret/content/display/ConnectedDisplayBlockEntity$AimReference;",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/hooya/stabilizedturret/content/controller/MianbaoMissileCompat;getDesignatorDirection(Lnet/minecraft/world/level/Level;Lcom/hooya/stabilizedturret/content/controller/BindingRef;)Lnet/minecraft/world/phys/Vec3;"
            )
    )
    private Vec3 firecontrolcompat$designatorDirection(Level level, BindingRef ref) {
        BlockEntity blockEntity = level == null ? null : level.getBlockEntity(ref.pos());
        if (HellfireBridge.isDirector(blockEntity)) {
            Vec3 direction = HellfireBridge.aimDirection(blockEntity);
            if (direction != null) {
                return direction;
            }
        }
        return MianbaoMissileCompat.getDesignatorDirection(level, ref);
    }
}
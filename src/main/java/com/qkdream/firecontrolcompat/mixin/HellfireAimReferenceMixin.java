package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.content.controller.MianbaoMissileCompat;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.qkdream.firecontrolcompat.HellfireBridge;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Treats a bound Hellfire director as a measured aim reference: its world
 * position is the origin and its body front direction is the aim direction.
 * The head-aim loop then steers the turret until the director's front points
 * at the gunner's screen center, so the director designates whatever the
 * player is looking at.
 */
@Mixin(StabilizerControllerBlockEntity.class)
public abstract class HellfireAimReferenceMixin {

    @Redirect(
            method = "measureAimReference(Lcom/hooya/stabilizedturret/content/controller/BindingRef;)Lcom/hooya/stabilizedturret/content/controller/StabilizerControllerBlockEntity$AimReference;",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/hooya/stabilizedturret/content/controller/MianbaoMissileCompat;isDesignator(Lnet/minecraft/world/level/block/state/BlockState;)Z"
            )
    )
    private boolean firecontrolcompat$isAimDesignator(BlockState state) {
        return MianbaoMissileCompat.isDesignator(state) || HellfireBridge.isDirector(state);
    }

    @Redirect(
            method = "measureAimReference(Lcom/hooya/stabilizedturret/content/controller/BindingRef;)Lcom/hooya/stabilizedturret/content/controller/StabilizerControllerBlockEntity$AimReference;",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/hooya/stabilizedturret/content/controller/MianbaoMissileCompat;getDesignatorOrigin(Lnet/minecraft/world/level/Level;Lcom/hooya/stabilizedturret/content/controller/BindingRef;)Lnet/minecraft/world/phys/Vec3;"
            )
    )
    private Vec3 firecontrolcompat$designatorOrigin(Level level, BindingRef ref) {
        BlockEntity blockEntity = level == null ? null : level.getBlockEntity(ref.pos());
        if (HellfireBridge.isDirector(blockEntity)) {
            return HellfireBridge.position(blockEntity);
        }
        return MianbaoMissileCompat.getDesignatorOrigin(level, ref);
    }

    @Redirect(
            method = "measureAimReference(Lcom/hooya/stabilizedturret/content/controller/BindingRef;)Lcom/hooya/stabilizedturret/content/controller/StabilizerControllerBlockEntity$AimReference;",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/hooya/stabilizedturret/content/controller/MianbaoMissileCompat;getDesignatorDirection(Lnet/minecraft/world/level/Level;Lcom/hooya/stabilizedturret/content/controller/BindingRef;)Lnet/minecraft/world/phys/Vec3;"
            )
    )
    private Vec3 firecontrolcompat$designatorDirection(Level level, BindingRef ref) {
        BlockEntity blockEntity = level == null ? null : level.getBlockEntity(ref.pos());
        if (HellfireBridge.isDirector(blockEntity)) {
            return HellfireBridge.aimDirection(blockEntity);
        }
        return MianbaoMissileCompat.getDesignatorDirection(level, ref);
    }
}

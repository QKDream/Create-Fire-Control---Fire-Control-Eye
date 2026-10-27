package com.qkdream.firecontrolcompat.mixin;

import com.qkdream.firecontrolcompat.compat.yuushya.YuushyaMuzzlePlacement;
import com.simibubi.create.content.contraptions.Contraption;

import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import rbasamoyai.createbigcannons.cannon_control.contraption.AbstractMountedCannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.MountedBigCannonContraption;

/**
 * Puts the muzzle effect of a shot on the mouth of a modelling barrel.
 *
 * <p>The one transform in the firing code turns the spot the plume and the
 * blast wave are placed at from the space the contraption lives in into the
 * world, so it is the one place the effect passes through before it is sent
 * out: moving it there leaves the shot itself, its recoil and its sound exactly
 * as Create Big Cannons wrote them.</p>
 */
@Mixin(MountedBigCannonContraption.class)
public abstract class MountedBigCannonMuzzleMixin {

    @Redirect(
            method = "fireShot",
            at = @At(
                    value = "INVOKE",
                    target = "Lrbasamoyai/createbigcannons/CBCCompatTransformers;transformVec3(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;"
            ),
            require = 0
    )
    private Vec3 firecontrolcompat$muzzleAtBarrelMouth(Level level, Vec3 position, Vec3 root) {
        Direction firing = ((AbstractMountedCannonContraption) (Object) this).initialOrientation();
        return YuushyaMuzzlePlacement.adjust((Contraption) (Object) this, firing, level, position, root);
    }
}
package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.content.controller.CannonMountCompat;
import com.qkdream.firecontrolcompat.TaovGunBridge;
import com.verr1.taov.weapons.content.gun.GunBlockEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Routes every CannonMountCompat query/drive call to the TAOV gun bridge when
 * the bound mount is a taov_weapons:gun, so the gun behaves like an assembled
 * CBC cannon from the perspective of the fire-control computer and the
 * connected display:
 *
 * <ul>
 * <li>isCannonMount / hasAssembledCannon - the gun is always "assembled".</li>
 * <li>getMountedCannonPosition / getAssembledCannonDirection - muzzle world
 * position and current aim direction for turret feedback loops.</li>
 * <li>setAutomaticFire - triggers the gun's plant fire input.</li>
 * </ul>
 */
@Mixin(CannonMountCompat.class)
public abstract class TaovGunCannonMountMixin {

    @Inject(method = "isCannonMount", at = @At("HEAD"), cancellable = true)
    private static void firecontrolcompat$gunIsCannonMount(BlockEntity blockEntity, CallbackInfoReturnable<Boolean> cir) {
        if (TaovGunBridge.isGun(blockEntity)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "hasAssembledCannon", at = @At("HEAD"), cancellable = true)
    private static void firecontrolcompat$gunIsAssembled(Level level, BindingRef ref, CallbackInfoReturnable<Boolean> cir) {
        if (TaovGunBridge.resolveGun(level, ref) != null) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "getMountedCannonPosition", at = @At("HEAD"), cancellable = true)
    private static void firecontrolcompat$gunMuzzlePosition(Level level, BindingRef ref, CallbackInfoReturnable<Vec3> cir) {
        GunBlockEntity gun = TaovGunBridge.resolveGun(level, ref);
        if (gun != null) {
            Vec3 muzzle = TaovGunBridge.muzzleWorld(gun);
            if (muzzle != null) {
                cir.setReturnValue(muzzle);
            }
        }
    }

    @Inject(method = "getAssembledCannonDirection", at = @At("HEAD"), cancellable = true)
    private static void firecontrolcompat$gunAimDirection(Level level, BindingRef ref, CallbackInfoReturnable<Vec3> cir) {
        GunBlockEntity gun = TaovGunBridge.resolveGun(level, ref);
        if (gun != null) {
            Vec3 direction = TaovGunBridge.aimDirection(gun);
            if (direction != null) {
                cir.setReturnValue(direction);
            }
        }
    }

    @Inject(
            method = "setAutomaticFire(Lnet/minecraft/world/level/Level;Lcom/hooya/stabilizedturret/content/controller/BindingRef;Z)Z",
            at = @At("HEAD"),
            cancellable = true
    )
    private static void firecontrolcompat$gunAutomaticFire(Level level, BindingRef ref, boolean powered, CallbackInfoReturnable<Boolean> cir) {
        GunBlockEntity gun = TaovGunBridge.resolveGun(level, ref);
        if (gun != null) {
            gun.setPlantFireActive(powered);
            cir.setReturnValue(true);
        }
    }

    @Inject(
            method = "setAutomaticFire(Lnet/minecraft/world/level/Level;Lcom/hooya/stabilizedturret/content/controller/BindingRef;ZI)Z",
            at = @At("HEAD"),
            cancellable = true
    )
    private static void firecontrolcompat$gunAutomaticFirePowered(
            Level level, BindingRef ref, boolean powered, int firePower, CallbackInfoReturnable<Boolean> cir) {
        GunBlockEntity gun = TaovGunBridge.resolveGun(level, ref);
        if (gun != null) {
            gun.setPlantFireActive(powered);
            cir.setReturnValue(true);
        }
    }

}

package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.content.controller.CannonBallisticsCompat;
import com.qkdream.firecontrolcompat.TaovGunBridge;
import com.verr1.taov.weapons.content.gun.GunBlockEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Ballistics hooks for the TAOV gun. The display's automatic fire gate reads
 * the muzzle from CannonBallisticsCompat, and its aim solver reads the shot
 * profile; the gun has no contraption geometry, so the muzzle is supplied by
 * the bridge and the profile is reported as null (straight-line aim with the
 * display's velocity lead fallback).
 */
@Mixin(CannonBallisticsCompat.class)
public abstract class TaovGunBallisticsMixin {

    @Inject(method = "getMuzzlePosition", at = @At("HEAD"), cancellable = true)
    private static void firecontrolcompat$gunBallisticMuzzle(Level level, BindingRef ref, CallbackInfoReturnable<Vec3> cir) {
        GunBlockEntity gun = TaovGunBridge.resolveGun(level, ref);
        if (gun != null) {
            Vec3 muzzle = TaovGunBridge.muzzleWorld(gun);
            if (muzzle != null) {
                cir.setReturnValue(muzzle);
            }
        }
    }

    @Inject(method = "analyze", at = @At("HEAD"), cancellable = true)
    private static void firecontrolcompat$gunNoBallisticProfile(
            Level level, BindingRef ref, CallbackInfoReturnable<CannonBallisticsCompat.ShotProfile> cir) {
        if (TaovGunBridge.resolveGun(level, ref) != null) {
            cir.setReturnValue(null);
        }
    }
}

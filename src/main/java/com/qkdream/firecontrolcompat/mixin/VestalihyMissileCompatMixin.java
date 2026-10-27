package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.content.controller.MianbaoMissileCompat;
import com.qkdream.firecontrolcompat.VestalihyBridge;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Teaches the fire control missile pipeline about vestalihy tube launchers:
 * a bound tube answers the ammunition and firing questions, the round it
 * releases is recognised as a guided missile, and the muzzle checks know
 * where the tube stands. The 9M14 is deliberately absent from the guided
 * set: it keeps its own keyboard steering.
 *
 * <p>A tube standing on a structure is stored in the plot space of that
 * structure, while the computer measures everything where the player sees it,
 * so the muzzle, the direction and the distance to a fresh round are asked for
 * in world space. The bound tube itself is still found from the block position
 * of the binding, which is the space the tube was bound in.</p>
 */
@Mixin(MianbaoMissileCompat.class)
public abstract class VestalihyMissileCompatMixin {

    @Inject(method = "isGuidedMissile", at = @At("RETURN"), cancellable = true)
    private static void firecontrolcompat$vestalihyGuided(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() && VestalihyBridge.isGuidedMissile(entity)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "isGuidedMissileType", at = @At("RETURN"), cancellable = true)
    private static void firecontrolcompat$vestalihyGuidedType(EntityType<?> type, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() && VestalihyBridge.isGuidedMissileType(type)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "getAmmoCount", at = @At("HEAD"), cancellable = true)
    private static void firecontrolcompat$vestalihyAmmo(Level level, BindingRef ref, CallbackInfoReturnable<Integer> cir) {
        VestalihyBridge.Launcher launcher = ref == null ? null : VestalihyBridge.launcherAt(level, ref.pos());
        if (launcher != null) {
            cir.setReturnValue(launcher.fccIsEmpty() ? 0 : 1);
        }
    }

    @Inject(method = "fireOne", at = @At("HEAD"), cancellable = true)
    private static void firecontrolcompat$vestalihyFire(Level level, BindingRef ref, CallbackInfoReturnable<Boolean> cir) {
        VestalihyBridge.Launcher launcher = ref == null ? null : VestalihyBridge.launcherAt(level, ref.pos());
        if (launcher != null) {
            cir.setReturnValue(!launcher.fccIsEmpty() && launcher.fccLaunch());
        }
    }

    @Inject(method = "getLauncherSpawnDistanceSqr", at = @At("HEAD"), cancellable = true)
    private static void firecontrolcompat$vestalihySpawnDistance(Level level, BindingRef ref, Entity missile,
            CallbackInfoReturnable<Double> cir) {
        VestalihyBridge.Launcher launcher = ref == null ? null : VestalihyBridge.launcherAt(level, ref.pos());
        if (launcher != null && missile != null) {
            cir.setReturnValue(VestalihyBridge.spawnDistanceSqr(launcher, missile));
        }
    }

    @Inject(method = "getLauncherMuzzlePosition", at = @At("HEAD"), cancellable = true)
    private static void firecontrolcompat$vestalihyMuzzle(Level level, BindingRef ref, CallbackInfoReturnable<Vec3> cir) {
        VestalihyBridge.Launcher launcher = ref == null ? null : VestalihyBridge.launcherAt(level, ref.pos());
        Vec3 muzzle = launcher == null ? null : VestalihyBridge.muzzle(launcher);
        if (muzzle != null) {
            cir.setReturnValue(muzzle);
        }
    }

    @Inject(method = "getLauncherDirection", at = @At("HEAD"), cancellable = true)
    private static void firecontrolcompat$vestalihyDirection(Level level, BindingRef ref, CallbackInfoReturnable<Vec3> cir) {
        VestalihyBridge.Launcher launcher = ref == null ? null : VestalihyBridge.launcherAt(level, ref.pos());
        Vec3 direction = launcher == null ? null : VestalihyBridge.direction(launcher);
        if (direction != null) {
            cir.setReturnValue(direction);
        }
    }
}

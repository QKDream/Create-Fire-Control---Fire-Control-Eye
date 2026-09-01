package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.content.controller.MianbaoAirDefenseCompat;
import com.qkdream.firecontrolcompat.HellfireBridge;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fire control 0.7.0 retired the laser-designator slot, so the TAOV Hellfire
 * director rides the air-defense launcher pipeline instead: it counts as a
 * present launcher (bindings are never pruned) and its live synaxis body
 * position / front direction are reported as the launcher muzzle and facing.
 * Both the fire-control computer and the connected display then measure the
 * director as a head-aim reference exactly like a mianbao AA launcher.
 */
@Mixin(MianbaoAirDefenseCompat.class)
public abstract class HellfireAirDefenseCompatMixin {

    @Inject(method = "isBoundLauncherPresent", at = @At("HEAD"), cancellable = true)
    private static void firecontrolcompat$directorIsPresent(
            Level level, BindingRef ref, CallbackInfoReturnable<Boolean> cir) {
        if (firecontrolcompat$director(level, ref) != null) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "getLauncherMuzzlePosition", at = @At("HEAD"), cancellable = true)
    private static void firecontrolcompat$directorMuzzle(
            Level level, BindingRef ref, CallbackInfoReturnable<Vec3> cir) {
        BlockEntity director = firecontrolcompat$director(level, ref);
        if (director != null) {
            Vec3 position = HellfireBridge.position(director);
            if (position != null) {
                cir.setReturnValue(position);
            }
        }
    }

    @Inject(method = "getLauncherDirection", at = @At("HEAD"), cancellable = true)
    private static void firecontrolcompat$directorDirection(
            Level level, BindingRef ref, CallbackInfoReturnable<Vec3> cir) {
        BlockEntity director = firecontrolcompat$director(level, ref);
        if (director != null) {
            Vec3 direction = HellfireBridge.aimDirection(director);
            if (direction != null) {
                cir.setReturnValue(direction);
            }
        }
    }

    @Unique
    private static BlockEntity firecontrolcompat$director(Level level, BindingRef ref) {
        if (level == null || ref == null) {
            return null;
        }
        BlockEntity blockEntity = ref.resolve(level);
        return HellfireBridge.isDirector(blockEntity) ? blockEntity : null;
    }
}

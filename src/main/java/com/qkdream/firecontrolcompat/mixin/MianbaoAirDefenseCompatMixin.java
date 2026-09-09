package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.content.controller.MianbaoAirDefenseCompat;
import com.qkdream.firecontrolcompat.BeamMissileRegistry;
import com.qkdream.firecontrolcompat.HeavyAirDefenseCompat;
import dev.ryanhcode.sable.Sable;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Teaches fire control's air-defense pipeline about the vertical silo and
 * the heavy air-defense missile: the silo counts as an AA launcher, the
 * missile is adopted by the AA guidance (controller or connected display),
 * can be seduced by countermeasure flares, and detonates with the ground
 * cruise missile warhead doubled.
 */
@Mixin(MianbaoAirDefenseCompat.class)
public abstract class MianbaoAirDefenseCompatMixin {

    @Inject(method = "isLauncher", at = @At("RETURN"), cancellable = true)
    private static void firecontrolcompat$siloLauncher(BlockState state, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() && HeavyAirDefenseCompat.isSiloHead(state)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "isMissile", at = @At("RETURN"), cancellable = true)
    private static void firecontrolcompat$heavyMissile(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() && entity != null
                && entity.getType() == BeamMissileRegistry.HEAVY_AA_TANSHE.get()) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "isMissileType", at = @At("RETURN"), cancellable = true)
    private static void firecontrolcompat$heavyMissileType(EntityType<?> type, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() && type == BeamMissileRegistry.HEAVY_AA_TANSHE.get()) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "isHighSpeedMissile", at = @At("RETURN"), cancellable = true)
    private static void firecontrolcompat$heavyIsHighSpeed(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() && entity != null
                && entity.getType() == BeamMissileRegistry.HEAVY_AA_TANSHE.get()) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "nativeExplosionStrength", at = @At("RETURN"), cancellable = true)
    private static void firecontrolcompat$heavyWarhead(Entity missile, CallbackInfoReturnable<Float> cir) {
        if (missile != null && missile.getType() == BeamMissileRegistry.HEAVY_AA_TANSHE.get()) {
            cir.setReturnValue(12.0F);
        }
    }

    @Inject(method = "getAmmoCount", at = @At("HEAD"), cancellable = true)
    private static void firecontrolcompat$siloAmmo(Level level, BindingRef ref, CallbackInfoReturnable<Integer> cir) {
        BlockEntity blockEntity = firecontrolcompat$silo(level, ref);
        if (blockEntity != null) {
            cir.setReturnValue(Math.max(0,
                    (int) Math.floor(blockEntity.getPersistentData().getDouble(HeavyAirDefenseCompat.HEAVY_AA_AMMO_KEY))));
        }
    }

    @Inject(method = "fireOne", at = @At("HEAD"), cancellable = true)
    private static void firecontrolcompat$siloFire(Level level, BindingRef ref, CallbackInfoReturnable<Boolean> cir) {
        BlockEntity blockEntity = firecontrolcompat$silo(level, ref);
        if (blockEntity != null && blockEntity.getPersistentData().getDouble(HeavyAirDefenseCompat.HEAVY_AA_AMMO_KEY) > 0.0) {
            cir.setReturnValue(HeavyAirDefenseCompat.tryLaunchSilo(blockEntity.getLevel(),
                    blockEntity.getBlockPos().getX(), blockEntity.getBlockPos().getY(), blockEntity.getBlockPos().getZ()));
        }
    }

    @Inject(method = "getLauncherMuzzlePosition", at = @At("HEAD"), cancellable = true)
    private static void firecontrolcompat$siloMuzzle(Level level, BindingRef ref, CallbackInfoReturnable<Vec3> cir) {
        BlockEntity blockEntity = firecontrolcompat$silo(level, ref);
        if (blockEntity != null) {
            Vec3 local = Vec3.atCenterOf(blockEntity.getBlockPos()).add(0.0, 2.0, 0.0);
            cir.setReturnValue(Sable.HELPER.projectOutOfSubLevel(blockEntity.getLevel(), local));
        }
    }

    @Inject(method = "getLauncherDirection", at = @At("HEAD"), cancellable = true)
    private static void firecontrolcompat$siloDirection(Level level, BindingRef ref, CallbackInfoReturnable<Vec3> cir) {
        BlockEntity blockEntity = firecontrolcompat$silo(level, ref);
        if (blockEntity != null) {
            Vec3 localBase = Vec3.atCenterOf(blockEntity.getBlockPos());
            Vec3 localFront = localBase.add(0.0, 1.0, 0.0);
            Vec3 worldBase = Sable.HELPER.projectOutOfSubLevel(blockEntity.getLevel(), localBase);
            Vec3 worldFront = Sable.HELPER.projectOutOfSubLevel(blockEntity.getLevel(), localFront);
            Vec3 direction = worldFront.subtract(worldBase);
            if (direction.lengthSqr() >= 1.0E-8) {
                cir.setReturnValue(direction.normalize());
            }
        }
    }

    private static BlockEntity firecontrolcompat$silo(Level level, BindingRef ref) {
        if (level == null || ref == null) {
            return null;
        }
        BlockEntity blockEntity = ref.resolve(level);
        if (blockEntity == null) {
            blockEntity = ref.resolveRebuiltAirDefenseLauncher(level);
        }
        return blockEntity != null && blockEntity.getLevel() != null
                && HeavyAirDefenseCompat.isSiloHead(blockEntity.getBlockState()) ? blockEntity : null;
    }
}
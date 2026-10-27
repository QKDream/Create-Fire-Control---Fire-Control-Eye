package com.qkdream.firecontrolcompat.mixin;

import com.qkdream.firecontrolcompat.CbcAutocannonPayloads;
import com.qkdream.firecontrolcompat.SonicPulse;
import com.verr1.shaolib.api.projectile.ProjectileHandle;
import com.verr1.shaolib.api.projectile.ProjectileSpawnInitializer;
import com.verr1.shaolib.api.projectile.ProjectileType;
import com.verr1.shaolib.api.projectile.ShaolibProjectiles;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets the water hear the weapons that fire Shaolib rounds.
 *
 * <p>TAOV's cannon mounts and CBCMS's own barrels hand their shot to Shaolib
 * instead of dropping a CBC entity into the level, so the CBC projectile hook
 * never sees them: a ship armed with those guns went silent as far as the
 * hydrophones were concerned, even while she was firing. Every one of those
 * rounds is born here, so one pulse on entry covers the whole family - the
 * mount, the type and the ship the muzzle belongs to are all read by the same
 * anchor the CBC shots use.</p>
 *
 * <p>The payload mapper spawns a one-tick marker of its own to hand TAOV a
 * handle; a marker is bookkeeping, not a shot, so it is skipped by id.</p>
 *
 * <p>Both overloads are named {@code spawn}, so each selector spells its own
 * descriptor out in full: a bare {@code spawn} matches both of them and Mixin
 * then pairs a handler with the wrong one, which fails the injection and takes
 * the whole mod state down with it. With the descriptors written out a future
 * change to either overload just means this hook stops matching - and
 * {@code require = 0} turns that into a silent no-op instead of a crash.</p>
 */
@Mixin(ShaolibProjectiles.class)
public abstract class ShaolibProjectileSpawnSonicMixin {

    @Inject(method = "spawn(Lnet/minecraft/server/level/ServerLevel;Lcom/verr1/shaolib/api/projectile/ProjectileType;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;)Lcom/verr1/shaolib/api/projectile/ProjectileHandle;", at = @At("HEAD"), require = 0)
    private static void firecontrolcompat$shot(ServerLevel level, ProjectileType<?> type, Vec3 position, Vec3 velocity,
                                               CallbackInfoReturnable<ProjectileHandle<?>> cir) {
        firecontrolcompat$gunfire(level, type, position);
    }

    @Inject(method = "spawn(Lnet/minecraft/server/level/ServerLevel;Lcom/verr1/shaolib/api/projectile/ProjectileType;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lcom/verr1/shaolib/api/projectile/ProjectileSpawnInitializer;)Lcom/verr1/shaolib/api/projectile/ProjectileHandle;", at = @At("HEAD"), require = 0)
    private static void firecontrolcompat$shotWithInitializer(ServerLevel level, ProjectileType<?> type, Vec3 position,
                                                              Vec3 velocity, ProjectileSpawnInitializer<?> initializer,
                                                              CallbackInfoReturnable<ProjectileHandle<?>> cir) {
        firecontrolcompat$gunfire(level, type, position);
    }

    private static void firecontrolcompat$gunfire(Level level, ProjectileType<?> type, Vec3 position) {
        try {
            if (level == null || level.isClientSide() || position == null) {
                return;
            }
            if (type != null && CbcAutocannonPayloads.markerId().equals(type.id())) {
                return;
            }
            SonicPulse.gunfire(level, position);
        } catch (Throwable ignored) {
        }
    }
}

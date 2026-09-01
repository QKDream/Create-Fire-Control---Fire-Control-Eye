package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.client.MissileLongRangeEffects;
import com.hooya.stabilizedturret.content.controller.MianbaoAirDefenseCompat;
import com.hooya.stabilizedturret.content.controller.MianbaoCountermeasureCompat;
import com.hooya.stabilizedturret.content.controller.MianbaoMissileCompat;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces fire control's long-range trail sampling for mianbao missiles.
 * Fire control 0.7.0 places the flame/smoke particles along
 * {@code position - velocity * fraction}, so the trail detaches into a
 * straight line whenever the missile turns. The particles are instead
 * interpolated along the real path segment the missile covered this tick.
 */
@Mixin(MissileLongRangeEffects.class)
public abstract class FireControlTrailPathMixin {

    @Unique
    private static final Map<Integer, Long> firecontrolcompat$TRAIL_TICK = new HashMap<>();
    @Unique
    private static final Map<Integer, Vec3> firecontrolcompat$TRAIL_POS = new HashMap<>();
    @Unique
    private static long firecontrolcompat$BUDGET_TICK = -1L;
    @Unique
    private static int firecontrolcompat$BUDGET = 128;

    @Invoker("particle")
    private static Optional<ParticleOptions> invokeParticle(String path) {
        throw new AbstractMethodError();
    }

    @Inject(method = "onMissileRendered", at = @At("HEAD"), cancellable = true)
    private static void firecontrolcompat$pathTrail(Entity missile, CallbackInfo ci) {
        if (MianbaoCountermeasureCompat.isCountermeasure(missile)
                || !MianbaoAirDefenseCompat.isAnyFireControlMissile(missile)) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        Camera camera = minecraft.gameRenderer.getMainCamera();
        if (level == null || camera == null) {
            ci.cancel();
            return;
        }
        Vec3 position = MianbaoMissileCompat.getWorldPosition(missile);
        boolean remote = missile.getPersistentData().getBoolean("CreateFireControlRemoteVisual");
        if (!remote && camera.getPosition().distanceToSqr(position) <= 1024.0) {
            ci.cancel();
            return;
        }
        long tick = level.getGameTime();
        Long previous = firecontrolcompat$TRAIL_TICK.put(missile.getId(), tick);
        if (previous != null && previous == tick) {
            ci.cancel();
            return;
        }
        if (firecontrolcompat$BUDGET_TICK != tick) {
            firecontrolcompat$BUDGET_TICK = tick;
            firecontrolcompat$BUDGET = 128;
        }
        if (firecontrolcompat$BUDGET > 0) {
            ParticleOptions flame = invokeParticle("rocketmissiletrail_3").orElse(null);
            ParticleOptions smoke = invokeParticle("newsmoke_2").orElse(null);
            if (flame != null || smoke != null) {
                Vec3 velocity = MianbaoMissileCompat.getWorldVelocity(missile);
                int samples = Mth.clamp((int) Math.ceil(velocity.length()), 1, 5);
                Vec3 lastPosition = firecontrolcompat$TRAIL_POS.get(missile.getId());
                boolean continuous = lastPosition != null && lastPosition.distanceToSqr(position) <= 400.0;
                RandomSource random = level.random;
                for (int i = 0; i < samples && firecontrolcompat$BUDGET > 0; i++) {
                    double fraction = (i + 0.25) / samples;
                    Vec3 sample = continuous ? lastPosition.lerp(position, fraction) : position;
                    if (flame != null && firecontrolcompat$BUDGET-- > 0) {
                        level.addAlwaysVisibleParticle(flame, true, sample.x, sample.y, sample.z, 0.0, 0.0, 0.0);
                    }
                    if (smoke != null && firecontrolcompat$BUDGET > 0) {
                        firecontrolcompat$BUDGET--;
                        level.addAlwaysVisibleParticle(
                                smoke,
                                true,
                                sample.x,
                                sample.y,
                                sample.z,
                                (random.nextDouble() - 0.5) * 0.35,
                                (random.nextDouble() - 0.5) * 0.35,
                                (random.nextDouble() - 0.5) * 0.35);
                    }
                }
                firecontrolcompat$TRAIL_POS.put(missile.getId(), position);
            }
        }
        if (firecontrolcompat$TRAIL_TICK.size() > 512) {
            firecontrolcompat$TRAIL_TICK.entrySet().removeIf(entry -> entry.getValue() < tick - 40L);
            firecontrolcompat$TRAIL_POS.keySet().retainAll(firecontrolcompat$TRAIL_TICK.keySet());
        }
        ci.cancel();
    }
}

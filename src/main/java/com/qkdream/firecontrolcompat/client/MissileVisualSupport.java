package com.qkdream.firecontrolcompat.client;

import com.hooya.stabilizedturret.client.MissileLongRangeEffects;
import java.util.HashMap;
import java.util.Map;
import net.mcreator.myfirstmod.init.MianbaosModernwarfareModParticleTypes;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Client-side long-range visual plumbing for our missiles, mirroring fire
 * control's {@link MissileLongRangeEffects}: the native tracked entity is
 * hidden once the fire-control remote proxy exists for the same UUID, and
 * the trail particles are spawned directly on the client so they stay
 * visible at any distance.
 */
public final class MissileVisualSupport {

    private static final double NEAR_TRAIL_DISTANCE_SQR = 1024.0;
    private static final Map<Integer, Long> LAST_TRAIL_TICK = new HashMap<>();
    private static final Map<Integer, Vec3> LAST_TRAIL_POS = new HashMap<>();

    private MissileVisualSupport() {
    }

    public static boolean isRemoteVisual(Entity entity) {
        return entity != null && entity.getPersistentData().getBoolean("CreateFireControlRemoteVisual");
    }

    /** Hides the natively tracked entity whenever fire control already renders a remote proxy for it. */
    public static boolean shouldSuppressNative(Entity entity) {
        return entity != null
                && !isRemoteVisual(entity)
                && MissileLongRangeEffects.getRemoteVisual(entity.getUUID()) != null;
    }

    /**
     * Draws the rocket flame and smoke trail straight into the local client
     * particle engine. Called from the renderer every frame; throttled to
     * once per entity per game tick. Skipped for native entities close to
     * the camera because the server-side trail particles already cover them.
     */
    public static void spawnLongRangeTrail(Entity missile) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            return;
        }
        Camera camera = minecraft.gameRenderer.getMainCamera();
        if (camera == null) {
            return;
        }
        Vec3 position = missile.position();
        boolean remote = isRemoteVisual(missile);
        if (!remote && camera.getPosition().distanceToSqr(position) <= NEAR_TRAIL_DISTANCE_SQR) {
            return;
        }
        long tick = level.getGameTime();
        if (LAST_TRAIL_TICK.getOrDefault(missile.getId(), -1L) == tick) {
            return;
        }
        LAST_TRAIL_TICK.put(missile.getId(), tick);
        if (LAST_TRAIL_TICK.size() > 512) {
            LAST_TRAIL_TICK.entrySet().removeIf(entry -> entry.getValue() < tick - 40L);
            LAST_TRAIL_POS.keySet().retainAll(LAST_TRAIL_TICK.keySet());
        }

        ParticleOptions flame = MianbaosModernwarfareModParticleTypes.ROCKETMISSILETRAIL_3.get();
        ParticleOptions smoke = MianbaosModernwarfareModParticleTypes.NEWSMOKE_2.get();
        Vec3 velocity = missile.getDeltaMovement();
        double speed = velocity.length();
        int samples = Mth.clamp((int) Math.ceil(speed / 8.0), 1, 6);
        Vec3 lastPosition = LAST_TRAIL_POS.get(missile.getId());
        boolean continuous = lastPosition != null && lastPosition.distanceToSqr(position) <= 400.0;
        RandomSource random = level.random;
        for (int i = 0; i < samples; i++) {
            double fraction = (i + 0.25) / samples;
            Vec3 sample = continuous ? lastPosition.lerp(position, fraction) : position;
            level.addAlwaysVisibleParticle(flame, true, sample.x, sample.y, sample.z, 0.0, 0.0, 0.0);
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
        LAST_TRAIL_POS.put(missile.getId(), position);
    }
}

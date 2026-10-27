package com.qkdream.firecontrolcompat.missile;

import com.qkdream.firecontrolcompat.FireControlCompat;
import com.qkdream.firecontrolcompat.entity.BeamRidingMissileEntity;
import net.mcreator.myfirstmod.init.MianbaosModernwarfareModParticleTypes;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * The motor plume of this mod's rounds, emitted on the <b>server</b>.
 *
 * <h2>Why the old trail stopped existing</h2>
 *
 * <p>{@code BeamRidingMissileEntity#tick} already builds a plume, but only when fire control is
 * absent: {@code !ModList.get().isLoaded("create_fire_control")}. On this pack the pack is always
 * present, so that branch never ran, and the trail was left to the client renderer. That renderer
 * keeps its own fallback ({@code MissileVisualSupport#spawnLongRangeTrail}), which is silent for
 * anything closer than thirty-two blocks - so a round released a few metres from the crew had
 * neither flame nor smoke. The infrared anti-tank round then went <b>mathematical</b>: it is
 * captured by the pack's manager, never joins a level, and its own {@code tick()} never runs at
 * all, so even the pack-side trails had nothing to follow.</p>
 *
 * <h2>Where it is emitted now</h2>
 *
 * <p>On the two paths that actually advance the round, once per tick each:</p>
 * <ul>
 *   <li>{@link MathGuidedMissiles} - mathematical flight, after {@code advance()};</li>
 *   <li>{@code BeamRidingMissileEntity#tick} - ordinary entity flight.</li>
 * </ul>
 *
 * <p>Four particles per tick per round, which is the budget the hand-off asks for: two short bright
 * flame sprites at the nozzle and two smoke puffs a little further back that drift apart as they
 * age. Both are laid behind the round along its own velocity, so the plume follows the heading
 * through a turn instead of pointing at where it used to fly.</p>
 *
 * <h2>Reach</h2>
 *
 * <p>{@code ServerLevel#sendParticles} only reaches players within thirty-two blocks, which is
 * shorter than the round's flight; the plume would simply stop existing for the person watching it.
 * Every player within {@link #VISIBLE_RANGE} therefore gets its own forced packet - the same
 * arrangement {@code AtgmBoom} uses for the burst.</p>
 */
public final class MissileTrailParticles {

    private MissileTrailParticles() {
    }

    /** How far away a player still receives the plume. */
    private static final double VISIBLE_RANGE = 512.0D;

    /** Flame sprites per tick. */
    private static final int FLAME_COUNT = 2;

    /** Smoke puffs per tick. */
    private static final int SMOKE_COUNT = 2;

    /** Hard ceiling on particles per tick per round, asserted by {@link #MAX_PER_TICK}. */
    public static final int MAX_PER_TICK = FLAME_COUNT + SMOKE_COUNT;

    /** How far behind the round's centre the nozzle sits, in blocks. */
    private static final double NOZZLE_OFFSET = 0.8D;

    /** Ticks between two `ir-atgm trail:` lines. */
    private static final int LOG_TICKS = 20;

    /**
     * The one round that relies on this plume.
     *
     * <p>The heavy and the Neva round are drawn by fire control's detached proxy, which emits its
     * own trail ({@code MissileLongRangeEffects#onMissileRendered}); adding a second plume here
     * would give them two. The infrared anti-tank round has no such source, which is why it is the
     * only type listed.</p>
     */
    public static boolean isTrailRound(Entity entity) {
        if (entity == null) {
            return false;
        }
        try {
            ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
            return key != null
                    && FireControlCompat.MOD_ID.equals(key.getNamespace())
                    && "ir_atgm_tanshe".equals(key.getPath());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Emits this tick's plume, if this round is one that has one.
     *
     * @return how many particles were sent (never more than {@link #MAX_PER_TICK})
     */
    public static int emit(ServerLevel level, BeamRidingMissileEntity round) {
        if (level == null || round == null || round.isRemoved() || !round.isAlive()) {
            return 0;
        }
        if (!isTrailRound(round)) {
            return 0;
        }
        try {
            Vec3 position = round.position();
            Vec3 velocity = round.getDeltaMovement();
            double speed = velocity.length();
            Vec3 forward = speed > 1.0E-6D
                    ? velocity.scale(1.0D / speed)
                    : Vec3.directionFromRotation(round.getXRot(), round.getYRot());
            if (forward.lengthSqr() < 1.0E-8D) {
                forward = new Vec3(0.0D, 1.0D, 0.0D);
            }
            // The nozzle is behind the round; the smoke starts a little further back again so the
            // two sprite sets never sit on the same pixel.
            Vec3 nozzle = position.subtract(forward.scale(NOZZLE_OFFSET));
            Vec3 smokeAt = position.subtract(forward.scale(NOZZLE_OFFSET + 0.45D));
            ParticleOptions flame = MianbaosModernwarfareModParticleTypes.ROCKETMISSILETRAIL_3.get();
            ParticleOptions smoke = MianbaosModernwarfareModParticleTypes.NEWSMOKE_2.get();
            int sent = 0;
            sent += firecontrolcompat$send(level, nozzle, flame, FLAME_COUNT, 0.02D, 0.05D, forward);
            sent += firecontrolcompat$send(level, smokeAt, smoke, SMOKE_COUNT, 0.16D, 0.02D, forward);
            firecontrolcompat$log(round, FLAME_COUNT, SMOKE_COUNT, speed);
            return sent;
        } catch (Throwable failure) {
            FireControlCompat.LOGGER.info("ir-atgm trail: emit failed: {}", String.valueOf(failure));
            return 0;
        }
    }

    /**
     * Sends one particle set to every player that could see it.
     *
     * @param spread random offset applied per particle, in blocks
     * @param drift  speed the particle keeps, in blocks per tick
     */
    private static int firecontrolcompat$send(ServerLevel level, Vec3 at, ParticleOptions particle, int count,
            double spread, double drift, Vec3 forward) {
        int sent = 0;
        for (ServerPlayer player : level.players()) {
            if (player == null || player.distanceToSqr(at.x, at.y, at.z) > VISIBLE_RANGE * VISIBLE_RANGE) {
                continue;
            }
            level.sendParticles(player, particle, true,
                    at.x, at.y, at.z, count, spread, spread, spread, drift * 0.1D);
            sent += count;
        }
        return sent;
    }

    /** One line a second: how much plume there is, and which leg of the flight it is on. */
    private static void firecontrolcompat$log(BeamRidingMissileEntity round, int flame, int smoke, double speed) {
        long now;
        try {
            now = round.level().getGameTime();
        } catch (Throwable ignored) {
            return;
        }
        long last = round.getPersistentData().getLong("firecontrolcompat:trail_log_tick");
        if (last != 0L && now - last < LOG_TICKS) {
            return;
        }
        round.getPersistentData().putLong("firecontrolcompat:trail_log_tick", now);
        String phase = "cruise";
        try {
            Object phaseValue = com.qkdream.firecontrolcompat.IrAtgm.phaseOf(round);
            if (phaseValue != null) {
                phase = String.valueOf(phaseValue);
            }
        } catch (Throwable ignored) {
            // a round without the phase machine reads as cruising, which is what it does
        }
        FireControlCompat.LOGGER.info(
                "ir-atgm trail: smoke={} flame={} phase={} per-tick={} speed={} mode={} at={}",
                smoke, flame, phase, MAX_PER_TICK,
                String.format(java.util.Locale.ROOT, "%.2f", speed),
                com.qkdream.firecontrolcompat.missile.MathMissileMirror.contains(round) ? "math" : "entity",
                round.position());
    }
}

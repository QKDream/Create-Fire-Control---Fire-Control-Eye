package com.qkdream.firecontrolcompat;

import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.Level.ExplosionInteraction;
import net.minecraft.world.phys.Vec3;

/**
 * The infrared anti-tank round's detonation, made to look and sound like 面包学's own anti-tank missile.
 *
 * <h2>What the reference does (面包学 2.6.5, decompiled)</h2>
 *
 * <p>{@code net.mcreator.myfirstmod.procedures.Antitankmissile1explodeProcedure.execute(LevelAccessor,
 * double, double, double, Entity)} - called from {@code Antitankmissile1tansheEntity#onHitEntity}/
 * {@code onHitBlock} (2.6.5 classes, {@code -classpath <pack>.jar} javap: one static
 * {@code execute} taking the level, the three impact coordinates and the round). In order:</p>
 *
 * <ol>
 *   <li>{@code mianbaos_modernwarfare:missile_explode}, volume 8, pitch 0.8-1.0;</li>
 *   <li>{@code mianbaos_modernwarfare:explode_far_sound3}, volume 32, pitch 0.7-1.0;</li>
 *   <li>{@code level.explode(null, x, y, z, 2.0F, ExplosionInteraction.BLOCK)} - radius <b>2.0</b>,
 *       not the 12 this mod's missiles used;</li>
 *   <li>a 3x3x3 sphere of blocks around the impact destroyed (era / fence armour tags and the pack's
 *       {@code ExplosionBlockGuard} veto excepted);</li>
 *   <li>a <b>hemisphere of {@code mianbaos_modernwarfare:explosionsmall}</b> particles, radius 3 and
 *       height 6, one particle per point with a slight upward drift
 *       ({@code ExplosionParticles.send(server, x, y, z, EXPLOSIONSMALL, 0.0, 0.5, 0.0, 0.1, 1, true, 0)});</li>
 *   <li>{@code Explosionshrapnel2Procedure.execute(world, x, y, z)} - <b>two</b> {@code shrapnel_2}
 *       projectiles (base damage 5, knockback 1, silent) thrown from the impact with a plus/minus pi
 *       spread at 1.5-2.5 blocks/tick, plus 20 {@code shrapneltrailparticle_2} particles;</li>
 *   <li>35 points of {@code DamageTypes.EXPLOSION} damage to every entity within 8.5 blocks, and a
 *       60 tick camera shake for players;</li>
 *   <li>the round is discarded.</li>
 * </ol>
 *
 * <p>The round is therefore <b>called through the pack's own procedure</b> rather than copied
 * parameter by parameter: the procedure is the reference, it is public and static, and a future pack
 * version then brings its own changed numbers along instead of leaving this mod behind. The one thing
 * that is <em>not</em> replaced is this mod's damage: {@link IrAtgm#heatWarhead} still throws the
 * shaped-charge jet, and it is spawned right after this call so it cannot be born inside the blast.</p>
 *
 * <p>If the procedure cannot be reached (a pack without it, or a class that fails to link) the same
 * parameter set is reproduced here - the two sounds, a {@link #RADIUS} explosion, and the
 * {@code explosionsmall} hemisphere - so the round still goes off looking like the reference instead
 * of falling back to the 12 block blast this mod's other missiles use.</p>
 *
 * <p><b>Power (2026-10-26).</b> The user asked for the blast to be slightly stronger. The pack's
 * procedure hardwires its own 2.0 radius and takes no options ({@link #PACK_RADIUS}), so the request
 * is met in the two places this mod controls: {@link #RADIUS} (2.0 -> 2.6) for {@link #blastAt} and
 * {@link #fallback}, and a small {@link #PACK_TOP_UP} blast on the pack path so a normal hit is
 * stronger too. The fragments are left at the pack's own two.</p>
 */
public final class AtgmBoom {

    private static final String PREFIX = "[firecontrolcompat] ";

    /**
     * Radius the reference's own procedure explodes at, hardwired inside 面包学.
     *
     * <p>{@code Antitankmissile1explodeProcedure} contains its own
     * {@code level.explode(null, x, y, z, 2.0F, BLOCK)} and takes no parameter for it, so this
     * figure <b>cannot be tuned</b> on the pack path. It is named here so the log can say what
     * really went off instead of quoting a number this mod does not control.</p>
     */
    public static final float PACK_RADIUS = 2.0F;

    /**
     * Radius this mod explodes at when it places the blast itself, in blocks.
     *
     * <p>Raised from 2.0 to <b>2.6</b> at the user's request ("slightly more powerful"). This is the
     * radius of the two blasts this mod owns - {@link #blastAt} (an interception, where fire
     * control's mathematical rounds have no entity to detonate) and {@link #fallback} (a pack
     * without the procedure) - so both sides of the chain use the same number.</p>
     */
    public static final float RADIUS = 2.6F;

    /**
     * Extra blast placed <b>on top of</b> the pack path, in blocks.
     *
     * <h2>Why the pack path needs one</h2>
     *
     * <p>The pack's procedure is atomic: sound, a hardwired 2.0 explosion, the {@code explosionsmall}
     * hemisphere, two fragments, 35 points of damage in 8.5 blocks and {@code discard()} all happen
     * inside one static call that takes no options. So raising {@link #RADIUS} alone would leave
     * every real hit (which goes through the procedure, {@code style=pack} in the log) exactly as
     * powerful as before - the number would only have changed on the two paths this mod owns.</p>
     *
     * <p>A second, <b>small</b> explosion is therefore placed at the same point after the procedure
     * has run: 1.2 blocks, {@code ExplosionInteraction.NONE}. It cannot remove blocks the pack's own
     * blast did not, and together with the 2.0 it widens the entity damage reach from four blocks to
     * a little over five, which is what "slightly stronger" looks like. Set this to {@code 0.0F} to
     * go back to the pack's own power exactly.</p>
     */
    private static final float PACK_TOP_UP = 1.2F;

    /** The particle the reference paints the blast with. */
    public static final String PARTICLE = "mianbaos_modernwarfare:explosionsmall";

    /** The two sounds the reference plays, in order. */
    public static final String SOUND_NEAR = "mianbaos_modernwarfare:missile_explode";

    public static final String SOUND_FAR = "mianbaos_modernwarfare:explode_far_sound3";

    /** Reference geometry of the blast particle cloud: horizontal radius and height, in blocks. */
    private static final int PARTICLE_RADIUS = 3;

    private static final int PARTICLE_HEIGHT = 6;

    /** Hits of the reference's shrapnel procedure, reported only. */
    private static final int SHRAPNEL = 2;

    /** One line per detonation, so two hits can be compared in the log. */
    private static String lastStyle = "";

    private AtgmBoom() {
    }

    /**
     * Goes off the way 面包学's anti-tank missile does.
     *
     * @param jet whether this mod's shaped-charge jet is spawned on top of the blast (reported, not
     *            decided, here)
     * @return {@code true} when the pack's own procedure ran (the faithful path), {@code false} when
     *         the fallback was used and the round is still standing.
     */
    public static boolean detonate(Entity round, Vec3 at, String cause, boolean jet) {
        if (round == null || at == null) {
            return false;
        }
        Level level = round.level();
        if (level == null || level.isClientSide()) {
            return false;
        }
        boolean pack = false;
        try {
            net.mcreator.myfirstmod.procedures.Antitankmissile1explodeProcedure.execute(
                    level, at.x, at.y, at.z, round);
            pack = true;
        } catch (Throwable failure) {
            FireControlCompat.LOGGER.info(
                    "{}ir-atgm boom: the pack's explosion refused at {}: {}", PREFIX, at,
                    String.valueOf(failure));
        }
        if (!pack) {
            fallback(round, at);
        } else {
            topUp(level, at);
        }
        report(pack ? "pack" : "fallback", cause, at, jet);
        return pack;
    }

    /**
     * The extra blast the pack path gets, because the procedure's own radius is hardwired.
     *
     * <p>{@code ExplosionInteraction.NONE}: the pack's procedure has already decided which blocks
     * this hit removes (a 3x3x3 sphere with its own {@code ExplosionBlockGuard} veto), and widening
     * that is not what was asked for. Only entities feel this one.</p>
     */
    private static void topUp(Level level, Vec3 at) {
        if (PACK_TOP_UP <= 0.0F) {
            return;
        }
        try {
            level.explode(null, at.x, at.y, at.z, PACK_TOP_UP, false, ExplosionInteraction.NONE);
        } catch (Throwable failure) {
            FireControlCompat.LOGGER.info("{}ir-atgm boom: top-up refused at {}: {}", PREFIX, at,
                    String.valueOf(failure));
        }
    }

    /**
     * The reference burst, played at a point in a level of the caller's choosing, with no round to
     * detonate.
     *
     * <h2>Why this exists</h2>
     *
     * <p>{@link #detonate} needs the round's own entity: the pack's procedure takes it, uses it for
     * the impact and removes it at the end. A round that was <b>intercepted</b> does not always have
     * one to hand - fire control's mathematically flown rounds exist only as records in
     * {@code MathematicalMissileManager}, and at the moment of the kill the entity is either not in
     * a level at all or is already being removed. Calling the procedure there is how "the round was
     * hit and nothing happened" was reported: the kill was recorded, the round vanished, and no
     * explosion was ever played anywhere.</p>
     *
     * <p>So the same four things the procedure does at the impact are done here, at the
     * <b>interception point, in the level the interception happened in</b> - the interceptor's own
     * level, which is a level players are really standing in:</p>
     *
     * <ol>
     *   <li>the two sounds ({@code missile_explode} volume 8, {@code explode_far_sound3} volume 32);</li>
     *   <li>{@code explode(null, x, y, z, RADIUS, NONE)} - {@link #RADIUS} (2.6 since the power
     *       request; the pack's own 2.0 is not tunable), with no block damage
     *       (an interception happens in the air, and a crater per kill is not what the reference is
     *       for);</li>
     *   <li>the {@code explosionsmall} hemisphere, radius 3 and height 6, sent to every player in
     *       range one by one - the eight-argument {@code sendParticles} drops it for anyone further
     *       than 32 blocks, and an interception is usually further away than that;</li>
     *   <li>{@code Explosionshrapnel2Procedure}: the reference's <b>two</b> {@code shrapnel_2}
     *       fragments plus their trail particles.</li>
     * </ol>
     *
     * @return whether the blast was actually placed
     */
    public static boolean blastAt(ServerLevel level, Vec3 at, String cause) {
        if (level == null || at == null) {
            return false;
        }
        boolean placed = false;
        BlockPos pos = BlockPos.containing(at);
        try {
            play(level, pos, SOUND_NEAR, 8.0F, 0.8F, 1.0F);
            play(level, pos, SOUND_FAR, 32.0F, 0.7F, 1.0F);
        } catch (Throwable ignored) {
            // a missing sound must not cost the blast
        }
        try {
            level.explode(null, at.x, at.y, at.z, RADIUS, false, ExplosionInteraction.NONE);
            placed = true;
        } catch (Throwable failure) {
            FireControlCompat.LOGGER.info("{}ir-atgm boom: explode refused at {}: {}", PREFIX, at,
                    String.valueOf(failure));
        }
        try {
            cloud(level, at);
        } catch (Throwable ignored) {
            // particles are the look, not the kill
        }
        try {
            net.mcreator.myfirstmod.procedures.Explosionshrapnel2Procedure.execute(level, at.x, at.y, at.z);
        } catch (Throwable ignored) {
            // the shrapnel procedure is the pack's; a pack without it still gets the blast
        }
        FireControlCompat.LOGGER.info(
                "{}ir-atgm boom: style=reference at={} level={} radius={} particle={} sound={}+{}"
                        + " shrapnel={} ok={} cause={}",
                PREFIX, at, level.dimension().location(),
                String.format(Locale.ROOT, "%.1f", RADIUS), PARTICLE, SOUND_NEAR, SOUND_FAR,
                SHRAPNEL, placed, cause);
        lastStyle = "reference";
        return placed;
    }

    /**
     * The reference's sound, blast and particle cloud, without the pack.
     *
     * <p>Only reached when the procedure class is missing, which is the one case where the round
     * must still look right. The round is removed here because the procedure normally does it.</p>
     */
    private static void fallback(Entity round, Vec3 at) {
        Level level = round.level();
        BlockPos pos = BlockPos.containing(at);
        play(level, pos, SOUND_NEAR, 8.0F, 0.8F, 1.0F);
        play(level, pos, SOUND_FAR, 32.0F, 0.7F, 1.0F);
        if (level instanceof ServerLevel server) {
            server.explode(null, at.x, at.y, at.z, RADIUS, ExplosionInteraction.BLOCK);
            cloud(server, at);
        }
        round.discard();
    }

    /** Horizontal reach, in blocks, that the blast's particles are pushed out to. */
    private static final double PARTICLE_REACH = 512.0D;

    /** The reference's {@code explosionsmall} hemisphere: one particle per point, radius 3, height 6. */
    private static void cloud(ServerLevel server, Vec3 at) {
        ParticleType<?> type = BuiltInRegistries.PARTICLE_TYPE.get(parse(PARTICLE));
        if (!(type instanceof ParticleOptions options)) {
            return;
        }
        RandomSource random = server.getRandom();
        for (int y = 0; y < PARTICLE_HEIGHT; y++) {
            for (int x = -PARTICLE_RADIUS; x <= PARTICLE_RADIUS; x++) {
                for (int z = -PARTICLE_RADIUS; z <= PARTICLE_RADIUS; z++) {
                    double share = (double) (x * x) / (PARTICLE_RADIUS * PARTICLE_RADIUS)
                            + (double) (y * y) / (PARTICLE_HEIGHT * PARTICLE_HEIGHT)
                            + (double) (z * z) / (PARTICLE_RADIUS * PARTICLE_RADIUS);
                    if (share > 1.0D) {
                        continue;
                    }
                    double px = at.x + x + Mth.nextInt(random, -1, 1);
                    double py = at.y + y + Mth.nextInt(random, -1, 1);
                    double pz = at.z + z + Mth.nextInt(random, -1, 1);
                    send(server, options, px, py, pz, 0.0D, 0.5D, 0.0D, 0.1D);
                }
            }
        }
    }

    /**
     * One particle to everybody who could see it.
     *
     * <p>The eight-argument {@code ServerLevel#sendParticles} hands the packet only to a player whose
     * <em>block position</em> is within 32 blocks ({@code closerToCenterThan(pos, 32.0D)} in the
     * bytecode) and silently drops everyone else, so a burst at a lock range of a few hundred blocks
     * reached nobody - the same trap that made an interception look silent before
     * (see {@code MathIntercept#firecontrolcompat$spawnInterceptEffect}). The packet here carries the
     * "long distance" flag, and it is sent player by player so the 32 block rule is not consulted.</p>
     */
    private static void send(ServerLevel server, ParticleOptions options, double x, double y, double z,
                             double xSpeed, double ySpeed, double zSpeed, double speed) {
        try {
            net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket packet =
                    new net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket(
                            options, true, x, y, z, 0.0F, 0.0F, 0.0F, (float) speed, 1);
            boolean delivered = false;
            for (net.minecraft.server.level.ServerPlayer player : server.players()) {
                if (player == null || player.connection == null) {
                    continue;
                }
                if (player.position().distanceToSqr(x, y, z) > PARTICLE_REACH * PARTICLE_REACH) {
                    continue;
                }
                player.connection.send(packet);
                delivered = true;
            }
            if (!delivered) {
                server.sendParticles(options, x, y, z, 1, xSpeed, ySpeed, zSpeed, speed);
            }
        } catch (Throwable ignored) {
            try {
                server.sendParticles(options, x, y, z, 1, xSpeed, ySpeed, zSpeed, speed);
            } catch (Throwable ignoredToo) {
                // nowhere to draw it: the blast and the sounds still happened
            }
        }
    }

    private static void play(Level level, BlockPos pos, String id, float volume, float low, float high) {
        SoundEvent sound = BuiltInRegistries.SOUND_EVENT.get(parse(id));
        if (sound == null) {
            return;
        }
        float pitch = (float) Mth.nextDouble(RandomSource.create(), low, high);
        level.playSound(null, pos, sound, SoundSource.NEUTRAL, volume, pitch);
    }

    private static ResourceLocation parse(String id) {
        return ResourceLocation.fromNamespaceAndPath(id.substring(0, id.indexOf(':')),
                id.substring(id.indexOf(':') + 1));
    }

    /**
     * One line per detonation: which style was used and with what parameters.
     *
     * <p>{@code style=pack} is the reference itself; {@code style=fallback} means the pack's procedure
     * could not be reached and the parameters above were reproduced by hand. {@code jet=true} says the
     * shaped-charge jet is spawned on top of the blast, which is this mod's damage source and is not
     * part of the reference.</p>
     */
    private static void report(String style, String cause, Vec3 at, boolean jet) {
        FireControlCompat.LOGGER.info(
                "{}ir-atgm boom: style={} radius={} particle={} sound={}+{} shrapnel={} damage=heat jet={}"
                        + " cause={} pos={}",
                PREFIX, style, firecontrolcompat$radius(style), PARTICLE, SOUND_NEAR, SOUND_FAR,
                style.equals("pack") ? SHRAPNEL : 0, jet, cause, at);
        lastStyle = style;
    }

    /**
     * The radius that actually went off, as text: the pack path is its own 2.0 plus this mod's
     * {@link #PACK_TOP_UP}, everything else is {@link #RADIUS}. Printed instead of the bare constant
     * so {@code radius=} in the log never claims a number that was not used.
     */
    private static String firecontrolcompat$radius(String style) {
        if ("pack".equals(style)) {
            return String.format(Locale.ROOT, "%.1f+%.1f", PACK_RADIUS, PACK_TOP_UP);
        }
        return String.format(Locale.ROOT, "%.1f", RADIUS);
    }

    /** The style the last detonation used, for the launch-side diagnostics. */
    public static String lastStyle() {
        return lastStyle;
    }
}

package com.qkdream.firecontrolcompat.laser;

import com.hooya.stabilizedturret.content.controller.MissileChunkTickets;
import com.qkdream.firecontrolcompat.FireControlCompat;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import net.mcreator.myfirstmod.entity.Antitankmissile1tansheEntity;
import net.mcreator.myfirstmod.entity.Antitankmissile2tansheEntity;
import net.mcreator.myfirstmod.entity.FarrocketlaserguidedtansheEntity;
import net.mcreator.myfirstmod.entity.Groundmissile3tansheEntity;
import net.mcreator.myfirstmod.entity.HeavyrocketlaserguidedtansheEntity;
import net.mcreator.myfirstmod.entity.Jdam1tansheEntity;
import net.mcreator.myfirstmod.entity.Jdam2TansheEntity;
import net.mcreator.myfirstmod.entity.LaserguidedmissiletansheEntity;
import net.mcreator.myfirstmod.entity.MissiletansheEntity;
import net.mcreator.myfirstmod.entity.MortarlaserguidedtansheEntity;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Keeps the chunks under 面包学's laser rounds loaded while they fly.
 *
 * <p>Fire control holds the chunks of the rounds it flies itself - that is what
 * {@link MissileChunkTickets} is for - but a laser round of 面包学's is not on
 * that list: it is flown by her own trail procedure, so nothing keeps its chunks
 * ticking and the round hangs in place the moment it runs past the loaded
 * region, exactly the way the cruise missile did before it was given tickets.</p>
 *
 * <p>This hands those rounds the same tickets fire control gives its own: the
 * very same class, so the lookahead, the ticking radius and the release
 * behaviour are fire control's rather than a copy of them. A round is picked up
 * the moment it comes into the world and released the moment it is gone.</p>
 */
public final class LaserRoundTickets {

    /** Our own ticket key, so our tickets are counted separately from fire control's. */
    private static final TicketType<UUID> TYPE =
            TicketType.create("firecontrolcompat_laser_round", UUID::compareTo);

    /** Rounds being kept loaded, and the tickets held for each. */
    private static final Map<Entity, MissileChunkTickets> HELD = new IdentityHashMap<>();

    /** The persistent-data key a round's own platform code is written under. */
    private static final String CODE_KEY = "fclasercode";

    /** How near a computer a round has to leave the world to count as its round. */
    /**
     * How near a launcher the computer has to stand for the round to be given that
     * computer's code.
     *
     * <p>It has to be close: with a wider reach a round leaving its rack picked up
     * whichever computer happened to be nearest, and a round carrying code 1 will
     * not answer the pod of a computer whose code is 1798 - the two do not match, so
     * the pod never lights for it and the round flies on unguided. A launcher that
     * is not sitting right next to a computer keeps code 0, which every pod serves.</p>
     */
    private static final double PLATFORM_RANGE = 8.0D;

    private LaserRoundTickets() {
    }

    /**
     * Whether that entity is a laser round 面包学 flies and nothing else keeps
     * loaded.
     *
     * <p>The list is every round of hers whose flight procedure looks for a
     * laser spot - the missiles of the missile racks (both the plain one the
     * 4-tube rack throws and the laser one), the anti-tank rounds, the laser
     * rockets, the ground missile, the laser mortar shell and the JDAM bombs.
     * The plain {@code missiletanshe} is on it because her own procedure also
     * homes on a spot, which is the round the 4-tube rack's first family fires.</p>
     */
    public static boolean isLaserRound(Entity entity) {
        if (entity == null) {
            return false;
        }
        try {
            return entity instanceof MissiletansheEntity
                    || entity instanceof LaserguidedmissiletansheEntity
                    || entity instanceof Antitankmissile1tansheEntity
                    || entity instanceof Antitankmissile2tansheEntity
                    || entity instanceof FarrocketlaserguidedtansheEntity
                    || entity instanceof HeavyrocketlaserguidedtansheEntity
                    || entity instanceof Groundmissile3tansheEntity
                    || entity instanceof MortarlaserguidedtansheEntity
                    || entity instanceof Jdam1tansheEntity
                    || entity instanceof Jdam2TansheEntity;
        } catch (LinkageError ignored) {
            return false;
        }
    }

    /** Starts holding chunks for a round that has just come into the world. */
    public static void adopt(Entity entity) {
        if (!isLaserRound(entity) || entity.level().isClientSide()) {
            return;
        }
        int code = tagPlatform(entity);
        synchronized (HELD) {
            HELD.computeIfAbsent(entity, round -> new MissileChunkTickets(TYPE, true));
        }
        FireControlCompat.LOGGER.info(
                "[firecontrolcompat] laser round adopted for chunk tickets type={} pos={} code={}",
                entity.getType(), entity.position(), code);
    }

    /**
     * Writes the launching platform's code onto a round that has just arrived.
     *
     * <p>面包学's rounds carry no channel of their own: her seeker flies at the
     * nearest spot. The platform a round came from is therefore what decides
     * which pod may light a spot for it, and that is recorded here the moment the
     * round exists - a round that came off an unbound launcher keeps 0, which
     * means "any pod".</p>
     */
    private static int tagPlatform(Entity entity) {
        try {
            int code = entity.getPersistentData().getInt(CODE_KEY);
            if (code > 0) {
                return code;
            }
            code = LaserSpots.codeNear(entity.level(), entity.position(), PLATFORM_RANGE);
            entity.getPersistentData().putInt(CODE_KEY, code);
            return code;
        } catch (RuntimeException | LinkageError ignored) {
            return 0;
        }
    }

    /** The platform code a round listens to. 0 means any pod. */
    public static int codeOf(Entity entity) {
        if (entity == null) {
            return 0;
        }
        try {
            return entity.getPersistentData().getInt(CODE_KEY);
        } catch (RuntimeException | LinkageError ignored) {
            return 0;
        }
    }

    /**
     * Whether any round in the air is asking for this pod's laser: it has to be
     * a laser round of 面包学's, inside the reach of her seeker, and either of
     * this platform's code or of no platform at all.
     */
    public static boolean anyDemands(Vec3 spot, int code, double range) {
        if (spot == null) {
            return false;
        }
        double limit = range * range;
        synchronized (HELD) {
            for (Entity round : HELD.keySet()) {
                if (round == null || round.isRemoved()) {
                    continue;
                }
                if (round.position().distanceToSqr(spot) > limit) {
                    continue;
                }
                int roundCode = codeOf(round);
                if (roundCode == 0 || roundCode == code) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether any round of that platform is in the air at all, at any distance.
     *
     * <p>The spot is lit - and the chunk under it is kept loaded - from the
     * moment such a round exists, not from the moment the round comes within
     * seeker range. That is what makes a shot at a target outside the loaded
     * region possible: the target's chunk has to be open while the round is
     * still hundreds of blocks away, or there is nothing there for it to find
     * when it arrives.</p>
     */
    public static boolean anyRoundOfCode(int code) {
        synchronized (HELD) {
            for (Entity round : HELD.keySet()) {
                if (round == null || round.isRemoved()) {
                    continue;
                }
                int roundCode = codeOf(round);
                if (roundCode == 0 || roundCode == code) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Refreshes every held round's path, and lets go of the ones that are gone. */
    public static void tick() {
        synchronized (HELD) {
            if (HELD.isEmpty()) {
                return;
            }
            HELD.entrySet().removeIf(entry -> {
                Entity round = entry.getKey();
                // Only removal counts: 面包学's rounds report isAlive() in ways
                // that come and go while they fly, and dropping one for a tick
                // drops the laser spot and the target chunk with it.
                if (round == null || round.isRemoved()) {
                    release(entry.getValue());
                    return true;
                }
                try {
                    entry.getValue().update(round, null);
                } catch (RuntimeException | LinkageError failure) {
                    release(entry.getValue());
                    return true;
                }
                return false;
            });
        }
    }

    /** Drops every ticket this mod holds, for a server that is shutting down. */
    public static void releaseAll() {
        synchronized (HELD) {
            HELD.values().forEach(LaserRoundTickets::release);
            HELD.clear();
        }
    }

    private static void release(MissileChunkTickets tickets) {
        try {
            tickets.release();
        } catch (RuntimeException | LinkageError ignored) {
        }
    }
}

package com.qkdream.firecontrolcompat.laser;

import com.qkdream.firecontrolcompat.FireControlCompat;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.mcreator.myfirstmod.entity.Laserpoint4Entity;
import net.mcreator.myfirstmod.init.MianbaosModernwarfareModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.phys.Vec3;

/**
 * Puts a laser spot where fire control's optical pod is looking.
 *
 * <p>面包学's own laser guidance is a spot entity: every laser round of hers -
 * the missiles of the missile racks, the anti-tank rounds, the laser rockets,
 * the laser mortar shell and the JDAM bombs - looks for the nearest
 * {@link Laserpoint4Entity} within a few hundred blocks and steers onto it. Her
 * own electro-optical pod does nothing more clever than spawning one of those at
 * the point it is aimed at.</p>
 *
 * <p>One thing about those spots has to be known to use them at all: a spot
 * lives for a <b>single tick</b>. {@code Laserpoint4Entity.baseTick} calls
 * {@code Laserpoint4disappearProcedure}, which on the server is nothing but
 * {@code entity.discard()}. A spot is therefore spawned fresh every tick, by her
 * pod and by this one alike, and a round finds it during that tick. Anything
 * that tries to keep one alive is keeping a corpse.</p>
 *
 * <p>So this is called once per server tick and drops one spot per designating
 * pod, with two differences from her pod: the line from the pod to the spot is
 * walked, so a spot behind a hill, a building or a smoke screen is not lit at
 * all, and a spot is only lit while a round of that platform's own code is
 * inside her seeker's reach - which is what keeps two aircraft from painting for
 * each other.</p>
 */
public final class PodLaserPointer {

    /** How near a round has to be to a spot for the spot to be worth lighting. */
    private static final double SEEKER_RANGE = 600.0D;

    /**
     * How many ticks apart a fresh spot is placed, from {@code spotInterval} in the
     * config.
     *
     * <p>面包学's spot lives one tick, so her pod puts a new one down every work
     * tick, and her seeker looks afresh each tick: every second tick spent without a
     * spot is a tick of guidance lost, which is what a crew felt as their bombs
     * starting to fall wide. One is therefore the default - the same cadence her own
     * pod uses - and the cost it brings is a mark entity per tick while a round is in
     * the air, no more than she already pays herself.</p>
     */
    private static final int SPOT_INTERVAL = readInterval();

    private static int readInterval() {
        try {
            java.nio.file.Path path = java.nio.file.Path.of("config/firecontrolcompat-pod.txt");
            if (!java.nio.file.Files.isRegularFile(path)) {
                return 1;
            }
            for (String line : java.nio.file.Files.readAllLines(path, java.nio.charset.StandardCharsets.UTF_8)) {
                String text = line.trim().toLowerCase().replace(" ", "");
                if (!text.startsWith("spotinterval")) {
                    continue;
                }
                int equals = text.indexOf('=');
                if (equals < 0) {
                    continue;
                }
                int ticks = Integer.parseInt(text.substring(equals + 1).trim());
                ticks = Math.max(1, Math.min(10, ticks));
                FireControlCompat.LOGGER.info("[firecontrolcompat] laser spot interval set to {} ticks", ticks);
                return ticks;
            }
            return 1;
        } catch (java.io.IOException | RuntimeException failure) {
            FireControlCompat.LOGGER.info("[firecontrolcompat] spotInterval unreadable, using 1 tick: {}",
                    failure.toString());
            return 1;
        }
    }

    /** The codes lit on the last pass, so the log only speaks when something changes. */
    private static Set<Integer> lastLit = Set.of();

    /**
     * The spot's own chunk is held open while a round is coming for it.
     *
     * <p>面包学's flight procedure force-loads the chunk the round itself is in
     * and nothing else, so the point the round is meant to fly to can sit in an
     * unloaded chunk: no spot entity exists there, the round finds nothing, and
     * it falls back to the fixed point its own procedure flies to when it has no
     * spot at all. Fire control reserves the impact chunk of its own rounds for
     * exactly this reason; this is the same reservation for the spot.</p>
     */
    private static final Map<ServerLevel, Set<ChunkPos>> HELD = new HashMap<>();

    private static final TicketType<UUID> SPOT_TICKET =
            TicketType.create("firecontrolcompat_laser_spot", UUID::compareTo, 40);

    /** One owner for every spot ticket, so they are counted together. */
    private static final UUID OWNER =
            UUID.nameUUIDFromBytes("firecontrolcompat-laser-spot".getBytes(StandardCharsets.UTF_8));

    private PodLaserPointer() {
    }

    /** Drops this tick's spots, one per designating pod that has a round asking. */
    public static void pulse() {
        if (!com.qkdream.firecontrolcompat.PodLinkSwitch.enabled()) {
            return;
        }
        // Spots live one tick each, so one has to be placed again and again - but
        // not twenty times a second: each one is a mob, and 面包学's seeker looks
        // for one every tick, so every second tick serves it just as well.
        if (SPOT_INTERVAL > 1 && !intervalDue()) {
            return;
        }
        Set<Integer> lit = new LinkedHashSet<>();
        Map<ServerLevel, Set<ChunkPos>> wanted = new HashMap<>();
        for (LaserSpots.Spot spot : LaserSpots.current()) {
            if (spot.blocked()) {
                continue;
            }
            // The spot entity 面包学's rounds home on is only placed while one of
            // them is in the air - that is what keeps the cost down to the moment it
            // can matter. The mark the crew sees is a different thing and appears
            // whenever the pod is painting at all, so a gunner lasing before he fires
            // can see where his laser is going.
            if (!LaserRoundTickets.anyRoundOfCode(spot.code())) {
                // No round of this platform in the air: nothing to paint for.
                continue;
            }
            if (spot.level() == null) {
                continue;
            }
            // The spot's chunk is kept open first: a point in an unloaded chunk
            // cannot exist, and a round with no point turns away. The ticket is only
            // taken when the point is somewhere this world actually reaches - a
            // ticket under a point read in the wrong frame made the server generate
            // terrain where there was none and stalled the game for half a minute -
            // but the spot itself is placed either way, because a platform whose
            // coordinates are its own is a platform that still has to work.
            if (ticketAllowed(spot)) {
                wanted.computeIfAbsent(spot.level(), level -> new HashSet<>())
                        .add(new ChunkPos(BlockPos.containing(spot.position())));
            }
            if (place(spot.level(), spot.position())) {
                lit.add(spot.code());
            }
        }
        hold(wanted);
        if (!lit.equals(lastLit)) {
            lastLit = lit;
            FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] laser spot lit codes={} spots={}", lit, LaserSpots.describe());
        }
    }

    /**
     * Keeps exactly the chunks the spots need loaded this tick, and lets go of
     * the ones that are no longer wanted.
     */
    private static void hold(Map<ServerLevel, Set<ChunkPos>> wanted) {
        Set<ServerLevel> levels = new HashSet<>(HELD.keySet());
        levels.addAll(wanted.keySet());
        for (ServerLevel level : levels) {
            Set<ChunkPos> before = HELD.getOrDefault(level, Set.of());
            Set<ChunkPos> now = wanted.getOrDefault(level, Set.of());
            if (before.equals(now) && !now.isEmpty()) {
                continue;
            }
            try {
                for (ChunkPos chunk : now) {
                    if (!before.contains(chunk)) {
                        level.getChunkSource().addRegionTicket(SPOT_TICKET, chunk, 2, OWNER, true);
                    }
                }
                for (ChunkPos chunk : before) {
                    if (!now.contains(chunk)) {
                        level.getChunkSource().removeRegionTicket(SPOT_TICKET, chunk, 2, OWNER, true);
                    }
                }
            } catch (RuntimeException | LinkageError ignored) {
            }
            if (now.isEmpty()) {
                HELD.remove(level);
            } else {
                HELD.put(level, new HashSet<>(now));
            }
        }
    }

    /** Whether this tick is one that places spots, when they are placed every few ticks. */
    private static boolean intervalDue() {
        net.minecraft.server.MinecraftServer server =
                net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return true;
        }
        return server.overworld().getGameTime() % SPOT_INTERVAL == 0L;
    }



    private static final double TICKET_RANGE = 2048.0D;

    /**
     * Whether the chunk under a spot may be held open.
     *
     * <p>A point in a platform's own frame - a pod on a structure keeps its block
     * position in the structure's space - is its own kind of place, and holding a
     * chunk there is fine. A point in world coordinates whose chunk is thousands of
     * blocks from the pod is the other case: the aim and the camera are being read
     * in two different frames, and taking a ticket there generated terrain on the
     * server thread and froze the game. Only the second case is refused; the spot is
     * still placed, so guidance keeps working.</p>
     */
    private static boolean ticketAllowed(LaserSpots.Spot spot) {
        if (!SPOT_TICKETS_ON || spot == null || spot.level() == null || spot.position() == null) {
            return false;
        }
        // Only a point in this world's own coordinates may be kept loaded. A point
        // on a platform's scale (a structure's own frame runs to tens of millions)
        // would ask the server for a chunk far outside any world, which it answers
        // by generating terrain - the half-minute stall a crew hit. The spot itself
        // is placed either way, so guidance is unaffected.
        return Math.abs(spot.position().x) <= 1.0E6D && Math.abs(spot.position().z) <= 1.0E6D;
    }

    /** Reads {@code spotTicket=on|off}; off by default, because a ticket in the wrong frame stalls. */
    private static boolean readTicket() {
        try {
            java.nio.file.Path path = java.nio.file.Path.of("config/firecontrolcompat-pod.txt");
            if (!java.nio.file.Files.isRegularFile(path)) {
                return false;
            }
            for (String line : java.nio.file.Files.readAllLines(path, java.nio.charset.StandardCharsets.UTF_8)) {
                String text = line.trim().toLowerCase().replace(" ", "");
                if (!text.startsWith("spotticket")) {
                    continue;
                }
                boolean on = text.endsWith("on") || text.endsWith("true") || text.endsWith("=1");
                FireControlCompat.LOGGER.info("[firecontrolcompat] laser spot chunk tickets: {}", on ? "on" : "off");
                return on;
            }
            return false;
        } catch (java.io.IOException | RuntimeException ignored) {
            return false;
        }
    }

    /** Whether spots may keep chunks loaded; off unless the config asks for it. */
    private static final boolean SPOT_TICKETS_ON = readTicket();

    /** Spawns one spot at that point, the way 面包学's own pod does. */
    private static boolean place(ServerLevel level, Vec3 spot) {
        try {
            Entity point = MianbaosModernwarfareModEntities.LASERPOINT_4.get()
                    .spawn(level, BlockPos.containing(spot), MobSpawnType.MOB_SUMMONED);
            if (point != null) {
                point.setYRot(level.getRandom().nextFloat() * 360.0F);
                return true;
            }
        } catch (RuntimeException | LinkageError failure) {
            FireControlCompat.LOGGER.info("[firecontrolcompat] laser spot could not be placed: {}", failure.toString());
        }
        return false;
    }
}

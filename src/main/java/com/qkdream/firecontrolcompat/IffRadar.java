package com.qkdream.firecontrolcompat;

import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.content.controller.ControllerTracker;
import com.hooya.stabilizedturret.content.controller.RemoteControlTracker;
import com.hooya.stabilizedturret.content.controller.RwrThreat;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.hooya.stabilizedturret.content.display.ConnectedDisplayBlockEntity;
import com.hooya.stabilizedturret.content.iff.FireControlMissileOwnership;
import com.hooya.stabilizedturret.content.iff.IffIdentification;
import com.hooya.stabilizedturret.content.iff.IffTransponderBlockEntity;
import com.qkdream.firecontrolcompat.iff.IffOwnership;
import com.qkdream.firecontrolcompat.network.ContactTypePayload;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Friend-or-foe lookups shared by the radar scan, the contact labels and the
 * RWR, answered by the fire control mod's own IFF system.
 *
 * <p>The identity of a rig is the set of channels of the transponders wired to
 * its fire control computer or connected display
 * ({@link IffIdentification#channelsForStructure}), so channels are entered in
 * the official transponder screen instead of a second, mod private frequency
 * band. The radar drops every contact that belongs to a platform sharing one of
 * the computer's channels, the RWR stays silent for threats fired or emitted by
 * those platforms, and ordnance keeps the marking of the structure that
 * released it.</p>
 */
public final class IffRadar {

    /** Startup cost probe: opens the static initialisation window (see the closing block). */
    static {
        StartupProbe.staticBegin("IffRadar");
    }

    /** How long one structure's channel reading is reused, in ticks. */
    private static final long CHANNEL_TTL_TICKS = 40L;

    /** Channels of one structure, valid for {@link #CHANNEL_TTL_TICKS} ticks. */
    private static final Map<String, Channels> CHANNEL_CACHE = new ConcurrentHashMap<>();

    /**
     * How long one friend-or-foe mark stays valid, in ticks (2 s).
     *
     * <p>This is the ceiling on how long a wrong answer can survive. The table used to answer the
     * same key for the rest of the session - it read the clock but never compared against it - so a
     * target that switched its transponder off, or changed to an enemy channel, kept its old marking
     * until the server restarted. That is the bug this window closes, and {@code 40} is the number
     * the acceptance criterion is written in: every change is either caught at once (the key carries
     * the target's channel state and a generation, see below) or at the latest inside this window.</p>
     */
    public static final long MARK_TTL_TICKS = 40L;

    /** One structure's channels, valid for the tick window it was read in. */
    private record Channels(long tick, Set<String> channels) {
    }

    /** One cached mark: the tick it was computed in, the generation it belongs to, and the answer. */
    private record Mark(long tick, long gen, byte value) {
    }

    /**
     * Friend-or-foe marks: valid for {@link #MARK_TTL_TICKS} ticks and for one generation.
     *
     * <p>The key is
     * {@code dimension/uuid/own<channels hash>/t<target channels hash>/g<generation>}.</p>
     *
     * <p>Both hashes are needed. The <b>own</b> one is the firing rig's identity, so two crews with
     * different channels never share an answer. The <b>target</b> one is what makes a target's own
     * transponder state part of the key: {@code IffRadar} marks a structure by the channels of the
     * transponders wired to the computers and displays standing on it, and a transponder stops
     * reporting its channel the moment it is switched off
     * ({@code IffTransponderBlockEntity#isEnabled} is {@code !channel.isEmpty()}, and
     * {@code IffIdentification#collect} filters on it). So switching a transponder off, or changing
     * its channel, changes this hash - and therefore the key - instead of hitting the old answer.
     * The reading comes through {@link #channelsOfStructure}, the same structure cache the answer
     * itself is computed from, so asking for it costs one map lookup per contact, not a full walk.</p>
     */
    private static final Map<String, Mark> MARK_CACHE = new ConcurrentHashMap<>();

    /**
     * Last answer reported per contact, so a mark that changed can say what it changed from.
     *
     * <p>Keyed without the generation and without the target state - that is exactly what has to be
     * held constant to see "it was hostile, the transponder went off, it is unknown now" in one
     * line. Kept small and swept against {@link #MARK_LAST_ANSWER_TTL_TICKS}.</p>
     */
    private static final Map<String, Mark> MARK_LAST_ANSWER = new ConcurrentHashMap<>();

    /** How long a reported answer is remembered for the {@code prev=} column, in ticks. */
    private static final long MARK_LAST_ANSWER_TTL_TICKS = 2400L;

    /**
     * Bumped whenever something that can change an answer is written.
     *
     * <p>Three kinds of write feed it: a transponder's channel (a transponder is switched off by
     * clearing it), a transponder binding on a computer or a display, and a binding that vanished.
     * Every key carries the generation it was computed under, so one bump makes every existing entry
     * unreachable at once <b>without touching the table</b> - the reason the old
     * {@code size() > 512 -> clear()} never appears here: a sweep at the insert site collects the
     * dead generations instead, so a busy scope cannot lose its whole cache in one tick.</p>
     */
    private static final AtomicLong MARK_GENERATION = new AtomicLong();

    /** Above this many entries the dead generations and the expired marks are swept. */
    private static final int MARK_SOFT_LIMIT = 512;

    /** Above this many entries the oldest marks of the reporting dimension are dropped as well. */
    private static final int MARK_HARD_LIMIT = 2048;

    /** How many detail lines one tick may print: the answer is asked for every contact, every scan. */
    private static final int MARK_LOG_BUDGET = 6;

    /** Ticks a batch of hit/miss counters covers before the aggregate line is printed. */
    private static final int MARK_AGGREGATE_TICKS = 20;

    /** Bindings last seen per computer/display, for the safety net that unbinds nothing itself. */
    private static final Map<Object, Integer> BINDING_COUNTS = new ConcurrentHashMap<>();

    private static long markLogTick = Long.MIN_VALUE;
    private static int markLogLeft = MARK_LOG_BUDGET;
    private static long markAggregateTick = Long.MIN_VALUE;
    private static long markHits;
    private static long markMisses;
    private static long markPruned;
    /** Tick the channel cache was last dropped on, so a burst of bumps walks it once, not N times. */
    private static volatile long channelCacheDroppedAt;

    /** Shortest gap between two channel-cache drops, in milliseconds. */
    private static final long CHANNEL_DROP_MILLIS = 50L;

    private IffRadar() {
    }

    /**
     * Invalidates every cached mark. Safe from any thread and cheap: the table is not touched.
     *
     * <p>The structure channel cache <b>is</b> dropped here, because the reason a mark is invalidated
     * is usually a transponder write - and a stale channel reading would otherwise answer the old
     * marking for up to the window even though the generation has already moved. To keep a burst of
     * bumps (binding a whole rig walks every slot, a screen edit fires one write per commit) from
     * turning into one structure walk per contact per bump, the drop happens at most once every
     * {@value #CHANNEL_DROP_MILLIS} ms; a second bump inside that window still moves the generation,
     * and the entries it makes stale are gone with it.</p>
     *
     * @param reason what moved, for the log line
     */
    public static void bump(String reason) {
        long generation = MARK_GENERATION.incrementAndGet();
        long now = System.currentTimeMillis();
        boolean dropped = channelCacheDroppedAt == 0L || now - channelCacheDroppedAt >= CHANNEL_DROP_MILLIS;
        if (dropped) {
            channelCacheDroppedAt = now;
            CHANNEL_CACHE.clear();
        }
        FireControlCompat.LOGGER.info("iff cache: invalidate gen={} reason={} keys={} ttl={} channels-dropped={}",
                generation, reason, MARK_CACHE.size(), MARK_TTL_TICKS, dropped);
    }

    /**
     * Records how many transponders a computer or a display is bound to right now.
     *
     * <p>Called once a tick from the pack's own binding validation. A binding that disappeared there
     * - the transponder was broken while its chunk was unloaded, so the explicit unbind never ran -
     * is the one binding change no write hook can see; the count changing is that signal.</p>
     */
    public static void noteBindingCount(Object owner, int count) {
        if (owner == null) {
            return;
        }
        try {
            Integer previous = BINDING_COUNTS.put(owner, Integer.valueOf(count));
            if (previous != null && previous.intValue() != count) {
                bump("bindings " + previous + "->" + count);
            }
            if (BINDING_COUNTS.size() > 256) {
                BINDING_COUNTS.clear();
            }
        } catch (Throwable ignored) {
            // a diagnostic bookkeeping failure is not worth a broken radar
        }
    }

    /** The generation the mark table is currently answering under, for the log lines. */
    public static long generation() {
        return MARK_GENERATION.get();
    }

    /** Own channels of a fire control computer, empty when it carries none. */
    public static Set<String> ownChannels(Level level, StabilizerControllerBlockEntity controller) {
        if (level == null || controller == null) {
            return Set.of();
        }
        // Answered once per computer per tick. Both the radar sweep and the maid's target pick
        // ask this every tick, and each answer walks the structure's transponder bindings and the
        // official structure lookup - work that cannot change twice inside one tick.
        long now;
        try {
            now = level.getGameTime();
        } catch (Throwable ignored) {
            now = Long.MIN_VALUE;
        }
        Object key = controller;
        OwnCached cached = OWN_CACHE.get(key);
        if (cached != null && now != Long.MIN_VALUE && cached.tick() == now) {
            return cached.channels();
        }
        Set<String> channels =
                rigChannels(level, controller.getOwnControlledSubLevelIds(), controller.getIffTransponderBindings());
        if (now != Long.MIN_VALUE) {
            if (OWN_CACHE.size() > 64) {
                OWN_CACHE.clear();
            }
            OWN_CACHE.put(key, new OwnCached(now, channels));
        }
        return channels;
    }

    /** One computer's own channels, valid for the tick they were read in. */
    private record OwnCached(long tick, Set<String> channels) {
    }

    private static final Map<Object, OwnCached> OWN_CACHE = new ConcurrentHashMap<>();

    /** Own channels of a connected display, empty when it carries none. */
    public static Set<String> ownChannels(Level level, ConnectedDisplayBlockEntity display) {
        if (level == null || display == null) {
            return Set.of();
        }
        return rigChannels(level, display.getOwnStructureIdsForIff(), display.getIffTransponderBindings());
    }

    /** Own channels of the computer the player currently operates. */
    public static Set<String> ownChannelsOf(Player player) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return Set.of();
        }
        StabilizerControllerBlockEntity controller = controlledBy(serverPlayer);
        return controller == null ? Set.of() : ownChannels(controller.getLevel(), controller);
    }

    /**
     * Channels carried by the platform behind a radar contact: a Sable structure
     * answers with the channels of its own rig, ordnance with the channels of the
     * structure that released it.
     */
    public static byte mark(Level level, UUID id, Set<String> own) {
        if (level == null || id == null || own == null || own.isEmpty() || ShaolibBridge.isShaolibId(id)) {
            return ContactTypePayload.IFF_NONE;
        }
        byte answer = firecontrolcompat$markOnce(level, id, own);
        return answer;
    }

    /**
     * The real mark, memoised for {@link #MARK_TTL_TICKS} ticks and one generation.
     *
     * <p>The maid's target pick asks for every contact on the scope every tick, and the radar's own
     * gate asks again over the same list in the same tick, so this is asked hundreds of times a
     * second. Everything the key needs is an O(1) lookup: the contact's entity (the level's own
     * entity map), its owner (a memory read through {@link IffOwnership}) and the owner's channels
     * (the structure cache). The expensive part - walking every loaded computer and display and its
     * transponder bindings - therefore runs once per structure per window, not once per contact per
     * tick.</p>
     */
    private static byte firecontrolcompat$markOnce(Level level, UUID id, Set<String> own) {
        long now;
        try {
            now = level.getGameTime();
        } catch (Throwable ignored) {
            now = Long.MIN_VALUE;
        }
        if (now == Long.MIN_VALUE) {
            // No clock (a client side level): answer straight from the world, never from the table.
            return firecontrolcompat$markCompute(level, id, own);
        }
        long generation = MARK_GENERATION.get();
        // The target's own transponder state: switching one off, or changing its channel, changes
        // this reading and therefore the key, which is what stops the old answer being reused.
        UUID owner = firecontrolcompat$markOwner(level, id);
        firecontrolcompat$reportExempt(level, id, owner);
        Set<String> targetChannels = owner == null ? Set.of() : channelsOfStructure(level, owner);
        String dimension = dimensionOf(level);
        String key = dimension + "/" + id + "/own" + own.hashCode()
                + "/t" + targetChannels.hashCode() + "/g" + generation;
        String base = dimension + "/" + id + "/own" + own.hashCode();
        Mark cached = MARK_CACHE.get(key);
        if (cached != null && cached.gen() == generation && now >= cached.tick()
                && now - cached.tick() <= MARK_TTL_TICKS) {
            markHits++;
            firecontrolcompat$reportMark(now, key, true, now - cached.tick(), generation,
                    cached.value(), cached.value());
            firecontrolcompat$window(now);
            return cached.value();
        }
        byte value = firecontrolcompat$markCompute(level, id, own);
        markMisses++;
        Mark reported = MARK_LAST_ANSWER.get(base);
        boolean known = reported != null
                && now >= reported.tick() && now - reported.tick() <= MARK_LAST_ANSWER_TTL_TICKS;
        byte previous = known ? reported.value() : value;
        MARK_LAST_ANSWER.put(base, new Mark(now, generation, value));
        MARK_CACHE.put(key, new Mark(now, generation, value));
        firecontrolcompat$reportMark(now, key, false, cached == null ? -1L : now - cached.tick(), generation,
                previous, value);
        if (MARK_CACHE.size() > MARK_SOFT_LIMIT) {
            firecontrolcompat$pruneMarks(dimension, now, generation);
        }
        if (MARK_LAST_ANSWER.size() > MARK_SOFT_LIMIT) {
            final long sweepTick = now;
            MARK_LAST_ANSWER.entrySet().removeIf(entry -> entry.getValue() == null
                    || sweepTick < entry.getValue().tick()
                    || sweepTick - entry.getValue().tick() > MARK_LAST_ANSWER_TTL_TICKS);
        }
        firecontrolcompat$window(now);
        return value;
    }

    /** Closes the hit/miss window every {@link #MARK_AGGREGATE_TICKS} ticks. */
    private static void firecontrolcompat$window(long now) {
        if (markAggregateTick == Long.MIN_VALUE || now < markAggregateTick) {
            // Start-up, or a world clock that was set backwards: re-seed instead of waiting out a
            // window that can no longer be reached. Never "now - Long.MIN_VALUE", which wraps.
            markAggregateTick = now + MARK_AGGREGATE_TICKS;
            return;
        }
        if (now < markAggregateTick) {
            return;
        }
        markAggregateTick = now + MARK_AGGREGATE_TICKS;
        firecontrolcompat$reportWindow();
    }

    /**
     * The one line per mark event, plus a batch line every {@link #MARK_AGGREGATE_TICKS} ticks.
     *
     * <p>A detail line is printed when the answer for a contact actually changed (that is the
     * interesting event and it is rare) or, failing that, while this tick's line budget lasts - the
     * cached answer is asked for every contact on every scan, so an unconditional line would be
     * hundreds of lines a tick.</p>
     */
    private static void firecontrolcompat$reportMark(long now, String key, boolean hit, long age,
            long generation, byte previous, byte value) {
        try {
            if (now != markLogTick) {
                markLogTick = now;
                markLogLeft = MARK_LOG_BUDGET;
            }
            boolean changed = !hit && previous != value;
            if (!changed && (hit || markLogLeft <= 0)) {
                return;
            }
            if (!hit) {
                markLogLeft--;
            }
            FireControlCompat.LOGGER.info(
                    "iff cache: key={} hit={} ttl={} age={} gen={} answer={} prev={} size={}",
                    key, hit, MARK_TTL_TICKS, age < 0L ? "-" : String.valueOf(age), generation,
                    answerName(value), changed ? answerName(previous) : "-", MARK_CACHE.size());
        } catch (Throwable ignored) {
            // the mark itself is already answered
        }
    }

    /** The batch line: how many answers were reused and how many had to be recomputed. */
    private static void firecontrolcompat$reportWindow() {
        try {
            if (markHits == 0L && markMisses == 0L) {
                return;
            }
            FireControlCompat.LOGGER.info(
                    "iff cache: window={} hits={} misses={} keys={} pruned={} ttl={} gen={}",
                    MARK_AGGREGATE_TICKS, markHits, markMisses, MARK_CACHE.size(), markPruned,
                    MARK_TTL_TICKS, MARK_GENERATION.get());
            markHits = 0L;
            markMisses = 0L;
            markPruned = 0L;
        } catch (Throwable ignored) {
            // counters only
        }
    }

    /**
     * Collects what can no longer be hit, instead of the whole-table {@code clear()} this replaces.
     *
     * <p>In order: every entry of an older generation (unreachable the moment {@link #bump} ran),
     * every entry past its time to live, and - only if the table is still over the hard limit -
     * the oldest marks of the reporting dimension. The table is never emptied wholesale, so the
     * answers of the other dimensions and of the current generation survive a busy scope.</p>
     */
    private static void firecontrolcompat$pruneMarks(String dimension, long now, long generation) {
        int removed = 0;
        int before = MARK_CACHE.size();
        MARK_CACHE.entrySet().removeIf(entry -> entry.getValue() == null || entry.getValue().gen() != generation);
        removed += before - MARK_CACHE.size();
        if (MARK_CACHE.size() > MARK_SOFT_LIMIT) {
            before = MARK_CACHE.size();
            final long sweepTick = now;
            MARK_CACHE.entrySet().removeIf(entry -> {
                Mark mark = entry.getValue();
                return mark == null || sweepTick < mark.tick()
                        || sweepTick - mark.tick() > MARK_TTL_TICKS;
            });
            removed += before - MARK_CACHE.size();
        }
        if (MARK_CACHE.size() > MARK_HARD_LIMIT) {
            String prefix = dimension + "/";
            java.util.List<Map.Entry<String, Mark>> mine = new java.util.ArrayList<>();
            for (Map.Entry<String, Mark> entry : MARK_CACHE.entrySet()) {
                if (entry.getKey().startsWith(prefix) && entry.getValue() != null) {
                    mine.add(entry);
                }
            }
            if (!mine.isEmpty()) {
                mine.sort((first, second) -> Long.compare(first.getValue().tick(), second.getValue().tick()));
                int keep = Math.max(MARK_SOFT_LIMIT, MARK_CACHE.size() - mine.size() / 2);
                for (Map.Entry<String, Mark> entry : mine) {
                    if (MARK_CACHE.size() <= keep) {
                        break;
                    }
                    if (MARK_CACHE.remove(entry.getKey()) != null) {
                        removed++;
                    }
                }
            }
        }
        markPruned += removed;
        if (removed > 0) {
            FireControlCompat.LOGGER.info("iff cache: sweep dimension={} removed={} keys={} gen={} ttl={}",
                    dimension, removed, MARK_CACHE.size(), generation, MARK_TTL_TICKS);
        }
    }

    /**
     * The structure whose channels decide a contact's mark: the contact itself when it is a
     * structure, the platform that released it when it is one of this mod's rounds, and null when it
     * is neither (such a contact is never marked at all, so it has no target state to key on).
     */
    /**
     * Diagnostic for the same-body question, at most once per second.
     *
     * <p>Prints what the exemption actually asked and what came back: the contact, the structure its
     * marking resolved to, whether that is the same body it stands in now, and which of the three
     * routes answered ({@code sublevel} = the owner was resolved, {@code remembered} = only the pin
     * was left, {@code none} = neither). "Same body" is the exemption, so this line is the one that
     * says whether it was even answerable.</p>
     */
    private static void firecontrolcompat$reportExempt(Level level, UUID id, UUID owner) {
        long now = System.currentTimeMillis();
        if (firecontrolcompat$lastExempt != 0L && now - firecontrolcompat$lastExempt < 1000L) {
            return;
        }
        firecontrolcompat$lastExempt = now;
        UUID current = null;
        boolean loaded = false;
        try {
            ServerLevel server = serverLevelOf(level);
            if (server != null) {
                var entity = server.getEntity(id);
                loaded = entity != null;
                current = IffOwnership.currentBody(server, entity);
            }
        } catch (Throwable ignored) {
            // an unreadable entity is reported as not standing in any body
        }
        FireControlCompat.LOGGER.info("iff exempt: a={} b={} same-body={} basis={}",
                id, owner == null ? "-" : owner,
                owner != null && owner.equals(current),
                owner == null ? "none" : (loaded ? "sublevel" : "remembered"));
    }

    private static long firecontrolcompat$lastExempt;

    private static UUID firecontrolcompat$markOwner(Level level, UUID id) {
        try {
            Entity entity = entityOf(level, id);
            if (entity == null) {
                return id;
            }
            if (!TargetClassifier.isCompatMissile(entity)) {
                return null;
            }
            UUID pinned = IffOwnership.subLevelOf(serverLevelOf(level), entity);
            if (pinned != null) {
                return pinned;
            }
            // The entity is loaded but stands in no structure any more (it cleared the hull): the
            // pin taken at launch is still the answer, and asking it by UUID is what keeps a round
            // of this body friendly after it has flown away.
            return IffOwnership.remembered(serverLevelOf(level), id);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** {@code dimension:path} for the log and the cache key, never null. */
    private static String dimensionOf(Level level) {
        try {
            return level == null ? "-" : level.dimension().location().toString();
        } catch (Throwable ignored) {
            return "-";
        }
    }

    /** The three answers the hand-off asks for, plus the "no mark at all" case. */
    public static String answerName(byte value) {
        if (value == ContactTypePayload.IFF_FRIENDLY) {
            return "friendly";
        }
        if (value == ContactTypePayload.IFF_ENEMY) {
            return "hostile";
        }
        if (value == ContactTypePayload.IFF_UNKNOWN) {
            return "unknown";
        }
        return "none";
    }

    private static byte firecontrolcompat$markCompute(Level level, UUID id, Set<String> own) {
        Entity entity = entityOf(level, id);
        boolean structure = entity == null;
        UUID owner;
        if (structure) {
            owner = id;
        } else {
            if (!TargetClassifier.isCompatMissile(entity)) {
                return ContactTypePayload.IFF_NONE;
            }
            if (officialFriendly(level, own, entity)) {
                return ContactTypePayload.IFF_FRIENDLY;
            }
            owner = IffOwnership.subLevelOf(serverLevelOf(level), entity);
        }
        if (owner == null) {
            return ContactTypePayload.IFF_NONE;
        }
        Set<String> channels = channelsOfStructure(level, owner);
        if (channels.isEmpty()) {
            // A structure without a transponder is unknown, its ordnance unmarked.
            return structure ? ContactTypePayload.IFF_UNKNOWN : ContactTypePayload.IFF_NONE;
        }
        return shares(channels, own) ? ContactTypePayload.IFF_FRIENDLY : ContactTypePayload.IFF_ENEMY;
    }

    /** Whether a computer contact has to be hidden from the radar. */
    public static boolean hiddenFromRadar(
            Level level, StabilizerControllerBlockEntity.RadarContact contact, Set<String> own) {
        if (contact == null) {
            return false;
        }
        return friendly(level, contact.subLevelId(), own);
    }

    /** Same, for a connected display contact. */
    public static boolean hiddenFromDisplayRadar(
            Level level, ConnectedDisplayBlockEntity.Contact contact, Set<String> own) {
        if (contact == null) {
            return false;
        }
        UUID id = contact.key() == null ? null : contact.key().id();
        return friendly(level, id, own);
    }

    /** Whether an RWR threat comes from own side and must not raise an alarm. */
    public static boolean friendlyThreat(Level level, RwrThreat threat, Set<String> own) {
        if (level == null || threat == null || own == null || own.isEmpty()) {
            return false;
        }
        if (threat.type() == RwrThreat.Type.MSL) {
            Entity round = entityOf(level, threat.id());
            if (round == null) {
                return false;
            }
            if (officialFriendly(level, own, round)) {
                return true;
            }
            UUID owner = IffOwnership.subLevelOf(serverLevelOf(level), round);
            return owner != null && shares(channelsOfStructure(level, owner), own);
        }
        StabilizerControllerBlockEntity controller = RadarEmitters.at(level, threat.position());
        if (controller != null && shares(ownChannels(level, controller), own)) {
            return true;
        }
        ConnectedDisplayBlockEntity display = RadarEmitters.displayAt(level, threat.position());
        if (display != null && shares(ownChannels(level, display), own)) {
            return true;
        }
        return shares(channelsOfStructure(level, structureAt(level, threat.position())), own);
    }

    /** True when one of the candidate channels is the same identity as one of ours. */
    public static boolean shares(Set<String> candidate, Set<String> own) {
        if (candidate == null || own == null || candidate.isEmpty() || own.isEmpty()) {
            return false;
        }
        for (String channel : candidate) {
            if (own.contains(channel)) {
                return true;
            }
        }
        return false;
    }

    /** The structure the position sits in, or null when it sits in open air. */
    public static UUID structureAt(Level level, Vec3 position) {
        if (level == null || position == null) {
            return null;
        }
        return subLevelAt(level, BlockPos.containing(position));
    }

    /** The structure containing a block position, or null in open air. */
    public static UUID subLevelAt(Level level, BlockPos pos) {
        if (level == null || pos == null) {
            return null;
        }
        try {
            SubLevel subLevel = Sable.HELPER.getContaining(level, pos);
            return subLevel == null || subLevel.isRemoved() ? null : subLevel.getUniqueId();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** The main level of the dimension the given level belongs to. */
    public static ServerLevel serverLevelOf(Level level) {
        if (level == null) {
            return null;
        }
        MinecraftServer server = level.getServer();
        if (server != null) {
            ServerLevel main = server.getLevel(level.dimension());
            if (main != null) {
                return main;
            }
        }
        return level instanceof ServerLevel serverLevel ? serverLevel : null;
    }

    /**
     * Channels of the rig that owns the given structures, plus the transponders
     * wired straight to it. The direct bindings matter for a rig standing in the
     * world rather than on a Sable structure, where the official structure
     * lookup alone finds nothing.
     */
    private static Set<String> rigChannels(Level level, Set<UUID> ownStructures, List<BindingRef> bindings) {
        Set<String> channels = new HashSet<>();
        try {
            if (ownStructures != null && !ownStructures.isEmpty()) {
                channels.addAll(IffIdentification.channelsForStructure(level, ownStructures));
            }
        } catch (Throwable ignored) {
        }
        collectBound(level, bindings, channels);
        return channels.isEmpty() ? Set.of() : Set.copyOf(channels);
    }

    /**
     * Channels of a structure, cached for {@link #CHANNEL_TTL_TICKS} ticks.
     *
     * <p>The window is the mark window on purpose. This reading is asked for twice per contact per
     * tick - once for the cache key, once for the answer - and it is what carries a target's
     * transponder state into that key, so it must be cheap; but it also must not be so old that a
     * marking is answered from a reading the mark's own time to live has already outlived. One walk
     * per structure per window does both.</p>
     */
    private static Set<String> channelsOfStructure(Level level, UUID structureId) {
        if (level == null || structureId == null) {
            return Set.of();
        }
        long now;
        try {
            now = level.getGameTime();
        } catch (Throwable ignored) {
            now = Long.MIN_VALUE;
        }
        String key = null;
        if (now != Long.MIN_VALUE) {
            key = dimensionOf(level) + "/" + structureId;
            Channels cached = CHANNEL_CACHE.get(key);
            if (cached != null && now >= cached.tick() && now - cached.tick() <= CHANNEL_TTL_TICKS) {
                return cached.channels();
            }
        }
        Set<String> channels = Set.of();
        try {
            channels = IffIdentification.channelsForStructure(level, Set.of(structureId));
        } catch (Throwable ignored) {
        }
        if (key != null) {
            if (CHANNEL_CACHE.size() > MARK_HARD_LIMIT) {
                final long sweepTick = now;
                CHANNEL_CACHE.entrySet().removeIf(entry -> {
                    Channels cached = entry.getValue();
                    return cached == null || sweepTick < cached.tick()
                            || sweepTick - cached.tick() > CHANNEL_TTL_TICKS;
                });
            }
            CHANNEL_CACHE.put(key, new Channels(now, channels));
        }
        return channels;
    }

    private static void collectBound(Level level, List<BindingRef> bindings, Set<String> channels) {
        if (bindings == null) {
            return;
        }
        for (BindingRef binding : bindings) {
            if (binding == null) {
                continue;
            }
            try {
                if (binding.resolve(level) instanceof IffTransponderBlockEntity transponder && transponder.isEnabled()) {
                    String channel = transponder.getChannel();
                    if (channel != null && !channel.isEmpty()) {
                        channels.add(channel);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static StabilizerControllerBlockEntity controlledBy(ServerPlayer player) {
        try {
            StabilizerControllerBlockEntity controller = RemoteControlTracker.findInputController(player);
            if (controller != null) {
                return controller;
            }
        } catch (Throwable ignored) {
        }
        try {
            return ControllerTracker.findForVehicle(player.level(), player.getVehicle());
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Friend-or-foe answer taken straight from the fire control mod, which pins
     * the rig that released each of its own rounds on the round itself. Rounds
     * it never adopted answer false here and fall back to the launch memory and
     * the hull scan.
     */
    private static boolean officialFriendly(Level level, Set<String> own, Entity entity) {
        if (level == null || own == null || own.isEmpty() || entity == null) {
            return false;
        }
        try {
            return FireControlMissileOwnership.isFriendly(level, own, entity);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean friendly(Level level, UUID id, Set<String> own) {
        return mark(level, id, own) == ContactTypePayload.IFF_FRIENDLY;
    }

    private static boolean belowSpeedGate(Vec3 velocity) {
        if (!FireControlLeadSettings.speedGateEnabled()) {
            return false;
        }
        double metresPerSecond = (velocity == null ? 0.0 : velocity.length()) * 20.0;
        return metresPerSecond < FireControlLeadSettings.speedGateSpeed();
    }

    private static Entity entityOf(Level level, UUID id) {
        ServerLevel server = serverLevelOf(level);
        if (server == null || id == null) {
            return null;
        }
        try {
            return server.getEntity(id);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Startup cost probe: closes the static initialisation window opened at the top. */
    static {
        StartupProbe.staticEnd("IffRadar");
    }
}

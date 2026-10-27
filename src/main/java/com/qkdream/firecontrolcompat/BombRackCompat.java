package com.qkdream.firecontrolcompat;

import com.hooya.stabilizedturret.content.controller.BindingRef;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import com.qkdream.firecontrolcompat.SubLevelPositions;
import com.hooya.stabilizedturret.content.controller.ControllerTracker;
import com.hooya.stabilizedturret.content.controller.MianbaoAircraftCompat;
import com.hooya.stabilizedturret.content.controller.MianbaoMissileCompat;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 面包学's bomb racks flown by the fire control computer.
 *
 * <p>A bomb rack is not one of the computer's aircraft weapon types, so the
 * rack answers as the air-to-ground one: it binds like an air-to-ground rail,
 * is selected with the air-to-ground line on the HUD, and is released with the
 * fire key. The round itself is left as 面包学's own - the drop, the fall, the
 * glide and the burst are all the mod's - so what the computer adds is where the
 * round goes: the ground point the computer holds is written onto the rack
 * before its own launch runs, and the round then flies the mod's own route to
 * that point.</p>
 *
 * <p>Rounds released from a rack mounted on a moving structure keep the
 * structure's own velocity, exactly like the computer's own aircraft rounds do,
 * so a bomb let go from a ship carries on over the ground the ship was making
 * for instead of dropping straight down.</p>
 */
public final class BombRackCompat {

    private static final String NS = "mianbaos_modernwarfare";

    /** One kind of rack: the name it is announced under, and how it lets go. */
    private record Spec(String name, String procedure, boolean groundPoint, boolean special) {
    }

    private static final Map<ResourceLocation, Spec> SPECS = specs();

    /**
     * The empty racks that answer the computer: 面包学's one spare rack (every
     * rack of hers turns into it once its round is away) and the composite
     * rack's empty form. The spare has to be here, or a rack bound while it
     * was empty stops being recognised the moment a round is loaded onto it -
     * fire control compares the recorded block id and only forgives a change
     * between rack variants.
     */
    private static final Set<ResourceLocation> EMPTY_RACKS = Set.of(
            id("mixedbombrack"),
            id("rack"));

    /** Every round of 面包学's bomb family, for the radar and the air defense. */
    private static final Set<ResourceLocation> BOMBS = Set.of(
            id("bombtanshe"),
            id("jdam_1tanshe"),
            id("jdam_2_tanshe"),
            id("clusterbombtanshe"),
            id("littlebomb"),
            id("earthpenetratorbombtanshe"),
            id("highresistancebombtanshe"),
            id("fire_bombtanshe"),
            id("medium_bombtanshe"),
            id("gasbombtanshe"),
            id("white_phosphorusbombtanshe"),
            id("whitephosphorusbombtanshe_2"),
            id("glidebombtanshe"),
            id("nuclearbombtanshe"));

    /** Where the rocket leaves the rack, under it and a little forward. */
    private static final double MUZZLE_DROP = 2.0D;
    private static final double MUZZLE_FORWARD = 0.6D;
    /** How close to a rack's muzzle a round has to come to be called hers. */
    private static final double CLAIM_SEARCH = 24.0D;
    /** Ticks a rack keeps her just-released round in mind. */
    private static final long PICKUP_TICKS = 10L;

    /** Label of the cruise line, which shares the ground slot with the bombs. */
    public static final String CRUISE_LABEL = "巡航导弹";

    /** Which entry of a computer's ground line its trigger is on. */
    private static final Map<BlockPos, Integer> SELECTED = new ConcurrentHashMap<>();

    /**
     * The kinds on a computer's ground line, in the order they were first seen.
     *
     * <p>Kept per computer and only ever added to. A list rebuilt from what is loaded
     * right now shrinks the moment a kind is used up, and every kind after it moves down
     * a place - so the trigger, still on the kind the crew chose, would release the one
     * that had just taken its index. That is how a crew came to pick one kind of round
     * and watch another leave the aircraft.</p>
     */
    private static final Map<BlockPos, List<String>> KIND_ORDER = new ConcurrentHashMap<>();

    /** Whether the rack is one of 面包学's cruise rails, read the robust way. */
    private static boolean cruiseRack(Level level, BindingRef rack) {
        if (level == null || rack == null) {
            return false;
        }
        try {
            if (CruiseMissileRackCompat.isCruiseRack(stateAt(level, rack))) {
                return true;
            }
        } catch (RuntimeException | LinkageError ignored) {
        }
        // The pack's own check resolves the binding and can come up empty on a structure.
        try {
            return CruiseMissileRackCompat.isCruiseRack(level, rack);
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    /** The kinds on this computer's ground line, in their fixed order. */
    public static List<String> kindOrder(Level level, BlockPos controller, List<BindingRef> racks) {
        List<String> order = controller == null ? null : KIND_ORDER.get(controller);
        if (order == null) {
            order = new ArrayList<>();
            if (controller != null) {
                KIND_ORDER.put(controller, order);
            }
        }
        if (level == null || racks == null) {
            return order;
        }
        for (BindingRef rack : racks) {
            // Every rack the pack counts as a ground weapon takes a place, not only the
            // bomb families and the cruise rails: an air-to-ground missile rail of the
            // pack's own was left out and could not be selected at all once a bomb was
            // loaded beside it.
            String label = groundLabel(level, rack);
            if (label != null && !order.contains(label)) {
                order.add(label);
            }
        }
        return order;
    }

    /**
     * The label of the ground line a rack belongs to.
     *
     * <p>A bomb rack is named for its family, and every rack of that family shares the
     * label - so a pair of the same bombs on two wings is one entry, not two. A cruise
     * rail answers with its own label, because it rides the same ground line and was
     * otherwise unreachable: the cycle saw one entry for every rack on it.</p>
     */
    public static String groundLabel(Level level, BindingRef rack) {
        if (level == null || rack == null) {
            return null;
        }
        if (cruiseRack(level, rack)) {
            return CRUISE_LABEL;
        }
        ResourceLocation id = blockId(level, rack);
        Spec spec = specOf(id);
        if (spec != null) {
            return spec.name();
        }
        // Anything else the pack counts as the ground line - a missile rack of its own,
        // a rail of another compat - still has to be selectable, so it is named after its
        // own block and takes its own entry. That is what "whatever the loadout, every
        // kind can be chosen" needs: a kind this mod has never heard of is a label, not a
        // hole in the cycle.
        if (id == null) {
            return null;
        }
        try {
            if (MianbaoAircraftCompat.weaponAt(level, rack)
                    != MianbaoAircraftCompat.WeaponType.AIR_TO_GROUND_DIRECT) {
                return null;
            }
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
        String path = id.getPath();
        return path.isEmpty() ? id.toString() : path;
    }

    /**
     * The loaded ground entries of a computer: one key per kind, in binding order.
     *
     * <p>Only kinds with a round on them appear, so the cycle walks what can actually be
     * released rather than every rack ever bound.</p>
     */
    public static LinkedHashMap<String, List<BindingRef>> loadedEntries(Level level, List<BindingRef> racks) {
        LinkedHashMap<String, List<BindingRef>> entries = new LinkedHashMap<>();
        if (level == null || racks == null) {
            return entries;
        }
        for (BindingRef rack : racks) {
            String label = groundLabel(level, rack);
            if (label == null) {
                continue;
            }
            boolean loaded = isLoadedBombRack(level, rack);
            if (!loaded) {
                // Not one of the bomb families: it counts as loaded while the pack itself
                // says it carries this weapon, which is how a missile rack of its own or a
                // rail from another compat enters the cycle.
                try {
                    loaded = MianbaoAircraftCompat.weaponAt(level, rack)
                            == MianbaoAircraftCompat.WeaponType.AIR_TO_GROUND_DIRECT
                            && MianbaoAircraftCompat.isRack(level.getBlockState(rack.pos()));
                } catch (RuntimeException | LinkageError ignored) {
                    loaded = false;
                }
            }
            if (loaded) {
                entries.computeIfAbsent(label, key -> new ArrayList<>()).add(rack);
            }
        }
        return entries;
    }

    /**
     * Steps the selection on by one kind of ground round, wrapping at the end.
     *
     * <p>面包学's weapon cycle counts weapon <em>types</em>, and every bomb rack and
     * cruise rail answers as the same ground type, so a mixed load had one entry between
     * them and the computer always released from the first. Each press that lands on the
     * ground line moves this pointer instead.</p>
     */
    public static void cycleSelection(Level level, BlockPos controller, List<BindingRef> racks) {
        if (level == null || controller == null) {
            return;
        }
        List<String> labels = kindOrder(level, controller, racks);
        if (labels.size() <= 1) {
            SELECTED.put(controller, 0);
            return;
        }
        int next = (SELECTED.getOrDefault(controller, -1) + 1) % labels.size();
        SELECTED.put(controller, next);
        FireControlCompat.LOGGER.info("[firecontrolcompat] ground selection probe {} of {} labels={} racks={}",
                next + 1, labels.size(), labels, racks.size());
    }

    /**
     * The racks of the kind the trigger is on.
     *
     * <p>Empty when that kind has been used up. Deliberately not falling back to another
     * kind: a crew that has thrown its last plain bomb should find the trigger idle, not
     * have the next kind of bomb start falling unasked.</p>
     */
    public static List<BindingRef> selectedRacks(Level level, BlockPos controller, List<BindingRef> racks) {
        if (controller == null || level == null || racks == null) {
            return List.of();
        }
        List<String> labels = kindOrder(level, controller, racks);
        if (labels.isEmpty()) {
            return List.of();
        }
        int index = Math.floorMod(SELECTED.getOrDefault(controller, 0), labels.size());
        String wanted = labels.get(index);
        List<BindingRef> chosen = new ArrayList<>();
        for (BindingRef rack : racks) {
            // Only racks of the chosen kind that still carry a round; an empty kind gives
            // an empty list, which is what keeps the trigger idle instead of moving on to
            // whichever kind happens to sit at that index now.
            String label = groundLabel(level, rack);
            if (label == null || !wanted.equals(label)) {
                continue;
            }
            // Any rack of that kind, not only the bomb families and the cruise rails: the
            // selection list was widened to every ground rack while this filter was not, so
            // an air-to-ground missile rail could be selected and would never be fired.
            chosen.add(rack);
        }
        return chosen;
    }

    /** Whether this computer has more than one kind of ground round to choose between. */
    public static boolean hasChoice(Level level, BindingRef ignored, List<BindingRef> racks) {
        return loadedEntries(level, racks).size() > 1;
    }

    /** What a family of rounds will steer on. */
    public enum Guidance {
        /** Falls on its own; nothing to satisfy. */
        FREE,
        /** The airborne chart point written with the strike terminal, and nothing else. */
        TERMINAL,
        /** The terminal's point or the computer's own pod lock. */
        TERMINAL_OR_POD,
        /** The pod's laser spot, and nothing else. */
        LASER,
        /** The terminal's point or the laser; a pod lock is not a substitute. */
        TERMINAL_OR_LASER
    }

    /** Whether the computer's pod has a ground target locked. */
    public static boolean hasPodLock(StabilizerControllerBlockEntity controller) {
        if (controller == null) {
            return false;
        }
        try {
            StabilizerControllerBlockEntity.GroundTargetSnapshot snapshot =
                    controller.getAirToGroundTargetSnapshot();
            return snapshot != null && snapshot.center() != null;
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    /**
     * The guidance each family of rounds accepts.
     *
     * <p>Written out per family because the three kinds a crew distinguishes behave
     * differently: the earth penetrator is aimed by the airborne chart point and does not
     * chase a laser, the laser-guided bomb is the other way round, and the composite ones
     * take either. A name that is not listed falls freely.</p>
     */
    private static Guidance guidanceOf(String label) {
        if (label == null) {
            return Guidance.FREE;
        }
        if (label.contains("钻地")) {
            // The penetrating round is aimed by the terminal's point alone: a pod lock is
            // not what it flies to, and neither is a laser.
            return Guidance.TERMINAL;
        }
        if (label.contains("巡航")) {
            // The cruise flies to whatever ground target the computer holds, a structure
            // locked with the pod included.
            return Guidance.TERMINAL_OR_POD;
        }
        if (label.contains("激光")) {
            return Guidance.LASER;
        }
        if (label.startsWith("复合挂架")) {
            // Both kinds of steering: the terminal's point, or a laser.
            return Guidance.TERMINAL_OR_LASER;
        }
        if (label.contains("JDAM")) {
            return Guidance.LASER;
        }
        if (label.contains("滑翔")) {
            return Guidance.TERMINAL_OR_POD;
        }
        return Guidance.FREE;
    }

    /**
     * Whether the round on that rack may be let go with what the computer has.
     *
     * <p>This is where a crew's rules are kept: a kind that wants the chart point is not
     * thrown on a laser alone, and one that follows the laser is not thrown without it.
     * The reason is written to the log, because a trigger that does nothing is otherwise
     * indistinguishable from one that is broken.</p>
     */
    public static boolean canRelease(
            Level level, BindingRef rack, boolean terminal, boolean pod, boolean lasing) {
        Guidance guidance = guidanceOf(groundLabel(level, rack));
        boolean ok = switch (guidance) {
            case FREE -> true;
            case TERMINAL -> terminal;
            case TERMINAL_OR_POD -> terminal || pod;
            case LASER -> lasing;
            case TERMINAL_OR_LASER -> terminal || lasing;
        };
        if (!ok) {
            FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] release held back guidance={} terminal={} pod={} lasing={} rack={}",
                    guidance, terminal, pod, lasing, rack == null ? null : rack.pos());
        }
        return ok;
    }

    /** Releases one round from the kind the trigger is on, cruise rails included. */
    public static boolean fireSelected(
            Level level, BlockPos controller, List<BindingRef> racks,
            boolean terminal, boolean pod, boolean lasing) {
        return fireOneOf(level, selectedRacks(level, controller, racks), terminal, pod, lasing);
    }

    /**
     * Releases one round from the first of these racks that may go, and stops there.
     *
     * <p>The body of {@link #fireSelected}, split out because 0.9.4 names each bomb family with a
     * <b>weapon type of its own</b> - {@code GLIDE_BOMB}, {@code JDAM_BOMB}, {@code LASER_BOMB} and
     * the rest of {@code MianbaoBombCompat}'s table - so the trigger's kind can be read off the
     * pack's own selection instead of guessed from this mod's label order. One press, one round,
     * and a kind whose guidance is not satisfied releases nothing at all.</p>
     */
    public static boolean fireOneOf(
            Level level, List<BindingRef> candidates,
            boolean terminal, boolean pod, boolean lasing) {
        for (BindingRef rack : candidates) {
            // The whole chain in one line: which label the trigger is on, which rack that
            // resolved to, what that rack calls itself, and which launch it will take.
            FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] dispatch selected-label={} rack={} block={} route={}",
                    groundLabel(level, rack), rack.pos(), blockId(level, rack),
                    CruiseMissileRackCompat.isCruiseRack(level, rack) ? "cruise"
                            : (specOf(blockId(level, rack)) != null ? "bomb" : "pack"));
            if (!canRelease(level, rack, terminal, pod, lasing)) {
                // One press, one round: a kind whose guidance is not satisfied is simply
                // not thrown, and the next kind is not thrown in its place.
                return false;
            }
            if (CruiseMissileRackCompat.isCruiseRack(level, rack)) {
                // Released through the pack's own one-shot launch, which this mod's mixin turns
                // into the cruise rail's own launch - the arrangement that was in place before
                // the selection work, and the one a crew says locked correctly.
                try {
                    if (MianbaoAircraftCompat.fireOne(
                            level, rack, MianbaoAircraftCompat.WeaponType.AIR_TO_GROUND_DIRECT)) {
                        return true;
                    }
                } catch (RuntimeException | LinkageError ignored) {
                }
                if (CruiseMissileRackCompat.fire(level, rack.pos())) {
                    return true;
                }
                continue;
            }
            if (isLoadedBombRack(level, rack)) {
                if (fire(level, rack)) {
                    reportSpawned(level, rack);
                    return true;
                }
                continue;
            }
            // A rail this mod does not throw itself: the pack's own release is asked, so
            // its missile leaves exactly as it would have from the stock ground slot.
            try {
                if (MianbaoAircraftCompat.fireOne(
                        level, rack, MianbaoAircraftCompat.WeaponType.AIR_TO_GROUND_DIRECT)) {
                    return true;
                }
            } catch (RuntimeException | LinkageError ignored) {
            }
        }
        FireControlCompat.LOGGER.info("[firecontrolcompat] ground release found nothing among {} racks of this kind",
                candidates.size());
        return false;
    }

    /**
     * Whether the pack is calling this kind a bomb of its own.
     *
     * <p>0.9.4 gave every bomb family a weapon type of its own. Its own release then walks the racks
     * by that type and never asks this mod's layer, so a glide bomb or a JDAM selected on the HUD
     * was released by the pack's stock path with no ground point written onto the rail - it fell
     * away from the aircraft with nothing to steer to. That type is the trigger for this mod's
     * release now, exactly like {@code AIR_TO_GROUND_DIRECT} always was for the racks the pack has
     * no type for.</p>
     */
    public static boolean isNativeBombSelection(MianbaoAircraftCompat.WeaponType type) {
        if (type == null) {
            return false;
        }
        try {
            return com.hooya.stabilizedturret.content.controller.MianbaoBombCompat.isBomb(type);
        } catch (RuntimeException | LinkageError ignored) {
            // A pack that has no such table answers by name only.
            String name = type.name();
            return name.endsWith("_BOMB") || "BOMB".equals(name) || "JDAM".equals(name);
        }
    }

    /**
     * The racks the pack's own selection names, for a weapon type it assigns itself.
     *
     * <p>Empty when the pack cannot answer for these rails ({@code weaponAt} needs the position's
     * chunk to be loaded in the level the computer asked about, which a Sable sub-level does not
     * always satisfy), in which case the caller keeps using this mod's own kind pointer.</p>
     */
    public static List<BindingRef> racksForSelection(
            Level level, List<BindingRef> racks, MianbaoAircraftCompat.WeaponType type) {
        if (level == null || racks == null || type == null) {
            return List.of();
        }
        List<BindingRef> chosen = new ArrayList<>();
        for (BindingRef rack : racks) {
            try {
                if (MianbaoAircraftCompat.weaponAt(level, rack) == type) {
                    chosen.add(rack);
                }
            } catch (RuntimeException | LinkageError ignored) {
                // a rail the pack cannot name is not one of this selection
            }
        }
        return chosen;
    }

    /** Launch procedures, found once each. */
    private static final Map<String, java.util.Optional<Method>> PROCEDURES = new ConcurrentHashMap<>();

    /** Racks that have just released, and the round not yet caught. */
    private static final List<Pending> PENDING = new ArrayList<>();

    /** Rounds of ours in the air, so their chunks are held and their speed kept. */
    private static final Set<Entity> FLYING = Collections.newSetFromMap(new IdentityHashMap<>());

    /** The chunks each round holds, and the level they belong to. */
    private static final Map<UUID, Path> PATHS = new HashMap<>();

    /** Share of the launcher's velocity handed to a bomb as it leaves. */
    private static final double BOMB_CARRIER_SHARE = 1.5D;

    /**
     * How much of a bomb's horizontal speed is given back each tick, undoing drag.
     *
     * <p>Raised sharply on request for every round except the high-drag one, whose whole
     * point is that it slows down: that family keeps the gentle figure.</p>
     */
    private static final double BOMB_DRAG_EASE = 1.02D;

    /** The high-drag family keeps the old, gentle easing. */
    private static final double HIGH_DRAG_DRAG_EASE = 1.003D;

    private static final double TICKING_AHEAD = 24.0D;
    private static final double LOADED_AHEAD = 160.0D;
    private static final double PROBE_STEP = 8.0D;
    private static final int TICKING_DISTANCE = 2;
    private static final int LOADED_DISTANCE = 0;

    private static final TicketType<UUID> TICKET =
            TicketType.create("firecontrolcompat_bomb", UUID::compareTo, 40);

    private static long lastReport;

    /** A rack that has just released, and the tick her round stops being waited for. */
    private static final class Pending {
        private final Level level;
        private final BindingRef rack;
        private final BlockPos pos;
        private final long until;
        private boolean claimed;

        private Pending(Level level, BindingRef rack, BlockPos pos, long until) {
            this.level = level;
            this.rack = rack;
            this.pos = pos;
            this.until = until;
        }
    }

    /** The chunks one round holds. */
    private static final class Path {
        private UUID owner;
        private ServerLevel level;
        private final Set<ChunkPos> ticking = new HashSet<>();
        private final Set<ChunkPos> loaded = new HashSet<>();
    }

    private BombRackCompat() {
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(NS, path);
    }

    private static Map<ResourceLocation, Spec> specs() {
        Map<ResourceLocation, Spec> map = new HashMap<>();
        // The plain bomb rack: the same launch 面包学 uses when the rack answers a
        // redstone signal, minus the signal - the computer presses the button.
        map.put(id("bombrack"), new Spec("航空炸弹", "BombgoProcedure", false, false));
        map.put(id("cluster_bombrack"), new Spec("集束炸弹", "ClusterBombgoProcedure", false, false));
        map.put(id("medium_bombrack"), new Spec("中型炸弹", "MediumBombgoProcedure", false, false));
        map.put(id("highresistancebombrack"), new Spec("高阻炸弹", "HighresistancebombgoProcedure", false, false));
        map.put(id("fire_bombrack"), new Spec("燃烧弹", "FireBombgoProcedure", false, false));
        map.put(id("gasbombrack"), new Spec("毒气弹", "GasbombgoProcedure", false, false));
        map.put(id("whitephosphorusbombrack"), new Spec("白磷弹", "WhitephosphorusbombgoProcedure", false, false));
        map.put(id("littilebombrack"), new Spec("小炸弹", "LittilebombgoProcedure", false, false));
        // The ones that steer to a point the rack carries.
        map.put(id("glidebombrack"), new Spec("滑翔炸弹", "Glide_bomb_goProcedure", true, false));
        map.put(id("jdam_2rack"), new Spec("JDAM", "Jdam2rackgoProcedure", true, false));
        map.put(id("earthpenetratorbombrack"), new Spec("钻地弹", "EarthpenetratorBombrackgoProcedure", true, false));
        map.put(id("jdam_1rack"), new Spec("JDAM", "Jdam1rackgoProcedure", false, false));
        // The composite rack, three rounds to a rack, one block state a round.
        map.put(id("mixedglidebomb_1"), new Spec("复合挂架·滑翔炸弹", "Mixed_glide_bomb_rack_goProcedure", true, false));
        map.put(id("mixedglidebomb_2"), new Spec("复合挂架·滑翔炸弹", "Mixed_glide_bomb_rack_goProcedure", true, false));
        map.put(id("mixedglidebomb_3"), new Spec("复合挂架·滑翔炸弹", "Mixed_glide_bomb_rack_goProcedure", true, false));
        map.put(id("mixedjdamrack_1"), new Spec("复合挂架·JDAM", "MixedjdamrackfireProcedure", false, false));
        map.put(id("mixedjdamrack_2"), new Spec("复合挂架·JDAM", "MixedjdamrackfireProcedure", false, false));
        map.put(id("mixedjdamrack_3"), new Spec("复合挂架·JDAM", "MixedjdamrackfireProcedure", false, false));
        map.put(id("mixedmediumbombrack_1"), new Spec("复合挂架·中型炸弹", "MixedmediumbombrackfireProcedure", false, false));
        map.put(id("mixedmediumbombrack_2"), new Spec("复合挂架·中型炸弹", "MixedmediumbombrackfireProcedure", false, false));
        map.put(id("mixedmediumbombrack_3"), new Spec("复合挂架·中型炸弹", "MixedmediumbombrackfireProcedure", false, false));
        // The nuclear rack has one launch and it answers a redstone signal only,
        // so the round is thrown here the way that launch throws it.
        map.put(id("nuclearbombrack"), new Spec("核弹", null, false, true));
        return Map.copyOf(map);
    }

    private static Set<ResourceLocation> bombIds() {
        return BOMBS;
    }

    /** Whether the block is one of 面包学's bomb racks, empty or loaded. */
    public static boolean isBombRack(BlockState state) {
        return state != null && isBombRackId(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
    }

    /** Whether the block in that binding is one of 面包学's bomb racks. */
    public static boolean isBombRack(Level level, BindingRef ref) {
        return isBombRack(stateAt(level, ref));
    }

    /**
     * The block entity a binding points at, asked of fire control first and of
     * the position itself when that refuses.
     *
     * <p>{@code BindingRef#resolve} insists the block there is still the one
     * that was recorded, and only forgives a change between two rack variants
     * it knows. A rack of ours loads and empties between families fire control
     * has no entry for - and on a welded structure the position may live in
     * the plot rather than in the level the computer was asked about - so the
     * position is read directly as well, which is the thing that actually
     * decides whether there is a rack to release.</p>
     */
    private static BlockEntity rackAt(Level level, BindingRef ref) {
        if (level == null || ref == null) {
            return null;
        }
        try {
            BlockEntity resolved = ref.resolve(level);
            if (resolved != null) {
                return resolved;
            }
        } catch (RuntimeException | LinkageError ignored) {
        }
        try {
            BlockEntity nearby = SubLevelPositions.blockEntity(level, ref.pos());
            if (nearby != null) {
                return nearby;
            }
        } catch (RuntimeException | LinkageError ignored) {
        }
        return rackInSubLevel(level, ref);
    }

    /**
     * The rack's own sub-level, asked for by the id the binding recorded.
     *
     * <p>A rack welded onto the structure the computer stands on lives in that
     * welded body's own plot, and looking for it in the level the computer was
     * asked about finds nothing there. The recorded sub-level id names the body
     * directly, so its plot is read instead.</p>
     */
    private static BlockEntity rackInSubLevel(Level level, BindingRef ref) {
        UUID id = ref.subLevelId();
        if (id == null) {
            return null;
        }
        try {
            SubLevel subLevel = SubLevelContainer.getContainer(level).getSubLevel(id);
            if (subLevel == null || subLevel.isRemoved() || subLevel.getLevel() == null) {
                return null;
            }
            return subLevel.getLevel().getBlockEntity(ref.pos());
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    /**
     * The block standing where a binding points, whether or not it has a block
     * entity.
     *
     * <p>Several of 面包学's bomb racks - the gas, white-phosphorus and nuclear
     * ones, and the composite medium / JDAM racks - are plain blocks with no
     * block entity at all. A lookup that starts from the entity therefore answers
     * "nothing here" for exactly the racks this mod exists to release, which is
     * why they were neither counted by the weapon selector nor let go. The block
     * state is what decides: it is read from the level the computer named, from
     * the level that actually holds the position, and from the sub-level the
     * binding recorded, in that order.</p>
     */
    private static BlockState stateAt(Level level, BindingRef ref) {
        if (level == null || ref == null) {
            return null;
        }
        try {
            BlockEntity blockEntity = rackAt(level, ref);
            if (blockEntity != null) {
                return blockEntity.getBlockState();
            }
        } catch (RuntimeException | LinkageError ignored) {
        }
        BlockPos pos = ref.pos();
        try {
            BlockState state = SubLevelPositions.blockState(level, pos);
            if (state != null && !state.isAir()) {
                return state;
            }
        } catch (RuntimeException | LinkageError ignored) {
        }
        UUID id = ref.subLevelId();
        if (id != null) {
            try {
                SubLevel subLevel = SubLevelContainer.getContainer(level).getSubLevel(id);
                if (subLevel != null && !subLevel.isRemoved() && subLevel.getLevel() != null) {
                    BlockState state = subLevel.getLevel().getBlockState(pos);
                    if (state != null && !state.isAir()) {
                        return state;
                    }
                }
            } catch (RuntimeException | LinkageError ignored) {
            }
        }
        return null;
    }

    /**
     * Names the racks a computer holds, for the release diagnostics: the block
     * each binding answers with now, whether or not fire control still counts
     * the binding as valid.
     */
    public static String describeRacks(Level level, List<BindingRef> racks) {
        if (racks == null || racks.isEmpty()) {
            return "-";
        }
        StringBuilder text = new StringBuilder();
        for (BindingRef rack : racks) {
            if (text.length() > 0) {
                text.append(',');
            }
            BlockEntity blockEntity = rackAt(level, rack);
            BlockState state = stateAt(level, rack);
            ResourceLocation id = state == null ? null : BuiltInRegistries.BLOCK.getKey(state.getBlock());
            // The block the binding was taken on, then the one standing there
            // now: a rack that was bound empty and loaded since reads
            // "rack>bombrack", and one that is gone altogether reads
            // "rack>unreadable".
            text.append(recordedBlock(rack)).append('>');
            text.append(id == null ? "unreadable" : id.getPath());
            text.append('@').append(rack.pos().toShortString());
            text.append('[').append(roundIn(blockEntity)).append(']');
        }
        return text.toString();
    }

    /**
     * The 4-tube laser rack of 面包学: empty it is the shared {@code mixedrack},
     * loaded it carries one block state per round, in two families - the plain
     * missile family and the laser family. Fire control knows neither, so both
     * are taught here.
     */
    private static final Set<ResourceLocation> LASER_RACKS = Set.of(
            id("mixedrack"),
            id("mixedracklasermissile_1"), id("mixedracklasermissile_2"),
            id("mixedracklasermissile_3"), id("mixedracklasermissile_4"),
            id("mixedracklasermissilesecond_1"), id("mixedracklasermissilesecond_2"),
            id("mixedracklasermissilesecond_3"), id("mixedracklasermissilesecond_4"));

    /** Whether a rack of that block id is one of ours, loaded or empty. */
    public static boolean isBombRackId(ResourceLocation blockId) {
        return blockId != null
                && ("mianbaos_modernwarfare".equals(blockId.getNamespace()))
                && (SPECS.containsKey(blockId) || EMPTY_RACKS.contains(blockId) || LASER_RACKS.contains(blockId));
    }

    /** Whether the block is a 4-tube laser rack, loaded or empty. */
    public static boolean isLaserRack(BlockState state) {
        return state != null && LASER_RACKS.contains(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
    }

    /** Whether a 4-tube laser rack of that id still has rounds on it. */
    public static boolean isLoadedLaserRackId(ResourceLocation blockId) {
        return blockId != null && LASER_RACKS.contains(blockId) && !id("mixedrack").equals(blockId);
    }

    /** Whether the 4-tube laser rack in that binding still has rounds on it. */
    public static boolean isLoadedLaserRack(Level level, BindingRef ref) {
        return isLoadedLaserRackId(blockId(level, ref));
    }

    /** Whether a rack of that block id carries something to release. */
    public static boolean isLoadedBombRackId(ResourceLocation blockId) {
        return blockId != null && SPECS.containsKey(blockId);
    }

    /** Whether the rack in that binding carries something to release. */
    public static boolean isLoadedBombRack(Level level, BindingRef ref) {
        return specAt(stateAt(level, ref)) != null;
    }

    /** Whether the rack's round steers to a place the computer has to write down. */
    public static boolean needsGroundPoint(Level level, BindingRef ref) {
        Spec spec = specAt(stateAt(level, ref));
        return spec != null && spec.groundPoint();
    }

    /** Whether the entity is one of 面包学's bombs. */
    public static boolean isBombEntity(Entity entity) {
        return entity != null && BOMBS.contains(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()));
    }

    /** The block a binding was taken on, named for the release diagnostics. */
    private static String recordedBlock(BindingRef rack) {
        try {
            CompoundTag saved = rack.save();
            String id = saved.contains("Block") ? saved.getString("Block") : "";
            if (id.isEmpty()) {
                return "?";
            }
            int colon = id.indexOf(':');
            return colon < 0 ? id : id.substring(colon + 1);
        } catch (RuntimeException | LinkageError ignored) {
            return "?";
        }
    }

    /** The round sitting in a rack, named for the release diagnostics. */
    private static String roundIn(BlockEntity blockEntity) {
        if (!(blockEntity instanceof Container container)) {
            return "-";
        }
        try {
            if (container.getContainerSize() <= 0) {
                return "-";
            }
            ItemStack stack = container.getItem(0);
            if (stack == null || stack.isEmpty()) {
                return "-";
            }
            ResourceLocation item = BuiltInRegistries.ITEM.getKey(stack.getItem());
            return item == null ? "?" : item.getPath() + "x" + stack.getCount();
        } catch (RuntimeException | LinkageError ignored) {
            return "?";
        }
    }

    private static ResourceLocation blockId(Level level, BindingRef ref) {
        BlockState state = stateAt(level, ref);
        return state == null ? null : BuiltInRegistries.BLOCK.getKey(state.getBlock());
    }

    /**
     * Loaded-form block ids mapped to the rack id this mod knows them by.
     *
     * <p>面包学 gives every bomb family two blocks: the empty rack ({@code …bombrack}) and
     * the one carrying its round, which has a name of its own - {@code …bombblock},
     * {@code bomb}, {@code fire_bomb}, {@code gasbomb}, {@code earthpenetratorbomb}. Only
     * the rack names were listed, so a rack that had been loaded stopped being seen as
     * carrying anything and could not be released - which is why the high-drag bomb was
     * the one that could not be dropped.</p>
     */
    private static final Map<String, String> LOADED_FORMS = Map.ofEntries(
            Map.entry("highresistancebombblock", "highresistancebombrack"),
            Map.entry("clusterbombblock", "cluster_bombrack"),
            Map.entry("glidebombblock", "glidebombrack"),
            Map.entry("bomb", "bombrack"),
            Map.entry("fire_bomb", "fire_bombrack"),
            Map.entry("gasbomb", "gasbombrack"),
            Map.entry("earthpenetratorbomb", "earthpenetratorbombrack"),
            Map.entry("whitephosphorusbombblock", "whitephosphorusbombrack"),
            Map.entry("mediumbombblock", "medium_bombrack"),
            Map.entry("littlebombblock", "littilebombrack"));

    /** The spec of a rack block, accepting the loaded form of its family as well. */
    private static Spec specOf(ResourceLocation rackId) {
        if (rackId == null) {
            return null;
        }
        Spec spec = SPECS.get(rackId);
        if (spec != null) {
            return spec;
        }
        String rack = LOADED_FORMS.get(rackId.getPath());
        return rack == null ? null : SPECS.get(id(rack));
    }

    private static Spec specAt(BlockState state) {
        if (state == null) {
            return null;
        }
        // A key of null is what an unregistered block answers, and the rack table
        // refuses null keys outright, so it is turned away before it is looked up.
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return key == null ? null : specOf(key);
    }

    /**
     * Releases the rack: the computer's ground point is written onto the rack
     * first, then the rack's own launch throws the round.
     */
    public static boolean fire(Level level, BindingRef ref) {
        if (level == null || ref == null) {
            return false;
        }
        BlockState state = stateAt(level, ref);
        if (isLaserRack(state)) {
            // The 4-tube rack is not one of the bomb racks below: its own launch
            // spawns 面包学's laser round and steps the rack's own state down, so
            // it is called rather than imitated. Fire control would otherwise
            // pick the single laser rack's procedure by weapon type and throw the
            // wrong round without ever emptying this rack.
            Level owner = SubLevelPositions.level(level, ref.pos());
            if (!(owner instanceof ServerLevel server)) {
                return false;
            }
            boolean released = runProcedure("MixedracklasermissilefireProcedure", server, ref.pos());
            FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] laser 4-rack launch rack={} released={}", ref.pos(), released);
            return released;
        }
        Spec spec = specAt(state);
        if (spec == null) {
            return false;
        }
        BlockPos pos = ref.pos();
        // 面包学's launch reads the block out of the level it is handed, so the
        // rack's own level is the one to pass on - on a welded structure that is
        // the plot, not the world the computer happens to name. Racks with no
        // block entity are the majority of 面包学's bomb family, so the state
        // decides what to release and the entity is only asked for the two jobs
        // that need one: writing a steering round's ground point, and reading
        // what sits in the rack.
        BlockEntity blockEntity = rackAt(level, ref);
        Level owner = blockEntity != null && blockEntity.getLevel() != null
                ? blockEntity.getLevel()
                : SubLevelPositions.level(level, pos);
        if (!(owner instanceof ServerLevel server)) {
            return false;
        }
        if (spec.groundPoint()) {
            if (blockEntity != null) {
                aimTheRack(server, pos, blockEntity);
            } else {
                FireControlCompat.LOGGER.info(
                        "[firecontrolcompat] bomb rack {} steers to a point but has no block entity to write it on",
                        pos);
            }
        }
        rememberPending(server, ref, pos);
        // A glide bomb is flown by fire control's mathematical mode from now on (see
        // MathGuidedMissiles), and that mode has to know which rack threw it: the bomb leaves through
        // 面包学's own procedure, which only copies the aim point onto the entity *after* it has
        // joined the level - and a round the mathematical mode captures never joins at all. The
        // marker is read inside the join event, on this same thread, so the aim point is handed over
        // here instead.
        boolean glide = spec.name() != null && spec.name().contains("滑翔");
        if (glide) {
            beginMathRelease(server, pos);
        }
        boolean thrown;
        try {
            thrown = spec.special()
                    ? launchNuclear(server, pos, state)
                    : runProcedure(spec.procedure(), server, pos);
        } finally {
            if (glide) {
                endMathRelease();
            }
        }
        if (!thrown) {
            forgetPending(pos);
            return false;
        }
        FireControlCompat.LOGGER.info("[firecontrolcompat] bomb rack released rack={} kind={}", pos, spec.name());
        return true;
    }

    /** Calls 面包学's own launch for the rack, which throws the round and empties the rack. */
    private static boolean runProcedure(String name, ServerLevel level, BlockPos pos) {
        if (name == null) {
            return false;
        }
        java.util.Optional<Method> method = PROCEDURES.computeIfAbsent(name, key -> {
            try {
                Class<?> type = Class.forName("net.mcreator.myfirstmod.procedures." + key);
                for (Method candidate : type.getMethods()) {
                    if ("execute".equals(candidate.getName()) && candidate.getParameterCount() == 4) {
                        return java.util.Optional.of(candidate);
                    }
                }
            } catch (LinkageError | ReflectiveOperationException ignored) {
            }
            return java.util.Optional.empty();
        });
        if (method.isEmpty()) {
            FireControlCompat.LOGGER.warn("[firecontrolcompat] bomb launch {} is missing", name);
            return false;
        }
        try {
            method.get().invoke(null, (LevelAccessor) level, (double) pos.getX(), (double) pos.getY(), (double) pos.getZ());
            return true;
        } catch (RuntimeException | LinkageError | ReflectiveOperationException error) {
            FireControlCompat.LOGGER.warn("[firecontrolcompat] bomb launch {} failed", name, error);
            return false;
        }
    }

    /** Throws the nuclear round the way its own redstone launch does. */
    private static boolean launchNuclear(ServerLevel level, BlockPos pos, BlockState state) {
        BlockState empty = emptyState(state);
        if (empty == null) {
            return false;
        }
        Vec3 muzzle = muzzleOf(level, pos, state);
        Entity round = BuiltInRegistries.ENTITY_TYPE.get(id("nuclearbombtanshe")).create(level);
        if (round == null) {
            return false;
        }
        round.setPos(muzzle.x, muzzle.y, muzzle.z);
        level.addFreshEntity(round);
        level.setBlock(pos, empty, 3);
        return true;
    }

    /** The empty rack a loaded rack turns into once its round is away. */
    private static BlockState emptyState(BlockState state) {
        ResourceLocation emptyId = isMixed(state) ? id("mixedbombrack") : id("rack");
        BlockState empty = BuiltInRegistries.BLOCK.get(emptyId).defaultBlockState();
        if (empty == null || empty.isAir()) {
            return null;
        }
        for (var property : state.getProperties()) {
            var other = empty.getBlock().getStateDefinition().getProperty(property.getName());
            if (other != null) {
                empty = copyProperty(empty, other, state.getValue(property));
            }
        }
        return empty;
    }

    /** Copies one property across, ignoring any the spare block does not take. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState copyProperty(BlockState target, Property property, Comparable value) {
        if (value != null && property.getValueClass().isInstance(value) && target.hasProperty(property)) {
            return target.setValue(property, value);
        }
        return target;
    }

    private static boolean isMixed(BlockState state) {
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return key != null && key.getPath().startsWith("mixed");
    }

    /** Where the round leaves the rack: under it, a little forward. */
    private static Vec3 muzzleOf(Level level, BlockPos pos, BlockState state) {
        if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING)) {
            net.minecraft.core.Direction facing = state.getValue(
                    net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING);
            return new Vec3(pos.getX() + 0.5D + facing.getStepX() * MUZZLE_FORWARD,
                    pos.getY() - 2.0D + facing.getStepY() * MUZZLE_FORWARD,
                    pos.getZ() + 0.5D + facing.getStepZ() * MUZZLE_FORWARD);
        }
        return new Vec3(pos.getX() + 0.5D, pos.getY() - 2.0D, pos.getZ() + 0.5D);
    }

    /**
     * Writes the ground point the computer holds onto the rack, which is where
     * the rack's own launch reads it from.
     */
    private static void aimTheRack(ServerLevel level, BlockPos pos, BlockEntity blockEntity) {
        Vec3 aim = groundPointOf(level, pos);
        if (aim == null) {
            return;
        }
        CompoundTag data = blockEntity.getPersistentData();
        data.putBoolean("坐标模式", true);
        data.putDouble("target_x", aim.x);
        data.putDouble("target_y", aim.y);
        data.putDouble("target_z", aim.z);
        blockEntity.setChanged();
    }

    /**
     * The point this rack should be released at: the strike point the terminal
     * left on the computer holding it, else the ground lock the computer holds.
     */
    /**
     * Writes the aim point onto a rail exactly the way a bomb rack gets it.
     *
     * <p>Shared so a cruise rail is aimed by the same code that feeds the glide bombs -
     * those lock and fly correctly, and their path is therefore the one known to work. A
     * hand-made writer with its own source and its own frame is what left the cruise round
     * with a point it could not fly to.</p>
     */
    public static boolean aimRackLikeABomb(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel server) || pos == null) {
            return false;
        }
        BlockEntity rack = server.getBlockEntity(pos);
        if (rack == null) {
            return false;
        }
        Vec3 aim = groundPointOf(server, pos);
        if (aim == null) {
            FireControlCompat.LOGGER.info("[firecontrolcompat] rail aim: no point held rail={}", pos);
            return false;
        }
        CompoundTag data = rack.getPersistentData();
        data.putBoolean("坐标模式", true);
        data.putDouble("target_x", aim.x);
        data.putDouble("target_y", aim.y);
        data.putDouble("target_z", aim.z);
        rack.setChanged();
        FireControlCompat.LOGGER.info("[firecontrolcompat] rail aim written rail={} point={}", pos, aim);
        return true;
    }

    public static Vec3 groundPointOf(ServerLevel level, BlockPos pos) {
        BlockEntity rack = level.getBlockEntity(pos);
        if (rack == null) {
            return null;
        }
        for (StabilizerControllerBlockEntity controller : ControllerTracker.loadedControllers()) {
            if (controller == null || controller.getLevel() == null) {
                continue;
            }
            for (BindingRef bound : controller.getAircraftMissileRacks()) {
                if (bound.resolve(controller.getLevel()) != rack && !bound.matches(level, pos)) {
                    continue;
                }
                Vec3 strike = StrikePoints.pointOf(controller);
                if (strike != null) {
                    return strike;
                }
                StabilizerControllerBlockEntity.GroundTargetSnapshot snapshot =
                        controller.getAirToGroundTargetSnapshot();
                return snapshot == null ? null : snapshot.center();
            }
        }
        return null;
    }

    /** Whether a computer holding a bomb rack has somewhere to send it. */
    public static boolean hasGroundPoint(StabilizerControllerBlockEntity controller) {
        if (controller == null) {
            return false;
        }
        if (StrikePoints.pointOf(controller) != null) {
            return true;
        }
        StabilizerControllerBlockEntity.GroundTargetSnapshot snapshot =
                controller.getAirToGroundTargetSnapshot();
        return snapshot != null && snapshot.center() != null;
    }

    /**
     * The name the air-to-ground line of the weapon HUD reads while bomb racks
     * are bound: the family of the first one, or the cruise name when the rack
     * beside it is a cruise rail, or null to leave the stock name alone.
     */
    public static String airToGroundSlotName(Level level, List<BindingRef> racks) {
        return airToGroundSlotName(level, racks, null);
    }

    /**
     * The name of the ground weapon line, named for the rack the trigger is on.
     *
     * <p>With the computer's position given, the name is the one kind of bomb its trigger
     * would release; without it, every kind found on the racks is named, which is what a
     * reader with no computer to hand can still make sense of.</p>
     */
    public static String airToGroundSlotName(Level level, List<BindingRef> racks, BlockPos controller) {
        if (level == null || racks == null || racks.isEmpty()) {
            return null;
        }
        try {
            if (controller != null) {
                List<BindingRef> chosen = selectedRacks(level, controller, racks);
                if (!chosen.isEmpty()) {
                    BindingRef selected = chosen.get(0);
                    if (CruiseMissileRackCompat.isCruiseRack(level, selected)) {
                        return CRUISE_LABEL;
                    }
                    Spec spec = specOf(blockId(level, selected));
                    if (spec != null) {
                        return spec.name();
                    }
                }
            }
            return airToGroundSlotNameOf(level, racks);
        } catch (RuntimeException | LinkageError failure) {
            // The name is read while the weapon is being cycled, so a rack that
            // cannot be read any more must not take the whole tick down with it.
            FireControlCompat.LOGGER.debug("[firecontrolcompat] bomb rack name lookup failed", failure);
            return null;
        }
    }

    private static String airToGroundSlotNameOf(Level level, List<BindingRef> racks) {
        boolean cruise = false;
        boolean ours = false;
        String name = null;
        java.util.LinkedHashSet<String> specNames = new java.util.LinkedHashSet<>();
        for (BindingRef rack : racks) {
            if (CruiseMissileRackCompat.isCruiseRack(level, rack)) {
                cruise = true;
                continue;
            }
            ResourceLocation rackId = blockId(level, rack);
            Spec spec = rackId == null ? null : SPECS.get(rackId);
            if (spec != null) {
                ours = true;
                if (name == null) {
                    name = spec.name();
                }
                continue;
            }
            if (isBombRack(level, rack)) {
                // An empty composite rack still counts as ours, and takes the
                // name of whatever is loaded beside it.
                ours = true;
                continue;
            }
            if (MianbaoAircraftCompat.weaponAt(level, rack)
                    == MianbaoAircraftCompat.WeaponType.AIR_TO_GROUND_DIRECT) {
                return null;
            }
        }
        if (cruise) {
            return "巡航导弹";
        }
        // Named for what is actually on the racks, and for every kind of it: a computer
        // holding a plain bomb rack and a medium one used to read as the single name of
        // whichever came first, so the two could not be told apart.
        if (specNames.size() > 1) {
            StringBuilder joined = new StringBuilder();
            for (String part : specNames) {
                if (joined.length() > 0) {
                    joined.append(" + ");
                }
                joined.append(part);
            }
            return joined.toString();
        }
        return ours ? name : null;
    }

    private static void rememberPending(ServerLevel level, BindingRef rack, BlockPos pos) {
        synchronized (PENDING) {
            PENDING.add(new Pending(level, rack, pos.immutable(), level.getGameTime() + PICKUP_TICKS));
        }
    }

    private static void forgetPending(BlockPos pos) {
        synchronized (PENDING) {
            PENDING.removeIf(pending -> pending.pos.equals(pos));
        }
    }

    /** Ran once a server tick: catches late rounds and keeps the ones in the air flying. */
    public static void tick() {
        collectLaunchedRounds();
        flyRounds();
        reportFlight();
    }

    private static void collectLaunchedRounds() {
        List<Pending> waiting;
        synchronized (PENDING) {
            if (PENDING.isEmpty()) {
                return;
            }
            waiting = List.copyOf(PENDING);
        }
        for (Pending pending : waiting) {
            Level level = pending.level;
            boolean spent = level == null;
            if (!spent) {
                boolean caught = claimNearest(level, pending);
                spent = caught || level.getGameTime() > pending.until;
            }
            if (spent) {
                synchronized (PENDING) {
                    PENDING.remove(pending);
                }
            }
        }
    }

    /** Takes the round sitting nearest a rack's muzzle as the one she threw. */
    private static boolean claimNearest(Level level, Pending pending) {
        Vec3 muzzle = Vec3.atCenterOf(pending.pos).add(0.0D, -MUZZLE_DROP, 0.0D);
        Entity nearest = null;
        double nearestDistance = MUZZLE_SEARCH * MUZZLE_SEARCH;
        for (Entity entity : level.getEntitiesOfClass(Entity.class, new AABB(muzzle, muzzle).inflate(MUZZLE_SEARCH))) {
            if (!isBombEntity(entity)) {
                continue;
            }
            double distance = entity.position().distanceToSqr(muzzle);
            if (distance <= nearestDistance) {
                nearestDistance = distance;
                nearest = entity;
            }
        }
        if (nearest == null) {
            return false;
        }
        pending.claimed = true;
        claim(nearest, pending.rack, "rack");
        return true;
    }

    private static final double MUZZLE_SEARCH = 8.0D;

    /**
     * Takes a round that has just come into the world as the one a rack threw,
     * and gives her the velocity of the body the rack is mounted on.
     */
    public static void adopt(Entity entity) {
        if (entity == null || !isBombEntity(entity)) {
            return;
        }
        if (entity.getPersistentData().getBoolean("firecontrolcompat:BombClaimed")) {
            return;
        }
        Vec3 at = entity.position();
        Pending matched = null;
        synchronized (PENDING) {
            for (Pending pending : PENDING) {
                if (pending.claimed) {
                    continue;
                }
                Vec3 muzzle = Vec3.atCenterOf(pending.pos).add(0.0D, -MUZZLE_DROP, 0.0D);
                if (at.distanceToSqr(muzzle) <= CLAIM_SEARCH * CLAIM_SEARCH) {
                    pending.claimed = true;
                    matched = pending;
                    break;
                }
            }
        }
        if (matched != null) {
            claim(entity, matched.rack, "rack-spawn");
        }
    }

    /**
     * Remembers the round as one of ours, and hands her the velocity of the
     * structure the rack rides on - a bomb let go from a moving ship carries on
     * over the ground the ship was making for.
     */
    private static void claim(Entity round, BindingRef rack, String how) {
        if (round == null) {
            return;
        }
        try {
            round.getPersistentData().putBoolean("firecontrolcompat:BombClaimed", true);
        } catch (Throwable ignored) {
        }
        inheritCarrierVelocity(round, rack);
        synchronized (FLYING) {
            FLYING.add(round);
        }
        FireControlCompat.LOGGER.info("[firecontrolcompat] bomb caught via {} type={} pos={}",
                how, BuiltInRegistries.ENTITY_TYPE.getKey(round.getType()), round.position());
    }

    /**
     * No longer adds the launching structure's velocity to the round.
     *
     * <p>Fire control 0.9.3 flies the bombs it drops itself, with the carrier's velocity folded in
     * by its own math flight, and the predicted impact ring is computed from that same flight. Adding
     * the structure's speed here as well made the round leave with twice the deck speed: the bomb
     * overshot the ring, and the ring was drawn for a flight the round was not taking. The round is
     * therefore left with whatever the pack gave it.</p>
     */
    private static void inheritCarrierVelocity(Entity round, BindingRef rack) {
        // Deliberately empty: see the note above.
    }

    /**
     * Says what actually came off a rack, by entity type.
     *
     * <p>Which round leaves is the mod's own launch procedure to decide, and a composite
     * rack may throw more than one kind; the entity types are the only honest answer to
     * "the trigger was on this kind, what flew?".</p>
     */
    private static void reportSpawned(Level level, BindingRef rack) {
        try {
            Vec3 centre = Vec3.atCenterOf(rack.pos());
            List<Entity> nearby = level.getEntities(
                    (Entity) null,
                    new net.minecraft.world.phys.AABB(centre, centre).inflate(10.0D),
                    entity -> entity != null && "mianbaos_modernwarfare".equals(
                            net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                                    .getKey(entity.getType()).getNamespace()));
            StringBuilder types = new StringBuilder();
            for (Entity entity : nearby) {
                if (types.length() > 0) {
                    types.append(", ");
                }
                types.append(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                        .getKey(entity.getType()).getPath());
            }
            FireControlCompat.LOGGER.info("[firecontrolcompat] spawned near rack={} count={} types=[{}]",
                    rack.pos(), nearby.size(), types);
        } catch (RuntimeException | LinkageError ignored) {
        }
    }

    /**
     * The glide rack whose release is in flight on this thread.
     *
     * <p>Read by {@link #claimMathRelease} inside the entity join event, which 面包学's release
     * procedure fires from the same thread between the two lines that write the aim point. A
     * thread-local is the whole hand-off: the server ticks on one thread, one release runs at a
     * time, and the value is cleared in a {@code finally} so a throw cannot leave it behind.</p>
     */
    private static final ThreadLocal<MathRelease> MATH_RELEASE = new ThreadLocal<>();

    /** One glide release: the level it happened in and the rack that threw the round. */
    private record MathRelease(ServerLevel level, BlockPos pos) {
    }

    /** Marks the release of this rack as one the mathematical flight mode may adopt. */
    private static void beginMathRelease(ServerLevel level, BlockPos pos) {
        MATH_RELEASE.set(new MathRelease(level, pos));
    }

    /** Ends the marker, whatever the release did. */
    private static void endMathRelease() {
        MATH_RELEASE.remove();
    }

    /**
     * The world-space point a bomb of ours is being released at, or null when this bomb is not ours.
     *
     * <p>Returns null for every bomb this mod did not throw - a bomb the pack released on its own
     * path is followed by {@code MianbaoBombProjectileHandler}, which is a complete drive of its
     * own, and two drivers for one bomb would step it twice.</p>
     *
     * <p>The aim point is copied onto the bomb <b>here</b> rather than by the pack, because the
     * pack's copy happens after the spawn and a bomb the mathematical mode captures never joins the
     * level, so the pack's search for it finds nothing. The coordinates are copied exactly as the
     * rack holds them; moving them into world space is the driver's job once it knows where the bomb
     * ended up, because a bomb that fire control declines to capture keeps flying in the rack's own
     * space and would be steered at a point twenty million blocks away if this converted early.</p>
     */
    public static Vec3 claimMathRelease(Level level, Entity bomb) {
        if (level == null || bomb == null) {
            return null;
        }
        MathRelease release = MATH_RELEASE.get();
        if (release == null || release.pos() == null) {
            return null;
        }
        try {
            BlockEntity rack = release.level() == null ? null : release.level().getBlockEntity(release.pos());
            if (rack == null) {
                rack = SubLevelPositions.blockEntity(level, release.pos());
            }
            if (rack == null) {
                return null;
            }
            Vec3 rackCentre = Vec3.atCenterOf(release.pos());
            if (bomb.position().distanceToSqr(rackCentre) > 64.0D) {
                // Not the round of this release: a bomb somebody else threw while we were releasing.
                return null;
            }
            CompoundTag data = rack.getPersistentData();
            if (!data.getBoolean("坐标模式")) {
                // No point was written on this rack, so there is nothing to glide to.
                return null;
            }
            Vec3 stored = new Vec3(data.getDouble("target_x"), data.getDouble("target_y"),
                    data.getDouble("target_z"));
            if (!Double.isFinite(stored.x) || !Double.isFinite(stored.y) || !Double.isFinite(stored.z)
                    || (Math.abs(stored.x) < 1.0E-6 && Math.abs(stored.y) < 1.0E-6
                            && Math.abs(stored.z) < 1.0E-6)) {
                return null;
            }
            CompoundTag tag = bomb.getPersistentData();
            tag.putBoolean("坐标模式", true);
            tag.putDouble("target_x", stored.x);
            tag.putDouble("target_y", stored.y);
            tag.putDouble("target_z", stored.z);
            tag.remove("firecontrolcompat:math_bomb_world");
            FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] math-guided: bomb aim rack={} target={} bomb={}",
                    release.pos().toShortString(), stored, bomb.getType());
            return stored;
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    /** Whether the round is the high-drag bomb, the one family meant to slow down. */
    private static boolean isHighDrag(Entity round) {        ResourceLocation type = BuiltInRegistries.ENTITY_TYPE.getKey(round.getType());
        return type != null && type.getPath().contains("highresistance");
    }

    /** Loaded form -> the empty rack of the same family, for swapping a round out. */
    private static String emptyFormOf(String path) {
        for (Map.Entry<String, String> entry : LOADED_FORMS.entrySet()) {
            if (entry.getValue().equals(path)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * Clears a loaded rack so a different round can be put on it in one click.
     *
     * <p>面包学 loads a rack only while it is empty, so changing the round on it meant
     * releasing the one already there first - the round was gone and the crew had to fly
     * another pass to swap a bomb for a different bomb. Right-clicking a loaded rack with
     * a round in hand now takes the old round off it (into the player's own hands in
     * survival) and leaves the rack empty, which is all the pack's own loading step needs
     * to put the held round on instead.</p>
     *
     * @return true when the rack was cleared and the caller should let the loading run
     */
    public static boolean swapLoadedRound(LevelAccessor world, BlockPos pos, Entity player) {
        if (!(world instanceof Level level) || pos == null || player == null) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        ResourceLocation rackId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        Spec spec = specOf(rackId);
        if (spec == null || rackId == null) {
            return false;
        }
        ItemStack held = player instanceof net.minecraft.world.entity.LivingEntity living
                ? living.getMainHandItem() : ItemStack.EMPTY;
        ResourceLocation heldId = held.isEmpty()
                ? null : BuiltInRegistries.ITEM.getKey(held.getItem());
        if (heldId == null || !"mianbaos_modernwarfare".equals(heldId.getNamespace())
                || !heldId.getPath().contains("bomb")) {
            // Nothing in hand to change it for: leave the rack as it is.
            return false;
        }
        String empty = emptyFormOf(rackId.getPath());
        if (empty == null || empty.equals(rackId.getPath())) {
            return false;
        }
        // Hand the round back before the rack loses it. Creative players keep nothing.
        boolean creative = player instanceof net.minecraft.world.entity.player.Player owner
                && owner.isCreative();
        if (!creative && player instanceof net.minecraft.world.entity.player.Player owner) {
            ItemStack back = new ItemStack(rackItemOf(rackId));
            if (!back.isEmpty() && !owner.getInventory().add(back)) {
                owner.drop(back, false);
            }
        }
        level.setBlockAndUpdate(pos, BuiltInRegistries.BLOCK
                .get(ResourceLocation.fromNamespaceAndPath(rackId.getNamespace(), empty))
                .defaultBlockState());
        FireControlCompat.LOGGER.info("[firecontrolcompat] rack {} emptied for a swap to {}",
                rackId.getPath(), heldId.getPath());
        return true;
    }

    /** The item a rack block stands for, so a swapped-out round can be given back. */
    private static Item rackItemOf(ResourceLocation rackId) {
        String path = rackId.getPath();
        String item = path.startsWith("highresistance") ? "highresistancebomb"
                : path.startsWith("cluster") ? "clusterbomb"
                : path.startsWith("glide") ? "glidebomb"
                : path.startsWith("earthpenetrator") ? "earthpenetratorbomb"
                : path.startsWith("whitephosphorus") ? "whitephosphorusbomb"
                : path.startsWith("medium") ? "mediumbomb"
                : path.startsWith("littile") || path.startsWith("little") ? "littlebomb"
                : path.startsWith("fire") ? "fire_bomb"
                : path.startsWith("gas") ? "gasbomb"
                : "bomb";
        return BuiltInRegistries.ITEM.get(
                ResourceLocation.fromNamespaceAndPath(rackId.getNamespace(), item));
    }

    /** Keeps every round of ours in the air: its chunks held, its own tick carried forward. */
    private static void flyRounds() {
        List<Entity> flying;
        synchronized (FLYING) {
            if (FLYING.isEmpty()) {
                return;
            }
            flying = new ArrayList<>(FLYING);
        }
        for (Entity round : flying) {
            // Drag eased off: the rounds ran out of horizontal speed sooner than a crew
            // expects, so each tick of flight gives a large part of it back - except for
            // the high-drag family, whose whole purpose is to slow down.
            Vec3 motion = round.getDeltaMovement();
            if (motion.y < 0.0D && motion.horizontalDistanceSqr() > 1.0E-6D) {
                double ease = isHighDrag(round) ? HIGH_DRAG_DRAG_EASE : BOMB_DRAG_EASE;
                round.setDeltaMovement(motion.x * ease, motion.y, motion.z * ease);
            }
            if (round == null || round.isRemoved() || !round.isAlive()) {
                synchronized (FLYING) {
                    FLYING.remove(round);
                }
                release(round);
                continue;
            }
            keepLoaded(round);
            MianbaoMissileCompat.advanceIfNativeTickMissed(round);
        }
    }

    private static void keepLoaded(Entity round) {
        try {
            ServerLevel root = MianbaoMissileCompat.getRootServerLevel(round);
            if (root == null) {
                return;
            }
            UUID owner = round.getUUID();
            Path path = PATHS.computeIfAbsent(owner, key -> new Path());
            if (path.level != root) {
                release(path);
                path.level = root;
            }
            path.owner = owner;
            Vec3 position = MianbaoMissileCompat.getWorldPosition(round);
            Vec3 velocity = MianbaoMissileCompat.getWorldVelocity(round);
            Set<ChunkPos> ticking = new HashSet<>();
            ahead(ticking, position, velocity, TICKING_AHEAD);
            Set<ChunkPos> loaded = new HashSet<>();
            ahead(loaded, position, velocity, LOADED_AHEAD);
            hold(root, path.ticking, ticking, TICKING_DISTANCE, owner);
            hold(root, path.loaded, loaded, LOADED_DISTANCE, owner);
        } catch (Throwable ignored) {
        }
    }

    private static void ahead(Set<ChunkPos> destination, Vec3 from, Vec3 velocity, double reach) {
        destination.add(chunkOf(from.x, from.z));
        double speed = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        if (!(speed > 1.0E-4D) || !(reach > 0.0D)) {
            return;
        }
        double stepX = velocity.x / speed * PROBE_STEP;
        double stepZ = velocity.z / speed * PROBE_STEP;
        double x = from.x;
        double z = from.z;
        for (double travelled = 0.0D; travelled < reach; travelled += PROBE_STEP) {
            x += stepX;
            z += stepZ;
            destination.add(chunkOf(x, z));
        }
    }

    private static ChunkPos chunkOf(double x, double z) {
        return new ChunkPos(Mth.floor(x) >> 4, Mth.floor(z) >> 4);
    }

    private static void hold(ServerLevel root, Set<ChunkPos> current, Set<ChunkPos> wanted,
            int distance, UUID owner) {
        for (ChunkPos chunk : current) {
            if (!wanted.contains(chunk)) {
                root.getChunkSource().removeRegionTicket(TICKET, chunk, distance, owner, true);
            }
        }
        for (ChunkPos chunk : wanted) {
            if (!current.contains(chunk)) {
                root.getChunkSource().addRegionTicket(TICKET, chunk, distance, owner, true);
            }
        }
        current.clear();
        current.addAll(wanted);
    }

    private static void release(Entity round) {
        if (round == null) {
            return;
        }
        release(PATHS.remove(round.getUUID()));
    }

    private static void release(Path path) {
        if (path == null || path.level == null || path.owner == null) {
            return;
        }
        try {
            for (ChunkPos chunk : path.ticking) {
                path.level.getChunkSource().removeRegionTicket(TICKET, chunk, TICKING_DISTANCE, path.owner, true);
            }
            for (ChunkPos chunk : path.loaded) {
                path.level.getChunkSource().removeRegionTicket(TICKET, chunk, LOADED_DISTANCE, path.owner, true);
            }
        } catch (Throwable ignored) {
        }
        path.ticking.clear();
        path.loaded.clear();
        path.level = null;
    }

    /** Says once a second what this mod has falling off a bomb rack. */
    private static void reportFlight() {
        long now = System.currentTimeMillis();
        if (now - lastReport < 1000L) {
            return;
        }
        lastReport = now;
        int rounds = 0;
        StringBuilder line = new StringBuilder();
        synchronized (FLYING) {
            for (Entity round : FLYING) {
                if (round == null || round.isRemoved()) {
                    continue;
                }
                rounds++;
                if (rounds > 3) {
                    continue;
                }
                Vec3 world = MianbaoMissileCompat.getWorldPosition(round);
                Vec3 velocity = MianbaoMissileCompat.getWorldVelocity(round);
                if (rounds > 1) {
                    line.append(' ');
                }
                line.append(BuiltInRegistries.ENTITY_TYPE.getKey(round.getType()).getPath())
                        .append('(').append((int) world.x).append(',').append((int) world.y)
                        .append(',').append((int) world.z)
                        .append(" age=").append(round.tickCount)
                        .append(" speed=").append(Math.round(velocity.length() * 100.0D) / 100.0D)
                        .append(')');
            }
        }
        if (rounds > 0) {
            FireControlCompat.LOGGER.info("[firecontrolcompat] bomb flight rounds={} {}", rounds, line);
        }
    }
}


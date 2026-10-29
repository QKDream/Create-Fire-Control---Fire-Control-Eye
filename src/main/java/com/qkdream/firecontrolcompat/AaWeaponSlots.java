package com.qkdream.firecontrolcompat;

import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.content.controller.FireControlWeaponInputs;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The round kind behind one of fire control's two anti-air weapon slots.
 *
 * <h2>The fault: four rounds, two slots</h2>
 *
 * <p>0.9.4 offers exactly three ground weapon slots and they are named after the <em>launcher
 * family</em>, not after the round:
 * {@code FireControlWeaponInputs.GroundWeapon = {ANTI_TANK, AIR_DEFENSE, HIGH_SPEED_AIR_DEFENSE}}
 * and the gate that decides whether a slot may release a rail is one line
 * ({@code GroundWeapon#acceptsAaRound}):</p>
 *
 * <pre>
 *   acceptsAaRound(ordinary) == (this == (ordinary ? AIR_DEFENSE : HIGH_SPEED_AIR_DEFENSE))
 * </pre>
 *
 * <p>where {@code ordinary} is "slot zero of that rail holds fire control's own
 * {@code portableantiairmissile}". Every other anti-air round this pack or this mod can load is
 * therefore <b>not ordinary</b> and answers to the same single slot:</p>
 *
 * <ul>
 *   <li>面包学's own high-speed {@code portableantiairmissile_2} (every 2.6.5 launcher),</li>
 *   <li>this mod's {@code infrared_missile} (loaded into an AA launcher's slot zero),</li>
 *   <li>this mod's vertical-silo {@code heavy_air_defense_missile} (counted in {@code fcheavy}),</li>
 *   <li>this mod's placed {@code neva_launcher} block (one round, always ready).</li>
 * </ul>
 *
 * <p>So selecting "high-speed air defence" fired whichever of those four the cursor reached
 * first: the heavy silo was released by a key press meant for the high-speed racks and vice
 * versa, which is the user's "重型防空与高速防空被当成同一个槽". The anti-tank round never had the
 * problem only because {@code ANTI_TANK} is a slot of its own.</p>
 *
 * <h2>What this class does about it</h2>
 *
 * <p>The enum is the pack's and cannot grow, so the <b>key</b> keeps selecting the family and this
 * class remembers <b>which kind of round inside that family</b> the operator wants. Pressing the
 * family's key again walks the kinds that are actually loaded; the firing walk then only ever
 * releases a rail of that kind (see {@code AaFireGateMixin#firecontrolcompat$fireSelectedKind}).
 * The four rounds therefore never displace each other:</p>
 *
 * <pre>
 *   ANTI_TANK slot            (key of its own)  - untouched by this class
 *   AIR_DEFENSE slot          -> NORMAL         (fire control's own portableantiairmissile)
 *   HIGH_SPEED_AIR_DEFENSE    -> HIGH_SPEED -> INFRARED -> HEAVY -> NEVA -> (back)
 * </pre>
 *
 * <p>Only the kinds that have rounds loaded appear in the walk, so a computer with one heavy silo
 * bound cycles straight to {@code heavy} instead of through four empty entries. The selection is
 * stored in the computer's own persistent data, so it survives a chunk unload and a restart, and it
 * is written back by {@code AaFireGateMixin} whenever the operator changes it.</p>
 */
public final class AaWeaponSlots {

    /** Persistent-data key of the selected kind (name of {@link Kind}). */
    public static final String KIND_KEY = "firecontrolcompat:aa_kind";

    /** How long a label produced here stays usable, in milliseconds. */
    private static final long LABEL_MEMORY_MS = 10000L;

    /** The round kinds this mod can tell apart, in the order the high-speed key walks them. */
    public enum Kind {
        /** Fire control's own {@code portableantiairmissile} - the pack's "ordinary" round. */
        NORMAL("普通防空导弹"),
        /** 面包学's {@code portableantiairmissile_2} - the pack's own high-speed round. */
        HIGH_SPEED("高速防空导弹"),
        /** This mod's infrared round loaded into an AA launcher. */
        INFRARED("红外锁定导弹"),
        /** This mod's heavy 48N6 round in the vertical silo. */
        HEAVY("重型防空导弹"),
        /** This mod's super-heavy 涅瓦 round, which is the placed block itself. */
        NEVA("涅瓦河导弹");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        /** {@code normal|high_speed|infrared|heavy|neva}, as it appears in the logs. */
        public String id() {
            return this.name().toLowerCase(Locale.ROOT);
        }

        /** Name shown in the weapon slot, so the operator can see what the key selected. */
        public String label() {
            return this.label;
        }

        /** The pack's ground weapon slot this kind answers to. */
        public FireControlWeaponInputs.GroundWeapon family() {
            return this == NORMAL
                    ? FireControlWeaponInputs.GroundWeapon.AIR_DEFENSE
                    : FireControlWeaponInputs.GroundWeapon.HIGH_SPEED_AIR_DEFENSE;
        }

        /** Whether the pack's {@code ordinary} flag should be set for a rail of this kind. */
        public boolean ordinary() {
            return this == NORMAL;
        }

        /**
         * Word the fire-interval table understands, so a heavy rail is paced like a heavy rail
         * even though its ammunition lives in persistent data instead of a container slot.
         */
        public String cadenceWord() {
            return this == HEAVY || this == NEVA ? "heavy" : "";
        }

        /** The kinds a family's key walks, in order. */
        public static Kind[] ladder(FireControlWeaponInputs.GroundWeapon family) {
            if (family == FireControlWeaponInputs.GroundWeapon.AIR_DEFENSE) {
                return new Kind[]{NORMAL};
            }
            return new Kind[]{HIGH_SPEED, INFRARED, HEAVY, NEVA};
        }

        /**
         * Every anti-air kind in the order the <b>ground</b> key walks them, whatever family the
         * pack's folder put them in.
         *
         * <p>The family split ({@code AIR_DEFENSE} for the pack's ordinary round, {@code
         * HIGH_SPEED_AIR_DEFENSE} for everything else) is the pack's folder, not the operator's
         * choice: 0.9.4 lumps 面包学's own {@code portableantiairmissile_2}, this mod's infrared
         * round, the 48N6 in the vertical silo and the placed 涅瓦 block into one slot, and it
         * decides which of them fires purely by the order the cursor happens to walk the rails.
         * That is the reported "the heavy and the ordinary round displace each other - one has to be
         * emptied before the other can be used". A ground key press therefore walks this one ladder
         * across <em>all</em> anti-air kinds, so 普通 ↔ 重型 ↔ 涅瓦 ↔ 红外 are all one key press
         * apart, and the firing walk is then filtered down to the selected kind.</p>
         */
        public static Kind[] antiAirLadder() {
            return new Kind[]{NORMAL, HIGH_SPEED, INFRARED, HEAVY, NEVA};
        }

        /** Parses a remembered name; null when it is not a kind. */
        public static Kind parse(String name) {
            if (name == null || name.isBlank()) {
                return null;
            }
            for (Kind kind : values()) {
                if (kind.name().equals(name)) {
                    return kind;
                }
            }
            return null;
        }
    }

    private AaWeaponSlots() {
    }

    // ------------------------------------------------------------------ classification

    /**
     * Which kind of round a bound anti-air rail is holding, or {@code null} when it is not a rail
     * this class has any business classifying (a silo with native cruise rounds in it, an empty
     * container, a block that is not an anti-air launcher at all).
     *
     * <p>Read from the same places the ammo count is read from, so the two can never disagree:
     * a silo's {@code fcheavy} counter, the placed 涅瓦 block, then the launcher's own container
     * slot zero - which is where 面包学 2.6.5 keeps both its own round and this mod's infrared one
     * (mianbao's {@code AntiairmissilelauncherreloadProcedure} writes
     * {@code setStackInSlot(0, new ItemStack(PORTABLEANTIAIRMISSILE_2, n))}).</p>
     */
    public static Kind kindOf(Level level, BindingRef ref) {
        if (level == null || ref == null) {
            return null;
        }
        try {
            BlockEntity blockEntity = ref.resolve(level);
            if (blockEntity == null) {
                blockEntity = ref.resolveRebuiltAirDefenseLauncher(level);
            }
            return kindOf(blockEntity);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** As {@link #kindOf(Level, BindingRef)}, from an already resolved block entity. */
    public static Kind kindOf(BlockEntity blockEntity) {
        if (blockEntity == null) {
            return null;
        }
        try {
            BlockState state = blockEntity.getBlockState();
            if (HeavyAirDefenseCompat.isSiloHead(state)) {
                // A silo loaded with 面包学's own cruise rounds is not an anti-air rail at all.
                CompoundTag data = blockEntity.getPersistentData();
                return data.getDouble(HeavyAirDefenseCompat.HEAVY_AA_AMMO_KEY) > 0.0
                        ? Kind.HEAVY : null;
            }
            if (NevaLaunchCompat.isNevaLauncher(state)) {
                return Kind.NEVA;
            }
            if (!(blockEntity instanceof Container container) || container.getContainerSize() <= 0) {
                return null;
            }
            ItemStack stack = container.getItem(0);
            if (stack == null || stack.isEmpty()) {
                return null;
            }
            String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
            if ("portableantiairmissile".equals(path)) {
                return Kind.NORMAL;
            }
            if ("infrared_missile".equals(path)) {
                return Kind.INFRARED;
            }
            if ("portableantiairmissile_2".equals(path) || path.startsWith("antiairmissile")) {
                return Kind.HIGH_SPEED;
            }
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ------------------------------------------------------------------ per-computer selection

    /** The kind this computer last selected, or {@code null} when it has never chosen one. */
    public static Kind selected(StabilizerControllerBlockEntity controller) {
        if (controller == null) {
            return null;
        }
        try {
            return Kind.parse(controller.getPersistentData().getString(KIND_KEY));
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Records the kind on the computer itself, so it outlives an unload and a restart. */
    public static void select(StabilizerControllerBlockEntity controller, Kind kind) {
        if (controller == null || kind == null) {
            return;
        }
        try {
            controller.getPersistentData().putString(KIND_KEY, kind.name());
            controller.setChanged();
        } catch (Throwable ignored) {
            // the selection is a convenience; the pack's own slot still decides the family
        }
        remember(kind);
    }

    /** Every kind of the given family that has at least one loaded rail, in ladder order. */
    public static List<Kind> loaded(Level level, List<BindingRef> launchers,
            FireControlWeaponInputs.GroundWeapon family) {
        Set<Kind> found = new LinkedHashSet<>();
        for (Kind kind : Kind.ladder(family)) {
            for (BindingRef ref : launchers) {
                if (kindOf(level, ref) == kind && ammo(level, ref) > 0) {
                    found.add(kind);
                    break;
                }
            }
        }
        return new ArrayList<>(found);
    }

    /**
     * Every anti-air kind that has at least one loaded rail, in {@link Kind#antiAirLadder()} order.
     *
     * <p>Deliberately <b>not</b> filtered by the family of the key that was pressed: that filter is
     * what made the ground selection unable to tell the heavy silo from the high-speed racks (see
     * {@link Kind#antiAirLadder()}).</p>
     */
    public static List<Kind> loadedAntiAir(Level level, List<BindingRef> launchers) {
        Set<Kind> found = new LinkedHashSet<>();
        for (Kind kind : Kind.antiAirLadder()) {
            for (BindingRef ref : launchers) {
                if (kindOf(level, ref) == kind && ammo(level, ref) > 0) {
                    found.add(kind);
                    break;
                }
            }
        }
        return new ArrayList<>(found);
    }

    /** {@code heavy=6,normal=24}, for the selection line: what the rails actually hold. */
    public static String ammoSummary(Level level, List<BindingRef> launchers, List<Kind> kinds) {
        StringBuilder text = new StringBuilder();
        if (kinds != null) {
            for (Kind kind : kinds) {
                if (text.length() > 0) {
                    text.append(',');
                }
                text.append(kind.id()).append('=').append(ammoOfKind(level, launchers, kind));
            }
        }
        return text.length() == 0 ? "-" : text.toString();
    }

    /** Rounds on the rails of one kind, summed. */
    public static int ammoOfKind(Level level, List<BindingRef> launchers, Kind kind) {
        int total = 0;
        for (BindingRef ref : launchers) {
            if (kindOf(level, ref) == kind) {
                total += Math.max(0, ammo(level, ref));
            }
        }
        return total;
    }

    /** The kind the given family's key should select, given what the operator already has. */
    public static Kind next(FireControlWeaponInputs.GroundWeapon family, Kind current,
            List<Kind> loadedKinds) {
        if (loadedKinds == null || loadedKinds.isEmpty()) {
            return null;
        }
        if (current == null || !loadedKinds.contains(current)) {
            return loadedKinds.get(0);
        }
        int index = loadedKinds.indexOf(current);
        return loadedKinds.get((index + 1) % loadedKinds.size());
    }

    /** The first loaded kind of the family, for a fresh selection or a reselect. */
    public static Kind first(FireControlWeaponInputs.GroundWeapon family, List<Kind> loadedKinds) {
        return loadedKinds == null || loadedKinds.isEmpty() ? null : loadedKinds.get(0);
    }

    private static int ammo(Level level, BindingRef ref) {
        try {
            return com.hooya.stabilizedturret.content.controller.MianbaoAirDefenseCompat
                    .getAmmoCount(level, ref);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ label memory

    private static Kind recent;
    private static long recentAt;
    private static String lastLabelLogged = "";

    /** Remembers a kind for the weapon-slot label, which has no block entity to ask. */
    public static void remember(Kind kind) {
        if (kind == null) {
            return;
        }
        recent = kind;
        recentAt = System.currentTimeMillis();
    }

    /**
     * The last kind selected anywhere, or {@code null} when that was longer than
     * {@link #LABEL_MEMORY_MS} ago.
     *
     * <p>Needed because {@code GroundWeapon#displayName()} is a method on the enum: it has no
     * computer, no launcher and no payload to ask, exactly like the anti-tank line before it. The
     * window is deliberately generous; a stale name for a few seconds is a smaller fault than a row
     * that snaps back to the pack's wording the moment the input page is opened.</p>
     */
    public static Kind recent() {
        if (recent == null || System.currentTimeMillis() - recentAt > LABEL_MEMORY_MS) {
            return null;
        }
        return recent;
    }

    /** One line per label change, so "the slot never renamed itself" is visible. */
    public static void logLabel(String slot, Kind kind, String source, String stock) {
        String row = slot + "/" + source + "/" + (kind == null ? "-" : kind.id());
        if (row.equals(lastLabelLogged)) {
            return;
        }
        lastLabelLogged = row;
        FireControlCompat.LOGGER.info(
                "[firecontrolcompat] weapon slot: kind={} type={} name={} src={} stock={}",
                kind == null ? "unknown" : kind.id(),
                kind == null ? "-" : kind.family().name(),
                kind == null ? "-" : kind.label(),
                source, stock);
    }
}

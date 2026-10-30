package com.qkdream.firecontrolcompat;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.hooya.stabilizedturret.content.controller.ControllerTracker;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * The lead settings of every fire control computer, keyed by the computer itself.
 *
 * <p>{@link ControllerLeadState} writes them into the computer's own block entity
 * tag first, which is what makes them travel with the block. That tag, however,
 * is only written to disk when the chunk holding the computer is saved, and a
 * computer standing on a Sable structure is not saved through the ordinary chunk
 * path. A pair of settings that survives until the world is reloaded is a pair
 * the crew has to type in again every session, so the same two values are also
 * filed here, in the world's saved data, under the platform and position of the
 * computer that owns them.</p>
 *
 * <p>The key is the computer's own position <em>and</em> the structure it stands
 * on, never the structure alone: two computers on one ship are two different
 * guns and must keep two different answers. Nothing is shared between computers
 * here, and there is no world-wide value at all - the mod-level default in
 * {@link FireControlLeadSettings} is only read for a computer that has never
 * been set.</p>
 */
public final class ControllerLeadStore extends SavedData {

    public static final String DATA_ID = "firecontrolcompat_controller_leads";

    /** This store's name in the {@code bind-store} and {@code migrate} lines. */
    private static final String STORE = BindStoreMirror.CONTROLLER_LEAD;
    /** Keys of the pair inside the copy that rides on the computer. */
    private static final String MIRROR_KIND = "kind";
    private static final String MIRROR_CHARGE = "charge";
    /** Whether that half was ever set, so "unset" is not mistaken for "empty string" / "0". */
    private static final String MIRROR_KIND_SET = "kindSet";
    private static final String MIRROR_CHARGE_SET = "chargeSet";

    /** Computer key -> cannon family name. */
    private final Map<String, String> kinds = new HashMap<>();
    /** Computer key -> big cannon powder charge. */
    private final Map<String, Integer> charges = new HashMap<>();

    public static ControllerLeadStore load(CompoundTag tag, HolderLookup.Provider registries) {
        ControllerLeadStore store = new ControllerLeadStore();
        CompoundTag storedKinds = tag.getCompound("kinds");
        for (String key : storedKinds.getAllKeys()) {
            String value = storedKinds.getString(key);
            if (!value.isEmpty()) {
                store.kinds.put(key, value);
            }
        }
        CompoundTag storedCharges = tag.getCompound("charges");
        for (String key : storedCharges.getAllKeys()) {
            store.charges.put(key, storedCharges.getInt(key));
        }
        return store;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag storedKinds = new CompoundTag();
        this.kinds.forEach(storedKinds::putString);
        tag.put("kinds", storedKinds);
        CompoundTag storedCharges = new CompoundTag();
        this.charges.forEach(storedCharges::putInt);
        tag.put("charges", storedCharges);
        return tag;
    }

    /**
     * Identity of one computer: the platform it stands on plus its own position.
     *
     * <p>Built from the values a settings page already carries, so a write can be
     * filed even while the computer itself is not loaded.</p>
     */
    public static String keyOf(Level level, BlockPos pos, UUID subLevel) {
        if (pos == null) {
            return "";
        }
        String dimension = level == null ? "unknown" : level.dimension().location().toString();
        String platform = subLevel == null ? "p:" + dimension : "s:" + subLevel;
        return platform + ":" + pos.asLong();
    }

    /** Identity of one computer, taken from the computer itself. */
    public static String keyOf(Level level, BlockEntity controller) {
        if (controller == null) {
            return "";
        }
        UUID subLevel = null;
        try {
            if (controller instanceof StabilizerControllerBlockEntity stabilizer) {
                subLevel = ControllerTracker.controllerSubLevelId(stabilizer);
            }
        } catch (Throwable ignored) {
            subLevel = null;
        }
        return keyOf(level, controller.getBlockPos(), subLevel);
    }

    /** One computer's pair as it was read, either half possibly unset. */
    private record Filed(String kind, int charge) {
    }

    /** Family filed for this computer, or null while it has none. */
    public static LeadCannonKind kindOf(Level level, String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        try {
            Filed filed = resolve(level, holderOf(level, key), key);
            if (filed == null) {
                return null;
            }
            String name = filed.kind();
            return name == null || name.isEmpty() ? null : LeadCannonKind.valueOf(name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Powder charge filed for this computer, or -1 while it has none. */
    public static int chargeOf(Level level, String key) {
        if (key == null || key.isEmpty()) {
            return -1;
        }
        try {
            Filed filed = resolve(level, holderOf(level, key), key);
            return filed == null ? -1 : filed.charge();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /**
     * Family filed for this computer, read through the computer itself.
     *
     * <p>This is the overload the block entity paths use: it knows <em>which</em> holder to repair
     * from, so a computer that has just been copied gets its pair filed back into the index on the
     * first read instead of the crew having to set the sight up again.</p>
     */
    public static LeadCannonKind kindOf(Level level, BlockEntity controller) {
        if (controller == null) {
            return null;
        }
        try {
            Filed filed = resolve(level, controller, keyOf(level, controller));
            if (filed == null) {
                return null;
            }
            String name = filed.kind();
            return name == null || name.isEmpty() ? null : LeadCannonKind.valueOf(name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Powder charge filed for this computer, read through the computer itself. */
    public static int chargeOf(Level level, BlockEntity controller) {
        if (controller == null) {
            return -1;
        }
        try {
            Filed filed = resolve(level, controller, keyOf(level, controller));
            return filed == null ? -1 : filed.charge();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /**
     * Files one computer's pair. A null family or a negative charge leaves that
     * half of the pair alone, so setting one never clears the other.
     *
     * <p>The pair is written on the computer as well whenever the computer can be
     * found from the key, so it rides along with a blueprint copy; the key-only
     * callers (a settings page whose computer is not loaded) are still filed, and
     * their copy is written the first time the computer is read.</p>
     */
    public static void put(Level level, String key, LeadCannonKind kind, int charge) {
        if (key == null || key.isEmpty()) {
            return;
        }
        try {
            ControllerLeadStore store = storeOf(level);
            if (store == null) {
                return;
            }
            boolean changed = false;
            if (kind != null && !kind.name().equals(store.kinds.get(key))) {
                store.kinds.put(key, kind.name());
                changed = true;
            }
            if (charge >= 0 && !Integer.valueOf(charge).equals(store.charges.get(key))) {
                store.charges.put(key, charge);
                changed = true;
            }
            if (changed) {
                store.setDirty();
            }
            // Written on the computer as well, so the pair rides along with a blueprint copy.
            BlockEntity holder = holderOf(level, key);
            if (holder != null) {
                mirror(holder, kind, charge);
            }
            FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] lead-store put key={} kind={} charge={} entries={}",
                    key, store.kinds.get(key), store.charges.get(key), store.kinds.size());
        } catch (Throwable ignored) {
        }
    }

    /**
     * Files the pair on the computer itself, so a copy carries it.
     *
     * <p>The family and the charge are one record here rather than two, and either
     * half may be left alone ({@code kind == null}, {@code charge < 0}): the half
     * that is left alone is taken from the index, which is always written first, so
     * setting one never clears the other in the copy either.</p>
     *
     * @return whether the computer carries the pair afterwards
     */
    public static boolean mirror(BlockEntity controller, LeadCannonKind kind, int charge) {
        if (controller == null) {
            return false;
        }
        try {
            Level level = controller.getLevel();
            String key = keyOf(level, controller);
            ControllerLeadStore store = storeOf(level);
            String filedKind = kind == null ? null : kind.name();
            int filedCharge = charge;
            if (store != null) {
                if (filedKind == null) {
                    filedKind = store.kinds.get(key);
                }
                if (filedCharge < 0) {
                    Integer indexed = store.charges.get(key);
                    filedCharge = indexed == null ? -1 : indexed;
                }
            }
            return file(controller, filedKind, filedCharge);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** The pair filed on the computer itself, or {@code null} when it carries none. */
    private static Filed mirrored(BlockEntity controller) {
        if (controller == null) {
            return null;
        }
        try {
            CompoundTag payload = BindStoreMirror.copy(controller.getPersistentData(), STORE);
            if (payload == null) {
                return null;
            }
            String kind = payload.getBoolean(MIRROR_KIND_SET) ? payload.getString(MIRROR_KIND) : null;
            int charge = payload.getBoolean(MIRROR_CHARGE_SET) ? payload.getInt(MIRROR_CHARGE) : -1;
            if (kind == null && charge < 0) {
                return null;
            }
            return new Filed(kind, charge);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Writes the pair on the computer and reports it; either half may be unset. */
    private static boolean file(BlockEntity controller, String kind, int charge) {
        try {
            if (kind == null && charge < 0) {
                return true;
            }
            CompoundTag holder = controller.getPersistentData();
            CompoundTag payload = new CompoundTag();
            payload.putBoolean(MIRROR_KIND_SET, kind != null);
            if (kind != null) {
                payload.putString(MIRROR_KIND, kind);
            }
            payload.putBoolean(MIRROR_CHARGE_SET, charge >= 0);
            if (charge >= 0) {
                payload.putInt(MIRROR_CHARGE, charge);
            }
            if (!BindStoreMirror.file(holder, STORE, payload, controller.getBlockPos())) {
                return false;
            }
            controller.setChanged();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Whether the computer already carries its own copy, so it is never written twice. */
    private static boolean hasMirror(BlockEntity controller) {
        try {
            return controller != null && BindStoreMirror.has(controller.getPersistentData(), STORE);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * The pair of one computer, repairing whichever side is missing.
     *
     * <p>Three cases, in this order: the index has the pair and the computer does not
     * (a world bound before the copies existed - the copy is written now); neither
     * has it (nothing filed); the computer has the copy and the index does not (a
     * computer that has just been copied - the index is rebuilt under its new key).
     * The holder may be {@code null} when only a key is known, and then the index is
     * read as it stands.</p>
     */
    private static Filed resolve(Level level, BlockEntity controller, String key) {
        ControllerLeadStore store = storeOf(level);
        if (store == null || key == null || key.isEmpty()) {
            return null;
        }
        sweep(level);
        String kind = store.kinds.get(key);
        Integer charge = store.charges.get(key);
        if (kind != null || charge != null) {
            boolean repaired = controller != null && !hasMirror(controller)
                    && mirror(controller, null, -1);
            if (controller != null) {
                BindStoreMirror.logLoad(STORE, controller.getBlockPos(), repaired, true);
            }
            if (repaired) {
                BindStoreMirror.logMigrateFromTable(STORE, 1);
            }
            return new Filed(kind, charge == null ? -1 : charge);
        }
        Filed copied = mirrored(controller);
        if (copied == null) {
            if (controller != null) {
                BindStoreMirror.logLoad(STORE, controller.getBlockPos(), false, false);
            }
            return null;
        }
        if (copied.kind() != null) {
            store.kinds.put(key, copied.kind());
        }
        if (copied.charge() >= 0) {
            store.charges.put(key, copied.charge());
        }
        store.setDirty();
        if (controller != null) {
            BindStoreMirror.logLoad(STORE, controller.getBlockPos(), true, true);
            BindStoreMirror.logMigrateFromCopy(STORE, 1);
        }
        return copied;
    }

    /**
     * Files a copy on every loaded computer that has a pair filed but not on itself.
     *
     * <p>Run once per store: it is the repair an old world needs, so a blueprint saved without the
     * settings page ever being opened still carries the pair. The per-computer path in
     * {@link #resolve} repairs anything this sweep missed.</p>
     */
    private static void sweep(Level level) {
        if (!BindStoreMirror.needsSweep(STORE)) {
            return;
        }
        try {
            ServerLevel server = IffRadar.serverLevelOf(level);
            if (server == null) {
                return;
            }
            int seen = 0;
            int entries = 0;
            for (StabilizerControllerBlockEntity controller : ControllerTracker.loadedControllers()) {
                if (controller == null || controller.isRemoved()) {
                    continue;
                }
                seen++;
                try {
                    Level at = controller.getLevel() == null ? server : controller.getLevel();
                    String key = keyOf(at, controller);
                    ControllerLeadStore store = storeOf(level);
                    if (store == null) {
                        return;
                    }
                    boolean filed = store.kinds.containsKey(key) || store.charges.containsKey(key);
                    if (!filed || hasMirror(controller)) {
                        continue;
                    }
                    if (mirror(controller, null, -1)) {
                        entries++;
                    }
                } catch (Throwable ignored) {
                    // one unreadable computer must not cost the others their copy
                }
            }
            if (seen > 0) {
                BindStoreMirror.sweepDone(STORE);
            }
            if (entries > 0) {
                BindStoreMirror.logMigrateFromTable(STORE, entries);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * The computer a key names, when the key was built from a world position.
     *
     * <p>A key is either {@code s:<sub-level>:<pos>} or {@code p:<dimension>:<pos>}.
     * Only the second names a block the level itself can hand back: the position of
     * the first is in the sub-level's own plot space, so a computer standing on a
     * ship is reached through {@link #kindOf(Level, BlockEntity)} instead.</p>
     */
    private static BlockEntity holderOf(Level level, String key) {
        if (level == null || key == null || !key.startsWith("p:")) {
            return null;
        }
        try {
            int split = key.lastIndexOf(':');
            if (split < 0 || split + 1 >= key.length()) {
                return null;
            }
            BlockPos pos = BlockPos.of(Long.parseLong(key.substring(split + 1)));
            return level.getBlockEntity(pos);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static ControllerLeadStore storeOf(Level level) {
        ServerLevel server = IffRadar.serverLevelOf(level);
        if (server == null) {
            return null;
        }
        MinecraftServer minecraftServer = server.getServer();
        ServerLevel overworld = minecraftServer == null ? server : minecraftServer.overworld();
        return overworld.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(ControllerLeadStore::new, ControllerLeadStore::load, null), DATA_ID);
    }
}

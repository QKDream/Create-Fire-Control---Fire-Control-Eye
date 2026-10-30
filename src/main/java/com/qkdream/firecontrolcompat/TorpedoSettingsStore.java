package com.qkdream.firecontrolcompat;

import com.hooya.stabilizedturret.content.controller.ControllerTracker;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * The swim a fire control computer files for the acoustic torpedoes its tubes
 * fire: whether an idle round patrols on the surface or waits at a depth, how
 * deep that depth is, and how far a round swims before its hydrophones come on.
 *
 * <p>The three values belong to one computer and to every round it fires, so
 * they are filed under that computer's own identity (see
 * {@link RadarNameStore#keyOf}) the way the beam riding launch mode is. Two
 * boats can therefore run their rounds differently: one holds hers deep and
 * deaf for a hundred blocks, the next lets hers run along the surface with
 * their ears on from the muzzle.</p>
 *
 * <p>A tube wired to no computer keeps the plain values, which is what the
 * settings page used to hold for the whole world.</p>
 */
public final class TorpedoSettingsStore extends SavedData {

    public static final String DATA_ID = "firecontrolcompat_torpedo_settings";

    /** This store's name in the {@code bind-store} and {@code migrate} lines. */
    private static final String STORE = BindStoreMirror.TORPEDO_SETTINGS;
    /** Keys of the three values inside the copy that rides on the computer. */
    private static final String MIRROR_HOLD = "hold";
    private static final String MIRROR_DEPTH = "depth";
    private static final String MIRROR_ACTIVATION = "activation";

    /** Owner key -> the three values. A computer with nothing filed runs plain. */
    private final Map<String, Entry> entries = new HashMap<>();

    /** One computer's three values. */
    public record Entry(boolean holdDepth, double depth, double activation) {
    }

    public static TorpedoSettingsStore load(CompoundTag tag, HolderLookup.Provider registries) {
        TorpedoSettingsStore store = new TorpedoSettingsStore();
        CompoundTag stored = tag.getCompound("settings");
        for (String key : stored.getAllKeys()) {
            CompoundTag entry = stored.getCompound(key);
            store.entries.put(key, new Entry(
                    entry.getBoolean("hold"),
                    FireControlLeadSettings.clampTorpedoDepth(entry.getDouble("depth")),
                    FireControlLeadSettings.clampTorpedoActivation(entry.getDouble("activation"))));
        }
        return store;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag stored = new CompoundTag();
        this.entries.forEach((key, entry) -> {
            CompoundTag one = new CompoundTag();
            one.putBoolean("hold", entry.holdDepth());
            one.putDouble("depth", entry.depth());
            one.putDouble("activation", entry.activation());
            stored.put(key, one);
        });
        tag.put("settings", stored);
        return tag;
    }

    /**
     * What the computer hands to the rounds its tubes fire.
     *
     * <p>Ownership was checked against the wiring: the three values are filed under
     * {@link RadarNameStore#keyOf} - the identity of the <em>computer</em>, not of the sonar set -
     * and the sonar page only ever asks for them through the computer it was opened on. The copy
     * therefore rides on the computer's block entity, like the call sign and the launch mode.</p>
     */
    public static Entry of(Level level, StabilizerControllerBlockEntity controller) {
        if (controller == null) {
            return plain();
        }
        try {
            sweep(level);
            String key = RadarNameStore.keyOf(level, controller);
            Entry filed = table(level).get(key);
            if (filed != null) {
                // A world bound before the copy existed: file the three values on the computer
                // now, so they travel with the block from here on. Once per computer.
                boolean repaired = !hasMirror(controller) && mirror(controller, filed);
                BindStoreMirror.logLoad(STORE, controller.getBlockPos(), repaired, true);
                if (repaired) {
                    BindStoreMirror.logMigrateFromTable(STORE, 1);
                }
                return filed;
            }
            return rebuilt(level, controller, key);
        } catch (Throwable ignored) {
            return plain();
        }
    }

    /**
     * Rebuilds the table entry of a computer that has just been copied.
     *
     * <p>A printed computer arrives under a new identity, so the table has nothing under its new
     * key while the copy of the three values is sitting on the block entity that was printed with
     * it. They are read back off the computer and filed under the new key, which is what makes a
     * printed boat's rounds hold the same depth without the crew setting it again.</p>
     */
    private static Entry rebuilt(Level level, StabilizerControllerBlockEntity controller, String key) {
        Entry mirrored = mirrored(controller);
        if (mirrored == null) {
            return plain();
        }
        try {
            ServerLevel server = IffRadar.serverLevelOf(level);
            if (server == null) {
                return plain();
            }
            TorpedoSettingsStore store = store(server);
            store.entries.put(key, mirrored);
            store.setDirty();
            BindStoreMirror.logLoad(STORE, controller.getBlockPos(), true, true);
            BindStoreMirror.logMigrateFromCopy(STORE, 1);
            return mirrored;
        } catch (Throwable ignored) {
            return plain();
        }
    }

    /**
     * Files the three values on the computer itself; {@code null} drops the copy.
     *
     * @return whether the computer carries the values afterwards
     */
    public static boolean mirror(BlockEntity controller, Entry entry) {
        if (controller == null) {
            return false;
        }
        try {
            CompoundTag holder = controller.getPersistentData();
            if (entry == null) {
                if (BindStoreMirror.drop(holder, STORE)) {
                    controller.setChanged();
                }
                return true;
            }
            CompoundTag payload = new CompoundTag();
            payload.putBoolean(MIRROR_HOLD, entry.holdDepth());
            payload.putDouble(MIRROR_DEPTH, entry.depth());
            payload.putDouble(MIRROR_ACTIVATION, entry.activation());
            if (!BindStoreMirror.file(holder, STORE, payload, controller.getBlockPos())) {
                return false;
            }
            controller.setChanged();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** The three values filed on the computer itself, or {@code null} when it carries none. */
    public static Entry mirrored(BlockEntity controller) {
        if (controller == null) {
            return null;
        }
        try {
            CompoundTag payload = BindStoreMirror.copy(controller.getPersistentData(), STORE);
            if (payload == null || !payload.contains(MIRROR_DEPTH)) {
                return null;
            }
            return new Entry(
                    payload.getBoolean(MIRROR_HOLD),
                    FireControlLeadSettings.clampTorpedoDepth(payload.getDouble(MIRROR_DEPTH)),
                    FireControlLeadSettings.clampTorpedoActivation(payload.getDouble(MIRROR_ACTIVATION)));
        } catch (Throwable ignored) {
            return null;
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
     * Files a copy on every loaded computer that has the three values filed but not on itself.
     *
     * <p>Run once per store: it is the repair an old world needs, so a blueprint saved without the
     * sonar page ever being opened still carries the swim. The per-computer path in {@link #of}
     * repairs anything this sweep missed.</p>
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
            TorpedoSettingsStore store = store(server);
            for (StabilizerControllerBlockEntity controller : ControllerTracker.loadedControllers()) {
                if (controller == null || controller.isRemoved()) {
                    continue;
                }
                seen++;
                try {
                    Level at = controller.getLevel() == null ? server : controller.getLevel();
                    Entry filed = store.entries.get(RadarNameStore.keyOf(at, controller));
                    if (filed == null || hasMirror(controller)) {
                        continue;
                    }
                    if (mirror(controller, filed)) {
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

    /** The values a launcher that answers to no computer runs on. */
    public static Entry plain() {
        return new Entry(
                FireControlLeadSettings.torpedoHoldDepth(),
                FireControlLeadSettings.torpedoDepth(),
                FireControlLeadSettings.torpedoActivationDistance());
    }

    /** Files an edit from the screen; false when the computer is gone. */
    public static boolean set(
            ServerPlayer player,
            BlockPos pos,
            UUID subLevel,
            boolean holdDepth,
            double depth,
            double activation) {
        if (player == null || pos == null) {
            return false;
        }
        StabilizerControllerBlockEntity controller =
                ControllerTracker.findLoadedForSettings(player, pos, subLevel);
        if (controller == null) {
            return false;
        }
        ServerLevel level = player.serverLevel();
        TorpedoSettingsStore store = store(level);
        Entry entry = new Entry(
                holdDepth,
                FireControlLeadSettings.clampTorpedoDepth(depth),
                FireControlLeadSettings.clampTorpedoActivation(activation));
        store.entries.put(RadarNameStore.keyOf(level, controller), entry);
        // Written on the computer as well, so the swim rides along with a blueprint copy.
        mirror(controller, entry);
        store.setDirty();
        return true;
    }

    private static Map<String, Entry> table(Level level) {
        TorpedoSettingsStore store = storeOf(level);
        return store == null ? Map.of() : store.entries;
    }

    private static TorpedoSettingsStore storeOf(Level level) {
        ServerLevel server = IffRadar.serverLevelOf(level);
        return server == null ? null : store(server);
    }

    private static TorpedoSettingsStore store(ServerLevel level) {
        MinecraftServer server = level.getServer();
        ServerLevel overworld = server == null ? level : server.overworld();
        return overworld.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(TorpedoSettingsStore::new, TorpedoSettingsStore::load, null), DATA_ID);
    }
}

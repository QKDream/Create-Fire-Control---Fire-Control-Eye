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

    /** What the computer hands to the rounds its tubes fire. */
    public static Entry of(Level level, StabilizerControllerBlockEntity controller) {
        if (controller == null) {
            return plain();
        }
        try {
            Entry entry = table(level).get(RadarNameStore.keyOf(level, controller));
            return entry == null ? plain() : entry;
        } catch (Throwable ignored) {
            return plain();
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
        store.entries.put(RadarNameStore.keyOf(level, controller), new Entry(
                holdDepth,
                FireControlLeadSettings.clampTorpedoDepth(depth),
                FireControlLeadSettings.clampTorpedoActivation(activation)));
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

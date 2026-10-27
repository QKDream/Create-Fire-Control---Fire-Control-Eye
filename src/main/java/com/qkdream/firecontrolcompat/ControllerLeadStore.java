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

    /** Family filed for this computer, or null while it has none. */
    public static LeadCannonKind kindOf(Level level, String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        try {
            ControllerLeadStore store = storeOf(level);
            if (store == null) {
                return null;
            }
            String name = store.kinds.get(key);
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
            ControllerLeadStore store = storeOf(level);
            if (store == null) {
                return -1;
            }
            Integer charge = store.charges.get(key);
            return charge == null ? -1 : charge;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /**
     * Files one computer's pair. A null family or a negative charge leaves that
     * half of the pair alone, so setting one never clears the other.
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
            FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] lead-store put key={} kind={} charge={} entries={}",
                    key, store.kinds.get(key), store.charges.get(key), store.kinds.size());
        } catch (Throwable ignored) {
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

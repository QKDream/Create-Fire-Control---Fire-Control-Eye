package com.qkdream.firecontrolcompat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * The target a gunner has called a fire control operator onto.
 *
 * <p>A mark belongs to one operator rather than to the world: the scope is
 * pointed at the crew that should take the shot, so a structure called onto one
 * of them leaves every other operator alone. Marking replaces whatever that
 * operator was on before, because a designation is an order and the last one
 * given is the one that stands; the player's own last mark is kept as well, so
 * a click at empty sky can take it back from every operator it went to.</p>
 */
public final class SpyglassMarkStore extends SavedData {

    private static final String DATA_ID = "firecontrolcompat_spyglass_marks";
    private static final String MARKED_TAG = "Marks";
    private static final String LAST_TAG = "Last";
    private static final String MAID_KEY = "Maid";
    private static final String STRUCTURE_KEY = "Structure";
    private static final String PLAYER_KEY = "Player";

    /** Operator -> the structure she has been called onto. */
    private final Map<UUID, UUID> marks = new HashMap<>();

    /** Player -> the structure their last mark went to, so it can be taken back. */
    private final Map<UUID, UUID> lastMark = new HashMap<>();

    public static SpyglassMarkStore get(Level level) {
        ServerLevel server = IffRadar.serverLevelOf(level);
        if (server == null || server.getServer() == null) {
            return null;
        }
        ServerLevel overworld = server.getServer().overworld();
        return overworld.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(SpyglassMarkStore::new, SpyglassMarkStore::load, null), DATA_ID);
    }

    /** Whether that operator has been called onto that structure. */
    public static boolean isMarked(Level level, UUID maidId, UUID structureId) {
        if (maidId == null || structureId == null) {
            return false;
        }
        SpyglassMarkStore store = get(level);
        return store != null && structureId.equals(store.marks.get(maidId));
    }

    /** Calls those operators onto a structure, replacing whatever each was on. */
    public static boolean mark(Level level, ServerPlayer player, UUID structureId, List<UUID> maids) {
        SpyglassMarkStore store = get(level);
        if (store == null || player == null || structureId == null || maids == null || maids.isEmpty()) {
            return false;
        }
        for (UUID maid : maids) {
            store.marks.put(maid, structureId);
        }
        store.lastMark.put(player.getUUID(), structureId);
        store.setDirty();
        return true;
    }

    /** Takes the mark of that player back, from every operator it went to. */
    public static boolean clearLast(Level level, ServerPlayer player) {
        SpyglassMarkStore store = get(level);
        if (store == null || player == null) {
            return false;
        }
        UUID last = store.lastMark.remove(player.getUUID());
        if (last == null) {
            return false;
        }
        store.marks.values().removeIf(last::equals);
        store.setDirty();
        return true;
    }

    /** Drops whatever that operator was called onto; used when she is unlinked. */
    public static void clearMaid(Level level, UUID maidId) {
        SpyglassMarkStore store = get(level);
        if (store == null || maidId == null) {
            return;
        }
        if (store.marks.remove(maidId) != null) {
            store.setDirty();
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag pairs = new ListTag();
        this.marks.forEach((maid, structure) -> {
            CompoundTag entry = new CompoundTag();
            entry.putUUID(MAID_KEY, maid);
            entry.putUUID(STRUCTURE_KEY, structure);
            pairs.add(entry);
        });
        tag.put(MARKED_TAG, pairs);
        ListTag last = new ListTag();
        this.lastMark.forEach((player, structure) -> {
            CompoundTag entry = new CompoundTag();
            entry.putUUID(PLAYER_KEY, player);
            entry.putUUID(STRUCTURE_KEY, structure);
            last.add(entry);
        });
        tag.put(LAST_TAG, last);
        return tag;
    }

    public static SpyglassMarkStore load(CompoundTag tag, HolderLookup.Provider registries) {
        SpyglassMarkStore store = new SpyglassMarkStore();
        ListTag pairs = tag.getList(MARKED_TAG, Tag.TAG_COMPOUND);
        for (int index = 0; index < pairs.size(); index++) {
            CompoundTag entry = pairs.getCompound(index);
            if (entry.hasUUID(MAID_KEY) && entry.hasUUID(STRUCTURE_KEY)) {
                store.marks.put(entry.getUUID(MAID_KEY), entry.getUUID(STRUCTURE_KEY));
            }
        }
        ListTag last = tag.getList(LAST_TAG, Tag.TAG_COMPOUND);
        for (int index = 0; index < last.size(); index++) {
            CompoundTag entry = last.getCompound(index);
            if (entry.hasUUID(PLAYER_KEY) && entry.hasUUID(STRUCTURE_KEY)) {
                store.lastMark.put(entry.getUUID(PLAYER_KEY), entry.getUUID(STRUCTURE_KEY));
            }
        }
        return store;
    }
}
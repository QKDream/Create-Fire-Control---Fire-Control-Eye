package com.qkdream.firecontrolcompat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
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
 *
 * <h2>Where the copy of a mark lives, and where it cannot</h2>
 *
 * <p>This is the one store of the five that has <b>no block entity owner</b>, and
 * that is a property of what a mark is: the key is an <em>operator's</em> UUID and
 * the value is the UUID of the <em>target's</em> Sable structure. Neither is a
 * block, and neither is inside the other - the target is usually somebody else's
 * ship. So a blueprint of a hull carries no mark for the same reason it carries no
 * operator: the operator is an entity, and the structure a mark names is created
 * when a blueprint is printed, not carried by it. There is nothing to remap either:
 * {@code SableBlueprintMapperRegistry} hands out one ref per <em>block</em>, and a
 * mark has no block of its own to hook.</p>
 *
 * <p>What is left, and what is done here, is the closest holder there is: the
 * operator's own entity, whose persistent data is written to her NBT by the same
 * path every other per-entity datum takes (see {@link BindStoreMirror}). A mark
 * therefore survives anything that carries the entity - a saved-and-loaded maid, a
 * copy of her NBT, a structure/vehicle that keeps her - and is rebuilt into the
 * index the first time she is asked about after arriving without one. A mark whose
 * <em>target</em> has been reprinted names a body that no longer exists and is
 * dropped by the engagement check, which is correct: the order was for that hull.</p>
 */
public final class SpyglassMarkStore extends SavedData {

    private static final String DATA_ID = "firecontrolcompat_spyglass_marks";
    private static final String MARKED_TAG = "Marks";
    private static final String LAST_TAG = "Last";
    private static final String MAID_KEY = "Maid";
    private static final String STRUCTURE_KEY = "Structure";
    private static final String PLAYER_KEY = "Player";

    /** This store's name in the {@code bind-store} and {@code migrate} lines. */
    private static final String STORE = BindStoreMirror.SPYGLASS_MARK;

    /** Operators already looked at and found to carry no copy, so it is not looked up again. */
    private static final Set<UUID> WITHOUT_COPY = ConcurrentHashMap.newKeySet();
    /** How many such operators are remembered before the set is dropped. */
    private static final int WITHOUT_COPY_LIMIT = 256;

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

    /**
     * Whether that operator has been called onto that structure.
     *
     * <p>The index is asked first; a mark that is there has its copy refreshed on the
     * operator if it went missing (a world bound before the copies existed). An
     * operator with nothing on file is asked for her own copy, and the index is
     * rebuilt from it - which is what makes a mark come back with the operator
     * instead of having to be given again.</p>
     */
    public static boolean isMarked(Level level, UUID maidId, UUID structureId) {
        if (maidId == null || structureId == null) {
            return false;
        }
        SpyglassMarkStore store = get(level);
        if (store == null) {
            return false;
        }
        try {
            UUID filed = store.marks.get(maidId);
            if (filed != null) {
                Entity operator = operatorOf(level, maidId);
                if (operator != null && !BindStoreMirror.has(operator.getPersistentData(), STORE)
                        && mirror(operator, maidId, filed)) {
                    BindStoreMirror.logLoad(STORE, operator.blockPosition(), false, true);
                    BindStoreMirror.logMigrateFromTable(STORE, 1);
                }
                BindStoreMirror.logLoad(STORE,
                        operator == null ? null : operator.blockPosition(), false, true);
                return structureId.equals(filed);
            }
            UUID restored = rebuilt(level, store, maidId);
            return structureId.equals(restored);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Rebuilds the index entry of an operator who arrived carrying a mark but no entry. */
    private static UUID rebuilt(Level level, SpyglassMarkStore store, UUID maidId) {
        if (WITHOUT_COPY.contains(maidId)) {
            return null;
        }
        Entity operator = operatorOf(level, maidId);
        if (operator == null) {
            // She is not loaded, so her own copy cannot be asked for yet; nothing is
            // remembered either, because a later tick may well find her.
            return null;
        }
        UUID restored = mirrored(operator);
        if (restored == null) {
            remember(maidId);
            BindStoreMirror.logLoad(STORE, operator.blockPosition(), false, false);
            return null;
        }
        store.marks.put(maidId, restored);
        store.setDirty();
        BindStoreMirror.logLoad(STORE, operator.blockPosition(), true, true);
        BindStoreMirror.logMigrateFromCopy(STORE, 1);
        return restored;
    }

    /** Calls those operators onto a structure, replacing whatever each was on. */
    public static boolean mark(Level level, ServerPlayer player, UUID structureId, List<UUID> maids) {
        SpyglassMarkStore store = get(level);
        if (store == null || player == null || structureId == null || maids == null || maids.isEmpty()) {
            return false;
        }
        for (UUID maid : maids) {
            store.marks.put(maid, structureId);
            WITHOUT_COPY.remove(maid);
            // Written on the operator as well, so the order rides along with her.
            Entity operator = operatorOf(level, maid);
            if (operator != null) {
                mirror(operator, maid, structureId);
            }
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
        // Collected first: the copies are dropped while the index is walked, and a map
        // must not be changed from inside its own iterator.
        List<UUID> cleared = new ArrayList<>(8);
        for (Map.Entry<UUID, UUID> entry : store.marks.entrySet()) {
            if (last.equals(entry.getValue())) {
                cleared.add(entry.getKey());
            }
        }
        for (UUID maid : cleared) {
            store.marks.remove(maid);
            WITHOUT_COPY.remove(maid);
            Entity operator = operatorOf(level, maid);
            if (operator != null) {
                mirror(operator, maid, null);
            }
        }
        store.setDirty();
        return true;
    }

    /** Drops whatever that operator was called onto; used when she is unlinked. */
    public static void clearMaid(Level level, UUID maidId) {
        SpyglassMarkStore store = get(level);
        if (store == null || maidId == null) {
            return;
        }
        UUID dropped = store.marks.remove(maidId);
        WITHOUT_COPY.remove(maidId);
        Entity operator = operatorOf(level, maidId);
        if (operator != null) {
            mirror(operator, maidId, null);
        }
        if (dropped != null) {
            store.setDirty();
        }
    }

    /**
     * Files the mark on the operator herself; {@code null} drops it.
     *
     * <p>This is the copy that travels with her (see the class comment for why she, and
     * not a block entity, is the holder of a mark).</p>
     *
     * @return whether the operator carries the mark afterwards
     */
    private static boolean mirror(Entity operator, UUID maidId, UUID structureId) {
        if (operator == null) {
            return false;
        }
        try {
            CompoundTag holder = operator.getPersistentData();
            if (structureId == null) {
                // An entity has no dirty flag to raise: her NBT is written from this data
                // whenever she is saved, so dropping the tag is the whole job.
                BindStoreMirror.drop(holder, STORE);
                return true;
            }
            CompoundTag payload = new CompoundTag();
            payload.putUUID(STRUCTURE_KEY, structureId);
            return BindStoreMirror.file(holder, STORE, payload, operator.blockPosition(),
                    "maid=" + maidId);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** The mark filed on the operator herself, or {@code null} when she carries none. */
    private static UUID mirrored(Entity operator) {
        if (operator == null) {
            return null;
        }
        try {
            CompoundTag payload = BindStoreMirror.copy(operator.getPersistentData(), STORE);
            if (payload == null || !payload.hasUUID(STRUCTURE_KEY)) {
                return null;
            }
            return payload.getUUID(STRUCTURE_KEY);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Remembers an operator who carries no copy, so the lookup is not repeated every tick. */
    private static void remember(UUID maidId) {
        try {
            if (WITHOUT_COPY.size() > WITHOUT_COPY_LIMIT) {
                WITHOUT_COPY.clear();
            }
            WITHOUT_COPY.add(maidId);
        } catch (Throwable ignored) {
            // a cache that cannot be written is simply not a cache
        }
    }

    /**
     * The operator's entity, wherever she is standing.
     *
     * <p>Every server level is asked, not just the one the caller holds: a maid on a ship
     * lives in that ship's plot level, while the mark is filed in the overworld's saved
     * data, so the level the settings came from is often not the level she is in.</p>
     */
    private static Entity operatorOf(Level level, UUID maidId) {
        if (level == null || maidId == null) {
            return null;
        }
        try {
            ServerLevel server = IffRadar.serverLevelOf(level);
            MinecraftServer root = server == null ? null : server.getServer();
            if (root == null) {
                return null;
            }
            Set<ServerLevel> levels = new LinkedHashSet<>();
            levels.add(server);
            for (ServerLevel one : root.getAllLevels()) {
                levels.add(one);
            }
            for (ServerLevel one : levels) {
                Entity found = one == null ? null : one.getEntity(maidId);
                if (found != null) {
                    return found;
                }
            }
            return null;
        } catch (Throwable ignored) {
            return null;
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

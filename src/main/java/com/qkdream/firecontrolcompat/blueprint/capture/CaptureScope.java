package com.qkdream.firecontrolcompat.blueprint.capture;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;

import com.qkdream.firecontrolcompat.blueprint.BlueprintExporter;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.Entity;

/**
 * Who each captured piece of geometry belongs to.
 *
 * <p>The exporter records vertices, not owners: a face arrives from a block model, a
 * block-entity renderer or an entity renderer, and once it is a triangle there is nothing left
 * that says which block or entity produced it. That is fine for an OBJ - the mesh does not care -
 * and it is the one thing missing for anything that has to map the mesh back onto the world (an
 * OBJ group bound to a block or entity id, so a part can be replaced instead of the whole
 * structure).</p>
 *
 * <p>So every capture run opens a scope around the call that produces the geometry
 * ({@code beginBlock} / {@code beginEntity}) and closes it afterwards, and every quad that is
 * recorded while a scope is open is stamped with the owner that scope names. Nothing about that
 * stamping reaches the export: the OBJ, the MTL and the textures are written exactly as before,
 * and only the log lines and the counters below know about it. This round is deliberately the
 * skeleton - the scope switch, the owner on each face, and the numbers that prove both work.</p>
 *
 * <p>Never throws: a scope that cannot be resolved is an owner of {@code ?} and a count of zero,
 * because losing a diagnostic is not worth losing an export.</p>
 */
public final class CaptureScope {

    /** How many per-block lines one export may write, so a big structure stays readable. */
    public static final int MAX_BLOCK_LINES = 40;

    /** How many per-entity lines one export may write. */
    public static final int MAX_ENTITY_LINES = 40;

    /** Most render types remembered for one scope; a block with more than this is not a block. */
    private static final int MAX_TYPES = 64;

    /** Deepest nesting searched for a contraption's block list in its NBT. */
    private static final int MAX_NBT_DEPTH = 4;

    private CaptureScope() {
    }

    /** One open scope: who it is, and what was drawn while it was open. */
    private static final class Scope {
        final boolean entity;
        final String owner;
        final int blocks;
        int quads;
        final LinkedHashSet<String> renderTypes = new LinkedHashSet<>();

        Scope(boolean entity, String owner, int blocks) {
            this.entity = entity;
            this.owner = owner == null ? "?" : owner;
            this.blocks = blocks;
        }
    }

    /** Everything one thread's capture run owns. */
    private static final class State {
        final Deque<Scope> open = new ArrayDeque<>();
        /** One contraption's block count costs a full NBT write, so it is read once per run. */
        final Map<Integer, Integer> contraptionBlocks = new HashMap<>();
        boolean capturing;
        int blocks;
        int entities;
        int tagged;
        int untagged;
        int blockLines;
        int entityLines;
        int suppressed;

        void reset() {
            open.clear();
            contraptionBlocks.clear();
            capturing = false;
            blocks = 0;
            entities = 0;
            tagged = 0;
            untagged = 0;
            blockLines = 0;
            entityLines = 0;
            suppressed = 0;
        }
    }

    /**
     * Per-thread, because the bake runs on the client thread and the live capture on the render
     * thread: one must never inherit the other's open scope.
     */
    private static final ThreadLocal<State> STATE = ThreadLocal.withInitial(State::new);

    /** Whether an export or a live capture is open, i.e. whether a scope is worth opening. */
    public static boolean isCapturing() {
        return STATE.get().capturing;
    }

    /** Starts a run. Called once at the beginning of an export or a live capture. */
    public static void reset() {
        State state = STATE.get();
        state.reset();
        state.capturing = true;
    }

    /** The owner the vertices being written right now belong to, or null when none is open. */
    public static String current() {
        Scope scope = STATE.get().open.peekLast();
        return scope == null ? null : scope.owner;
    }

    /** Opens the scope of one block, for the block-entity pass. */
    public static void beginBlock(BlockPos pos) {
        beginBlock(pos == null ? "?"
            : pos.getX() + "," + pos.getY() + "," + pos.getZ());
    }

    /** Opens the scope of one block under an already formatted owner. */
    public static void beginBlock(String owner) {
        State state = STATE.get();
        if (!state.capturing) {
            return;
        }
        state.open.addLast(new Scope(false, owner, 0));
    }

    /** Opens the scope of one entity, for the live capture. */
    public static void beginEntity(Entity entity) {
        if (entity == null) {
            return;
        }
        beginEntity(entityOwner(entity), contraptionBlockCount(entity));
    }

    /** Opens the scope of one entity that is only known from NBT (a blueprint's entity list). */
    public static void beginEntity(String owner, int blocks) {
        State state = STATE.get();
        if (!state.capturing) {
            return;
        }
        // A renderer that threw leaves its scope open - RETURN never runs - and the next entity
        // would inherit it. Closing it here keeps every entity's count its own.
        Scope leaked = state.open.peekLast();
        if (leaked != null && leaked.entity) {
            state.open.pollLast();
            report(state, leaked);
        }
        state.open.addLast(new Scope(true, owner, Math.max(0, blocks)));
    }

    /** Closes whatever scope is on top of the stack. Used by the block-entity pass. */
    public static void end() {
        State state = STATE.get();
        Scope scope = state.open.pollLast();
        if (scope != null) {
            report(state, scope);
        }
    }

    /** Closes the top scope only if it is an entity's, so a stray call cannot eat a block's. */
    public static void endEntity() {
        State state = STATE.get();
        Scope scope = state.open.peekLast();
        if (scope == null || !scope.entity) {
            return;
        }
        state.open.pollLast();
        report(state, scope);
    }

    /** Notes one quad for whoever is open. Callers pass no owner; the stack knows it. */
    public static void noteQuad() {
        State state = STATE.get();
        if (!state.capturing) {
            return;
        }
        Scope scope = state.open.peekLast();
        if (scope == null) {
            state.untagged++;
            return;
        }
        scope.quads++;
        state.tagged++;
    }

    /** Notes the render type a quad was written under, for the per-block line. */
    public static void noteRenderType(String name) {
        State state = STATE.get();
        if (!state.capturing || name == null) {
            return;
        }
        Scope scope = state.open.peekLast();
        if (scope != null && scope.renderTypes.size() < MAX_TYPES) {
            scope.renderTypes.add(name);
        }
    }

    /** Writes the run's totals and closes it. Called once at the end of an export. */
    public static void summarise() {
        State state = STATE.get();
        if (!state.capturing) {
            return;
        }
        state.capturing = false;
        try {
            BlueprintExporter.LOGGER.info(
                "capture-scope: summary blocks={} entities={} quads-tagged={} quads-untagged={}",
                state.blocks, state.entities, state.tagged, state.untagged);
            if (state.suppressed > 0) {
                BlueprintExporter.LOGGER.info(
                    "capture-scope: {} line(s) not printed ({} block / {} entity lines per export)",
                    state.suppressed, MAX_BLOCK_LINES, MAX_ENTITY_LINES);
            }
        } catch (Throwable ignored) {
            // a log line is never worth an exception
        }
    }

    /**
     * Logs the entities a blueprint carries.
     *
     * <p>The per-block exporter never calls {@code EntityRenderDispatcher}: a blueprint's entities
     * are records in its NBT, not something that is drawn, so there is no render call to wrap. What
     * can be done is the other half - detection and a block count - which is what this is: each
     * entry in the blueprint's own {@code entities} lists becomes one owner, with the size of the
     * contraption it carries when it is one.</p>
     */
    public static void noteBlueprintEntities(CompoundTag root) {
        if (root == null) {
            return;
        }
        State state = STATE.get();
        if (!state.capturing) {
            return;
        }
        try {
            noteEntityList(root.getList("entities", Tag.TAG_COMPOUND));
            ListTag subs = root.getList("sub_levels", Tag.TAG_COMPOUND);
            for (int i = 0; i < subs.size(); i++) {
                noteEntityList(subs.getCompound(i).getList("entities", Tag.TAG_COMPOUND));
            }
        } catch (Throwable ignored) {
            // an unreadable entity list is zero entities, not a failed export
        }
    }

    /** One blueprint's entity list: one scope per entry, opened and closed immediately. */
    private static void noteEntityList(ListTag entities) {
        if (entities == null) {
            return;
        }
        for (int i = 0; i < entities.size(); i++) {
            CompoundTag entry = entities.getCompound(i);
            String owner = entityOwner(entry);
            beginEntity(owner, blocksIn(entry, 0));
            endEntity();
        }
    }

    /** The owner label of an entity record: its uuid when it has one, else its id. */
    private static String entityOwner(CompoundTag entry) {
        try {
            String id = entry.getString("id");
            int[] uuid = entry.getIntArray("UUID");
            if (uuid != null && uuid.length == 4) {
                return new java.util.UUID(((long) uuid[0] << 32) | (uuid[1] & 0xFFFFFFFFL),
                                          ((long) uuid[2] << 32) | (uuid[3] & 0xFFFFFFFFL))
                    .toString();
            }
            return id == null || id.isEmpty() ? "?" : id;
        } catch (Throwable t) {
            return "?";
        }
    }

    private static void report(State state, Scope scope) {
        try {
            if (scope.entity) {
                state.entities++;
                if (state.entityLines < MAX_ENTITY_LINES) {
                    state.entityLines++;
                    BlueprintExporter.LOGGER.info(
                        "capture-scope: entity owner={} blocks={} quads={}",
                        scope.owner, scope.blocks, scope.quads);
                } else {
                    state.suppressed++;
                }
                return;
            }
            state.blocks++;
            if (scope.quads <= 0) {
                // A block that drew nothing is counted, not printed: the line is only useful
                // when it can be matched against geometry in the file.
                return;
            }
            if (state.blockLines < MAX_BLOCK_LINES) {
                state.blockLines++;
                BlueprintExporter.LOGGER.info(
                    "capture-scope: block owner={} quads={} renderTypes={}",
                    scope.owner, scope.quads, scope.renderTypes.size());
            } else {
                state.suppressed++;
            }
        } catch (Throwable ignored) {
            // never let a diagnostic break the pass it is describing
        }
    }

    /** How many blocks a contraption entity carries, or 0 when that cannot be read. */
    public static int contraptionBlockCount(Entity entity) {
        if (entity == null) {
            return 0;
        }
        State state = STATE.get();
        int key = System.identityHashCode(entity);
        Integer cached = state.contraptionBlocks.get(key);
        if (cached != null) {
            return cached;
        }
        int count = 0;
        try {
            if (isContraption(entity)) {
                // The live list first: it is the same data without serialising a moving structure
                // of a thousand blocks on every frame of the capture.
                count = contraptionBlocksByReflection(entity);
                if (count <= 0) {
                    count = blocksIn(entity.saveWithoutId(new CompoundTag()), 0);
                }
            }
        } catch (Throwable t) {
            // "cannot read it" is the answer 0 is for, not an error
            count = 0;
        }
        state.contraptionBlocks.put(key, count);
        return count;
    }

    /** A contraption is recognised by name, so Create stays an optional dependency. */
    private static boolean isContraption(Entity entity) {
        try {
            return entity.getClass().getName().toLowerCase(java.util.Locale.ROOT)
                .contains("contraption");
        } catch (Throwable t) {
            return false;
        }
    }

    /** {@code AbstractContraptionEntity#getContraption()#getBlocks()}, without naming Create. */
    private static int contraptionBlocksByReflection(Entity entity) {
        try {
            Method getContraption = entity.getClass().getMethod("getContraption");
            Object contraption = getContraption.invoke(entity);
            if (contraption == null) {
                return 0;
            }
            Method getBlocks = contraption.getClass().getMethod("getBlocks");
            Object blocks = getBlocks.invoke(contraption);
            return blocks instanceof Map<?, ?> map ? map.size() : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * The size of the first {@code Blocks} list anywhere in this NBT, or 0.
     *
     * <p>Create stores a contraption's blocks under its own {@code Blocks} key, but at depth: the
     * entity writes a {@code Contraption} compound that holds them. Searching by key rather than
     * by path means a version that moves the wrapper does not silently report zero.</p>
     */
    private static int blocksIn(CompoundTag tag, int depth) {
        if (tag == null || depth > MAX_NBT_DEPTH) {
            return 0;
        }
        int best = 0;
        try {
            if (tag.contains("Blocks", Tag.TAG_LIST)) {
                best = tag.getList("Blocks", Tag.TAG_COMPOUND).size();
                if (best > 0) {
                    return best;
                }
            }
            for (String key : tag.getAllKeys()) {
                byte type = tag.getTagType(key);
                if (type == Tag.TAG_COMPOUND) {
                    best = Math.max(best, blocksIn(tag.getCompound(key), depth + 1));
                } else if (type == Tag.TAG_LIST) {
                    ListTag list = tag.getList(key, Tag.TAG_COMPOUND);
                    // Only short lists are walked: the long ones are block and actor lists, whose
                    // own "Blocks" keys were found above by name.
                    for (int i = 0; i < Math.min(list.size(), 8); i++) {
                        best = Math.max(best, blocksIn(list.getCompound(i), depth + 1));
                    }
                }
            }
        } catch (Throwable t) {
            return best;
        }
        return best;
    }

    /** The owner label of a live entity: its uuid, or its class when it has none. */
    private static String entityOwner(Entity entity) {
        try {
            java.util.UUID uuid = entity.getUUID();
            if (uuid != null) {
                return uuid.toString();
            }
        } catch (Throwable ignored) {
            // fall through to the class name
        }
        try {
            String name = entity.getClass().getSimpleName();
            return name == null || name.isEmpty() ? "?" : name;
        } catch (Throwable t) {
            return "?";
        }
    }
}

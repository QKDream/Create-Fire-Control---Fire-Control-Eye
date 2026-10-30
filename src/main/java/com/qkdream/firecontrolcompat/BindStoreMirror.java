package com.qkdream.firecontrolcompat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

/**
 * The second copy of a saved-data entry, filed on the thing that entry belongs to.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Every per-computer setting this mod keeps - the call sign, the torpedo swim, the lead pair,
 * the beam riding launch mode - is indexed by {@link RadarNameStore#keyOf}: the platform the
 * computer stands on plus, depending on the store, its own position. A blueprint copy changes that
 * identity (a printed body gets a new sub-level id, and a computer in the world is a new block
 * position), so the table is the one place a copy cannot follow. The computer's own block entity,
 * on the other hand, is copied whole, persistent data and all.</p>
 *
 * <p>{@code SonarSelectInputStore} established the shape for the sonar pick: keep the table as the
 * index the screens read and write, file a copy on the holder, and repair the index from the copy -
 * or the copy from the index - the first time either side is found missing. This class is the part
 * of that shape every store shares: the key name of the copy, the read/write of it, and the log
 * lines, so all five stores say the same thing in the same words.</p>
 *
 * <h2>Key name</h2>
 *
 * <p>Every copy is filed under {@code firecontrolcompat:<store>_mirror}, where {@code <store>} is
 * one of the {@code *_MIRROR} store names below. The payload is that store's own compound, written
 * by the store, never by this class.</p>
 *
 * <h2>Logging</h2>
 *
 * <ul>
 * <li>{@code bind-store: save store=<name> be=<pos> keys=[...]} - a copy was written.</li>
 * <li>{@code bind-store: load store=<name> be=<pos> restored=<bool> resolved=<bool>} - a copy was
 * read; reported on every repair, and once per holder otherwise, so a read that happens every tick
 * cannot flood the log.</li>
 * <li>{@code migrate saveddata->nbt store=<name> entries=<n>} - the index had the entry and the
 * holder did not, so the copy was written (a world bound before the copies existed).</li>
 * <li>{@code migrate nbt->index store=<name> entries=<n>} - the holder had the copy and the index
 * did not, so the index was rebuilt (a computer that has just been copied).</li>
 * </ul>
 *
 * <p>The "once" in the first list is counted by name rather than by clock: no
 * {@code System.currentTimeMillis()} arithmetic is involved anywhere here, so the
 * {@code now - Long.MIN_VALUE} overflow that used to poison rate limited lines cannot happen.</p>
 *
 * <p>Nothing in this class throws: a store is never worth taking a caller down.</p>
 */
public final class BindStoreMirror {

    /** Prefix of every copy's key: {@code firecontrolcompat:<store>_mirror}. */
    public static final String PREFIX = "firecontrolcompat:";
    /** Suffix of every copy's key. */
    public static final String SUFFIX = "_mirror";

    /** Store name of {@link RadarNameStore}. */
    public static final String RADAR_NAME = "radar_name";
    /** Store name of {@link TorpedoSettingsStore}. */
    public static final String TORPEDO_SETTINGS = "torpedo_settings";
    /** Store name of {@link ControllerLeadStore}. */
    public static final String CONTROLLER_LEAD = "controller_lead";
    /** Store name of {@link BeamLaunchModeStore}. */
    public static final String BEAM_LAUNCH_MODE = "beam_launch_mode";
    /** Store name of {@link SpyglassMarkStore}: the operator's mark. */
    public static final String SPYGLASS_MARK = "spyglass_mark";

    /** How many holders are remembered before the "already reported" set is dropped. */
    private static final int SEEN_LIMIT = 512;

    /** "load store@holder" lines already printed, so an every-tick read reports once. */
    private static final Set<String> SEEN = ConcurrentHashMap.newKeySet();

    /** Stores whose one-shot sweep of the loaded holders has already been done. */
    private static final Set<String> SWEPT = ConcurrentHashMap.newKeySet();

    private BindStoreMirror() {
    }

    /** The key a copy of that store is filed under. */
    public static String keyOf(String store) {
        return PREFIX + store + SUFFIX;
    }

    /** Whether that holder carries a copy of that store. */
    public static boolean has(CompoundTag holder, String store) {
        if (holder == null) {
            return false;
        }
        try {
            return holder.contains(keyOf(store));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** The copy filed on that holder, or {@code null} when it carries none. */
    public static CompoundTag copy(CompoundTag holder, String store) {
        if (holder == null) {
            return null;
        }
        try {
            String key = keyOf(store);
            if (!holder.contains(key)) {
                return null;
            }
            CompoundTag stored = holder.getCompound(key);
            return stored.isEmpty() ? null : stored;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Files a copy on that holder and reports it.
     *
     * <p>The payload is copied rather than handed over, so a store that keeps reusing one compound
     * cannot rewrite a copy that is already filed.</p>
     *
     * @return whether the copy is on the holder afterwards
     */
    public static boolean file(CompoundTag holder, String store, CompoundTag payload, BlockPos at) {
        return file(holder, store, payload, at, null);
    }

    /**
     * Files a copy on that holder and reports it, with one extra field on the line.
     *
     * <p>The extra field is for the stores whose holder is not a block: a mark on an operator rides
     * on her entity, and the line then says which operator it was as well as where she stood.</p>
     */
    public static boolean file(
            CompoundTag holder, String store, CompoundTag payload, BlockPos at, String detail) {
        if (holder == null || payload == null) {
            return false;
        }
        try {
            holder.put(keyOf(store), payload.copy());
            logSave(store, at, payload, detail);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Drops the copy of that store; {@code true} when there was one. */
    public static boolean drop(CompoundTag holder, String store) {
        if (holder == null) {
            return false;
        }
        try {
            String key = keyOf(store);
            if (!holder.contains(key)) {
                return false;
            }
            holder.remove(key);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Reports a copy that was written. */
    public static void logSave(String store, BlockPos at, CompoundTag payload) {
        logSave(store, at, payload, null);
    }

    /** Reports a copy that was written, with one extra field. */
    public static void logSave(String store, BlockPos at, CompoundTag payload, String detail) {
        try {
            if (detail == null || detail.isEmpty()) {
                FireControlCompat.LOGGER.info("bind-store: save store={} be={} keys={}",
                        store, describe(at), describe(payload));
                return;
            }
            FireControlCompat.LOGGER.info("bind-store: save store={} be={} keys={} {}",
                    store, describe(at), describe(payload), detail);
        } catch (Throwable ignored) {
            // a log line is never worth a failed setting
        }
    }

    /**
     * Reports a copy that was read.
     *
     * <p>{@code restored} means the index was repaired from the copy; that is rare and always
     * printed. The ordinary read - the index answered and the copy was there - is printed once per
     * holder, because this sits on the path a tick takes.</p>
     */
    public static void logLoad(String store, BlockPos at, boolean restored, boolean resolved) {
        try {
            if (!restored && !firstTime("load:" + store + "@" + marker(at))) {
                return;
            }
            FireControlCompat.LOGGER.info("bind-store: load store={} be={} restored={} resolved={}",
                    store, describe(at), restored, resolved);
        } catch (Throwable ignored) {
            // a log line is never worth a failed read
        }
    }

    /** Reports a copy written because the index had the entry and the holder did not. */
    public static void logMigrateFromTable(String store, int entries) {
        try {
            FireControlCompat.LOGGER.info("migrate saveddata->nbt store={} entries={}", store, entries);
        } catch (Throwable ignored) {
            // a log line is never worth a failed migration
        }
    }

    /** Reports an index rebuilt because the holder had the copy and the index did not. */
    public static void logMigrateFromCopy(String store, int entries) {
        try {
            FireControlCompat.LOGGER.info("migrate nbt->index store={} entries={}", store, entries);
        } catch (Throwable ignored) {
            // a log line is never worth a failed migration
        }
    }

    /**
     * Whether that store's one-shot sweep of the loaded holders still has to be run.
     *
     * <p>The sweep is what makes an <em>old</em> world safe before anything is read: a call sign
     * that was entered before the copies existed is only on the index, and a blueprint saved
     * without ever opening the screen would otherwise not carry it. A store marks itself swept only
     * once the sweep has actually seen a holder, so a read that happens before any computer is
     * loaded does not use the one chance up.</p>
     */
    public static boolean needsSweep(String store) {
        try {
            return !SWEPT.contains(store);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Marks a store's sweep as done; called only after it walked at least one holder. */
    public static void sweepDone(String store) {
        try {
            SWEPT.add(store);
        } catch (Throwable ignored) {
            // a sweep that cannot be remembered simply runs again
        }
    }

    /** The keys of a payload, sorted, as {@code [a, b]}, for the save line. */
    private static String describe(CompoundTag payload) {
        if (payload == null || payload.isEmpty()) {
            return "[]";
        }
        List<String> keys = new ArrayList<>(payload.getAllKeys());
        Collections.sort(keys);
        return keys.toString();
    }

    /** The position as the log prints it, or {@code -} when the holder has none. */
    private static String describe(BlockPos at) {
        try {
            return at == null ? "-" : at.toShortString();
        } catch (Throwable ignored) {
            return "-";
        }
    }

    /** Identity of a holder for the "already reported" set. */
    private static String marker(BlockPos at) {
        try {
            return at == null ? "-" : Long.toString(at.asLong());
        } catch (Throwable ignored) {
            return "-";
        }
    }

    /** Whether this marker is seen for the first time; the set is dropped when it grows too far. */
    private static boolean firstTime(String marker) {
        try {
            if (SEEN.size() > SEEN_LIMIT) {
                SEEN.clear();
            }
            return SEEN.add(marker);
        } catch (Throwable ignored) {
            return false;
        }
    }
}

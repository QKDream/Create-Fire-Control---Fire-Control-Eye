package com.qkdream.firecontrolcompat.sonar;

import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.qkdream.firecontrolcompat.IffRadar;
import com.qkdream.firecontrolcompat.RadarNameStore;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;

/**
 * The one mark a fire control computer has taken off its sonar picture.
 *
 * <p>The key the operator presses picks the contact nearest their sight line
 * and files it here, on the computer rather than on the dish, because the mark
 * is what the tubes wired to that computer are laid on: one computer's pick is
 * one computer's business, and a second rig alongside keeps its own.</p>
 *
 * <p>The mark is held until it is picked again: pressing the key with the same
 * contact under the sight line takes the lock back, so a boat picked by mistake
 * costs one more press and no more.</p>
 */
public final class SonarLockStore extends SavedData {

    public static final String DATA_ID = "firecontrolcompat_sonar_locks";

    /** What was picked: where it stood, and what was heard there. */
    public record Lock(Vec3 position, SonarKind kind) {

        /**
         * Whether the mark stands for steel. A wire can be laid on a hull
         * itself - a boat that has stopped her engines is still run down - while
         * a swimmer or a warhead in open water has to keep making a noise to be
         * followed at all.
         */
        public boolean hull() {
            return this.kind == SonarKind.BODY
                    || this.kind == SonarKind.GUNFIRE
                    || this.kind == SonarKind.MACHINE;
        }
    }

    /** Owner key -> the mark that computer holds. A computer with none holds nothing. */
    private final Map<String, Lock> entries = new HashMap<>();

    public static SonarLockStore load(CompoundTag tag, HolderLookup.Provider registries) {
        SonarLockStore store = new SonarLockStore();
        CompoundTag stored = tag.getCompound("locks");
        for (String key : stored.getAllKeys()) {
            CompoundTag entry = stored.getCompound(key);
            store.entries.put(key, new Lock(
                    new Vec3(entry.getDouble("x"), entry.getDouble("y"), entry.getDouble("z")),
                    SonarKind.byOrdinal(entry.getInt("kind"))));
        }
        return store;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag stored = new CompoundTag();
        this.entries.forEach((key, lock) -> {
            CompoundTag one = new CompoundTag();
            one.putDouble("x", lock.position().x);
            one.putDouble("y", lock.position().y);
            one.putDouble("z", lock.position().z);
            one.putInt("kind", lock.kind().ordinal());
            stored.put(key, one);
        });
        tag.put("locks", stored);
        return tag;
    }

    /** The mark the computer holds, or null when it holds none. */
    public static Lock of(Level level, StabilizerControllerBlockEntity controller) {
        if (controller == null) {
            return null;
        }
        try {
            SonarLockStore store = storeOf(level);
            return store == null ? null : store.entries.get(RadarNameStore.keyOf(level, controller));
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Files a mark, or takes the lock back when nothing is handed in. */
    public static void set(Level level, StabilizerControllerBlockEntity controller, Lock lock) {
        if (controller == null) {
            return;
        }
        try {
            SonarLockStore store = storeOf(level);
            if (store == null) {
                return;
            }
            String key = RadarNameStore.keyOf(level, controller);
            if (lock == null) {
                store.entries.remove(key);
            } else {
                store.entries.put(key, lock);
            }
            store.setDirty();
        } catch (Throwable ignored) {
        }
    }

    private static SonarLockStore storeOf(Level level) {
        ServerLevel server = IffRadar.serverLevelOf(level);
        if (server == null) {
            return null;
        }
        MinecraftServer minecraftServer = server.getServer();
        ServerLevel overworld = minecraftServer == null ? server : minecraftServer.overworld();
        return overworld.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(SonarLockStore::new, SonarLockStore::load, null), DATA_ID);
    }
}

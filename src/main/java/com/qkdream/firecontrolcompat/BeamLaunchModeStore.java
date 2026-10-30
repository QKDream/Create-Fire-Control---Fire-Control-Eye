package com.qkdream.firecontrolcompat;

import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.content.controller.ControllerTracker;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.qkdream.firecontrolcompat.network.BeamLaunchModeSyncPayload;
import java.util.HashMap;
import java.util.List;
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
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Beam riding missile launch mode, one value per fire control computer.
 *
 * <p>The mode used to be a single value shared by every computer in the world,
 * which meant a mount that wants its rounds kicked out of the tube vertically
 * could only get that by giving every other mount in the world the same
 * behaviour. It is filed here under the platform identity of the computer that
 * owns the launcher (see {@link RadarNameStore#keyOf}), so each rig carries its
 * own answer and the missile reads the one belonging to the mount that fired
 * it.</p>
 *
 * <p>A launcher that is not bound to any computer falls back to the value on
 * the settings page, which keeps a plain "point the tube and press" launcher
 * working exactly as before.</p>
 */
public final class BeamLaunchModeStore extends SavedData {

    public static final String DATA_ID = "firecontrolcompat_beam_launch_modes";

    /** This store's name in the {@code bind-store} and {@code migrate} lines. */
    private static final String STORE = BindStoreMirror.BEAM_LAUNCH_MODE;
    /** Key the launch mode is filed under inside the copy that rides on the computer. */
    private static final String MIRROR_VERTICAL = "vertical";

    /** Owner key -> vertical launch. Horizontal is the absence of an entry. */
    private final Map<String, Boolean> vertical = new HashMap<>();

    public static BeamLaunchModeStore load(CompoundTag tag, HolderLookup.Provider registries) {
        BeamLaunchModeStore store = new BeamLaunchModeStore();
        CompoundTag stored = tag.getCompound("vertical");
        for (String key : stored.getAllKeys()) {
            if (stored.getBoolean(key)) {
                store.vertical.put(key, Boolean.TRUE);
            }
        }
        return store;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag stored = new CompoundTag();
        this.vertical.forEach(stored::putBoolean);
        tag.put("vertical", stored);
        return tag;
    }

    /** Launch mode stored for one computer; horizontal unless it was switched. */
    public static boolean isVertical(Level level, StabilizerControllerBlockEntity controller) {
        if (controller == null) {
            return false;
        }
        try {
            sweep(level);
            String key = RadarNameStore.keyOf(level, controller);
            if (Boolean.TRUE.equals(table(level).get(key))) {
                // A world bound before the copy existed: file the mode on the computer now, so it
                // travels with the block from here on. Once per computer.
                boolean repaired = !hasMirror(controller) && mirror(controller, true);
                BindStoreMirror.logLoad(STORE, controller.getBlockPos(), repaired, true);
                if (repaired) {
                    BindStoreMirror.logMigrateFromTable(STORE, 1);
                }
                return true;
            }
            return rebuilt(level, controller, key);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Rebuilds the table entry of a computer that has just been copied.
     *
     * <p>Horizontal is the absence of an entry, so only a vertical mount has anything to restore:
     * a copied launcher that was kicked out of the tube vertically is filed as vertical again under
     * the identity it arrived with. A horizontal one needs nothing - the absence is already what it
     * reads, and a copy that said "horizontal" is dropped on purpose by {@link #mirror}.</p>
     */
    private static boolean rebuilt(Level level, StabilizerControllerBlockEntity controller, String key) {
        Boolean mirrored = mirrored(controller);
        if (!Boolean.TRUE.equals(mirrored)) {
            return false;
        }
        try {
            ServerLevel server = IffRadar.serverLevelOf(level);
            if (server == null) {
                return false;
            }
            BeamLaunchModeStore store = store(server);
            store.vertical.put(key, Boolean.TRUE);
            store.setDirty();
            BindStoreMirror.logLoad(STORE, controller.getBlockPos(), true, true);
            BindStoreMirror.logMigrateFromCopy(STORE, 1);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Files the launch mode on the computer itself; horizontal drops the copy.
     *
     * <p>Dropped rather than written as {@code false}: the table spells horizontal as "no entry",
     * so the copy spells it the same way, and a reprinted horizontal mount comes out exactly as it
     * was - horizontal, with no stale vertical copy to resurrect.</p>
     *
     * @return whether the computer carries the mode afterwards
     */
    public static boolean mirror(BlockEntity controller, boolean verticalLaunch) {
        if (controller == null) {
            return false;
        }
        try {
            CompoundTag holder = controller.getPersistentData();
            if (!verticalLaunch) {
                if (BindStoreMirror.drop(holder, STORE)) {
                    controller.setChanged();
                }
                return true;
            }
            CompoundTag payload = new CompoundTag();
            payload.putBoolean(MIRROR_VERTICAL, true);
            if (!BindStoreMirror.file(holder, STORE, payload, controller.getBlockPos())) {
                return false;
            }
            controller.setChanged();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** The launch mode filed on the computer itself, or {@code null} when it carries none. */
    public static Boolean mirrored(BlockEntity controller) {
        if (controller == null) {
            return null;
        }
        try {
            CompoundTag payload = BindStoreMirror.copy(controller.getPersistentData(), STORE);
            if (payload == null) {
                return null;
            }
            return payload.getBoolean(MIRROR_VERTICAL);
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
     * Files a copy on every loaded computer that is switched to vertical but does not carry it.
     *
     * <p>Run once per store: it is the repair an old world needs, so a blueprint saved without the
     * screen ever being opened still carries the mode. Horizontal mounts are not touched - the
     * absence is already how that is spelled. The per-computer path in {@link #isVertical} repairs
     * anything this sweep missed.</p>
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
            BeamLaunchModeStore store = store(server);
            for (StabilizerControllerBlockEntity controller : ControllerTracker.loadedControllers()) {
                if (controller == null || controller.isRemoved()) {
                    continue;
                }
                seen++;
                try {
                    Level at = controller.getLevel() == null ? server : controller.getLevel();
                    if (!Boolean.TRUE.equals(store.vertical.get(RadarNameStore.keyOf(at, controller)))
                            || hasMirror(controller)) {
                        continue;
                    }
                    if (mirror(controller, true)) {
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

    /** Launch mode of the computer that owns the launcher at this position. */
    public static boolean isVerticalForLauncher(Level level, BlockPos launcherPos) {
        StabilizerControllerBlockEntity controller = controllerForLauncher(level, launcherPos);
        if (controller == null) {
            // A tube nobody bound keeps the plain setting of the computer screen.
            return FireControlLeadSettings.beamVerticalLaunch();
        }
        return isVertical(level, controller);
    }

    /** The bound computer of a launcher, or null while it is bound to none. */
    public static StabilizerControllerBlockEntity controllerForLauncher(Level level, BlockPos launcherPos) {
        if (level == null || launcherPos == null) {
            return null;
        }
        try {
            for (StabilizerControllerBlockEntity controller : ControllerTracker.loadedControllers()) {
                if (controller == null || controller.isRemoved()) {
                    continue;
                }
                Level controllerLevel = controller.getLevel();
                if (controllerLevel == null || !controllerLevel.dimension().equals(level.dimension())) {
                    continue;
                }
                if (bound(controller.getAaMissileLaunchers(), launcherPos)
                        || bound(controller.getMissileLaunchers(), launcherPos)) {
                    return controller;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean bound(List<BindingRef> bindings, BlockPos pos) {
        if (bindings == null) {
            return false;
        }
        for (BindingRef binding : bindings) {
            if (binding != null && pos.equals(binding.pos())) {
                return true;
            }
        }
        return false;
    }

    /** Applies an edit from the screen; false when the computer is gone. */
    public static boolean setMode(ServerPlayer player, BlockPos pos, UUID subLevel, boolean verticalLaunch) {
        if (player == null || pos == null) {
            return false;
        }
        StabilizerControllerBlockEntity controller =
                ControllerTracker.findLoadedForSettings(player, pos, subLevel);
        if (controller == null) {
            return false;
        }
        ServerLevel level = player.serverLevel();
        BeamLaunchModeStore store = store(level);
        String key = RadarNameStore.keyOf(level, controller);
        if (verticalLaunch) {
            store.vertical.put(key, Boolean.TRUE);
        } else {
            store.vertical.remove(key);
        }
        // Written on the computer as well, so the launch mode rides along with a blueprint copy.
        mirror(controller, verticalLaunch);
        store.setDirty();
        return true;
    }

    /** Tells the client which launch mode the computer it just opened carries. */
    public static void sendStatus(ServerPlayer player, ServerLevel level, BlockPos pos, UUID subLevel) {
        if (player == null || level == null || pos == null) {
            return;
        }
        StabilizerControllerBlockEntity controller =
                ControllerTracker.findLoadedForSettings(player, pos, subLevel);
        PacketDistributor.sendToPlayer(player, new BeamLaunchModeSyncPayload(isVertical(level, controller)));
    }

    private static Map<String, Boolean> table(Level level) {
        BeamLaunchModeStore store = storeOf(level);
        return store == null ? Map.of() : store.vertical;
    }

    private static BeamLaunchModeStore storeOf(Level level) {
        ServerLevel server = IffRadar.serverLevelOf(level);
        return server == null ? null : store(server);
    }

    private static BeamLaunchModeStore store(ServerLevel level) {
        MinecraftServer server = level.getServer();
        ServerLevel overworld = server == null ? level : server.overworld();
        return overworld.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(BeamLaunchModeStore::new, BeamLaunchModeStore::load, null), DATA_ID);
    }
}
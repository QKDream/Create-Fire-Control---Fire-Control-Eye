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
            return Boolean.TRUE.equals(table(level).get(RadarNameStore.keyOf(level, controller)));
        } catch (Throwable ignored) {
            return false;
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
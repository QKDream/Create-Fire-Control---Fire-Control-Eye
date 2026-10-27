package com.qkdream.firecontrolcompat.sonar;

import com.hooya.stabilizedturret.content.controller.ControllerTracker;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.qkdream.firecontrolcompat.TorpedoSettingsStore;
import com.qkdream.firecontrolcompat.network.SonarSettingsSyncPayload;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Reads and writes the settings a fire control computer holds for its sonar
 * sets. The screen sends what the player typed for one set, the server lands it
 * on that set alone, and the stored values of every set wired to the computer
 * are sent back so the page lists them the next time it is opened.
 */
public final class SonarSettings {

    private SonarSettings() {
    }

    /** Sends the settings of the computer that has just been opened. */
    public static void sendStatus(ServerPlayer player, StabilizerControllerBlockEntity controller) {
        if (player == null || controller == null) {
            return;
        }
        UUID subLevel = null;
        try {
            subLevel = ControllerTracker.controllerSubLevelId(controller);
        } catch (Throwable ignored) {
        }
        sendStatus(player, controller.getBlockPos(), subLevel);
    }

    /**
     * Sends the settings stored on every set reporting to one computer, along
     * with the swim that computer files for the acoustic torpedoes its tubes
     * fire - that page is where the swim is written, so it travels with it.
     */
    public static void sendStatus(ServerPlayer player, BlockPos controllerPos, UUID controllerSubLevel) {
        if (player == null) {
            return;
        }
        TorpedoSettingsStore.Entry torpedo = torpedoOf(player, controllerPos, controllerSubLevel);
        SonarSettingsSyncPayload.Select select = selectOf(player, controllerPos, controllerSubLevel);
        List<SonarBlockEntity> sets = all(player.level(), controllerPos, controllerSubLevel);
        if (sets.isEmpty()) {
            PacketDistributor.sendToPlayer(player, SonarSettingsSyncPayload.none(torpedo, select));
            return;
        }
        List<SonarSettingsSyncPayload.Entry> entries = new ArrayList<>(sets.size());
        for (SonarBlockEntity sonar : sets) {
            entries.add(new SonarSettingsSyncPayload.Entry(
                    sonar.getBlockPos().asLong(),
                    SonarTracker.subLevelOf(sonar),
                    sonar.range(),
                    sonar.halfAngle(),
                    sonar.elevationUp(),
                    sonar.elevationDown(),
                    sonar.activeMode(),
                    sonar.hudX(),
                    sonar.hudY(),
                    sonar.hudScale(),
                    sonar.name()));
        }
        PacketDistributor.sendToPlayer(player, new SonarSettingsSyncPayload(
                true, torpedo.holdDepth(), torpedo.depth(), torpedo.activation(), select, entries));
    }

    /** The swim the computer files for the rounds its tubes fire. */
    private static TorpedoSettingsStore.Entry torpedoOf(
            ServerPlayer player, BlockPos controllerPos, UUID controllerSubLevel) {
        try {
            StabilizerControllerBlockEntity controller =
                    ControllerTracker.findLoadedForSettings(player, controllerPos, controllerSubLevel);
            return TorpedoSettingsStore.of(player.level(), controller);
        } catch (Throwable ignored) {
            return TorpedoSettingsStore.plain();
        }
    }

    /**
     * The bindings the computer answers to for the sonar pick, as the page draws
     * them: the names of the two frequency items and the two remote signals.
     */
    private static SonarSettingsSyncPayload.Select selectOf(
            ServerPlayer player, BlockPos controllerPos, UUID controllerSubLevel) {
        try {
            StabilizerControllerBlockEntity controller =
                    ControllerTracker.findLoadedForSettings(player, controllerPos, controllerSubLevel);
            SonarSelectInputStore.Input input = SonarSelectInputStore.of(player.level(), controller);
            return new SonarSettingsSyncPayload.Select(
                    nameOf(input.first()), nameOf(input.second()), input.normalKey(),
                    input.advancedKey(), input.wirelessKey());
        } catch (Throwable ignored) {
            return SonarSettingsSyncPayload.Select.none();
        }
    }

    /** What a bound frequency calls itself on the page, empty when nothing is bound. */
    private static String nameOf(ItemStack stack) {
        return stack == null || stack.isEmpty() ? "" : stack.getHoverName().getString();
    }

    /** Lands the values from the settings page on the one set they were written for. */
    public static void apply(
            ServerPlayer player,
            BlockPos controllerPos,
            UUID controllerSubLevel,
            long sonarPos,
            UUID sonarSubLevel,
            double range,
            double halfAngle,
            double elevationUp,
            double elevationDown,
            boolean active,
            double hudX,
            double hudY,
            double hudScale,
            String name) {
        if (player == null || controllerPos == null) {
            return;
        }
        SonarBlockEntity sonar = find(player.level(), controllerPos, controllerSubLevel, sonarPos, sonarSubLevel);
        if (sonar != null) {
            sonar.applySettings(
                    range, halfAngle, elevationUp, elevationDown, active, hudX, hudY, hudScale, name);
            sonar.sweepNow();
        }
        sendStatus(player, controllerPos, controllerSubLevel);
    }

    /** The set at that place, when it really reports to that computer. */
    public static SonarBlockEntity find(
            Level level, BlockPos controllerPos, UUID controllerSubLevel, long sonarPos, UUID sonarSubLevel) {
        if (sonarPos == 0L && sonarSubLevel == null) {
            return null;
        }
        for (SonarBlockEntity sonar : all(level, controllerPos, controllerSubLevel)) {
            if (sonar.getBlockPos().asLong() != sonarPos) {
                continue;
            }
            UUID owner = SonarTracker.subLevelOf(sonar);
            if (sonarSubLevel == null || owner == null || sonarSubLevel.equals(owner)) {
                return sonar;
            }
        }
        return null;
    }

    /** Every set wired to a computer, in the order they were placed. */
    public static List<SonarBlockEntity> all(Level level, BlockPos controllerPos, UUID controllerSubLevel) {
        return SonarTracker.forController(level, controllerPos, controllerSubLevel);
    }

    /** The first set wired to a computer, or null when there is none. */
    public static SonarBlockEntity firstOf(Level level, BlockPos controllerPos, UUID controllerSubLevel) {
        List<SonarBlockEntity> sets = all(level, controllerPos, controllerSubLevel);
        return sets.isEmpty() ? null : sets.get(0);
    }
}

package com.qkdream.firecontrolcompat.sonar;

import com.hooya.stabilizedturret.content.controller.ControllerTracker;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.qkdream.firecontrolcompat.FireControlCompat;
import com.qkdream.firecontrolcompat.network.SonarSessionPayload;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Keeps the sonar pictures on the screen of whoever is sitting at a station.
 *
 * <p>A player in the seat of a fire control computer that has sets wired to it
 * is sent every set's picture a few times a second - each dish's place and
 * heading, the window it listens through, where its picture belongs on the
 * screen, and every mark it heard - so a computer running several dishes shows
 * all of their pictures at once, together with the one mark that computer has
 * picked off them - the mark the screen draws its bars around and the mark its
 * torpedo tubes are laid on. Anyone else is told the pictures are gone, so they
 * disappear when the player gets up.</p>
 */
@EventBusSubscriber(modid = FireControlCompat.MOD_ID)
public final class SonarServerSync {

    /** Ticks between two pictures: the dishes sweep every ten. */
    private static final int PERIOD = 5;

    /** Players currently being shown a picture, so it is only cleared once. */
    private static final Set<UUID> SHOWING = new HashSet<>();

    private SonarServerSync() {
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.tickCount % PERIOD != 0) {
            return;
        }
        try {
            send(player);
        } catch (Throwable ignored) {
        }
    }

    private static void send(ServerPlayer player) {
        StabilizerControllerBlockEntity controller =
                ControllerTracker.findForVehicle(player.level(), player.getVehicle());
        List<SonarBlockEntity> sets = controller == null
                ? List.of()
                : SonarSettings.all(
                        player.level(), controller.getBlockPos(), ControllerTracker.controllerSubLevelId(controller));
        if (sets.isEmpty()) {
            if (SHOWING.remove(player.getUUID())) {
                PacketDistributor.sendToPlayer(player, SonarSessionPayload.inactive());
            }
            return;
        }
        List<SonarSessionPayload.Panel> panels = new ArrayList<>(sets.size());
        for (SonarBlockEntity sonar : sets) {
            List<SonarSessionPayload.Mark> marks = new ArrayList<>();
            for (SonarContact contact : sonar.contacts()) {
                marks.add(new SonarSessionPayload.Mark(
                    contact.kind(), contact.kind().weight(), contact.echo(), contact.position()));
            }
            Vec3 dish = sonar.dishPosition();
            Vec3 heading = sonar.dishForward();
            panels.add(new SonarSessionPayload.Panel(
                    dish, heading, sonar.range(), sonar.halfAngle(),
                    sonar.elevationUp(), sonar.elevationDown(), sonar.activeMode(),
                    sonar.hudX(), sonar.hudY(), sonar.hudScale(), sonar.name(), marks));
        }
        SonarLockStore.Lock held = SonarLockStore.of(player.level(), controller);
        SHOWING.add(player.getUUID());
        PacketDistributor.sendToPlayer(player, new SonarSessionPayload(
                true,
                held == null ? null : held.position(),
                held == null ? null : held.kind(),
                panels));
    }

    /**
     * Sends the picture again at once, so a pick the operator has just taken
     * shows on their screen without waiting for the next sweep.
     */
    public static void refresh(ServerPlayer player) {
        if (player == null) {
            return;
        }
        try {
            send(player);
        } catch (Throwable ignored) {
        }
    }

    /** Forgets a player that left, so the next session starts clean. */
    public static void forget(UUID player) {
        SHOWING.remove(player);
    }
}

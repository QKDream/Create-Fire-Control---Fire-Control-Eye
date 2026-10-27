package com.qkdream.firecontrolcompat.sonar;

import com.hooya.stabilizedturret.content.controller.ControllerTracker;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.qkdream.firecontrolcompat.SubLevelPositions;
import com.simibubi.create.AllItems;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Wiring a sonar set to a fire control computer.
 *
 * <p>The computer comes first, exactly as it does for the turret's own modules:
 * a wrench on the computer arms the binding, and the next wrench on a set wires
 * the two together. A wrench on a set while sneaking cuts the wire again. The
 * set remembers the computer it reports to, which is what the settings page on
 * that computer reads - and because the set keeps that memory itself, one
 * computer can carry as many sets as the player cares to bolt down.</p>
 */
public final class SonarBinding {

    /** How long an armed computer waits for a set to be pointed at. */
    private static final long TIMEOUT_TICKS = 1200L;

    private static final Map<UUID, Pending> PENDING = new HashMap<>();

    private record Pending(BlockPos pos, UUID subLevel, long expiresAt) {
    }

    private SonarBinding() {
    }

    /**
     * Handles a wrench click on a computer or on a dish.
     *
     * @return true when the click was ours and the caller should stop
     */
    public static boolean handle(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        Player player = event.getEntity();
        if (level == null || player == null || !AllItems.WRENCH.isIn(event.getItemStack())) {
            return false;
        }
        if (level.isClientSide || !(player instanceof ServerPlayer serverPlayer)) {
            return false;
        }
        BlockEntity clicked = SubLevelPositions.blockEntity(level, event.getPos());
        if (clicked instanceof SonarBlockEntity sonar) {
            event.setCanceled(true);
            if (player.isShiftKeyDown()) {
                sonar.clearBinding();
                PENDING.remove(player.getUUID());
                serverPlayer.displayClientMessage(
                        Component.translatable("message.firecontrolcompat.sonar.cleared"), true);
                return true;
            }
            Pending pending = PENDING.get(player.getUUID());
            if (pending == null || pending.expiresAt() < level.getGameTime()) {
                PENDING.remove(player.getUUID());
                serverPlayer.displayClientMessage(
                        Component.translatable("message.firecontrolcompat.sonar.need_controller"), true);
                return true;
            }
            PENDING.remove(player.getUUID());
            BlockEntity target = SubLevelPositions.blockEntity(level, pending.pos());
            if (!(target instanceof StabilizerControllerBlockEntity controller)) {
                serverPlayer.displayClientMessage(
                        Component.translatable("message.firecontrolcompat.sonar.selection_lost"), true);
                return true;
            }
            boolean first = !sonar.isBoundTo(pending.pos(), pending.subLevel());
            sonar.bind(pending.pos(), pending.subLevel());
            if (first) {
                int taken = SonarSettings.all(level, pending.pos(), pending.subLevel()).size() - 1;
                sonar.placeOnHud(Math.max(0, taken));
            }
            sonar.sweepNow();
            serverPlayer.displayClientMessage(
                    Component.translatable("message.firecontrolcompat.sonar.connected"), true);
            SonarSettings.sendStatus(serverPlayer, controller);
            return true;
        }
        if (clicked instanceof StabilizerControllerBlockEntity controller) {
            // The computer's own flow runs as well, so the player gets its
            // "pick a module" message; we only remember which computer was armed.
            UUID subLevel = null;
            try {
                subLevel = ControllerTracker.controllerSubLevelId(controller);
            } catch (Throwable ignored) {
            }
            PENDING.put(player.getUUID(), new Pending(
                    controller.getBlockPos().immutable(), subLevel, level.getGameTime() + TIMEOUT_TICKS));
            return false;
        }
        return false;
    }
}

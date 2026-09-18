package com.qkdream.firecontrolcompat.iff;

import com.hooya.stabilizedturret.content.controller.ControllerTracker;
import com.hooya.stabilizedturret.content.controller.RemoteControlTracker;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.simibubi.create.AllItems;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock;

/**
 * Binds the IFF transponder to a fire control computer the same way the fire
 * control mod binds its own modules: wrench the computer first (that prints
 * "right click a module to bind"), then wrench the transponder. Wrenching the
 * transponder again releases the link.
 */
public final class IffLinking {

    private static final long SESSION_TICKS = 1200L;
    private static final Map<UUID, PendingController> PENDING_CONTROLLER = new HashMap<>();

    private IffLinking() {
    }

    public static boolean isWrench(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        try {
            return AllItems.WRENCH.isIn(stack);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Runs before the fire control binding handler so the transponder never
     * falls through to its "this block cannot be bound" reply.
     *
     * @return true when the click was handled here and the vanilla handler must
     *         be skipped for it.
     */
    public static boolean onWrenchClick(RightClickBlock event) {
        if (event.getEntity() == null || !isWrench(event.getItemStack())) {
            return false;
        }
        Level level = event.getLevel();
        BlockPos pos = event.getPos();
        if (level.getBlockEntity(pos) instanceof IffTransponderBlockEntity transponder) {
            consume(event);
            if (level.isClientSide) {
                return true;
            }
            if (event.getEntity() instanceof ServerPlayer player && level instanceof ServerLevel serverLevel) {
                openOrLink(player, serverLevel, transponder, pos);
            }
            return true;
        }
        if (!level.isClientSide
                && level.getBlockEntity(pos) instanceof StabilizerControllerBlockEntity
                && event.getEntity() instanceof ServerPlayer player) {
            // The fire control handler itself prints "right click a module to bind".
            rememberController(player, level, pos);
        }
        return false;
    }

    private static void openOrLink(
            ServerPlayer player, ServerLevel level, IffTransponderBlockEntity transponder, BlockPos pos) {
        long now = level.getGameTime();
        PendingController pending = PENDING_CONTROLLER.remove(player.getUUID());
        if (pending != null && !pending.expired(now)) {
            transponder.linkTo(pending.dimension(), pending.pos(), pending.subLevel());
            player.displayClientMessage(Component.translatable("message.firecontrolcompat.iff.linked"), true);
            return;
        }
        if (transponder.isLinked()) {
            transponder.unlink();
            player.displayClientMessage(Component.translatable("message.firecontrolcompat.iff.unlinked"), true);
            return;
        }
        player.displayClientMessage(Component.translatable("message.firecontrolcompat.iff.need_controller"), true);
    }

    private static void rememberController(ServerPlayer player, Level level, BlockPos pos) {
        long now = level.getGameTime();
        PENDING_CONTROLLER.put(player.getUUID(), new PendingController(
                pos.immutable(),
                IffTransponderBlockEntity.containingSubLevelId(level, pos),
                level.dimension().location(),
                now + SESSION_TICKS));
    }

    private static void consume(RightClickBlock event) {
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
    }

    /** The band of the transponder wired to the computer the player currently operates. */
    public static IffBand ownBand(Player player) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return null;
        }
        StabilizerControllerBlockEntity controller = null;
        try {
            // Remote terminals and pods report their computer through the input tracker.
            controller = RemoteControlTracker.findInputController(serverPlayer);
        } catch (Throwable ignored) {
        }
        if (controller == null) {
            try {
                controller = ControllerTracker.findForVehicle(player.level(), player.getVehicle());
            } catch (Throwable ignored) {
                return null;
            }
        }
        if (controller == null) {
            return null;
        }
        Level level = controller.getLevel();
        if (level == null) {
            return null;
        }
        BlockPos pos = controller.getBlockPos();
        IffTransponderBlockEntity transponder = IffTransponderBlockEntity.findForController(
                level, pos, IffTransponderBlockEntity.containingSubLevelId(level, pos));
        if (transponder == null) {
            return null;
        }
        IffBand band = transponder.band();
        return band.valid() ? band : null;
    }

    private record PendingController(BlockPos pos, UUID subLevel, ResourceLocation dimension, long expiresAt) {

        boolean expired(long now) {
            return now > this.expiresAt;
        }
    }
}
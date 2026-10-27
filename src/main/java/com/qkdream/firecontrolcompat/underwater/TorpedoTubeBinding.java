package com.qkdream.firecontrolcompat.underwater;

import com.hooya.stabilizedturret.content.controller.ControllerTracker;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.qkdream.firecontrolcompat.SubLevelPositions;
import com.simibubi.create.AllItems;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Wiring a torpedo tube to a fire control computer.
 *
 * <p>The computer comes first, exactly as it does for the turret's own modules:
 * a wrench on the computer arms the binding, and the next wrench on a tube
 * wires the two together. A wrench on a tube while sneaking cuts the wire
 * again. The tube keeps the computer it answers to in its own saved data, which
 * is what makes the wire survive being assembled onto a cannon mount: the tube
 * carries its memory into the contraption with it.</p>
 *
 * <p>A tube is not one of the computer's own modules - the slots it offers are
 * fixed and a torpedo tube is not among them - so the tube remembers the
 * computer itself, the way a sonar set does. What the computer is used for is
 * the shot: the hull on its screen is written into the round as the round
 * leaves the barrel.</p>
 */
public final class TorpedoTubeBinding {

    /** Keys the tube keeps its computer under. */
    private static final String CONTROLLER_POS = "FCTubeController";
    private static final String CONTROLLER_SUB = "FCTubeControllerSub";

    /** The namespace every CBCMS torpedo tube lives under. */
    private static final String CBCMS = "com.cainiao1053.cbcmoreshells.";

    /** The namespace the 面包学 blocks live under. */
    private static final String MIANBAO = "net.mcreator.myfirstmod.";

    /** How long an armed computer waits for a tube to be pointed at. */
    private static final long TIMEOUT_TICKS = 1200L;

    private static final Map<UUID, Pending> PENDING = new HashMap<>();

    private record Pending(BlockPos pos, UUID subLevel, long expiresAt) {
    }

    private TorpedoTubeBinding() {
    }

    /**
     * Handles a wrench click on a computer or on a tube.
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
        if (clicked instanceof StabilizerControllerBlockEntity controller) {
            // The computer's own flow runs as well, so the player still gets its
            // "pick a module" message; this only remembers which computer was armed.
            UUID subLevel = null;
            try {
                subLevel = ControllerTracker.controllerSubLevelId(controller);
            } catch (Throwable ignored) {
            }
            PENDING.put(player.getUUID(), new Pending(
                    controller.getBlockPos().immutable(), subLevel, level.getGameTime() + TIMEOUT_TICKS));
            return false;
        }
        if (!isTube(clicked)) {
            return false;
        }
        event.setCanceled(true);
        if (player.isShiftKeyDown()) {
            clearTube(clicked);
            PENDING.remove(player.getUUID());
            serverPlayer.displayClientMessage(
                    Component.translatable("message.firecontrolcompat.torpedo.cleared"), true);
            return true;
        }
        Pending pending = PENDING.get(player.getUUID());
        if (pending == null || pending.expiresAt() < level.getGameTime()) {
            PENDING.remove(player.getUUID());
            serverPlayer.displayClientMessage(
                    Component.translatable("message.firecontrolcompat.torpedo.need_controller"), true);
            return true;
        }
        PENDING.remove(player.getUUID());
        if (!(SubLevelPositions.blockEntity(level, pending.pos()) instanceof StabilizerControllerBlockEntity)) {
            serverPlayer.displayClientMessage(
                    Component.translatable("message.firecontrolcompat.torpedo.selection_lost"), true);
            return true;
        }
        bindTube(clicked, pending.pos(), pending.subLevel());
        serverPlayer.displayClientMessage(
                Component.translatable("message.firecontrolcompat.torpedo.connected"), true);
        return true;
    }

    /**
     * Whether a block is one of CBCMS's torpedo tubes. The test is by name, so
     * the rest of the mod stays loadable with that mod absent - nothing here
     * makes the game resolve a class it may not have.
     */
    public static boolean isTube(BlockEntity blockEntity) {
        if (blockEntity == null) {
            return false;
        }
        for (Class<?> type = blockEntity.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            if (isTubeName(type.getName())) {
                return true;
            }
            for (Class<?> face : type.getInterfaces()) {
                if (isTubeName(face.getName())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * A tube is either one of CBCMS's or the head of 面包学's launcher: the head is
     * the block that carries the round, so it is the one a computer is wired to.
     * Both are read by name, so neither mod has to be present for the other to
     * work.
     */
    private static boolean isTubeName(String name) {
        if (name == null) {
            return false;
        }
        if (name.startsWith(CBCMS)) {
            return name.contains("TorpedoTube");
        }
        return name.startsWith(MIANBAO) && name.contains("Torpedolauncherhead");
    }

    /** The computer a tube answers to, or null when it answers to none. */
    public static StabilizerControllerBlockEntity controller(BlockEntity tube) {
        BlockPos pos = controllerPos(tube);
        if (pos == null || tube == null || tube.getLevel() == null) {
            return null;
        }
        try {
            return SubLevelPositions.blockEntity(tube.getLevel(), pos)
                    instanceof StabilizerControllerBlockEntity controller ? controller : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Whether a tube carries a computer. */
    public static boolean isBound(BlockEntity tube) {
        return controllerPos(tube) != null;
    }

    /** The first tube among a contraption's blocks that carries a computer. */
    public static BlockEntity boundTube(Iterable<BlockEntity> blocks) {
        if (blocks == null) {
            return null;
        }
        for (BlockEntity block : blocks) {
            if (isBound(block)) {
                return block;
            }
        }
        return null;
    }

    private static BlockPos controllerPos(BlockEntity tube) {
        if (tube == null) {
            return null;
        }
        try {
            CompoundTag data = tube.getPersistentData();
            return data.contains(CONTROLLER_POS) ? BlockPos.of(data.getLong(CONTROLLER_POS)) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void bindTube(BlockEntity tube, BlockPos controllerPos, UUID subLevel) {
        try {
            CompoundTag data = tube.getPersistentData();
            data.putLong(CONTROLLER_POS, controllerPos.asLong());
            if (subLevel != null) {
                data.putUUID(CONTROLLER_SUB, subLevel);
            } else {
                data.remove(CONTROLLER_SUB);
            }
            tube.setChanged();
        } catch (Throwable ignored) {
        }
    }

    private static void clearTube(BlockEntity tube) {
        try {
            CompoundTag data = tube.getPersistentData();
            data.remove(CONTROLLER_POS);
            data.remove(CONTROLLER_SUB);
            tube.setChanged();
        } catch (Throwable ignored) {
        }
    }
}

package com.qkdream.firecontrolcompat;

import com.cainiao1053.cbcmoreshells.blocks.ammo_rack.AmmoRackBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Access to the CBCMS water-jacket ammunition rack, the block the crew also
 * uses as an ammunition store. Rounds are handed over through the rack's own
 * item handler and the rack's filter says which round it wants, so a maid
 * filling it takes exactly what a player set on the filter.
 */
public final class AmmoRackCompat {

    /** Block entity type id of both rack sizes. */
    private static final String RACK_ID = "cbcmoreshells:ammo_rack";

    private AmmoRackCompat() {
    }

    public static boolean isRack(Level level, BlockPos pos) {
        return FireControlCompat.isModLoaded("cbcmoreshells") && rack(level, pos) != null;
    }

    /** The round the rack's filter asks for, {@code EMPTY} when no filter is set. */
    public static ItemStack wanted(Level level, BlockPos pos) {
        AmmoRackBlockEntity rack = rack(level, pos);
        if (rack == null) {
            return ItemStack.EMPTY;
        }
        try {
            ItemStack filter = rack.getFilter() == null ? ItemStack.EMPTY : rack.getFilter().getFilter();
            return filter == null ? ItemStack.EMPTY : filter;
        } catch (Throwable t) {
            FireControlCompat.LOGGER.debug("[firecontrolcompat] rack filter read failed at {}", pos, t);
            return ItemStack.EMPTY;
        }
    }

    /** Rounds the rack holds, summing every stack it carries. */
    public static int count(Level level, BlockPos pos) {
        AmmoRackBlockEntity rack = rack(level, pos);
        IItemHandler handler = rack == null ? null : handler(rack);
        if (handler == null) {
            return 0;
        }
        int total = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            total += handler.getStackInSlot(slot).getCount();
        }
        return total;
    }

    /** Takes one round out of the rack, honouring the filter when one is set. */
    public static ItemStack take(Level level, BlockPos pos) {
        AmmoRackBlockEntity rack = rack(level, pos);
        IItemHandler handler = rack == null ? null : handler(rack);
        if (handler == null) {
            return ItemStack.EMPTY;
        }
        ItemStack filter = wanted(level, pos);
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack inSlot = handler.getStackInSlot(slot);
            if (inSlot.isEmpty()) {
                continue;
            }
            if (!filter.isEmpty() && !ItemStack.isSameItemSameComponents(filter, inSlot)) {
                continue;
            }
            ItemStack taken = handler.extractItem(slot, 1, false);
            if (!taken.isEmpty()) {
                return taken;
            }
        }
        return ItemStack.EMPTY;
    }

    /** Puts a round into the rack; returns true when it was stored. */
    public static boolean store(Level level, BlockPos pos, ItemStack round) {
        AmmoRackBlockEntity rack = rack(level, pos);
        IItemHandler handler = rack == null ? null : handler(rack);
        if (handler == null || round.isEmpty()) {
            return false;
        }
        ItemStack remaining = handler.insertItem(0, round.copyWithCount(1), false);
        if (remaining.isEmpty()) {
            return true;
        }
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            remaining = handler.insertItem(slot, round.copyWithCount(1), false);
            if (remaining.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** Nearest rack within {@code radius} blocks of the given point, or null. */
    public static BlockPos findNearest(Level level, Vec3 from, double radius) {
        if (!FireControlCompat.isModLoaded("cbcmoreshells") || level == null || from == null) {
            return null;
        }
        BlockPos origin = BlockPos.containing(from);
        int span = (int) Math.ceil(radius);
        BlockPos best = null;
        double bestDistance = radius * radius;
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-span, -span, -span), origin.offset(span, span, span))) {
            if (!level.isLoaded(pos)) {
                continue;
            }
            if (!RACK_ID.equals(rackId(level, pos))) {
                continue;
            }
            double distance = Vec3.atCenterOf(pos).distanceToSqr(from);
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = pos.immutable();
            }
        }
        return best;
    }

    private static String rackId(Level level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        return blockEntity == null ? "" : net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE
                .getKey(blockEntity.getType()).toString();
    }

    private static AmmoRackBlockEntity rack(Level level, BlockPos pos) {
        if (level == null || pos == null) {
            return null;
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);
        return blockEntity instanceof AmmoRackBlockEntity rack ? rack : null;
    }

    private static IItemHandler handler(AmmoRackBlockEntity rack) {
        try {
            return rack.getItemHandler();
        } catch (Throwable t) {
            FireControlCompat.LOGGER.debug("[firecontrolcompat] rack handler read failed", t);
            return null;
        }
    }
}

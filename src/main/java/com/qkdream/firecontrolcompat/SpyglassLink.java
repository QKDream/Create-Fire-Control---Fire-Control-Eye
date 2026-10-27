package com.qkdream.firecontrolcompat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

/**
 * The fire control operators a spyglass is pointed at.
 *
 * <p>A gunner calls an operator onto a target by looking at the structure
 * through the scope and pulling the trigger, so the scope has to know whose
 * guns to call: the operators linked to it, and only those. The list lives on
 * the item rather than on the player, so two scopes can be pointed at two
 * different crews, and it travels with the item the same way the ammunition
 * loading table's own binding does.</p>
 */
public final class SpyglassLink {

    private static final String KEY = "FireControlMaids";
    private static final String ID_TAG = "Id";

    /** How many operators one scope may hold, so a list cannot grow without bound. */
    public static final int MAX_LINKS = 16;

    private SpyglassLink() {
    }

    /** Whether that stack is the scope this feature hangs off. */
    public static boolean isSpyglass(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.is(Items.SPYGLASS);
    }

    /** The operators this scope is pointed at, in the order they were linked. */
    public static List<UUID> linkedMaids(ItemStack stack) {
        if (!isSpyglass(stack)) {
            return List.of();
        }
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        if (!tag.contains(KEY, Tag.TAG_LIST)) {
            return List.of();
        }
        ListTag list = tag.getList(KEY, Tag.TAG_COMPOUND);
        List<UUID> maids = new ArrayList<>(list.size());
        for (int index = 0; index < list.size(); index++) {
            CompoundTag entry = list.getCompound(index);
            if (entry.hasUUID(ID_TAG)) {
                maids.add(entry.getUUID(ID_TAG));
            }
        }
        return maids;
    }

    /**
     * Adds or removes one operator on this scope; true when she was added,
     * false when she was already there and has just been dropped. A list that
     * is already full refuses new entries rather than dropping an old one.
     */
    public static boolean toggle(ItemStack stack, UUID maid) {
        if (!isSpyglass(stack) || maid == null) {
            return false;
        }
        List<UUID> maids = new ArrayList<>(linkedMaids(stack));
        if (maids.remove(maid)) {
            write(stack, maids);
            return false;
        }
        if (maids.size() >= MAX_LINKS) {
            return false;
        }
        maids.add(maid);
        write(stack, maids);
        return true;
    }

    private static void write(ItemStack stack, List<UUID> maids) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            ListTag list = new ListTag();
            for (UUID maid : maids) {
                CompoundTag entry = new CompoundTag();
                entry.putUUID(ID_TAG, maid);
                list.add(entry);
            }
            tag.put(KEY, list);
        });
    }
}
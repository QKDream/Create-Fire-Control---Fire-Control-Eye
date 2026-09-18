package com.qkdream.firecontrolcompat.iff;

import net.minecraft.world.item.ItemStack;

/**
 * Two-item identity code, mirroring how Create's wireless redstone pairs two
 * frequency items. A band only counts once both slots are filled.
 */
public record IffBand(ItemStack first, ItemStack second) {

    public static final IffBand EMPTY = new IffBand(ItemStack.EMPTY, ItemStack.EMPTY);

    public IffBand {
        first = single(first);
        second = single(second);
    }

    private static ItemStack single(ItemStack stack) {
        return stack == null || stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1);
    }

    /** Convenience factory; the two items may be empty, {@link #valid()} reports that. */
    public static IffBand of(ItemStack first, ItemStack second) {
        return new IffBand(first, second);
    }

    public boolean valid() {
        return !this.first.isEmpty() && !this.second.isEmpty();
    }

    public boolean sameAs(IffBand other) {
        if (other == null || !this.valid() || !other.valid()) {
            return false;
        }
        return ItemStack.isSameItemSameComponents(this.first, other.first)
                && ItemStack.isSameItemSameComponents(this.second, other.second);
    }
}
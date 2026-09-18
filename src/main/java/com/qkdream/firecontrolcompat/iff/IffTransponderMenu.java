package com.qkdream.firecontrolcompat.iff;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** Two frequency slots plus the player inventory, used by the transponder block and the fire control screen. */
public class IffTransponderMenu extends AbstractContainerMenu {

    public static final int BAND_SLOTS = 2;

    private final Container container;

    public IffTransponderMenu(int id, Inventory inventory, RegistryFriendlyByteBuf data) {
        this(id, inventory, new SimpleContainer(BAND_SLOTS));
    }

    public IffTransponderMenu(int id, Inventory inventory, Container container) {
        super(IffRegistry.IFF_MENU.get(), id);
        this.container = container;
        container.startOpen(inventory.player);

        this.addSlot(new BandSlot(container, 0, 44, 35));
        this.addSlot(new BandSlot(container, 1, 80, 35));

        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                this.addSlot(new Slot(inventory, column + row * 9 + 9, 8 + column * 18, 84 + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            this.addSlot(new Slot(inventory, column, 8 + column * 18, 142));
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack result = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        result = stack.copy();
        if (index < BAND_SLOTS) {
            if (!this.moveItemStackTo(stack, BAND_SLOTS, this.slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else if (!this.moveItemStackTo(stack, 0, BAND_SLOTS, false)) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return result;
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        this.container.stopOpen(player);
    }

    @Override
    public boolean stillValid(Player player) {
        return this.container.stillValid(player);
    }

    private static final class BandSlot extends Slot {

        BandSlot(Container container, int index, int x, int y) {
            super(container, index, x, y);
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }
    }
}
package com.qkdream.firecontrolcompat.iff;

import com.qkdream.firecontrolcompat.network.IffBandPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Frequency band screen. The two slots are only a place to drop the items that
 * define the band: as soon as both are filled the pair is recorded on the
 * transponder block and the items are handed straight back to the player, so
 * the block itself never stores an inventory.
 */
public class IffTransponderMenu extends AbstractContainerMenu {

    public static final int BAND_SLOTS = 2;

    /** Scratch area for the two items; emptied the moment the band is read. */
    private final Container input = new SimpleContainer(BAND_SLOTS);

    /** Null on the client, where the band is drawn from the synced copy. */
    private final IffTransponderBlockEntity transponder;

    private final Player player;
    private boolean settling;

    public IffTransponderMenu(int id, Inventory inventory, RegistryFriendlyByteBuf data) {
        this(id, inventory, (IffTransponderBlockEntity) null);
    }

    public IffTransponderMenu(int id, Inventory inventory, IffTransponderBlockEntity transponder) {
        super(IffRegistry.IFF_MENU.get(), id);
        this.transponder = transponder;
        this.player = inventory.player;
        this.input.startOpen(inventory.player);

        this.addSlot(new BandSlot(this.input, 0, 44, 35));
        this.addSlot(new BandSlot(this.input, 1, 80, 35));

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
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        super.clicked(slotId, button, clickType, player);
        this.readBand();
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        if (player.level().isClientSide()) {
            return;
        }
        for (int slot = 0; slot < BAND_SLOTS; slot++) {
            ItemStack stack = this.input.getItem(slot);
            if (!stack.isEmpty()) {
                this.input.setItem(slot, ItemStack.EMPTY);
                this.giveBack(stack);
            }
        }
    }

    /** Forgets the recorded band; used by the screen's clear button. */
    public void clearBand() {
        if (this.transponder == null || this.player.level().isClientSide()) {
            return;
        }
        this.transponder.clearBand();
        IffBandPayload.send(this.player, IffBand.EMPTY);
        this.player.displayClientMessage(
                Component.translatable("message.firecontrolcompat.iff.band_cleared"), true);
    }

    /**
     * Reads the band once both slots hold an item: the pair is written to the
     * block and both items go straight back to the player, so nothing is ever
     * consumed and the block keeps no items of its own.
     */
    private void readBand() {
        if (this.settling || this.player.level().isClientSide()) {
            return;
        }
        ItemStack first = this.input.getItem(0);
        ItemStack second = this.input.getItem(1);
        if (first.isEmpty() || second.isEmpty()) {
            return;
        }
        this.settling = true;
        try {
            this.input.setItem(0, ItemStack.EMPTY);
            this.input.setItem(1, ItemStack.EMPTY);
            if (this.transponder != null) {
                this.transponder.setBand(IffBand.of(first, second));
                IffBandPayload.send(this.player, this.transponder.band());
                this.player.displayClientMessage(
                        Component.translatable("message.firecontrolcompat.iff.band_set"), true);
            }
            this.giveBack(first);
            this.giveBack(second);
        } finally {
            this.settling = false;
        }
    }

    /** Returns an item to the player, dropping it on the ground if there is no room. */
    private void giveBack(ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        ItemStack remainder = stack.copy();
        this.player.getInventory().add(remainder);
        if (!remainder.isEmpty()) {
            this.player.drop(remainder, false);
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
    public boolean stillValid(Player player) {
        if (this.transponder == null) {
            return true;
        }
        BlockPos pos = this.transponder.getBlockPos();
        return this.transponder.getLevel() != null
                && this.transponder.getLevel().getBlockEntity(pos) == this.transponder
                && player.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) <= 64.0D;
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
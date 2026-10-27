package com.qkdream.firecontrolcompat.crate;

import com.qkdream.firecontrolcompat.AmmoLoadingCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Storage of one ammunition type, at most {@link #CAPACITY} rounds.
 *
 * <p>The crate keeps a single stack plus a counter instead of eight slots, so
 * "one type only" is structural rather than a rule that has to be checked in
 * every path. Only the ammunition families the fire control eye works with are
 * accepted: the missiles of 面包学, of TAOV weapons and of this mod itself.</p>
 */
public class AmmoCrateBlockEntity extends BlockEntity {

    /** Rounds a single crate holds. */
    public static final int CAPACITY = 8;

    private static final String KEY_ITEM = "StoredItem";
    private static final String KEY_COUNT = "StoredCount";

    private ItemStack stored = ItemStack.EMPTY;
    private int count;

    public AmmoCrateBlockEntity(BlockPos pos, BlockState state) {
        super(com.qkdream.firecontrolcompat.crate.AmmoCrateRegistry.AMMO_CRATE_BE.get(), pos, state);
    }

    /** The stored type as a single round, {@code EMPTY} when the crate is empty. */
    public ItemStack storedType() {
        return this.stored.isEmpty() ? ItemStack.EMPTY : this.stored.copyWithCount(1);
    }

    public int count() {
        return this.count;
    }

    public boolean isEmpty() {
        return this.stored.isEmpty() || this.count <= 0;
    }

    /** True when the crate is empty or already holds the very same round. */
    public boolean canAccept(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !AmmoLoadingCompat.isMissileAmmo(stack)) {
            return false;
        }
        if (this.isEmpty()) {
            return true;
        }
        return ItemStack.isSameItemSameComponents(this.stored, stack);
    }

    /** Inserts as many rounds of the given stack as fit; returns the accepted amount. */
    public int insert(ItemStack stack) {
        if (!this.canAccept(stack)) {
            return 0;
        }
        int room = CAPACITY - this.count;
        if (room <= 0) {
            return 0;
        }
        int accepted = Math.min(room, stack.getCount());
        if (accepted <= 0) {
            return 0;
        }
        if (this.stored.isEmpty()) {
            this.stored = stack.copyWithCount(1);
        }
        this.count += accepted;
        this.setChanged();
        this.sync();
        return accepted;
    }

    /** Removes a single round, {@code EMPTY} when there was none. */
    public ItemStack extractOne() {
        if (this.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack out = this.stored.copyWithCount(1);
        this.count--;
        if (this.count <= 0) {
            this.count = 0;
            this.stored = ItemStack.EMPTY;
        }
        this.setChanged();
        this.sync();
        return out;
    }

    /** Drops every round that is still inside; used when the crate is broken. */
    public void dropContents() {
        if (this.level == null || this.level.isClientSide()) {
            return;
        }
        while (!this.isEmpty()) {
            ItemStack out = this.extractOne();
            if (out.isEmpty()) {
                break;
            }
            net.minecraft.world.Containers.dropItemStack(this.level, this.worldPosition.getX() + 0.5,
                    this.worldPosition.getY() + 0.5, this.worldPosition.getZ() + 0.5, out);
        }
    }

    private void sync() {
        if (this.level != null && !this.level.isClientSide()) {
            this.level.sendBlockUpdated(this.worldPosition, this.getBlockState(), this.getBlockState(), 3);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt(KEY_COUNT, this.count);
        if (!this.stored.isEmpty()) {
            tag.put(KEY_ITEM, this.stored.save(registries));
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.count = Math.max(0, Math.min(CAPACITY, tag.getInt(KEY_COUNT)));
        this.stored = tag.contains(KEY_ITEM)
                ? ItemStack.parse(registries, tag.getCompound(KEY_ITEM)).orElse(ItemStack.EMPTY)
                : ItemStack.EMPTY;
        if (this.stored.isEmpty()) {
            this.count = 0;
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return this.saveWithoutMetadata(registries);
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    /** Rounds of the given launcher type this crate can still supply. */
    public int availableFor(ItemStack wanted) {
        if (wanted == null || wanted.isEmpty() || this.isEmpty()) {
            return 0;
        }
        return ItemStack.isSameItemSameComponents(this.stored, wanted) ? this.count : 0;
    }
}

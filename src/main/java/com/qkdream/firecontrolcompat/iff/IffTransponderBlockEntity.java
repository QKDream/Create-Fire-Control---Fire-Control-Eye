package com.qkdream.firecontrolcompat.iff;

import com.qkdream.firecontrolcompat.PhysicsGroups;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Stores the IFF frequency band (two items) and the fire control computer it
 * was wrenched onto. The band is compared against every other transponder to
 * decide whether a radar contact is friendly, hostile or unknown.
 */
public class IffTransponderBlockEntity extends BlockEntity implements Container, MenuProvider {

    public static final int SLOTS = 2;

    private static final Set<IffTransponderBlockEntity> LOADED = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, Presence> PRESENCE_CACHE = new HashMap<>();
    private static final Map<UUID, Set<UUID>> PRESENCE_CHAIN_CACHE = new HashMap<>();
    private static long presenceCacheTick = Long.MIN_VALUE;

    private final NonNullList<ItemStack> items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);

    private BlockPos controllerPos;
    private UUID controllerSubLevel;
    private ResourceLocation controllerDimension;

    public IffTransponderBlockEntity(BlockPos pos, BlockState state) {
        super(IffRegistry.IFF_TRANSPONDER_BE.get(), pos, state);
    }

    // ------------------------------------------------------------------ band

    public IffBand band() {
        return IffBand.of(this.items.get(0), this.items.get(1));
    }

    // ------------------------------------------------------------------ link

    public void linkTo(ResourceLocation dimension, BlockPos pos, UUID subLevel) {
        this.controllerPos = pos.immutable();
        this.controllerSubLevel = subLevel;
        this.controllerDimension = dimension;
        this.setChanged();
    }

    public void unlink() {
        this.controllerPos = null;
        this.controllerSubLevel = null;
        this.controllerDimension = null;
        this.setChanged();
    }

    public boolean isLinked() {
        return this.controllerPos != null;
    }

    public boolean linkedTo(ResourceLocation dimension, BlockPos pos, UUID subLevel) {
        return this.controllerPos != null
                && this.controllerPos.equals(pos)
                && Objects.equals(this.controllerSubLevel, subLevel)
                && Objects.equals(this.controllerDimension, dimension);
    }

    public static IffTransponderBlockEntity findForController(Level level, BlockPos pos, UUID subLevel) {
        ResourceLocation dimension = level.dimension().location();
        for (IffTransponderBlockEntity transponder : LOADED) {
            if (!transponder.isRemoved() && transponder.linkedTo(dimension, pos, subLevel)) {
                return transponder;
            }
        }
        return null;
    }

    /** Finds a loaded transponder by its own level identity. */
    public static IffTransponderBlockEntity findAt(
            ResourceLocation dimension, BlockPos pos, UUID subLevel) {
        for (IffTransponderBlockEntity transponder : LOADED) {
            if (transponder.isRemoved()) {
                continue;
            }
            Level level = transponder.getLevel();
            if (level == null
                    || !dimension.equals(level.dimension().location())
                    || !pos.equals(transponder.getBlockPos())) {
                continue;
            }
            if (Objects.equals(subLevel, containingSubLevelId(level, transponder.getBlockPos()))) {
                return transponder;
            }
        }
        return null;
    }

    // -------------------------------------------------------- radar identity

    /** Whether a structure carries a transponder, and every band found there. */
    public record Presence(boolean present, List<IffBand> bands) {

        public static final Presence ABSENT = new Presence(false, List.of());
    }

    public static UUID containingSubLevelId(Level level, BlockPos pos) {
        if (level == null || pos == null) {
            return null;
        }
        try {
            SubLevel subLevel = Sable.HELPER.getContaining(level, pos);
            return subLevel == null || subLevel.isRemoved() ? null : subLevel.getUniqueId();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Cached for one game tick; the radar asks once per contact and per player.
     * A sub-structure inherits the band of the group it is docked to, so
     * ordnance released by a child structure is marked like the whole group.
     */
    public static Presence presenceInSubLevel(long gameTime, Level level, UUID subLevelId) {
        if (subLevelId == null) {
            return Presence.ABSENT;
        }
        if (presenceCacheTick != gameTime) {
            presenceCacheTick = gameTime;
            PRESENCE_CACHE.clear();
            PRESENCE_CHAIN_CACHE.clear();
        }
        Presence cached = PRESENCE_CACHE.get(subLevelId);
        if (cached != null) {
            return cached;
        }
        Presence scanned = scanSubLevel(level, subLevelId);
        PRESENCE_CACHE.put(subLevelId, scanned);
        return scanned;
    }

    /**
     * The structure plus the whole platform it belongs to: every connected or
     * welded body shares one identity, so ordnance released by a sub-structure
     * inherits the band of the mother body.
     */
    private static Set<UUID> relatedSubLevels(Level level, UUID subLevelId) {
        Set<UUID> cached = PRESENCE_CHAIN_CACHE.get(subLevelId);
        if (cached != null) {
            return cached;
        }
        Set<UUID> related = PhysicsGroups.group(level, subLevelId);
        PRESENCE_CHAIN_CACHE.put(subLevelId, related);
        return related;
    }
    private static Presence scanSubLevel(Level level, UUID subLevelId) {
        Set<UUID> related = relatedSubLevels(level, subLevelId);
        boolean present = false;
        List<IffBand> bands = null;
        for (IffTransponderBlockEntity transponder : LOADED) {
            if (transponder.isRemoved()) {
                continue;
            }
            Level hostLevel = transponder.getLevel();
            if (hostLevel == null) {
                continue;
            }
            UUID host = containingSubLevelId(hostLevel, transponder.getBlockPos());
            if (host == null || !related.contains(host)) {
                continue;
            }
            present = true;
            IffBand band = transponder.band();
            if (band.valid()) {
                if (bands == null) {
                    bands = new ArrayList<>(2);
                }
                bands.add(band);
            }
        }
        return present ? new Presence(true, bands == null ? List.of() : bands) : Presence.ABSENT;
    }
    // --------------------------------------------------------------- lifecycle

    @Override
    public void setLevel(Level level) {
        super.setLevel(level);
        LOADED.add(this);
    }

    @Override
    public void setRemoved() {
        LOADED.remove(this);
        super.setRemoved();
    }

    @Override
    public void clearRemoved() {
        super.clearRemoved();
        LOADED.add(this);
    }

    public void dropContents() {
        Level level = this.getLevel();
        if (level == null) {
            return;
        }
        for (ItemStack stack : this.items) {
            if (!stack.isEmpty()) {
                net.minecraft.world.Containers.dropItemStack(
                        level,
                        this.worldPosition.getX() + 0.5D,
                        this.worldPosition.getY() + 0.5D,
                        this.worldPosition.getZ() + 0.5D,
                        stack);
            }
        }
        this.items.clear();
    }

    // ------------------------------------------------------------------- nbt

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ContainerHelper.saveAllItems(tag, this.items, registries);
        BlockPos pos = this.controllerPos;
        if (pos != null) {
            tag.putLong("IffController", pos.asLong());
            if (this.controllerDimension != null) {
                tag.putString("IffControllerDim", this.controllerDimension.toString());
            }
            if (this.controllerSubLevel != null) {
                tag.putUUID("IffControllerSub", this.controllerSubLevel);
            }
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.items.clear();
        ContainerHelper.loadAllItems(tag, this.items, registries);
        if (tag.contains("IffController")) {
            this.controllerPos = BlockPos.of(tag.getLong("IffController"));
            this.controllerDimension = tag.contains("IffControllerDim")
                    ? ResourceLocation.tryParse(tag.getString("IffControllerDim"))
                    : null;
            this.controllerSubLevel = tag.hasUUID("IffControllerSub")
                    ? tag.getUUID("IffControllerSub")
                    : null;
        } else {
            this.controllerPos = null;
            this.controllerDimension = null;
            this.controllerSubLevel = null;
        }
    }

    // --------------------------------------------------------------- container

    @Override
    public int getContainerSize() {
        return SLOTS;
    }

    @Override
    public boolean isEmpty() {
        for (ItemStack stack : this.items) {
            if (!stack.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        return this.items.get(slot);
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        ItemStack removed = ContainerHelper.removeItem(this.items, slot, amount);
        if (!removed.isEmpty()) {
            this.setChanged();
        }
        return removed;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return ContainerHelper.takeItem(this.items, slot);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        this.items.set(slot, stack);
        if (stack.getCount() > this.getMaxStackSize()) {
            stack.setCount(this.getMaxStackSize());
        }
        this.setChanged();
    }

    @Override
    public int getMaxStackSize() {
        return 1;
    }

    @Override
    public boolean stillValid(Player player) {
        return Container.stillValidBlockEntity(this, player);
    }

    @Override
    public void clearContent() {
        this.items.clear();
        this.setChanged();
    }

    // ------------------------------------------------------------------ menu

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.firecontrolcompat.iff_transponder");
    }

    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        return new IffTransponderMenu(id, inventory, this);
    }
}

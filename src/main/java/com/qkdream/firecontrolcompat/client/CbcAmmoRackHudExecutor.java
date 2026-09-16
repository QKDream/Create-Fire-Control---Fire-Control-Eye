package com.qkdream.firecontrolcompat.client;

import com.cainiao1053.cbcmoreshells.blocks.ammo_rack.AmmoRackBlockEntity;
import com.qkdream.firecontrolcompat.FireControlCompat;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;
import com.verr1.taov.core.weaponhud.WeaponHudEvent;
import com.verr1.taov.core.weaponhud.client.WeaponHudComposeContext;
import com.verr1.taov.core.weaponhud.client.WeaponHudContext;
import com.verr1.taov.core.weaponhud.client.WeaponHudExecutorState;
import com.verr1.taov.core.weaponhud.client.WeaponHudExecutorType;
import com.verr1.taov.core.weaponhud.client.WeaponHudExecutors;
import com.verr1.taov.core.weaponhud.client.WeaponHudRenderContext;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;

/**
 * Client-side HUD renderer for the CBCMS water-jacket ammo racks bound
 * through the TAOV HUD-linker: shows the currently selected ammunition type
 * as the card icon (the rack's filter slot) and the remaining count of that
 * type in the rack.
 */
public final class CbcAmmoRackHudExecutor
        implements WeaponHudExecutorType<AmmoRackBlockEntity, CbcAmmoRackHudExecutor.State> {

    public static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(FireControlCompat.MOD_ID, "weapon_hud/cbc_ammo_rack");

    public enum State implements WeaponHudExecutorState {
        IDLE
    }

    public static void register() {
        if (!ModList.get().isLoaded("cbcmoreshells")) {
            return;
        }
        try {
            WeaponHudExecutors.register(new CbcAmmoRackHudExecutor());
        } catch (Throwable ignored) {
            FireControlCompat.LOGGER.warn("[firecontrolcompat] CBCMS ammo rack HUD executor unavailable", ignored);
        }
    }

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public Class<AmmoRackBlockEntity> sourceClass() {
        return AmmoRackBlockEntity.class;
    }

    @Override
    public Class<State> stateClass() {
        return State.class;
    }

    @Override
    public State createState(WeaponHudContext context, AmmoRackBlockEntity source) {
        return State.IDLE;
    }

    @Override
    public State tick(WeaponHudContext context, AmmoRackBlockEntity source, State state) {
        return State.IDLE;
    }

    @Override
    public State handleEvent(
            WeaponHudContext context, AmmoRackBlockEntity source, State state, WeaponHudEvent event) {
        return State.IDLE;
    }

    @Override
    public void renderOverlay(WeaponHudRenderContext context, AmmoRackBlockEntity source, State state) {
    }

    @Override
    public void contribute(WeaponHudComposeContext context, AmmoRackBlockEntity source, State state) {
        ItemStack selected = selectedAmmo(source);
        ItemStack icon = selected.isEmpty()
                ? new ItemStack(source.getBlockState().getBlock().asItem())
                : selected.copy();
        context.sink()
                .card(
                        ResourceLocation.fromNamespaceAndPath(
                                FireControlCompat.MOD_ID,
                                "weapon_hud/source/" + context.base().source().instanceId()),
                        context.base().source().priority(),
                        icon,
                        selected.isEmpty() ? 0 : countMatching(source, selected),
                        0.0F);
    }

    private static ItemStack selectedAmmo(AmmoRackBlockEntity source) {
        FilteringBehaviour filter = source.getFilter();
        if (filter == null) {
            return ItemStack.EMPTY;
        }
        ItemStack selected = filter.getFilter();
        return selected == null ? ItemStack.EMPTY : selected;
    }

    private static int countMatching(AmmoRackBlockEntity source, ItemStack selected) {
        Item targetItem = selected.getItem();
        int total = 0;
        for (int slot = 0; slot < source.getInventory().getSlots(); slot++) {
            ItemStack stack = source.getInventory().getStackInSlot(slot);
            if (!stack.isEmpty() && stack.getItem() == targetItem) {
                total += stack.getCount();
            }
        }
        return total;
    }
}

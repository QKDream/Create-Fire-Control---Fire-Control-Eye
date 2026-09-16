package com.qkdream.firecontrolcompat;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * Detects the TAOV weapon HUD-linker by registry name.
 *
 * <p>The check deliberately avoids referencing any TAOV class: it runs for
 * every click on a Mianbao launcher or a CBCMS ammo rack, and loading a
 * TAOV dependent helper there would throw when TAOV is not installed.</p>
 */
public final class WeaponHudLinker {

    public static final ResourceLocation ITEM_ID =
            ResourceLocation.fromNamespaceAndPath("taov_weapons", "weapon_hud_linker");

    private WeaponHudLinker() {
    }

    public static boolean isLinker(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !FireControlCompat.isModLoaded("taov_weapons")) {
            return false;
        }
        try {
            return ITEM_ID.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()));
        } catch (Throwable ignored) {
            return false;
        }
    }
}

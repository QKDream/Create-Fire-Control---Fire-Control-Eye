package com.qkdream.firecontrolcompat.compat.cbcmodernwarfare.item;

import com.qkdream.firecontrolcompat.compat.cbcmodernwarfare.CbcModernWarfareCompat;

import net.minecraft.world.item.ItemStack;
import riftyboi.cbcmodernwarfare.munitions.medium_cannon.MediumcannonCartridgeItem;

public class AtgmMediumcannonCartridgeItem extends MediumcannonCartridgeItem {

    public AtgmMediumcannonCartridgeItem(Properties properties) {
        super(properties);
    }

    /** A cartridge that already carries this round, for the creative menus. */
    public ItemStack getCreativeTabCartridgeItem() {
        ItemStack cartridge = new ItemStack(this);
        MediumcannonCartridgeItem.writeProjectile(
                new ItemStack(CbcModernWarfareCompat.ATGM_MEDIUMCANNON_ROUND.get()), cartridge);
        return cartridge;
    }
}
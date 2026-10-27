package com.qkdream.firecontrolcompat.compat.cbcmodernwarfare.item;

import net.minecraft.world.item.ItemStack;
import riftyboi.cbcmodernwarfare.munitions.medium_cannon.MediumcannonCartridgeItem;

import com.qkdream.firecontrolcompat.compat.cbcmodernwarfare.CbcModernWarfareCompat;

public class TrafficConeMediumcannonCartridgeItem extends MediumcannonCartridgeItem {

    public TrafficConeMediumcannonCartridgeItem(Properties properties) {
        super(properties);
    }

    /** A cartridge that already carries this round, for the creative menus. */
    public ItemStack getCreativeTabCartridgeItem() {
        ItemStack cartridge = new ItemStack(this);
        MediumcannonCartridgeItem.writeProjectile(
                new ItemStack(CbcModernWarfareCompat.TRAFFIC_CONE_MEDIUMCANNON_ROUND.get()), cartridge);
        return cartridge;
    }
}

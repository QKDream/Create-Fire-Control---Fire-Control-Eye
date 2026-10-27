package com.qkdream.firecontrolcompat.compat.cbcmodernwarfare.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import riftyboi.cbcmodernwarfare.munitions.medium_cannon.apfsds.APFSDSMediumcannonProjectile;

import com.qkdream.firecontrolcompat.compat.cbcmodernwarfare.CbcModernWarfareCompat;

/**
 * Traffic cone medium cannon shell. It borrows the discarding sabot shell for
 * its ballistics - and therefore keeps the same inert projectile behaviour, no
 * explosion at all - while the shell properties in the data pack push the
 * penetration far past every other round in the pack.
 */
public class TrafficConeMediumcannonProjectile extends APFSDSMediumcannonProjectile {

    public TrafficConeMediumcannonProjectile(EntityType<? extends TrafficConeMediumcannonProjectile> type, Level level) {
        super(type, level);
    }

    @Override
    public ItemStack getItem() {
        return new ItemStack(CbcModernWarfareCompat.TRAFFIC_CONE_MEDIUMCANNON_ROUND.get());
    }
}

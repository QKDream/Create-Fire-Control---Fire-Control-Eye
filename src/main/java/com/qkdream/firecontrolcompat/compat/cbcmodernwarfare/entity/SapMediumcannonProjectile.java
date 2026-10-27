package com.qkdream.firecontrolcompat.compat.cbcmodernwarfare.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import riftyboi.cbcmodernwarfare.munitions.medium_cannon.aphe.APHEMediumcannonProjectile;

import com.qkdream.firecontrolcompat.compat.cbcmodernwarfare.CbcModernWarfareCompat;

/**
 * Semi-armour-piercing medium cannon shell. It is built on the armour-piercing
 * high-explosive shell so it keeps the reduced charge and the stronger
 * penetration, and it carries no fuze of its own: like every other round of the
 * pack it only goes off when a fuze item has been fitted to it in the
 * assembly bench.
 *
 * <p>This round deliberately makes no metal jet. A semi-armour-piercing shell
 * has a small charge behind a thick cap rather than a shaped charge cone, so the
 * jet belongs to the HEAT rounds: CBC Modern Warfare's medium cannon and big
 * cannon HEAT rounds, and this mod's own gun launched missile. The jet is
 * attached to those three at their detonation (see {@code MetalJetTrigger}).</p>
 */
public class SapMediumcannonProjectile extends APHEMediumcannonProjectile {

    public SapMediumcannonProjectile(EntityType<? extends SapMediumcannonProjectile> type, Level level) {
        super(type, level);
    }

    @Override
    public ItemStack getItem() {
        return new ItemStack(CbcModernWarfareCompat.SAP_MEDIUMCANNON_ROUND.get());
    }
}

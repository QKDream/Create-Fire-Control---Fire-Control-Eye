package com.qkdream.firecontrolcompat.compat.cbcmodernwarfare.item;

import com.qkdream.firecontrolcompat.compat.cbcmodernwarfare.CbcModernWarfareCompat;
import com.qkdream.firecontrolcompat.compat.cbcmodernwarfare.entity.SensorFuzedMediumcannonProjectile;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import riftyboi.cbcmodernwarfare.index.CBCModernWarfareMunitionPropertiesHandlers;
import riftyboi.cbcmodernwarfare.munitions.ItemStackDataUtil;
import riftyboi.cbcmodernwarfare.munitions.medium_cannon.AbstractMediumcannonProjectile;
import riftyboi.cbcmodernwarfare.munitions.medium_cannon.FuzedMediumItemMunition;
import riftyboi.cbcmodernwarfare.munitions.medium_cannon.MediumcannonRoundItem;
import riftyboi.cbcmodernwarfare.munitions.medium_cannon.config.MediumcannonProjectilePropertiesComponent;
import riftyboi.cbcmodernwarfare.munitions.medium_cannon.heap.HEAPMediumcannonProjectileProperties;

/**
 * Sensor fuzed shell, loaded into a medium cannon like any other round. The
 * shell carries no charge of its own: it only opens once a fuze fitted in the
 * assembly bench tells it to, and it can be given a tracer tip the same way
 * every other medium cannon round can.
 */
public class SfmMediumcannonRoundItem extends MediumcannonRoundItem implements FuzedMediumItemMunition {

    public SfmMediumcannonRoundItem(Properties properties) {
        super(properties);
    }

    @Override
    public AbstractMediumcannonProjectile getMediumcannonProjectile(ItemStack stack, Level level) {
        SensorFuzedMediumcannonProjectile projectile = CbcModernWarfareCompat.SFM_MEDIUMSHELL.get().create(level);
        if (projectile != null) {
            ItemStack fuze = ItemStackDataUtil.getFuze(stack);
            if (!fuze.isEmpty()) {
                projectile.setFuze(fuze);
            }
            if (ItemStackDataUtil.isTracer(stack)) {
                projectile.setTracer(true);
            }
        }
        return projectile;
    }

    @Override
    public MediumcannonProjectilePropertiesComponent getMediumcannonProperties(ItemStack itemStack) {
        return ((HEAPMediumcannonProjectileProperties) CBCModernWarfareMunitionPropertiesHandlers.HEAP_MEDIUMCANNON
                .getPropertiesOf(this.getEntityType(itemStack))).mediumcannonProperties();
    }

    @Override
    public EntityType<?> getEntityType(ItemStack stack) {
        return CbcModernWarfareCompat.SFM_MEDIUMSHELL.get();
    }

    @Override
    public ItemStack getCartridgeType() {
        return new ItemStack(CbcModernWarfareCompat.SFM_MEDIUMCANNON_CARTRIDGE.get());
    }
}
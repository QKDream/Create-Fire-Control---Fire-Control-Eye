package com.qkdream.firecontrolcompat.compat.maid;

import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.datafixers.util.Pair;
import java.util.Collections;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.fml.ModList;

/**
 * Maid task "酒狐装弹": she carries rounds from the ammunition crate next to
 * her into the bound launcher. The task icon is TAOV's Tau missile.
 */
public final class TaskAmmoLoader implements IMaidTask {

    public static final ResourceLocation UID =
            ResourceLocation.fromNamespaceAndPath("firecontrolcompat", "ammo_loader");

    private static final ResourceLocation TAU_ID =
            ResourceLocation.fromNamespaceAndPath("taov_weapons", "tau_missile");

    @Override
    public ResourceLocation getUid() {
        return UID;
    }

    @Override
    public ItemStack getIcon() {
        if (!BuiltInRegistries.ITEM.containsKey(TAU_ID)) {
            return Items.AIR.getDefaultInstance();
        }
        Item tau = BuiltInRegistries.ITEM.get(TAU_ID);
        return tau == Items.AIR ? Items.AIR.getDefaultInstance() : tau.getDefaultInstance();
    }

    @Override
    public SoundEvent getAmbientSound(EntityMaid maid) {
        return null;
    }

    @Override
    public List<Pair<Integer, BehaviorControl<? super EntityMaid>>> createBrainTasks(EntityMaid maid) {
        return Collections.emptyList();
    }

    @Override
    public List<Pair<Integer, BehaviorControl<? super EntityMaid>>> createRideBrainTasks(EntityMaid maid) {
        return Collections.emptyList();
    }

    @Override
    public boolean isEnable(EntityMaid maid) {
        return ModList.get().isLoaded("create_fire_control");
    }

    @Override
    public boolean enableLookAndRandomWalk(EntityMaid maid) {
        return true;
    }

    @Override
    public boolean enablePanic(EntityMaid maid) {
        return false;
    }

    @Override
    public boolean enableEating(EntityMaid maid) {
        return false;
    }

    @Override
    public String getMaidActionSummary() {
        return "Carry the missiles stored in an ammunition crate into the bound launcher";
    }
}

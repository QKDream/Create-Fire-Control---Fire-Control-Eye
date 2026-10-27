package com.qkdream.firecontrolcompat.compat.maid;

import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.datafixers.util.Pair;
import java.util.Collections;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.fml.ModList;

/**
 * Maid task of the manual fire control operator: she keeps intercepting
 * incoming ordnance, but she only shoots at structures a player has marked with
 * a scoped spyglass instead of working the whole scope on her own. The task
 * icon is the vanilla spyglass, the tool that sets those marks.
 */
public final class TaskManualFireControlOperator implements IMaidTask {

    public static final ResourceLocation UID =
            ResourceLocation.fromNamespaceAndPath("firecontrolcompat", "fire_control_operator_manual");

    @Override
    public ResourceLocation getUid() {
        return UID;
    }

    @Override
    public ItemStack getIcon() {
        return Items.SPYGLASS.getDefaultInstance();
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
        return false;
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
        return "Sit on a seat bound to a fire control computer: intercept incoming ordnance, and engage only the structures a player has marked with a scoped spyglass (left click a structure to mark it, left click at nothing to drop the mark)";
    }
}
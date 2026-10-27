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
 * Maid task of the fire control operator: she mans the seat that was bound to a
 * fire control computer and works its weapons. The task icon is the fire
 * control computer item itself.
 */
public final class TaskFireControlOperator implements IMaidTask {

    public static final ResourceLocation UID =
            ResourceLocation.fromNamespaceAndPath("firecontrolcompat", "fire_control_operator");

    private static final ResourceLocation COMPUTER_ID =
            ResourceLocation.fromNamespaceAndPath("create_fire_control", "stabilizer_controller");

    @Override
    public ResourceLocation getUid() {
        return UID;
    }

    @Override
    public ItemStack getIcon() {
        if (!BuiltInRegistries.ITEM.containsKey(COMPUTER_ID)) {
            return Items.AIR.getDefaultInstance();
        }
        Item computer = BuiltInRegistries.ITEM.get(COMPUTER_ID);
        return computer == Items.AIR ? Items.AIR.getDefaultInstance() : computer.getDefaultInstance();
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
        return "Sit on a seat bound to a fire control computer, then work its weapons against hostile or unknown radar contacts and incoming ordnance";
    }
}

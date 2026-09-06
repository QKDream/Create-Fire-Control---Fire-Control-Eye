package com.qkdream.firecontrolcompat.client;

import com.qkdream.firecontrolcompat.FireControlCompat;
import com.qkdream.firecontrolcompat.MianbaoWeaponHud;
import com.verr1.taov.core.weaponhud.WeaponHudEvent;
import com.verr1.taov.core.weaponhud.client.WeaponHudComposeContext;
import com.verr1.taov.core.weaponhud.client.WeaponHudContext;
import com.verr1.taov.core.weaponhud.client.WeaponHudExecutorState;
import com.verr1.taov.core.weaponhud.client.WeaponHudExecutorType;
import com.verr1.taov.core.weaponhud.client.WeaponHudExecutors;
import com.verr1.taov.core.weaponhud.client.WeaponHudRenderContext;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;

/**
 * Client-side HUD renderer for Mianbao launchers and racks bound through the
 * TAOV HUD-linker: draws the standard weapon card with the loaded payload
 * icon and the total remaining ammunition, mirroring TAOV rocket racks.
 */
public final class MianbaoWeaponHudExecutor
        implements WeaponHudExecutorType<RandomizableContainerBlockEntity, MianbaoWeaponHudExecutor.MianbaoState> {

    public static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(FireControlCompat.MOD_ID, "weapon_hud/mianbao");

    public enum MianbaoState implements WeaponHudExecutorState {
        IDLE
    }

    public static void register() {
        WeaponHudExecutors.register(new MianbaoWeaponHudExecutor());
    }

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public Class<RandomizableContainerBlockEntity> sourceClass() {
        return RandomizableContainerBlockEntity.class;
    }

    @Override
    public Class<MianbaoState> stateClass() {
        return MianbaoState.class;
    }

    @Override
    public MianbaoState createState(WeaponHudContext context, RandomizableContainerBlockEntity source) {
        return MianbaoState.IDLE;
    }

    @Override
    public MianbaoState tick(WeaponHudContext context, RandomizableContainerBlockEntity source, MianbaoState state) {
        return MianbaoState.IDLE;
    }

    @Override
    public MianbaoState handleEvent(
            WeaponHudContext context, RandomizableContainerBlockEntity source, MianbaoState state, WeaponHudEvent event) {
        return MianbaoState.IDLE;
    }

    @Override
    public void renderOverlay(WeaponHudRenderContext context, RandomizableContainerBlockEntity source, MianbaoState state) {
    }

    @Override
    public void contribute(WeaponHudComposeContext context, RandomizableContainerBlockEntity source, MianbaoState state) {
        ItemStack icon = firstStack(source);
        context.sink()
                .card(
                        ResourceLocation.fromNamespaceAndPath(
                                FireControlCompat.MOD_ID,
                                "weapon_hud/source/" + context.base().source().instanceId()),
                        context.base().source().priority(),
                        icon.isEmpty() ? new ItemStack(source.getBlockState().getBlock().asItem()) : icon.copy(),
                        MianbaoWeaponHud.ammoCount(source),
                        0.0F);
    }

    private static ItemStack firstStack(RandomizableContainerBlockEntity source) {
        for (int slot = 0; slot < source.getContainerSize(); slot++) {
            ItemStack stack = source.getItem(slot);
            if (!stack.isEmpty()) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

}

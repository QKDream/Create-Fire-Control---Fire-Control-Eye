package com.qkdream.firecontrolcompat.mixin;

import com.qkdream.firecontrolcompat.MianbaoWeaponHud;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Persists HUD-linker seat bindings attached to Mianbao launcher / rack
 * block entities. All of those entities extend RandomizableContainerBlockEntity,
 * and the side table only ever contains entries for registered Mianbao types,
 * so this hook is a no-op for every other container block entity.
 */
@Mixin(RandomizableContainerBlockEntity.class)
public abstract class MianbaoWeaponHudPersistMixin {

    @Inject(method = "saveAdditional", at = @At("TAIL"))
    private void firecontrolcompat$saveWeaponHudBinding(
            CompoundTag tag, HolderLookup.Provider registries, CallbackInfo ci) {
        MianbaoWeaponHud.saveBinding((BlockEntity) (Object) this, tag);
    }

    @Inject(method = "loadAdditional", at = @At("TAIL"))
    private void firecontrolcompat$loadWeaponHudBinding(
            CompoundTag tag, HolderLookup.Provider registries, CallbackInfo ci) {
        MianbaoWeaponHud.loadBinding((BlockEntity) (Object) this, tag);
    }
}

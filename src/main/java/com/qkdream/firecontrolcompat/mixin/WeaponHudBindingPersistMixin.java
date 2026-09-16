package com.qkdream.firecontrolcompat.mixin;

import com.qkdream.firecontrolcompat.MianbaoWeaponHud;
import com.qkdream.firecontrolcompat.CbcAmmoRackHud;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Persists HUD-linker seat bindings attached to Mianbao launcher / rack
 * block entities and CBCMS ammo racks. The side tables only ever contain
 * entries for registered source types, so these hooks are a no-op for every
 * other block entity.
 */
@Mixin(BlockEntity.class)
public abstract class WeaponHudBindingPersistMixin {

    @Inject(method = "saveWithFullMetadata", at = @At("RETURN"))
    private void firecontrolcompat$saveWeaponHudBinding(
            HolderLookup.Provider registries, CallbackInfoReturnable<CompoundTag> cir) {
        BlockEntity self = (BlockEntity) (Object) this;
        MianbaoWeaponHud.saveBinding(self, cir.getReturnValue());
        CbcAmmoRackHud.saveBinding(self, cir.getReturnValue());
    }

    @Inject(method = "loadWithComponents", at = @At("HEAD"))
    private void firecontrolcompat$loadWeaponHudBinding(
            CompoundTag tag, HolderLookup.Provider registries, CallbackInfo ci) {
        BlockEntity self = (BlockEntity) (Object) this;
        MianbaoWeaponHud.loadBinding(self, tag);
        CbcAmmoRackHud.loadBinding(self, tag);
    }
}

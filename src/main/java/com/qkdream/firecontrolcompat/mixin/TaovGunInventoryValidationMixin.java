package com.qkdream.firecontrolcompat.mixin;

import com.qkdream.firecontrolcompat.CbcAutocannonPayloads;
import com.verr1.taov.weapons.content.gun.GunAmmoInventory;
import com.verr1.taov.weapons.content.machinegun.MachineGunInventory;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets native CBC Modern Warfare and CBC Autocannon Revolution rounds be
 * inserted into TAOV's gun (and control-seat machine gun) ammunition
 * inventories. Without this, {@code isItemValid} rejects the rounds before
 * the firing pipeline ever sees them.
 */
@Mixin({GunAmmoInventory.class, MachineGunInventory.class})
public abstract class TaovGunInventoryValidationMixin {

    @Inject(method = "isItemValid", at = @At("HEAD"), cancellable = true)
    private void firecontrolcompat$acceptNativeCbcRounds(int slot, ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (CbcAutocannonPayloads.isCbcRound(stack)) {
            cir.setReturnValue(true);
        }
    }
}

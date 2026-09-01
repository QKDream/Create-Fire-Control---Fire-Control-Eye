package com.qkdream.firecontrolcompat.mixin;

import com.qkdream.firecontrolcompat.CbcAutocannonPayloads;
import com.verr1.taov.weapons.air.shaolib.TaovShaolibPayloadMapper;
import java.util.Optional;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Maps native CBC Modern Warfare and CBC Autocannon Revolution rounds to
 * their own real projectile entities in TAOV's machine gun payload mapper.
 * Rounds that do not resolve through {@link CbcAutocannonPayloads} fall
 * through to TAOV's original AP/flak/machine gun mappings.
 */
@Mixin(TaovShaolibPayloadMapper.class)
public abstract class TaovCbcAmmoMappingMixin {

    @Inject(method = "machineGun", at = @At("HEAD"), cancellable = true)
    private static void firecontrolcompat$mapNativeCbcRounds(
            ItemStack payload,
            CallbackInfoReturnable<Optional<TaovShaolibPayloadMapper.MappedProjectile>> cir) {
        Optional<TaovShaolibPayloadMapper.MappedProjectile> mapped = CbcAutocannonPayloads.map(payload);
        if (mapped.isPresent()) {
            cir.setReturnValue(mapped);
        }
    }
}

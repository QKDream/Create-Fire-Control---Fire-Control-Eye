package com.qkdream.firecontrolcompat.mixin;

import com.qkdream.firecontrolcompat.SonicPulse;
import com.simibubi.create.content.kinetics.steamEngine.PoweredShaftBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets the sculk network hear a steam engine run.
 *
 * <p>A boiler is only audible through the shaft it turns: Create keeps the
 * engine's output on the powered shaft next to it, so an efficiency above zero
 * together with a turning shaft is the same thing as steam being used. Boilers
 * that are heated but not turning anything stay quiet, which is what a player
 * standing next to them hears too.</p>
 */
@Mixin(PoweredShaftBlockEntity.class)
public abstract class SteamEngineSonicMixin {

    @Inject(method = "tick", at = @At("TAIL"), require = 0)
    private void firecontrolcompat$machineRun(CallbackInfo ci) {
        try {
            if ((Object) this instanceof PoweredShaftBlockEntity shaft
                    && shaft.engineEfficiency > 0.0F
                    && shaft.getSpeed() != 0.0F) {
                SonicPulse.machine(shaft);
            }
        } catch (Throwable ignored) {
        }
    }
}

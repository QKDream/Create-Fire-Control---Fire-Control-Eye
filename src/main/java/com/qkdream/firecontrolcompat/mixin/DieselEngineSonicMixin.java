package com.qkdream.firecontrolcompat.mixin;

import com.jesz.createdieselgenerators.content.burner.BurnerBlockEntity;
import com.jesz.createdieselgenerators.content.diesel_engine.IEngine;
import com.jesz.createdieselgenerators.content.diesel_engine.huge.HugeDieselEngineBlockEntity;
import com.jesz.createdieselgenerators.content.diesel_engine.modular.ModularDieselEngineBlockEntity;
import com.jesz.createdieselgenerators.content.diesel_engine.normal.DieselEngineBlockEntity;
import com.qkdream.firecontrolcompat.SonicPulse;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets the sculk network hear a diesel engine or a burner run.
 *
 * <p>All three engine sizes share the same tick and the same fuel interface, so
 * one tail injector covers them: as long as the engine has fuel, is switched on
 * and is not idling at zero throttle, the machine is turning and the vibration
 * goes out. The burner is checked the same way through its heat, which stays
 * negative until it is actually lit.</p>
 */
@Mixin({
        DieselEngineBlockEntity.class,
        HugeDieselEngineBlockEntity.class,
        ModularDieselEngineBlockEntity.class,
        BurnerBlockEntity.class
})
public abstract class DieselEngineSonicMixin {

    @Inject(method = "tick", at = @At("TAIL"), require = 0)
    private void firecontrolcompat$machineRun(CallbackInfo ci) {
        try {
            Object self = this;
            boolean running = false;
            if (self instanceof IEngine engine) {
                running = engine.enabled() && engine.getThrottle() > 0.0F && engine.getFuelSpeed() > 0.0F;
            } else if (self instanceof BurnerBlockEntity burner) {
                running = burner.heat > 0.0F;
            }
            if (running && self instanceof BlockEntity blockEntity) {
                SonicPulse.machine(blockEntity);
            }
        } catch (Throwable ignored) {
        }
    }
}

package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.content.controller.AutoDefenseDisplayBindingHandler;
import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.content.controller.BindingSlot;
import com.hooya.stabilizedturret.content.display.ConnectedDisplayBlockEntity;
import com.qkdream.firecontrolcompat.HeavyAirDefenseCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The connected display prefers to bind vertical silos as cruise launchers.
 * When the silo carries the heavy air-defense missile the binding is
 * converted to the AA slot instead, so the display's air-defense category
 * can lock, fire and fuze it. Native cruise silos keep their original
 * binding.
 */
@Mixin(AutoDefenseDisplayBindingHandler.class)
public abstract class AutoDefenseDisplayBindingRedirectMixin {

    @Redirect(
            method = "rightClick",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/hooya/stabilizedturret/content/display/ConnectedDisplayBlockEntity;bind(Lcom/hooya/stabilizedturret/content/controller/BindingSlot;Lcom/hooya/stabilizedturret/content/controller/BindingRef;)Z"
            )
    )
    private static boolean firecontrolcompat$redirectDisplayBind(
            ConnectedDisplayBlockEntity display, BindingSlot slot, BindingRef ref) {
        if (slot == BindingSlot.CRUISE_MISSILE_LAUNCHER
                && display != null
                && display.getLevel() != null
                && HeavyAirDefenseCompat.hasHeavyAmmo(display.getLevel(), ref)) {
            return display.bind(BindingSlot.AA_MISSILE_LAUNCHER, ref);
        }
        return display.bind(slot, ref);
    }
}
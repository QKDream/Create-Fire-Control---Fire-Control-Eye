package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.content.controller.FireControlWeaponInputs;
import com.hooya.stabilizedturret.content.controller.MianbaoAircraftCompat;
import com.qkdream.firecontrolcompat.FireControlCompat;
import com.qkdream.firecontrolcompat.network.SeekerHudPayload;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Names the aircraft weapon-slot row on the input page after the round the slot fires.
 *
 * <p>0.9.4's {@code FireControlWeaponInputs.label(int)} is
 * {@code (I)Lnet/minecraft/network/chat/Component;} (javap) and its aircraft branch is
 * {@code WeaponType#displayName()}, so the close-range row reads the pack's stock wording whatever
 * is on the rail. This mod's anti-radiation round rides that slot, and the round <em>is</em> the
 * block: 面包学 loads it by swapping the empty {@code rack} for its own
 * {@code antiradiationmissilerack} variant, which carries the round's model inside the block
 * model. {@link AircraftWeaponTypeLabelMixin} now covers the same enum method for every other
 * caller; this hook is kept because the input page is the one place where the action number is
 * known, which is what the diagnostic below prints.</p>
 *
 * <p>The rename is driven by the state the seated player is already sent each tick
 * ({@link SeekerHudPayload}), so there is one source of truth. Nothing is renamed globally and no
 * other slot changes: only the close-range slot is touched, and only while one of this mod's two
 * rounds is the one on the rail.</p>
 */
@Mixin(FireControlWeaponInputs.class)
public abstract class WeaponSlotLabelMixin {

    /** Printed once per session, so "the hook never applied" and "the hook applied but renamed
     *  nothing" can be told apart without guessing. */
    private static boolean firecontrolcompat$applied;

    /** The last row this hook renamed, so the diagnostic prints on a change and not per frame. */
    private static String firecontrolcompat$lastRow = "";

    @Inject(method = "label", at = @At("RETURN"), cancellable = true, require = 0)
    private static void firecontrolcompat$armSlotName(int action, CallbackInfoReturnable<Component> cir) {
        try {
            if (!firecontrolcompat$applied) {
                firecontrolcompat$applied = true;
                FireControlCompat.LOGGER.info(
                        "[firecontrolcompat] weapon label mixin applied: FireControlWeaponInputs.label(int)->Component first={}",
                        action);
            }
            if (FireControlWeaponInputs.aircraftWeapon(action)
                    != MianbaoAircraftCompat.WeaponType.CLOSE) {
                return;
            }
            Component stock = cir.getReturnValue();
            String stockName = stock == null ? "-" : stock.getString();
            if (!SeekerHudPayload.isNameFresh(System.currentTimeMillis())) {
                // The window says the computer has stopped reporting for this player. Printed so a
                // slot that stays on the pack's wording can be told from a hook that never ran.
                firecontrolcompat$report(action, stockName, "stale", stockName);
                return;
            }
            String name = null;
            String source = "native";
            if ("反辐射导弹".equals(SeekerHudPayload.groundSlotName())) {
                name = "反辐射导弹";
                source = "arm";
            } else if (SeekerHudPayload.isAircraftIrSelected()) {
                name = "空射红外弹";
                source = "ir";
            }
            if (name == null) {
                firecontrolcompat$report(action, stockName, "native", stockName);
                return;
            }
            cir.setReturnValue(Component.literal(name));
            firecontrolcompat$report(action, name, source, stockName);
        } catch (Throwable ignored) {
            // Keep the pack's own wording.
        }
    }

    private static void firecontrolcompat$report(int action, String name, String source, String stock) {
        String row = action + "/" + source + "/" + name;
        if (row.equals(firecontrolcompat$lastRow)) {
            return;
        }
        firecontrolcompat$lastRow = row;
        FireControlCompat.LOGGER.info(
                "[firecontrolcompat] weapon label: slot={} name={} src={} stock={}",
                action, name, source, stock);
    }
}

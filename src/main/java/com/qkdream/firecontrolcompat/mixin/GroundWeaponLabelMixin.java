package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.content.controller.FireControlWeaponInputs;
import com.qkdream.firecontrolcompat.FireControlCompat;
import com.qkdream.firecontrolcompat.network.SeekerHudPayload;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Names the <b>ground</b> anti-tank line after the round that is loaded in the launcher.
 *
 * <h2>Why the ground line needs this and the aircraft one did not</h2>
 *
 * <p>0.9.4 draws its three ground weapon slots from
 * {@code FireControlWeaponInputs.GroundWeapon#displayName()}, which is a plain translation key per
 * enum constant - {@code weapon.create_fire_control.ground.anti_tank} = 「反坦克导弹」. That says
 * what the <em>launcher family</em> is, not what is inside it, so a bound anti-tank launcher loaded
 * with this mod's infrared round announced 面包学's anti-tank missile. There is exactly one place
 * that answers the name (javap: {@code (Lnet/minecraft/network/chat/Component;)}) and every reader
 * goes through it:</p>
 *
 * <ul>
 *   <li>{@code FireControlWeaponInputs.label(int)} :39-47 - the input page's weapon rows;</li>
 *   <li>{@code StabilizerControllerBlockEntity.selectBoundWeapon} :523-543 - the
 *       「已选择武器」message printed when a slot is cycled;</li>
 *   <li>the HUD, when it prints the selected ground weapon.</li>
 * </ul>
 *
 * <p>Only {@code ANTI_TANK} is touched, and only while a bound launcher is actually carrying this
 * mod's round: the name comes from {@link SeekerHudPayload}, which the server fills from slot zero of
 * the launchers bound to the seated player's computer ({@code SeekerHudServerSync}), so a launcher
 * holding 面包学's own {@code antitankmissile_1/_2} keeps the pack's wording - which is the one thing
 * the request was explicit about. The two air-defence slots are never renamed.</p>
 */
@Mixin(FireControlWeaponInputs.GroundWeapon.class)
public abstract class GroundWeaponLabelMixin {

    /** Last rename produced, so the diagnostic prints per change and not per frame. */
    private static String firecontrolcompat$lastLogged = "";

    @Inject(method = "displayName()Lnet/minecraft/network/chat/Component;", at = @At("RETURN"),
            cancellable = true, require = 0)
    private void firecontrolcompat$atgmName(CallbackInfoReturnable<Component> cir) {
        try {
            if ((Object) this != FireControlWeaponInputs.GroundWeapon.ANTI_TANK) {
                return;
            }
            String stock = cir.getReturnValue() == null ? "-" : cir.getReturnValue().getString();
            // Deliberately not gated on isNameFresh: the payload is only sent to a *seated* player, so
            // a freshness window makes the row snap back to the pack's wording the moment the crew
            // opens the input page - the exact bug the air slot had to be fixed for. The server sends
            // null as soon as no bound launcher holds this mod's round, so the name clears itself.
            String name = SeekerHudPayload.groundWeaponSlotName();
            if (name == null || name.isBlank()) {
                firecontrolcompat$report(stock, "native", stock);
                return;
            }
            firecontrolcompat$report(name, "atgm", stock);
            cir.setReturnValue(Component.literal(name));
        } catch (Throwable ignored) {
            // Keep the pack's own wording.
        }
    }

    private static void firecontrolcompat$report(String name, String source, String stock) {
        String row = source + "/" + name;
        if (row.equals(firecontrolcompat$lastLogged)) {
            return;
        }
        firecontrolcompat$lastLogged = row;
        FireControlCompat.LOGGER.info(
                "[firecontrolcompat] weapon label: slot=anti_tank name={} src={} stock={}",
                name, source, stock);
    }
}

package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.client.RadarHudRenderer;
import com.hooya.stabilizedturret.content.controller.MianbaoAircraftCompat;
import com.qkdream.firecontrolcompat.network.SeekerHudPayload;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The aircraft flight HUD prints the selected weapon through
 * {@code WeaponType#displayName} since fire control 0.8, which would read the
 * close-combat missile's model name while our aircraft infrared missile is
 * loaded. The seated player is already told that the close racks carry our
 * missile (see {@code SeekerHudServerSync}), so the same flag renames the line.
 */
@Mixin(RadarHudRenderer.class)
public abstract class AircraftWeaponHudNameMixin {

    @Redirect(
            method = "aircraftWeaponHudLines",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/hooya/stabilizedturret/content/controller/MianbaoAircraftCompat$WeaponType;"
                            + "displayName()Lnet/minecraft/network/chat/Component;"),
            require = 0)
    private static Component firecontrolcompat$hudWeaponDisplayName(MianbaoAircraftCompat.WeaponType type) {
        if (!SeekerHudPayload.isFresh(System.currentTimeMillis())) {
            return type.displayName();
        }
        if (type == MianbaoAircraftCompat.WeaponType.CLOSE) {
            // The anti-radiation round shares the rail with the infrared one, so the name the
            // computer synced decides which of the two the trigger is on.
            if ("反辐射导弹".equals(SeekerHudPayload.groundSlotName())) {
                return Component.literal("反辐射导弹");
            }
            if (SeekerHudPayload.isAircraftIrSelected()) {
                return Component.literal("空射红外弹");
            }
        }
        if (type == MianbaoAircraftCompat.WeaponType.AIR_TO_GROUND_DIRECT) {
            String name = SeekerHudPayload.groundSlotName();
            if (name != null) {
                return Component.literal(name);
            }
        }
        return type.displayName();
    }
}



package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.content.controller.MianbaoAircraftCompat;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.qkdream.firecontrolcompat.BeamMissileCompat;
import com.qkdream.firecontrolcompat.BombRackCompat;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Renames the weapon announced by the fire-control computer's aircraft weapon
 * selector when the close-combat racks carry our aircraft infrared missile: the
 * message reads "空射红外弹" instead of the name taken from the
 * missile model.
 *
 * <p>The aircraft slot also answers for 面包学's cruise rail, which is filed as an
 * air-to-ground rack; a computer holding one is told separately, because the
 * air-to-ground name is the wrong one for that rail.
 *
 * <p>Fire control 0.8 dropped the private {@code aircraftWeaponName} helper and
 * now asks {@code MianbaoAircraftCompat.WeaponType#displayName} instead, so this
 * hook redirects that call. {@link AircraftWeaponNameMixin} still covers 0.7 and
 * the two are never applied together: a redirect whose target is absent aborts
 * mod loading, so {@code FireControlCompatMixinPlugin} picks the one that
 * matches the installed fire control version.</p>
 */
@Mixin(StabilizerControllerBlockEntity.class)
public abstract class AircraftWeaponNameDisplayMixin {

    @Redirect(
            method = "cycleAircraftWeapon",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/hooya/stabilizedturret/content/controller/MianbaoAircraftCompat$WeaponType;"
                            + "displayName()Lnet/minecraft/network/chat/Component;"),
            require = 0)
    private Component firecontrolcompat$weaponDisplayName(MianbaoAircraftCompat.WeaponType type) {
        StabilizerControllerBlockEntity self = (StabilizerControllerBlockEntity) (Object) this;
        Level level = self.getLevel();
        if (level != null) {
            // RETIRED(ir-arm-rounds): the anti-radiation and airborne infrared names stood here;
            // both rounds are the pack's own from its next version on.
            if (type == MianbaoAircraftCompat.WeaponType.AIR_TO_GROUND_DIRECT) {
                String name = BombRackCompat.airToGroundSlotName(level, self.getAircraftMissileRacks(), self.getBlockPos());
                if (name != null) {
                    return Component.literal(name);
                }
            }
        }
        return type.displayName();
    }
}


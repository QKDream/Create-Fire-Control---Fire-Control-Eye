package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.content.controller.MianbaoAircraftCompat;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.qkdream.firecontrolcompat.BeamMissileCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Renames the weapon shown by the fire-control computer's aircraft weapon
 * selector when the close-combat racks carry our aircraft infrared missile:
 * it displays "空射红外弹" instead of the model-derived "近距离格斗导弹".
 */
@Mixin(StabilizerControllerBlockEntity.class)
public abstract class AircraftWeaponNameMixin {

    @Redirect(
            method = "cycleAircraftWeapon",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/hooya/stabilizedturret/content/controller/StabilizerControllerBlockEntity;"
                            + "aircraftWeaponName(Lcom/hooya/stabilizedturret/content/controller/MianbaoAircraftCompat$WeaponType;)"
                            + "Ljava/lang/String;"))
    private String firecontrolcompat$weaponName(MianbaoAircraftCompat.WeaponType type) {
        if (type == MianbaoAircraftCompat.WeaponType.CLOSE) {
            StabilizerControllerBlockEntity self = (StabilizerControllerBlockEntity) (Object) this;
            if (self.getLevel() != null) {
                for (BindingRef rack : self.getAircraftMissileRacks()) {
                    if (BeamMissileCompat.isIrRackLoaded(self.getLevel(), rack)) {
                        return "空射红外弹";
                    }
                }
            }
        }
        return switch (type) {
            case FAR -> "远距离空空导弹";
            case MEDIUM -> "中距离空空导弹";
            case CLOSE -> "近距离格斗导弹";
            case AIR_TO_GROUND_DIRECT -> "普通空对地导弹";
            case AIR_TO_GROUND_TOP_ATTACK -> "攻顶空对地导弹";
            case LASER_GUIDED -> "激光制导导弹";
            case ROCKET_POD -> "火箭弹";
            case OPTICAL_GUIDED -> "光学制导导弹";
        };
    }
}

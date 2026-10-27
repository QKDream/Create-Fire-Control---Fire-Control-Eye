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
 *
 * <p>This is the 0.7 shape: the name came from a private
 * {@code aircraftWeaponName} helper. Fire control 0.8 moved it onto the
 * weapon type enum and dropped the helper, so
 * {@link AircraftWeaponNameDisplayMixin} takes over there. Exactly one of
 * the two is applied - {@code FireControlCompatMixinPlugin} picks - because
 * a redirect whose target is missing aborts mod loading.</p>
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
        StabilizerControllerBlockEntity self = (StabilizerControllerBlockEntity) (Object) this;
        // The anti-radiation round rides the same empty rack as the infrared one, so it is asked
        // for before either of the two rack lines names itself: a rail carrying it is neither a
        // close-combat missile nor a plain air-to-ground one, and the crew would otherwise read
        // the wrong weapon for the shot the trigger is about to release.
        // RETIRED(ir-arm-rounds): this block named a rail after this mod's own anti-radiation
        // or airborne infrared round. Both are 面包学's rounds from its next version on, so the
        // weapon a rail answers as is the pack's own name - nothing here overrides it.
        return switch (type) {
            case FAR -> "远距离空空导弹";
            case MEDIUM -> "中距离空空导弹";
            case CLOSE -> "近距离格斗导弹";
            // Named for the kind the trigger would actually release. This line is what the
            // crew reads when the weapon is cycled, and a fixed wording here said the same
            // thing whatever was on the racks - so a crew cycling to a cruise rail or a
            // glide bomb was told "普通空对地导弹" and read the shot that followed as the
            // wrong weapon, while the release itself had gone to the right rack.
            case AIR_TO_GROUND_DIRECT -> {
                StabilizerControllerBlockEntity owner = (StabilizerControllerBlockEntity) (Object) this;
                String selected = owner.getLevel() == null ? null
                        : com.qkdream.firecontrolcompat.BombRackCompat.airToGroundSlotName(
                                owner.getLevel(), owner.getAircraftMissileRacks(), owner.getBlockPos());
                yield selected == null ? "普通空对地导弹" : selected;
            }
            case AIR_TO_GROUND_TOP_ATTACK -> "攻顶空对地导弹";
            case LASER_GUIDED -> "激光制导导弹";
            case ROCKET_POD -> "火箭弹";
            case OPTICAL_GUIDED -> "光学制导导弹";
            case MICA_EM -> "MICA-EM";
            case AIM_120D -> "AIM-120D";
            // Fire control 0.9.3 added weapon kinds of its own (the bomb lines among them). This
            // hook only runs on 0.7 pages, but it is compiled against whatever pack the build
            // uses, so anything this switch does not name takes the pack's own word for it
            // instead of failing to compile.
            default -> type.displayName().getString();
        };
    }
}

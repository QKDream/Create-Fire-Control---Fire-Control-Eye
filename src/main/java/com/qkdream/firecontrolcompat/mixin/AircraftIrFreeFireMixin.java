package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.content.controller.MianbaoAircraftCompat;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.qkdream.firecontrolcompat.BeamMissileCompat;
import java.util.List;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets the aircraft infrared missile be fired through fire control without
 * any radar lock. When the selected weapon is air-to-air and a bound rack
 * carries our infrared missile, it fires immediately instead of demanding an
 * active aircraft radar and a selected radar target.
 */
@Mixin(StabilizerControllerBlockEntity.class)
public abstract class AircraftIrFreeFireMixin {

    @Shadow
    private boolean hasInputConfigured(int action) {
        throw new AbstractMethodError();
    }

    @Shadow
    private void removeInvalidBindings() {
        throw new AbstractMethodError();
    }

    @Shadow
    private void notifyMissileOperator(Component message) {
        throw new AbstractMethodError();
    }

    @Inject(method = "fireNextAircraftMissile", at = @At("HEAD"), cancellable = true)
    private void firecontrolcompat$fireIrWithoutLock(CallbackInfo ci) {
        StabilizerControllerBlockEntity self = (StabilizerControllerBlockEntity) (Object) this;
        if (!this.hasInputConfigured(6) || self.getLevel() == null) {
            return;
        }
        MianbaoAircraftCompat.WeaponType selected = self.getSelectedAircraftWeapon();
        if (!MianbaoAircraftCompat.isAirToAir(selected)) {
            return;
        }
        if (self.isAircraftRadarActive() && self.getAircraftGuidanceTargetId() != null) {
            return;
        }
        this.removeInvalidBindings();
        List<BindingRef> racks = self.getAircraftMissileRacks();
        for (BindingRef rack : racks) {
            if (!BeamMissileCompat.isIrRackLoaded(self.getLevel(), rack)) {
                continue;
            }
            MianbaoAircraftCompat.WeaponType rackType = MianbaoAircraftCompat.weaponAt(self.getLevel(), rack);
            if (rackType == null) {
                continue;
            }
            boolean fired = MianbaoAircraftCompat.fireOne(self.getLevel(), rack, rackType);
            self.setChanged();
            if (!fired) {
                this.notifyMissileOperator(Component.literal("机载导弹发射失败"));
            }
            ci.cancel();
            return;
        }
    }
}
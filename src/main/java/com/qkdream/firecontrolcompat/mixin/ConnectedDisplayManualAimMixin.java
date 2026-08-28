package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.content.display.ConnectedDisplayBlockEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Lets a manually indicated point on the connected display drive the bound
 * turret groups (cannon mounts, AA launchers, laser designators) even when
 * the selected weapon is not CANNON. Vanilla only walks the manual aim path
 * for the CANNON weapon category; for AIR_DEFENSE it falls through to
 * {@code returnBearingsToNeutral} and the turrets ignore the manual point.
 *
 * <p>Injected at the third {@code returnBearingsToNeutral} call inside
 * {@code serverTick}, which is the no-contact fallback path reached after
 * {@code engageFixedCannonPoint} already handled the CANNON case and
 * returned. At that bytecode location {@code manualAimPoint != null} means
 * the weapon category is not CANNON, so the only missing behaviour is
 * rotating the turret groups towards the indicated point.
 */
@Mixin(ConnectedDisplayBlockEntity.class)
public abstract class ConnectedDisplayManualAimMixin {

    @Shadow
    private Vec3 manualAimPoint;

    @Invoker("aimTurretGroups")
    public abstract void invokeAimTurretGroups(Vec3 point);

    @Invoker("returnBearingsToNeutral")
    public abstract void invokeReturnBearingsToNeutral();

    @Redirect(
            method = "serverTick",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/hooya/stabilizedturret/content/display/ConnectedDisplayBlockEntity;returnBearingsToNeutral()V",
                    ordinal = 2
            )
    )
    private void firecontrolcompat$manualAimDrivesTurretGroups(ConnectedDisplayBlockEntity instance) {
        Vec3 point = this.manualAimPoint;
        if (point != null) {
            this.invokeAimTurretGroups(point);
        } else {
            this.invokeReturnBearingsToNeutral();
        }
    }
}

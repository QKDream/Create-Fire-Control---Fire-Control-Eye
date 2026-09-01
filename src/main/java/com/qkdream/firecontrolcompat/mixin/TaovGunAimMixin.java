package com.qkdream.firecontrolcompat.mixin;

import com.qkdream.firecontrolcompat.TaovGunAimControl;
import com.qkdream.firecontrolcompat.TaovGunBridge;
import com.verr1.synaxis.foundation.physics.PhysicsBodyView;
import com.verr1.taov.weapons.content.gun.GunBlockEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Gives TAOV's gun a fire-control-only barrel deflection so the fire-control
 * computer's pod control path (CannonMountCompat.pointAssembledCannonAt /
 * Along) can steer the gun directly. Vanilla TAOV state is left untouched:
 * the gun's own horizontal/vertical offsets keep shaping the ammo chain and
 * its default values still fire straight ahead when fire control is idle.
 */
@Pseudo
@Mixin(GunBlockEntity.class)
public abstract class TaovGunAimMixin implements TaovGunAimControl {

    /** Seconds of inactivity before a fire-control deflection expires. */
    @Unique
    private static final long FIRECONTROLCOMPAT$AIM_TIMEOUT_TICKS = 60L;

    @Unique
    private double firecontrolcompat$aimHorizontal;

    @Unique
    private double firecontrolcompat$aimVertical;

    @Unique
    private long firecontrolcompat$aimTick = Long.MIN_VALUE;

    @Override
    public void firecontrolcompat$setAim(double horizontalTangent, double verticalTangent) {
        this.firecontrolcompat$aimHorizontal = horizontalTangent;
        this.firecontrolcompat$aimVertical = verticalTangent;
        Level level = ((GunBlockEntity) (Object) this).getLevel();
        this.firecontrolcompat$aimTick = level == null ? Long.MIN_VALUE : level.getGameTime();
    }

    @Override
    public Vec3 firecontrolcompat$deflectedDirection() {
        if (!this.firecontrolcompat$aimActive()) {
            return null;
        }
        Vector3d world = TaovGunBridge.deflectDirection(
                (GunBlockEntity) (Object) this, this.firecontrolcompat$aimHorizontal, this.firecontrolcompat$aimVertical);
        return new Vec3(world.x, world.y, world.z);
    }

    @Unique
    private boolean firecontrolcompat$aimActive() {
        Level level = ((GunBlockEntity) (Object) this).getLevel();
        if (level == null || this.firecontrolcompat$aimTick == Long.MIN_VALUE) {
            return false;
        }
        long age = level.getGameTime() - this.firecontrolcompat$aimTick;
        return age >= 0L && age <= FIRECONTROLCOMPAT$AIM_TIMEOUT_TICKS;
    }

    @Inject(method = "launchDirectionWorld", at = @At("HEAD"), cancellable = true, remap = false)
    private void firecontrolcompat$applyAimDeflection(PhysicsBodyView view, CallbackInfoReturnable<Vector3d> cir) {
        Vec3 deflected = this.firecontrolcompat$deflectedDirection();
        if (deflected != null && deflected.lengthSqr() > 1.0E-8) {
            cir.setReturnValue(new Vector3d(deflected.x, deflected.y, deflected.z).normalize());
        }
    }
}


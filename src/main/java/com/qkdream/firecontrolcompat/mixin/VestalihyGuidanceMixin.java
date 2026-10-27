package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.content.controller.MianbaoMissileCompat;
import com.qkdream.firecontrolcompat.FireControlCompat;
import com.qkdream.firecontrolcompat.FireControlCompat;
import com.qkdream.firecontrolcompat.VestalihyBridge;
import com.hooya.stabilizedturret.content.controller.ControllerTracker;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import net.minecraft.nbt.CompoundTag;
import com.vestalihy.entity.PturEntity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hands the flight of an adopted vestalihy round over to the fire control
 * computer.
 *
 * <p>Once the computer has adopted a round it flies the round on its aim beam
 * every server tick, so the launcher's own sight riding has to stop or the two
 * pull against each other. What is kept is everything the round needs to stay
 * airworthy: the acceleration towards its top speed and the body rotation
 * following the velocity, so the round keeps speeding up and keeps pointing
 * where it flies. Rounds inside a sub level, and the 9M14 with its keyboard
 * steering, are left alone.</p>
 *
 * <p>The round also reports its own tick, the way the fire control's own
 * missiles do. Its post tick pass advances any guided round whose own tick did
 * not run, and a vestalihy round always runs one - the mod keeps the chunks it
 * flies through loaded itself - so without the report every round was moved a
 * second time on every tick, which doubled its speed and made its position
 * jump between two steps, and the body no longer matched the way it travelled.</p>
 *
 * <p>Everything is measured in the world, which is where the fire control
 * computer aims, and written back through the same conversions, so the flight
 * stays on the aim line no matter which space the round is stored in. A round
 * that still sits in a structure's plot space is turned by the pose of that
 * structure when it is drawn, so its body is angled in plot space there and in
 * world space everywhere else - a round fired off a ship used to be angled in
 * the wrong space, which showed as a body pointing beside its flight path.</p>
 *
 * <p>The body is also stamped onto the round the moment it turns, the way the
 * fire control does for its own rounds ({@code MianbaoOpticalMissileHandler}).
 * A plain entity has its rotation sent every third tick and is then smoothed
 * towards it on the client, so a round that swings onto the beam right after
 * leaving a ship's tube was drawn up to three ticks behind where it flew.</p>
 */
@Mixin(PturEntity.class)
public abstract class VestalihyGuidanceMixin {

    /** Flags the fire control sets on a round it has taken over. */
    private static final String[] FLIGHT_FLAGS = {
        "CreateFireControlGuided",
        "CreateFireControlAaGuided",
        "CreateFireControlAircraftGuided",
        "CreateFireControlCruiseGuided",
        "CreateFireControlBallisticGuided"
    };

    /** Marks a round whose first guided tick has already been reported. */
    private static final String GUIDANCE_LOGGED = "CreateFireControlVestalihyGuidanceLogged";

    /** Marks a round whose recovery reference has already been written. */
    private static final String RECOVERY_TAG = "CreateFireControlGuidedController";

    /** The sub-level of the computer, when it is known. */
    private static final String RECOVERY_SUBLEVEL_TAG = "CreateFireControlGuidedControllerSubLevel";

    /** Whether that sub-level is known at all. */
    private static final String RECOVERY_SUBLEVEL_KNOWN_TAG = "CreateFireControlGuidedControllerSubLevelKnown";

    /**
     * Writes the reference the fire control needs to recover a round.
     *
     * <p>Recovery parks a round whose computer cannot be reached and attaches it
     * again once that computer loads; it only ever parks rounds carrying both the
     * guided flag and the position of the computer that took them
     * ({@code MianbaoMissileGuidanceHandler.onEntityJoin}). A vestalihy round is
     * handed over through this mod's bridge, which writes the launcher reference
     * and not that position, so such a round flew on until its computer left the
     * loaded area and was then never recovered - the "cannot recover" a crew
     * reported. The reference is filled in here, with the same keys and types the
     * pack itself uses, and only when it is missing.</p>
     */
    private static void rememberRecoveryReference(PturEntity round) {
        try {
            CompoundTag data = round.getPersistentData();
            if (data.contains(RECOVERY_TAG)) {
                return;
            }
            StabilizerControllerBlockEntity controller =
                    ControllerTracker.findForMianbaoMissile(round);
            if (controller == null) {
                return;
            }
            java.util.UUID subLevel = ControllerTracker.controllerSubLevelId(controller);
            data.putLong(RECOVERY_TAG, controller.getBlockPos().asLong());
            data.putBoolean(RECOVERY_SUBLEVEL_KNOWN_TAG, subLevel != null);
            if (subLevel != null) {
                data.putUUID(RECOVERY_SUBLEVEL_TAG, subLevel);
            }
            FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] vestalihy round {} given its recovery reference to computer at {}",
                    round.getUUID(), controller.getBlockPos());
        } catch (RuntimeException | LinkageError ignored) {
        }
    }

    /** Per tick acceleration towards the top speed of the round. */
    private static final double ACCELERATION = 0.12;

    @Shadow
    protected abstract double getMaxSpeed();

    @Inject(method = "guideMissile", at = @At("HEAD"), cancellable = true)
    private void firecontrolcompat$fireControlGuidance(CallbackInfo ci) {
        PturEntity self = (PturEntity) (Object) this;
        if (!VestalihyBridge.isGuidedMissile(self) || !isFlownByFireControl(self.getPersistentData())) {
            return;
        }
        rememberRecoveryReference(self);
        MianbaoMissileCompat.noteNativeMissileTick(self);
        SubLevel subLevel = containingSubLevel(self);
        Vec3 worldVelocity = MianbaoMissileCompat.getWorldVelocity(self);
        double speed = worldVelocity.length();
        double maxSpeed = this.getMaxSpeed();
        if (speed < maxSpeed) {
            speed = Math.min(maxSpeed, speed + ACCELERATION);
        }
        Vec3 worldDirection = worldVelocity.lengthSqr() < 1.0E-8
            ? worldLookDirection(self, subLevel)
            : worldVelocity.normalize();
        MianbaoMissileCompat.setWorldVelocity(self, worldDirection.scale(speed));
        reportFirstGuidedTick(self, subLevel, worldDirection, speed);
        faceFlightDirection(self, subLevel, worldDirection);
        ci.cancel();
    }

    /**
     * Angles the body at the way the round flies. An adopted round is normally
     * already out of the structure it was fired from, so this is the flight
     * direction in the world; a round that is still stored in a plot space is
     * angled in that space instead, because the structure's pose is applied on
     * top of the body when the round is drawn.
     */
    private static void faceFlightDirection(PturEntity round, SubLevel subLevel, Vec3 worldDirection) {
        Vec3 facing = subLevel == null
            ? worldDirection
            : safeNormalInverse(subLevel, worldDirection);
        double flat = Math.sqrt(facing.x * facing.x + facing.z * facing.z);
        float yaw = Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(-facing.x, facing.z)));
        float pitch = Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(-facing.y, flat)));
        round.setYRot(yaw);
        round.setXRot(pitch);
        // Body and previous body rotation follow the flight on the same tick, and
        // the change is pushed to the clients straight away rather than up to
        // three ticks later, so the drawn round is not left behind its flight.
        round.yRotO = yaw;
        round.xRotO = pitch;
        round.hasImpulse = true;
    }

    /** Where a round points, measured in the world. */
    private static Vec3 worldLookDirection(PturEntity round, SubLevel subLevel) {
        Vec3 look = round.getLookAngle();
        if (subLevel == null) {
            return look;
        }
        try {
            Vec3 worldLook = subLevel.logicalPose().transformNormal(look);
            return worldLook.lengthSqr() < 1.0E-8 ? look : worldLook;
        } catch (Throwable ignored) {
            return look;
        }
    }

    /** The sub level that stores a round, or null for a round in the world. */
    private static SubLevel containingSubLevel(PturEntity round) {
        try {
            return Sable.HELPER.getContaining(round);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Vec3 safeNormalInverse(SubLevel subLevel, Vec3 worldDirection) {
        try {
            Vec3 local = subLevel.logicalPose().transformNormalInverse(worldDirection);
            return local.lengthSqr() < 1.0E-8 ? worldDirection : local;
        } catch (Throwable ignored) {
            return worldDirection;
        }
    }

    /**
     * One line per adopted round, so a report about a body that points beside
     * its flight path says which space the round was turned in.
     */
    private static void reportFirstGuidedTick(PturEntity round, SubLevel subLevel, Vec3 worldDirection, double speed) {
        CompoundTag data = round.getPersistentData();
        if (data.getBoolean(GUIDANCE_LOGGED)) {
            return;
        }
        data.putBoolean(GUIDANCE_LOGGED, true);
        FireControlCompat.LOGGER.info(
            "vestalihy round {} guided in {} space: direction={} velocity={} speed={}",
            round.getId(),
            subLevel == null ? "world" : "plot",
            worldDirection,
            round.getDeltaMovement(),
            speed);
    }

    /** True while the fire control computer owns this round's flight. */
    private static boolean isFlownByFireControl(CompoundTag data) {
        for (String flag : FLIGHT_FLAGS) {
            if (data.getBoolean(flag)) {
                return true;
            }
        }
        return false;
    }
}

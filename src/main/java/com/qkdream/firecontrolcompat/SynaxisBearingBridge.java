package com.qkdream.firecontrolcompat;

import com.hooya.stabilizedturret.compat.synaxis.SynaxisWeldCompat;
import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.content.controller.BindingSlot;
import com.verr1.synaxis.content.blocks.motor.AbstractDynamicMotorBlockEntity;
import com.verr1.synaxis.content.blocks.motor.DynamicJointMotorBlockEntity;
import com.verr1.synaxis.content.blocks.motor.DynamicRevoluteMotorBlockEntity;
import com.verr1.synaxis.foundation.physics.BodyId;
import com.verr1.synaxis.foundation.physics.PhysicsBodyView;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

/**
 * Bridge between fire-control and Synaxis dynamic motors.
 *
 * <p>A Synaxis joint motor (pitch) or revolute motor (yaw) bound to a fire
 * control computer or connected display is driven exactly like a swivel
 * bearing: every tick the requested world direction is compared with the
 * measured turret direction and the motor target angle is advanced by the
 * signed, speed-limited delta around its own world axis. The fire control
 * offset setting is deliberately ignored here - offset stays owned by the
 * Synaxis motors themselves and only applies to real swivel bearings.</p>
 */
public final class SynaxisBearingBridge {

    private static final double MIN_DIRECTION_LENGTH_SQR = 1.0E-8;
    private static final double MIN_PROJECTED_LENGTH_SQR = 1.0E-6;
    private static final double MIN_COMMAND_RADIANS = 1.0E-5;

    private static final Map<AbstractDynamicMotorBlockEntity, Boolean> ANGLE_MODE =
            java.util.Collections.synchronizedMap(new WeakHashMap<>());

    private SynaxisBearingBridge() {
    }

    public static boolean isMotor(BlockEntity blockEntity) {
        return blockEntity instanceof DynamicRevoluteMotorBlockEntity
                || blockEntity instanceof DynamicJointMotorBlockEntity;
    }

    public static AbstractDynamicMotorBlockEntity resolveMotor(Level level, BindingRef ref) {
        if (level == null || ref == null) {
            return null;
        }
        BlockEntity blockEntity = ref.resolve(level);
        return isMotor(blockEntity) ? (AbstractDynamicMotorBlockEntity) blockEntity : null;
    }

    /** Vertical-axis motors default to YAW, horizontal ones to PITCH. */
    public static BindingSlot defaultSlot(AbstractDynamicMotorBlockEntity motor) {
        Direction axis = motor.renderAxis();
        if (axis == Direction.UP || axis == Direction.DOWN) {
            return BindingSlot.YAW;
        }
        return BindingSlot.PITCH;
    }

    /**
     * Advances the motor target angle towards the requested direction,
     * replicating the swivel bearing aim loop without the fire control
     * offset. Returns true when the motor handled the command.
     */
    public static boolean commandAim(
            AbstractDynamicMotorBlockEntity motor,
            boolean yaw,
            Vec3 measured,
            Vec3 requested,
            double speedDegreesPerSecond,
            double errorScale,
            double depressionDegrees,
            double elevationDegrees) {
        try {
            Level level = motor.getLevel();
            if (level == null || level.isClientSide || motor.isRemoved()) {
                return false;
            }
            if (measured == null || requested == null) {
                return false;
            }
            if (measured.lengthSqr() < MIN_DIRECTION_LENGTH_SQR
                    || requested.lengthSqr() < MIN_DIRECTION_LENGTH_SQR) {
                return false;
            }
            if (!motor.hasResolvedCompanion()) {
                return false;
            }
            Vector3d axis = worldAxis(motor);
            if (axis == null || axis.lengthSquared() < MIN_DIRECTION_LENGTH_SQR) {
                return false;
            }
            axis.normalize();
            Vector3d measuredVector = new Vector3d(measured.x, measured.y, measured.z).normalize();
            Vector3d requestedVector = new Vector3d(requested.x, requested.y, requested.z).normalize();
            if (!yaw) {
                requestedVector = clampElevation(motor, requestedVector, depressionDegrees, elevationDegrees);
            }
            Vector3d measuredProjected =
                    new Vector3d(measuredVector).sub(new Vector3d(axis).mul(measuredVector.dot(axis)));
            Vector3d requestedProjected =
                    new Vector3d(requestedVector).sub(new Vector3d(axis).mul(requestedVector.dot(axis)));
            if (measuredProjected.lengthSquared() < MIN_PROJECTED_LENGTH_SQR
                    || requestedProjected.lengthSquared() < MIN_PROJECTED_LENGTH_SQR) {
                return false;
            }
            measuredProjected.normalize();
            requestedProjected.normalize();
            double sine = axis.dot(new Vector3d(measuredProjected).cross(requestedProjected));
            double cosine = Mth.clamp(measuredProjected.dot(requestedProjected), -1.0, 1.0);
            double delta = Math.atan2(sine, cosine) * errorScale * axisSign(motor);
            double maxStep = Math.toRadians(Math.max(0.0, speedDegreesPerSecond)) / 20.0;
            if (Math.abs(delta) > maxStep) {
                delta = Math.copySign(maxStep, delta);
            }
            if (Math.abs(delta) < MIN_COMMAND_RADIANS) {
                return true;
            }
            synchronized (ANGLE_MODE) {
                if (!Boolean.TRUE.equals(ANGLE_MODE.get(motor))) {
                    motor.setAngleMode(true);
                    ANGLE_MODE.put(motor, Boolean.TRUE);
                }
            }
            motor.setTarget(motor.currentAngle() + delta);
            return true;
        } catch (RuntimeException | LinkageError t) {
            FireControlCompat.LOGGER.debug("[firecontrolcompat] Synaxis bearing aim failed for {}", motor, t);
            return false;
        }
    }

    /**
     * Topology distance from the motor's rotated (companion) body to the
     * device sub-level, mirroring the swivel bearing child distance logic.
     */
    public static int childDistance(Level level, AbstractDynamicMotorBlockEntity motor, UUID deviceSubLevelId) {
        if (level == null || motor == null || deviceSubLevelId == null) {
            return Integer.MAX_VALUE;
        }
        UUID childId = companionUuid(motor);
        if (childId == null) {
            SubLevel containing = Sable.HELPER.getContaining(motor);
            if (containing != null) {
                childId = containing.getUniqueId();
            }
        }
        if (childId == null) {
            return Integer.MAX_VALUE;
        }
        if (childId.equals(deviceSubLevelId)) {
            return 0;
        }
        if (level instanceof ServerLevel serverLevel) {
            int weldedDistance = SynaxisWeldCompat.weldedDistance(serverLevel, childId, deviceSubLevelId);
            if (weldedDistance >= 0) {
                return weldedDistance;
            }
        }
        SubLevel child = SubLevelContainer.getContainer(level).getSubLevel(childId);
        if (child == null || child.isRemoved()) {
            return Integer.MAX_VALUE;
        }
        List<SubLevel> queue = new ArrayList<>();
        Map<UUID, Integer> distance = new HashMap<>();
        queue.add(child);
        distance.put(childId, 0);
        for (int index = 0; index < queue.size(); index++) {
            SubLevel current = queue.get(index);
            int currentDistance = distance.get(current.getUniqueId());
            for (BlockEntitySubLevelActor actor : current.getPlot().getBlockEntityActors()) {
                Iterable<SubLevel> dependencies = actor.sable$getConnectionDependencies();
                if (dependencies == null) {
                    continue;
                }
                for (SubLevel connected : dependencies) {
                    if (connected == null || connected.isRemoved()) {
                        continue;
                    }
                    UUID id = connected.getUniqueId();
                    if (distance.putIfAbsent(id, currentDistance + 1) == null) {
                        if (deviceSubLevelId.equals(id)) {
                            return currentDistance + 1;
                        }
                        queue.add(connected);
                    }
                }
            }
        }
        return Integer.MAX_VALUE;
    }

    private static Vector3d worldAxis(AbstractDynamicMotorBlockEntity motor) {
        Direction renderAxis = motor.renderAxis();
        Vector3d local = new Vector3d(renderAxis.getStepX(), renderAxis.getStepY(), renderAxis.getStepZ());
        PhysicsBodyView self = motor.readSelfPhysics();
        if (self == null || self.isEmpty()) {
            return local;
        }
        return self.bodyToWorldDirection(local, new Vector3d());
    }

    /** Flipped motor blocks rotate against the right-hand rule. */
    private static double axisSign(AbstractDynamicMotorBlockEntity motor) {
        return motor.renderAxis().getAxisDirection() == Direction.AxisDirection.NEGATIVE ? -1.0 : 1.0;
    }

    private static Vector3d clampElevation(
            AbstractDynamicMotorBlockEntity motor,
            Vector3d requested,
            double depressionDegrees,
            double elevationDegrees) {
        Vector3d up = childUp(motor);
        double vertical = Mth.clamp(requested.dot(up), -1.0, 1.0);
        double elevation = Math.asin(vertical);
        double clampedElevation = Mth.clamp(
                elevation,
                -Math.toRadians(Math.max(0.0, depressionDegrees)),
                Math.toRadians(Math.max(0.0, elevationDegrees)));
        if (Math.abs(elevation - clampedElevation) < 1.0E-8) {
            return requested;
        }
        Vector3d horizontal = new Vector3d(requested).sub(new Vector3d(up).mul(vertical));
        if (horizontal.lengthSquared() < MIN_PROJECTED_LENGTH_SQR) {
            return requested;
        }
        return horizontal.normalize()
                .mul(Math.cos(clampedElevation))
                .add(new Vector3d(up).mul(Math.sin(clampedElevation)))
                .normalize();
    }

    private static Vector3d childUp(AbstractDynamicMotorBlockEntity motor) {
        try {
            PhysicsBodyView companion = motor.readCompanionPhysics();
            if (companion != null && !companion.isEmpty()) {
                Vector3d up = companion.bodyToWorldDirection(new Vector3d(0.0, 1.0, 0.0), new Vector3d());
                if (up.lengthSquared() > MIN_DIRECTION_LENGTH_SQR) {
                    return up.normalize();
                }
            }
        } catch (RuntimeException | LinkageError ignored) {
            // fall through to world up
        }
        return new Vector3d(0.0, 1.0, 0.0);
    }

    private static UUID companionUuid(AbstractDynamicMotorBlockEntity motor) {
        try {
            Optional<BodyId> resolved = motor.resolveCompanionBodyId();
            if (resolved != null && resolved.isPresent() && resolved.get().id() != null) {
                return resolved.get().id();
            }
            Optional<UUID> expected = motor.expectedCompanionUuid();
            if (expected != null && expected.isPresent()) {
                return expected.get();
            }
        } catch (RuntimeException | LinkageError ignored) {
            // fall through
        }
        return null;
    }
}

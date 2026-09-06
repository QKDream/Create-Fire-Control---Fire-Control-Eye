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

    /** Low-pass factor applied to the per-tick aim error, filtering measurement noise. */
    private static final double FILTER_ALPHA = 0.3;

    /** Start commanding once the filtered error exceeds this. */
    private static final double COMMAND_ENTER_DEGREES = 0.25;

    /** Stop commanding once the filtered error drops below this while active. */
    private static final double COMMAND_EXIT_DEGREES = 0.12;

    private static final Map<AbstractDynamicMotorBlockEntity, AimFilter> FILTER_YAW =
            java.util.Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<AbstractDynamicMotorBlockEntity, AimFilter> FILTER_PITCH =
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
            String source,
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
            if (source == null) {
                source = "unknown";
            }
            if (measured == null || requested == null) {
                skipLog(level, motor, source, yaw, "null-input");
                return false;
            }
            if (!isFinite(measured) || !isFinite(requested)) {
                skipLog(level, motor, source, yaw, "non-finite-input");
                return false;
            }
            if (measured.lengthSqr() < MIN_DIRECTION_LENGTH_SQR
                    || requested.lengthSqr() < MIN_DIRECTION_LENGTH_SQR) {
                skipLog(level, motor, source, yaw, "zero-input");
                return false;
            }
            if (!motor.hasResolvedCompanion()) {
                skipLog(level, motor, source, yaw, "companion-unresolved");
                return false;
            }
            Vector3d axis = worldAxis(motor);
            if (axis == null || !isFinite(axis) || axis.lengthSquared() < MIN_DIRECTION_LENGTH_SQR) {
                skipLog(level, motor, source, yaw, "bad-axis");
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
                skipLog(level, motor, source, yaw, "degenerate-projection");
                return false;
            }
            measuredProjected.normalize();
            requestedProjected.normalize();
            double sine = axis.dot(new Vector3d(measuredProjected).cross(requestedProjected));
            double cosine = Mth.clamp(measuredProjected.dot(requestedProjected), -1.0, 1.0);
            double delta = Math.atan2(sine, cosine) * errorScale * axisSign(motor);
            double rawErrorDegrees = Math.toDegrees(delta);
            Map<AbstractDynamicMotorBlockEntity, AimFilter> filterMap = yaw ? FILTER_YAW : FILTER_PITCH;
            AimFilter filter;
            synchronized (filterMap) {
                filter = filterMap.get(motor);
                if (filter == null) {
                    filter = new AimFilter();
                    filterMap.put(motor, filter);
                }
            }
            double filteredError;
            boolean active;
            synchronized (filter) {
                filter.filteredError += FILTER_ALPHA * (rawErrorDegrees - filter.filteredError);
                filteredError = filter.filteredError;
                if (!filter.active) {
                    if (Math.abs(filteredError) <= COMMAND_ENTER_DEGREES) {
                        debugLog(level, motor, source, yaw, rawErrorDegrees, filteredError, 0.0, false);
                        return true;
                    }
                    filter.active = true;
                } else if (Math.abs(filteredError) < COMMAND_EXIT_DEGREES) {
                    filter.active = false;
                    debugLog(level, motor, source, yaw, rawErrorDegrees, filteredError, 0.0, false);
                    return true;
                }
                active = filter.active;
            }
            double maxStep = Math.max(0.0, speedDegreesPerSecond) / 20.0;
            if (maxStep <= 0.0) {
                return true;
            }
            double step = Mth.clamp(filteredError, -maxStep, maxStep);
            double currentAngle = motor.currentAngle();
            if (!Double.isFinite(currentAngle)) {
                return false;
            }
            if (!motor.angleMode()) {
                motor.setAngleMode(true);
            }
            motor.setTarget(currentAngle + Math.toRadians(step));
            debugLog(level, motor, source, yaw, rawErrorDegrees, filteredError, step, active);
            return true;
        } catch (RuntimeException | LinkageError t) {
            FireControlCompat.LOGGER.debug("[firecontrolcompat] Synaxis bearing aim failed for {}", motor, t);
            return false;
        }
    }

    private static void skipLog(Level level, AbstractDynamicMotorBlockEntity motor, String source, boolean yaw, String reason) {
        if (level != null && level.getGameTime() % 100 == 0) {
            FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] synaxis aim skip {} motor={} yaw={} reason={}",
                    source, motor.getBlockPos(), yaw, reason);
        }
    }

    private static void debugLog(
            Level level,
            AbstractDynamicMotorBlockEntity motor,
            String source,
            boolean yaw,
            double rawErrorDegrees,
            double filteredErrorDegrees,
            double stepDegrees,
            boolean active) {
        if (level != null && level.getGameTime() % 20 == 0) {
            FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] synaxis aim {} motor={} yaw={} raw={}deg filt={}deg step={}deg active={} angle={}rad target={}rad",
                    source,
                    motor.getBlockPos(),
                    yaw,
                    String.format(java.util.Locale.ROOT, "%.4f", rawErrorDegrees),
                    String.format(java.util.Locale.ROOT, "%.4f", filteredErrorDegrees),
                    String.format(java.util.Locale.ROOT, "%.4f", stepDegrees),
                    active,
                    String.format(java.util.Locale.ROOT, "%.4f", motor.currentAngle()),
                    String.format(java.util.Locale.ROOT, "%.4f", motor.target()));
        }
    }

    private static final class AimFilter {
        private double filteredError;
        private boolean active;
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
        Vector3d world = self.bodyToWorldDirection(local, new Vector3d());
        return isFinite(world) ? world : local;
    }

    private static boolean isFinite(Vec3 vector) {
        return Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }

    private static boolean isFinite(Vector3d vector) {
        return Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
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
                if (isFinite(up) && up.lengthSquared() > MIN_DIRECTION_LENGTH_SQR) {
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

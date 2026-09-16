package com.qkdream.firecontrolcompat;

import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.content.controller.BindingSlot;
import com.hooya.stabilizedturret.registry.ModBlockEntities;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.SubLevelHelper;
import dev.ryanhcode.sable.mixinterface.clip_overwrite.ClipContextExtension;
import dev.ryanhcode.sable.sublevel.SubLevel;
import com.verr1.synaxis.foundation.cimulink.core.component.ComponentSchema;
import com.verr1.synaxis.foundation.cimulink.core.component.ComponentTypeId;
import com.verr1.synaxis.foundation.cimulink.core.component.ExecutionDomain;
import com.verr1.synaxis.foundation.cimulink.core.signal.PortDef;
import com.verr1.synaxis.foundation.cimulink.core.signal.SignalType;
import com.verr1.synaxis.foundation.cimulink.core.signal.SignalValue;
import com.verr1.synaxis.foundation.cimulink.game.body.GameThreadPlantPort;
import com.verr1.synaxis.foundation.cimulink.game.body.PlantPortProviders;
import com.verr1.synaxis.foundation.cimulink.game.endpoint.EndpointAddress;
import com.verr1.synaxis.foundation.cimulink.game.endpoint.EndpointId;
import com.verr1.synaxis.foundation.physics.PhysicsBodyView;
import com.verr1.synaxis.foundation.physics.SynaxisPhysics;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Quaterniondc;
import org.joml.Vector3d;

/**
 * Exposes the view angles of the gunner seated on the seat bound to a fire
 * control computer as a Synaxis plant port.
 *
 * <p>A Synaxis plant proxy placed against the fire control computer block
 * discovers this port through {@link PlantPortProviders} and outputs two
 * REAL signals, {@code view_yaw} and {@code view_pitch}, both in radians.
 * Note: Synaxis' own control chair publishes {@code local_yaw} / {@code local_pitch}
 * in degrees, so a circuit written against that port needs a unit conversion.
 * Yaw is negated relative to the Minecraft player yaw so the turret rotates
 * in the same horizontal direction the seated player turns. While the fire
 * control head aim is active the gunner's camera ray is clipped against
 * terrain and foreign structures, skipping the sublevels carrying the bound
 * operator seat, and the requested direction converges on the ray's hit
 * point as measured from the turret muzzle, mirroring native bearing
 * binding. When the ray reaches no surface the parallel view direction is
 * used instead. The angles share the same basis as the fire control
 * computer's measured muzzle direction, so Synaxis motors on the same
 * structure keep tracking while it is rotated or moving. The values are
 * sampled once per tick from the player riding the bound operator seat;
 * while nobody is seated the last sampled angles are held.</p>
 *
 * <p>Two optional REAL inputs help the port trim its aim: {@code bearing_yaw}
 * and {@code bearing_pitch} take the angle the driven bearing now stands at, in
 * radians and in the same sense as the port commands it. They tell the port how
 * far the mount has actually come - only ticks in which the mount has stopped
 * moving are learned from - whether the circuit is driving the axis at all, and,
 * over a clear move, which way that axis answers, so a crossed or backwards
 * circuit is noticed instead of trusted. The request is placed on the mount from
 * the aim the fire control computer reports: while the mount stands still, the
 * angle its muzzle reached minus the angle the request asked for is a zero error
 * and nothing else, and feeding it back a step at a time lands the muzzle on the
 * request whether that request came from the crosshair or from a lock, so a lock
 * cannot sit on a different zero than free aim does. A lock taken, a source
 * switched or a request that jumped freezes the trim for a moment, so the swing
 * they cause cannot be mistaken for one. The readback, the distance it stands
 * from the command and the direction it answers in are printed in the debug
 * line.</p>
 *
 * <p>While a plant proxy is attached, the fire control computer is also made
 * equivalent to having its YAW and PITCH bearings bound: a virtual binding
 * pointing at the computer itself is registered through the public
 * {@code StabilizerControllerBlockEntity#bind}/{@code #unbindDevice} APIs so
 * the full bearing-bound feature set (head aim status, scopes, aim reference
 * capture) activates, while the actual slewing stays with the Synaxis
 * circuit. Bindings made by the player always take precedence over the
 * virtual one, and the virtual binding is removed shortly after the proxy
 * is detached.</p>
 */
@EventBusSubscriber(modid = FireControlCompat.MOD_ID)
public final class SynaxisSeatViewPortCompat {

    private static final Map<StabilizerControllerBlockEntity, SeatViewSample> SAMPLES =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<StabilizerControllerBlockEntity, Long> PROXY_LAST_SEEN =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final long PROXY_TIMEOUT_TICKS = 40L;

    private SynaxisSeatViewPortCompat() {
    }

    public static void register() {
        BlockEntityType<StabilizerControllerBlockEntity> controllerType =
                ModBlockEntities.STABILIZER_CONTROLLER.get();
        PlantPortProviders.register(
                controllerType,
                (blockEntity, endpointId, givenName) -> {
                    if (!(blockEntity instanceof StabilizerControllerBlockEntity controller)) {
                        return Optional.empty();
                    }
                    onProxyAttached(controller);
                    return Optional.of(new SeatViewPlantPort(controller, endpointId, givenName));
                });
    }

    private static void onProxyAttached(StabilizerControllerBlockEntity controller) {
        Level level = controller.getLevel();
        if (level == null || level.isClientSide) {
            return;
        }
        PROXY_LAST_SEEN.put(controller, level.getGameTime());
        reconcileVirtualBindings(controller, level);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        List<StabilizerControllerBlockEntity> tracked;
        synchronized (PROXY_LAST_SEEN) {
            tracked = new ArrayList<>(PROXY_LAST_SEEN.keySet());
        }
        for (StabilizerControllerBlockEntity controller : tracked) {
            Level level = controller.getLevel();
            if (level == null || controller.isRemoved()) {
                PROXY_LAST_SEEN.remove(controller);
                continue;
            }
            Long lastSeen = PROXY_LAST_SEEN.get(controller);
            if (lastSeen == null || level.getGameTime() - lastSeen <= PROXY_TIMEOUT_TICKS) {
                continue;
            }
            PROXY_LAST_SEEN.remove(controller);
            removeVirtualBindings(controller, level);
        }
    }

    private static void reconcileVirtualBindings(
            StabilizerControllerBlockEntity controller, Level level) {
        BlockPos selfPos = controller.getBlockPos();
        reconcileAxis(controller, level, selfPos, BindingSlot.YAW);
        reconcileAxis(controller, level, selfPos, BindingSlot.PITCH);
    }

    private static void reconcileAxis(
            StabilizerControllerBlockEntity controller,
            Level level,
            BlockPos selfPos,
            BindingSlot slot) {
        List<BindingRef> refs = slot == BindingSlot.YAW
                ? controller.getYawBearingBindings()
                : controller.getPitchBearingBindings();
        int virtualIndex = -1;
        boolean hasForeign = false;
        for (int index = 0; index < refs.size(); index++) {
            if (refs.get(index).pos().equals(selfPos)) {
                virtualIndex = index;
            } else {
                hasForeign = true;
            }
        }
        if (hasForeign) {
            if (virtualIndex >= 0) {
                controller.unbindDevice(slot, virtualIndex, selfPos);
            }
            return;
        }
        if (virtualIndex >= 0 || controller.getBinding(slot) != null) {
            return;
        }
        controller.bind(slot, BindingRef.capture(level, selfPos));
    }

    private static void removeVirtualBindings(
            StabilizerControllerBlockEntity controller, Level level) {
        BlockPos selfPos = controller.getBlockPos();
        removeVirtualAxis(controller, level, selfPos, BindingSlot.YAW);
        removeVirtualAxis(controller, level, selfPos, BindingSlot.PITCH);
        synchronized (SAMPLES) {
            SeatViewSample sample = SAMPLES.get(controller);
            if (sample != null) {
                sample.bearingYawSeen = false;
                sample.bearingYawLive = false;
                sample.bearingYawDegrees = Double.NaN;
                sample.bearingPitchSeen = false;
                sample.bearingPitchLive = false;
                sample.bearingPitchDegrees = Double.NaN;
                sample.signKnownYaw = false;
                sample.signKnownPitch = false;
                sample.signVoteYaw = 0;
                sample.signVotePitch = 0;
                sample.yawSamples.clear();
                sample.pitchSamples.clear();
                clearSignReference(sample, true);
                clearSignReference(sample, false);
                sample.zeroFreezeTicks = ZERO_FREEZE_TICKS;
                sample.forgetLockFrame();
            }
        }
    }

    private static void removeVirtualAxis(
            StabilizerControllerBlockEntity controller,
            Level level,
            BlockPos selfPos,
            BindingSlot slot) {
        List<BindingRef> refs = slot == BindingSlot.YAW
                ? controller.getYawBearingBindings()
                : controller.getPitchBearingBindings();
        for (int index = 0; index < refs.size(); index++) {
            if (refs.get(index).pos().equals(selfPos)) {
                controller.unbindDevice(slot, index, selfPos);
                return;
            }
        }
    }

    private static ServerPlayer findSeatedGunner(
            StabilizerControllerBlockEntity controller, Level level) {
        if (level.getServer() == null) {
            return null;
        }
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            if (controller.isSeat(player.getVehicle())) {
                return player;
            }
        }
        return null;
    }

    private static SeatViewSample sample(StabilizerControllerBlockEntity controller) {
        Level level = controller.getLevel();
        if (level == null) {
            return new SeatViewSample();
        }
        synchronized (SAMPLES) {
            SeatViewSample sample = SAMPLES.computeIfAbsent(controller, ignored -> new SeatViewSample());
            long gameTime = level.getGameTime();
            if (sample.sampledTick != gameTime) {
                sample.sampledTick = gameTime;
                ServerPlayer gunner = findSeatedGunner(controller, level);
                if (gunner != null) {
                    sampleViewAngles(controller, level, gunner, sample);
                }
            }
            return sample;
        }
    }

    private static void sampleViewAngles(
            StabilizerControllerBlockEntity controller,
            Level level,
            ServerPlayer gunner,
            SeatViewSample sample) {
        updateBearingLiveness(sample);
        float yawDegrees;
        float pitchDegrees;
        Optional<ViewAnglesDegrees> localAngles =
                headAimLocalAngles(controller, level, gunner, sample);
        if (localAngles.isPresent()) {
            yawDegrees = localAngles.get().yaw();
            pitchDegrees = localAngles.get().pitch();
            sample.requestedWorld = localAngles.get().requestedWorld();
            sample.hitDistance = localAngles.get().hitDistance();
            sample.usingLockTarget = localAngles.get().usingLockTarget();
            updateAimTrim(controller, sample, localAngles.get().requestedWorld());
        } else {
            yawDegrees = Mth.wrapDegrees(gunner.getViewYRot(1.0F));
            pitchDegrees = Mth.clamp(gunner.getViewXRot(1.0F), -90.0F, 90.0F);
            sample.requestedWorld = null;
            sample.hitDistance = Double.NaN;
            sample.usingLockTarget = false;
            sample.measuredAimRaw = null;
            sample.measuredAimBody = null;
            updateAimTrim(controller, sample, null);
        }
        double publishedYaw = -Mth.wrapDegrees(yawDegrees);
        sample.publishedYawDegrees = publishedYaw;
        sample.yawRadians = Math.toRadians(sample.signYaw * publishedYaw + sample.zeroYawDegrees);
        double publishedPitch = Mth.clamp(pitchDegrees, -90.0F, 90.0F);
        sample.publishedPitchDegrees = publishedPitch;
        sample.pitchRadians = Math.toRadians(Mth.clamp(
                sample.signPitch * publishedPitch + sample.zeroPitchDegrees, -90.0, 90.0));
        sample.rawYawRadians = Math.toRadians(Mth.wrapDegrees(gunner.getViewYRot(1.0F)));
        sample.rawPitchRadians = Math.toRadians(Mth.clamp(gunner.getViewXRot(1.0F), -90.0F, 90.0F));
        sample.lastBearingYawDegrees = sample.bearingYawDegrees;
        sample.lastBearingPitchDegrees = sample.bearingPitchDegrees;
        debugLog(controller, level, gunner, sample);
    }

    /**
     * The point the lock is aiming at, and the point the request was actually built
     * from when those differ: a lock carried by an entity that rides a structure
     * arrives in the structure's own plot coordinates and is pulled back out first.
     */
    private static String lockTargetText(SeatViewSample sample) {
        Vec3 raw = sample.lockTargetRaw;
        Vec3 used = sample.lockTargetUsed;
        if (raw == null && used == null) {
            return "none";
        }
        Vec3 shown = used == null ? raw : used;
        if (raw == null || used == null || raw.equals(used)) {
            return String.format(Locale.ROOT, "(%.2f, %.2f, %.2f)", shown.x, shown.y, shown.z);
        }
        return String.format(
                Locale.ROOT,
                "(%.2f, %.2f, %.2f)->(%.2f, %.2f, %.2f)",
                raw.x, raw.y, raw.z, used.x, used.y, used.z);
    }

    /**
     * Which reading of the locked point the mount settled on - world as reported, or the
     * body reading of the same offset - together with the angle each reading makes with the
     * reticle. The reading under the reticle is the one the lock means; a large pair of
     * angles means the gunner is looking somewhere else than the target.
     */
    private static String lockFrameText(SeatViewSample sample) {
        String frame = sample.lockTargetWorldFrame == null
                ? "?"
                : sample.lockTargetWorldFrame ? "world" : "local";
        if (!Double.isFinite(sample.lockFrameAngleWorld)) {
            return frame;
        }
        return String.format(
                Locale.ROOT,
                "%s(%.1f/%.1f)",
                frame,
                sample.lockFrameAngleWorld,
                sample.lockFrameAngleLocal);
    }

    private static void debugLog(
            StabilizerControllerBlockEntity controller,
            Level level,
            ServerPlayer gunner,
            SeatViewSample sample) {
        long gameTime = level.getGameTime();
        if (gameTime % 40L != 0L || sample.debugLoggedTick == gameTime) {
            return;
        }
        sample.debugLoggedTick = gameTime;
        BindingRef seat = controller.getBinding(BindingSlot.SEAT);
        BlockPos bodyPos = seat != null ? seat.pos() : controller.getBlockPos();
        Optional<PhysicsBodyView> body = SynaxisPhysics.bodyAt(level, bodyPos)
                .flatMap(SynaxisPhysics::body)
                .filter(view -> !view.isEmpty());
        Quaterniondc orientation = body.map(PhysicsBodyView::orientation).orElse(null);
        String orientationText = orientation == null
                ? "none"
                : String.format(
                        Locale.ROOT,
                        "(w=%.4f x=%.4f y=%.4f z=%.4f)",
                        orientation.w(),
                        orientation.x(),
                        orientation.y(),
                        orientation.z());
        String motionText = body.isEmpty()
                ? "none"
                : String.format(
                        Locale.ROOT,
                        "vel=(%.3f, %.3f, %.3f) omega=(%.3f, %.3f, %.3f)",
                        body.get().velocity().x(),
                        body.get().velocity().y(),
                        body.get().velocity().z(),
                        body.get().omega().x(),
                        body.get().omega().y(),
                        body.get().omega().z());
        Vec3 view = Vec3.directionFromRotation(
                Mth.clamp(gunner.getViewXRot(1.0F), -90.0F, 90.0F),
                Mth.wrapDegrees(gunner.getViewYRot(1.0F)));
        Vec3 scopePos = controller.getScopePosition();
        Vec3 measuredOrigin = controller.getMeasuredAimOrigin();
        Vec3 measuredDirection = controller.getMeasuredAimDirection();
        Vec3 lookAngle = gunner.getLookAngle();
        Vec3 bodyLook = body.isEmpty() || sample.rawView == null
                ? null
                : bodyToWorld(body.get(), sample.rawView);
        Vec3 measuredLocal = body.isEmpty() || measuredDirection == null
                ? null
                : worldToBody(body.get(), measuredDirection);
        String requestedText = sample.requestedWorld == null
                ? "raw"
                : String.format(
                        Locale.ROOT,
                        "(%.3f, %.3f, %.3f)",
                        sample.requestedWorld.x,
                        sample.requestedWorld.y,
                        sample.requestedWorld.z);
        String bearingText = Double.isFinite(bearingFeedback(controller, sample, true))
                ? String.format(
                        Locale.ROOT,
                        "(%.1fdeg, %.1fdeg) err=(%.2f, %.2f)deg freeze=%d",
                        sample.bearingYawDegrees,
                        sample.bearingPitchDegrees,
                        bearingErrorDegrees(sample, true),
                        bearingErrorDegrees(sample, false),
                        sample.zeroFreezeTicks)
                : sample.bearingYawSeen ? "idle" : "none";
        String lockTargetText = lockTargetText(sample);
        Vec3 worldAim = sample.measuredAimRaw == null
                ? null
                : sample.measuredAimInStructureFrame && body.isPresent()
                        ? bodyToWorld(body.get(), sample.measuredAimRaw)
                        : sample.measuredAimRaw;
        double worldAimError = worldAim == null || sample.requestedWorld == null
                ? Double.NaN
                : angleBetweenDegrees(worldAim, sample.requestedWorld);
        String trimText = String.format(
                Locale.ROOT,
                "(yaw=%.2f, pitch=%.2f)deg sign=(%.0f, %.0f) signKnown=(%s, %s) votes=(%d, %d) samples=(%d, %d) basis=%s zero=(%s, %s) resid=%s residAlt=%.2fdeg",
                sample.zeroYawDegrees,
                sample.zeroPitchDegrees,
                sample.signYaw,
                sample.signPitch,
                sample.signKnownYaw,
                sample.signKnownPitch,
                sample.signReadbackVotesYaw,
                sample.signReadbackVotesPitch,
                sample.yawSamples.count,
                sample.pitchSamples.count,
                sample.measuredAimInStructureFrame ? "structure" : "world",
                sample.zeroSourceYaw,
                sample.zeroSourcePitch,
                Double.isFinite(sample.trimResidualYaw)
                        ? String.format(
                                Locale.ROOT,
                                "(yaw=%.2f, pitch=%.2f)deg",
                                sample.trimResidualYaw,
                                sample.trimResidualPitch)
                        : "hold",
                sample.trimResidualAlternateYaw);
        String message = String.format(
                Locale.ROOT,
                "[synaxis-seat-view] seat=%s bodyPos=%s headAim=%s headAimReady=%s scoped=%s "
                        + "raw=(%.2fdeg, %.2fdeg) body=%s orient=%s view=(%.3f, %.3f, %.3f) "
                        + "out=(%.4frad, %.4frad) bearing=%s trim=%s req=%s hitDist=%s lock=%s "
                        + "lockFrame=%s src=%s lockTarget=%s motion=%s worldAim=%s worldAimErr=%.1fdeg "
                        + "youLook=%s bodyLook=%s lookMatch=%.1fdeg measuredLocal=(%.1fdeg, %.1fdeg) "
                        + "scopePos=%s measuredOrigin=%s measuredDir=%s",
                seat != null ? seat.pos() : "none",
                bodyPos,
                controller.isHeadAimEnabled(),
                controller.isHeadAimReady(),
                controller.isScopeEnabled(gunner),
                Mth.wrapDegrees(gunner.getViewYRot(1.0F)),
                Mth.clamp(gunner.getViewXRot(1.0F), -90.0F, 90.0F),
                body.isPresent() ? "present" : "empty",
                orientationText,
                view.x,
                view.y,
                view.z,
                sample.yawRadians,
                sample.pitchRadians,
                bearingText,
                trimText,
                requestedText,
                Double.isFinite(sample.hitDistance)
                        ? String.format(Locale.ROOT, "%.2f", sample.hitDistance)
                        : "none",
                sample.usingLockTarget,
                lockFrameText(sample),
                sample.aimSource,
                lockTargetText,
                motionText,
                worldAim == null
                        ? "none"
                        : String.format(Locale.ROOT, "(%.3f, %.3f, %.3f)", worldAim.x, worldAim.y, worldAim.z),
                worldAimError,
                lookAngle == null ? "none" : String.format(Locale.ROOT, "(%.3f, %.3f, %.3f)", lookAngle.x, lookAngle.y, lookAngle.z),
                bodyLook == null ? "none" : String.format(Locale.ROOT, "(%.3f, %.3f, %.3f)", bodyLook.x, bodyLook.y, bodyLook.z),
                lookAngle == null || bodyLook == null ? -1.0 : angleBetweenDegrees(lookAngle, bodyLook),
                measuredLocal == null ? Double.NaN : publishedYawDegrees(measuredLocal),
                measuredLocal == null ? Double.NaN : publishedPitchDegrees(measuredLocal),
                scopePos == null ? "none" : String.format(Locale.ROOT, "(%.2f, %.2f, %.2f)", scopePos.x, scopePos.y, scopePos.z),
                measuredOrigin == null ? "none" : String.format(Locale.ROOT, "(%.2f, %.2f, %.2f)", measuredOrigin.x, measuredOrigin.y, measuredOrigin.z),
                measuredDirection == null ? "none" : String.format(Locale.ROOT, "(%.3f, %.3f, %.3f)", measuredDirection.x, measuredDirection.y, measuredDirection.z));
        FireControlCompat.LOGGER.info(message);
    }

    private static Optional<ViewAnglesDegrees> headAimLocalAngles(
            StabilizerControllerBlockEntity controller,
            Level level,
            ServerPlayer gunner,
            SeatViewSample sample) {
        if (!controller.isHeadAimEnabled()) {
            return Optional.empty();
        }
        float rawYaw = Mth.wrapDegrees(gunner.getViewYRot(1.0F));
        float rawPitch = Mth.clamp(gunner.getViewXRot(1.0F), -90.0F, 90.0F);
        if (!Float.isFinite(rawYaw) || !Float.isFinite(rawPitch)) {
            return Optional.empty();
        }
        Vec3 cameraLook = Vec3.directionFromRotation(rawPitch, rawYaw);
        BindingRef seat = controller.getBinding(BindingSlot.SEAT);
        BlockPos bodyPos = seat != null ? seat.pos() : controller.getBlockPos();
        Optional<PhysicsBodyView> body = SynaxisPhysics.bodyAt(level, bodyPos)
                .flatMap(SynaxisPhysics::body)
                .filter(view -> !view.isEmpty());
        if (body.isEmpty()) {
            return Optional.empty();
        }
        PhysicsBodyView bodyView = body.get();
        Vec3 measuredAim = controller.getMeasuredAimDirection();
        sample.measuredAimRaw = measuredAim;
        sample.measuredAimBody = measuredAim == null ? null : worldToBody(bodyView, measuredAim);
        Vec3 worldLook = bodyToWorld(bodyView, cameraLook);
        sample.rawView = cameraLook;
        Vec3 requestedWorld =
                resolveRequestedWorldDirection(controller, level, gunner, bodyView, worldLook, sample);
        if (!Double.isFinite(requestedWorld.x)
                || !Double.isFinite(requestedWorld.y)
                || !Double.isFinite(requestedWorld.z)
                || requestedWorld.lengthSqr() <= 1.0E-12) {
            return Optional.empty();
        }
        Vec3 requestedLocal = worldToBody(bodyView, requestedWorld);
        double horizontal = Math.sqrt(
                requestedLocal.x * requestedLocal.x + requestedLocal.z * requestedLocal.z);
        float yaw = (float) Math.toDegrees(Math.atan2(-requestedLocal.x, requestedLocal.z));
        float pitch = (float) Math.toDegrees(Math.atan2(-requestedLocal.y, horizontal));
        sample.publishedYawDegrees = -Mth.wrapDegrees(yaw);
        sample.publishedPitchDegrees = Mth.clamp(pitch, -90.0F, 90.0F);
        return Optional.of(
                new ViewAnglesDegrees(
                        yaw,
                        pitch,
                        requestedWorld,
                        hitDistance.get() == null ? Double.NaN : hitDistance.get(),
                        Boolean.TRUE.equals(usingLockTarget.get())));
    }

    private static final ThreadLocal<Double> hitDistance = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> usingLockTarget = new ThreadLocal<>();
    private static final double MAX_AIM_CLIP_DISTANCE = 4096.0;
    private static final double MAX_VALID_HIT_DISTANCE = 8192.0;
    private static final double TRIM_SETTLE_DEGREES_PER_TICK = 0.35;
    private static final double TRIM_SETTLE_MEASURED_DEGREES_PER_TICK = 0.5;
    private static final double BEARING_SETTLE_DEGREES_PER_TICK = 0.6;
    private static final double BEARING_STUCK_MOVE_DEGREES = 0.02;
    private static final double BEARING_LIVE_MOVE_DEGREES = 0.05;
    private static final int BEARING_FEEDBACK_STALE_TICKS = 200;
    private static final double FRAME_SWITCH_GOOD_RESIDUAL_DEGREES = 10.0;
    private static final double FRAME_SWITCH_BASIS_SEPARATION_DEGREES = 30.0;
    private static final int FRAME_SWITCH_TICKS = 20;
    private static final double ZERO_PITCH_LIMIT_DEGREES = 89.0;
    private static final int ZERO_WINDOW_SAMPLES = 48;
    private static final int ZERO_FIT_MIN_SAMPLES = 10;
    private static final int ZERO_FIT_INTERVAL_TICKS = 4;
    private static final double ZERO_FIT_MIN_SPREAD_DEGREES = 15.0;
    private static final double ZERO_FIT_MIN_SLOPE = 0.5;
    private static final double ZERO_FIT_GAIN = 0.5;
    private static final int ZERO_FIT_SIGN_VOTES = 2;
    private static final double ZERO_STUCK_MOVE_DEGREES = 0.02;
    private static final double ZERO_STUCK_COMMAND_DEGREES = 0.5;
    private static final double ZERO_REIDENTIFY_DEGREES = 25.0;
    private static final int ZERO_REIDENTIFY_TICKS = 20;
    private static final double MIN_AIM_HIT_DISTANCE = 1.5;
    private static final long MAX_AIM_MEMORY_TICKS = 200L;
    private static final double MAX_WORLD_COORDINATE = 1.0E6;
    private static final double LOCK_RANGE_BASELINE_FACTOR = 4.0;
    /**
     * A locked point has to be lifted into the world frame the muzzle, the scope and the
     * clip ray all share. A point Sable projected out of a plot already is; a sub-level
     * anchor arrives through the structure pose, i.e. rotated by the vehicle the gunner
     * sits on. The reading that puts the point under the reticle - where fire control
     * parks it when the lock is taken - separates the two.
     */
    private static final double LOCK_FRAME_CONFIRM_DEGREES = 20.0;
    private static final double LOCK_FRAME_MARGIN_DEGREES = 25.0;
    private static final int LOCK_FRAME_VOTES = 3;
    private static final double ZERO_CORRECT_GAIN = 0.5;
    private static final double ZERO_CORRECT_MAX_STEP_DEGREES = 20.0;
    private static final double ZERO_CORRECT_DEADBAND_DEGREES = 0.05;
    private static final double ZERO_CORRECT_MAX_ERROR_DEGREES = 60.0;
    private static final int ZERO_FREEZE_TICKS = 12;
    private static final double ZERO_FREEZE_JUMP_DEGREES = 12.0;
    private static final int ZERO_FIT_IDLE_TICKS = 20;
    private static final double SIGN_READBACK_ANGLE_MOVE_DEGREES = 5.0;
    private static final double SIGN_READBACK_AIM_MOVE_DEGREES = 1.0;
    private static final double SIGN_READBACK_MISMATCH_DEGREES = 25.0;
    private static final int SIGN_READBACK_VOTES = 3;

    private static Vec3 resolveRequestedWorldDirection(
            StabilizerControllerBlockEntity controller,
            Level level,
            ServerPlayer gunner,
            PhysicsBodyView bodyView,
            Vec3 fallbackDirection,
            SeatViewSample sample) {
        hitDistance.remove();
        usingLockTarget.remove();
        sample.aimSource = "view";
        sample.lockTargetRaw = null;
        sample.lockTargetUsed = null;
        sample.lockTargetProjected = false;
        Vec3 safeFallback = fallbackDirection.lengthSqr() < 1.0E-8
                ? new Vec3(0.0, 0.0, -1.0)
                : fallbackDirection.normalize();
        try {
            Vec3 muzzle = controller.getMeasuredAimOrigin();
            Vec3 lockTarget = controller.getOpticalLockTarget();
            if (lockTarget != null) {
                usingLockTarget.set(true);
                sample.lockTargetRaw = lockTarget;
                sample.lockTargetUsed = resolveWorldFramePoint(level, lockTarget, sample);
            } else {
                sample.forgetLockFrame();
            }
            Vec3 cameraOrigin;
            if (controller.isScopeEnabled(gunner)) {
                Vec3 scope = controller.getScopePosition();
                cameraOrigin = scope != null ? scope : worldEyePosition(level, gunner);
            } else {
                cameraOrigin = worldEyePosition(level, gunner);
            }
            // While a lock is held the mount tracks the locked point itself: fire control
            // parks the reticle on that point when the lock is taken, but the gunner can
            // look away, and the mount is meant to stay on the target.
            Vec3 lockedDirection = resolveLockedDirection(bodyView, sample, cameraOrigin, safeFallback);
            if (lockedDirection != null && muzzle != null) {
                double lockRange = cameraOrigin.distanceTo(sample.lockTargetUsed);
                if (Double.isFinite(lockRange)
                        && lockRange >= MIN_AIM_HIT_DISTANCE
                        && lockRange <= MAX_VALID_HIT_DISTANCE) {
                    Vec3 aimPoint = cameraOrigin.add(lockedDirection.scale(lockRange));
                    Vec3 requested = aimPoint.subtract(muzzle);
                    if (requested.lengthSqr() >= 1.0E-8) {
                        hitDistance.set(lockRange);
                        sample.hitDistance = lockRange;
                        sample.aimSource = "lock";
                        return requested.normalize();
                    }
                }
            }
            Level clipLevel = gunner.level() != null ? gunner.level() : level;
            double loadedLimit = MAX_AIM_CLIP_DISTANCE;
            if (!clipLevel.isLoaded(BlockPos.containing(cameraOrigin))) {
                loadedLimit = 0.0;
            } else {
                for (double distance = 8.0; distance <= MAX_AIM_CLIP_DISTANCE; distance += 8.0) {
                    if (!clipLevel.isLoaded(BlockPos.containing(cameraOrigin.add(safeFallback.scale(distance))))) {
                        loadedLimit = distance - 8.0;
                        break;
                    }
                }
            }
            if (loadedLimit >= MIN_AIM_HIT_DISTANCE) {
                Vec3 clipEnd = cameraOrigin.add(safeFallback.scale(loadedLimit));
                BlockHitResult result = clipAimRay(
                        clipLevel, controller, gunner, cameraOrigin, clipEnd, Set.of());
                double clipHitDistance = aimHitDistance(clipLevel, cameraOrigin, result);
                if (clipHitDistance < MIN_AIM_HIT_DISTANCE) {
                    result = clipAimRay(
                            clipLevel,
                            controller,
                            gunner,
                            cameraOrigin,
                            clipEnd,
                            blockedStructureChain(clipLevel, result));
                    clipHitDistance = aimHitDistance(clipLevel, cameraOrigin, result);
                }
                if (clipHitDistance >= MIN_AIM_HIT_DISTANCE
                        && clipHitDistance <= MAX_VALID_HIT_DISTANCE
                        && muzzle != null) {
                    Vec3 worldHit = Sable.HELPER.projectOutOfSubLevel(clipLevel, result.getLocation());
                    Vec3 requested = worldHit.subtract(muzzle);
                    if (requested.lengthSqr() >= 1.0E-8) {
                        hitDistance.set(clipHitDistance);
                        sample.aimDistance = clipHitDistance;
                        sample.aimDistanceTick = clipLevel.getGameTime();
                        sample.aimSource = "hit";
                        return requested.normalize();
                    }
                }
            }
            if (muzzle != null && sample.lockTargetUsed != null) {
                // The locked point is not readable yet: borrow its range along the view ray
                // the way native bearing binding does with lockAimOffset.
                double lockRange = cameraOrigin.distanceTo(sample.lockTargetUsed);
                double baseline = cameraOrigin.distanceTo(muzzle);
                if (Double.isFinite(lockRange)
                        && lockRange >= MIN_AIM_HIT_DISTANCE
                        && lockRange <= MAX_VALID_HIT_DISTANCE
                        && lockRange >= LOCK_RANGE_BASELINE_FACTOR * baseline) {
                    Vec3 aimPoint = cameraOrigin.add(safeFallback.scale(lockRange));
                    Vec3 requested = aimPoint.subtract(muzzle);
                    if (requested.lengthSqr() >= 1.0E-8) {
                        hitDistance.set(lockRange);
                        sample.hitDistance = lockRange;
                        sample.aimSource = "lock";
                        return requested.normalize();
                    }
                }
            }
            if (muzzle != null
                    && Double.isFinite(sample.aimDistance)
                    && clipLevel.getGameTime() - sample.aimDistanceTick <= MAX_AIM_MEMORY_TICKS) {
                Vec3 aimPoint = cameraOrigin.add(safeFallback.scale(sample.aimDistance));
                Vec3 requested = aimPoint.subtract(muzzle);
                if (requested.lengthSqr() >= 1.0E-8) {
                    sample.aimSource = "memory";
                    return requested.normalize();
                }
            }
        } catch (LinkageError | RuntimeException ignored) {
            // Fall back to the parallel view direction.
        }
        return safeFallback;
    }

    /**
     * Direction from the scope to the locked point, expressed in world coordinates, or null
     * while that reading is still undecided. The frame is settled once per lock and then kept
     * for as long as the lock lasts: the reading that puts the point under the reticle wins,
     * and a point Sable already projected out of a plot is world by construction.
     */
    private static Vec3 resolveLockedDirection(
            PhysicsBodyView bodyView, SeatViewSample sample, Vec3 cameraOrigin, Vec3 worldLook) {
        Vec3 locked = sample.lockTargetUsed;
        if (locked == null || cameraOrigin == null) {
            return null;
        }
        Vec3 delta = locked.subtract(cameraOrigin);
        double length = delta.length();
        if (length <= 1.0E-4) {
            return null;
        }
        Vec3 worldReading = delta.scale(1.0 / length);
        Vec3 bodyReading = bodyView == null ? null : bodyToWorld(bodyView, delta);
        if (bodyReading != null) {
            double bodyLength = bodyReading.length();
            bodyReading = bodyLength <= 1.0E-4 ? null : bodyReading.scale(1.0 / bodyLength);
        }
        double worldAngle = angleBetweenDegrees(worldReading, worldLook);
        double bodyAngle = bodyReading == null ? Double.NaN : angleBetweenDegrees(bodyReading, worldLook);
        sample.lockFrameAngleWorld = worldAngle;
        sample.lockFrameAngleLocal = bodyAngle;
        if (bodyReading == null) {
            sample.lockTargetWorldFrame = Boolean.TRUE;
        } else if (sample.lockTargetWorldFrame == null) {
            boolean worldClear = worldAngle <= LOCK_FRAME_CONFIRM_DEGREES
                    && worldAngle + LOCK_FRAME_MARGIN_DEGREES < bodyAngle;
            boolean bodyClear = bodyAngle <= LOCK_FRAME_CONFIRM_DEGREES
                    && bodyAngle + LOCK_FRAME_MARGIN_DEGREES < worldAngle;
            if (worldClear || bodyClear) {
                if (worldClear) {
                    sample.lockFrameWorldVotes++;
                    sample.lockFrameLocalVotes = 0;
                } else {
                    sample.lockFrameLocalVotes++;
                    sample.lockFrameWorldVotes = 0;
                }
                if (sample.lockFrameWorldVotes >= LOCK_FRAME_VOTES) {
                    sample.lockTargetWorldFrame = Boolean.TRUE;
                } else if (sample.lockFrameLocalVotes >= LOCK_FRAME_VOTES) {
                    sample.lockTargetWorldFrame = Boolean.FALSE;
                }
            } else if (Math.abs(worldAngle - bodyAngle) < LOCK_FRAME_MARGIN_DEGREES
                    && Math.min(worldAngle, bodyAngle) <= LOCK_FRAME_CONFIRM_DEGREES) {
                // Both readings agree closely, so either one aims at the target.
                sample.lockTargetWorldFrame = worldAngle <= bodyAngle;
            }
        }
        if (sample.lockTargetWorldFrame == null) {
            return null;
        }
        return sample.lockTargetWorldFrame ? worldReading : bodyReading;
    }

    private static BlockHitResult clipAimRay(
            Level level,
            StabilizerControllerBlockEntity controller,
            ServerPlayer gunner,
            Vec3 origin,
            Vec3 end,
            Set<SubLevel> extraIgnored) {
        ClipContext context = new ClipContext(
                origin,
                end,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                gunner);
        ignoreOwnStructures(level, controller, gunner, context, extraIgnored);
        return level.clip(context);
    }

    /**
     * A locked target arrives in whatever frame the lock captured it in: a lock held on
     * a structure resolves through that structure's pose, but an entity that rides one -
     * a missile on a launcher, a mob on a deck - keeps the plot-space position it is
     * simulated at, which sits millions of blocks away from the world copy the muzzle,
     * the scope and the player live in. A point that far out is pulled back out of its
     * sub-level, so the aim still points at the target instead of at the far-away plot.
     * Both the raw and the used point are remembered for the debug line.
     */
    private static Vec3 resolveWorldFramePoint(Level level, Vec3 point, SeatViewSample sample) {
        if (Math.abs(point.x) <= MAX_WORLD_COORDINATE
                && Math.abs(point.y) <= MAX_WORLD_COORDINATE
                && Math.abs(point.z) <= MAX_WORLD_COORDINATE) {
            return point;
        }
        try {
            SubLevel containing = Sable.HELPER.getContaining(level, point);
            if (containing != null && !containing.isRemoved()) {
                Vec3 projected = Sable.HELPER.projectOutOfSubLevel(level, point);
                if (projected != null
                        && Double.isFinite(projected.x)
                        && Double.isFinite(projected.y)
                        && Double.isFinite(projected.z)
                        && projected.lengthSqr() < point.lengthSqr()) {
                    sample.lockTargetProjected = true;
                    return projected;
                }
            }
        } catch (LinkageError | RuntimeException ignoredFailure) {
            // Sable missing or the point is not inside a plot: keep the reported value.
        }
        return point;
    }

    private static double aimHitDistance(Level level, Vec3 origin, BlockHitResult result) {
        if (result.getType() != HitResult.Type.BLOCK) {
            return Double.NaN;
        }
        return origin.distanceTo(Sable.HELPER.projectOutOfSubLevel(level, result.getLocation()));
    }

    private static Set<SubLevel> blockedStructureChain(Level level, BlockHitResult result) {
        Set<SubLevel> ignored = new HashSet<>();
        if (result.getType() == HitResult.Type.BLOCK) {
            collectIgnoredStructure(level, result.getLocation(), ignored);
        }
        return ignored;
    }

    /**
     * A seated player tracked by Sable lives inside the structure's sublevel,
     * so {@code getEyePosition()} returns plot coordinates. Project those back
     * to world space (matching the world-space muzzle and scope positions)
     * before building the clip; points outside every sublevel are returned
     * unchanged.
     */
    private static Vec3 worldEyePosition(Level level, ServerPlayer gunner) {
        Vec3 eye = gunner.getEyePosition();
        try {
            SubLevel containing = Sable.HELPER.getContaining(level, eye);
            if (containing != null && !containing.isRemoved()) {
                Vec3 projected = Sable.HELPER.projectOutOfSubLevel(level, eye);
                if (projected != null
                        && Double.isFinite(projected.x)
                        && Double.isFinite(projected.y)
                        && Double.isFinite(projected.z)) {
                    return projected;
                }
            }
        } catch (LinkageError | RuntimeException ignored) {
            // Fall back to the raw eye position.
        }
        return eye;
    }

    /**
     * Marks the sublevels carrying the gunner and the turret as ignored on
     * the clip context, mirroring the fire control client's own aim ray
     * ({@code ClipContextExtension#sable$setSubLevelIgnoring}). Sable's plot
     * lookup operates in plot coordinates, so the seeds are block/entity
     * positions that already live in plot space when the structure is inside
     * a physics plot. The server-side clip then skips our own structure and
     * reports world-space hits on real terrain and hostile structures, which
     * is what native bearing binding converges on.
     */
    private static void ignoreOwnStructures(
            Level level,
            StabilizerControllerBlockEntity controller,
            ServerPlayer gunner,
            ClipContext context,
            Set<SubLevel> extraIgnored) {
        try {
            if (!(context instanceof ClipContextExtension extension)) {
                return;
            }
            Set<SubLevel> ignored = new HashSet<>(extraIgnored);
            collectIgnoredStructure(level, Vec3.atCenterOf(controller.getBlockPos()), ignored);
            BindingRef seat = controller.getBinding(BindingSlot.SEAT);
            if (seat != null) {
                collectIgnoredStructure(level, Vec3.atCenterOf(seat.pos()), ignored);
            }
            collectIgnoredStructure(level, gunner.position(), ignored);
            if (gunner.getVehicle() != null) {
                collectIgnoredStructure(level, gunner.getVehicle().position(), ignored);
            }
            Vec3 scope = controller.getScopePosition();
            if (scope != null) {
                collectIgnoredStructure(level, scope, ignored);
            }
            Vec3 muzzle = controller.getMeasuredAimOrigin();
            if (muzzle != null) {
                collectIgnoredStructure(level, muzzle, ignored);
            }
            if (!ignored.isEmpty()) {
                extension.sable$setSubLevelIgnoring(ignored::contains);
            }
        } catch (LinkageError | RuntimeException ignoredFailure) {
            // Sable not present or not initialized: fall back to the plain clip.
        }
    }

    private static void collectIgnoredStructure(
            Level level,
            Vec3 plotPoint,
            Set<SubLevel> ignored) {
        SubLevel containing = Sable.HELPER.getContaining(level, plotPoint);
        if (containing == null || containing.isRemoved()) {
            return;
        }
        for (SubLevel member : SubLevelHelper.getConnectedChain(containing)) {
            if (member != null && !member.isRemoved()) {
                ignored.add(member);
            }
        }
    }

    /**
     * Identifies the constant aim error between the published angles and the aim the
     * mount actually reaches, so the mount zero, the direction it answers in and any
     * gain error stop mattering. Only ticks where the request and the measured aim both
     * stand still are used: while either one moves the difference is servo lag rather
     * than a mount error. Each axis fits measured = direction * command + offset over a
     * sliding window, which also notices an axis that was re-bound and now answers the
     * other way round.
     *
     * <p>The reported muzzle direction is compared against the request in whichever of
     * the two bases leaves the mount looking aligned: some builds answer in the
     * structure's own frame, others in world terms. The port starts in the structure
     * frame and only switches while the other basis is both clearly better and clearly
     * apart from the current one, so a mount that is merely slewing never flips it.</p>
     *
     * <p>Every settled tick the aim error is fed back into the zero, which places a
     * request that is standing still - a lock on a parked target, say - without waiting
     * for the window to gather a spread, and does it from the aim itself, so the request
     * is placed the same way whichever mode produced it. The fit keeps the direction the
     * axis answers in and takes the zero back over whenever that correction is idle.</p>
     */
    private static void updateAimTrim(
            StabilizerControllerBlockEntity controller, SeatViewSample sample, Vec3 requestedWorld) {
        sample.trimResidualYaw = Double.NaN;
        sample.trimResidualPitch = Double.NaN;
        sample.trimResidualAlternateYaw = Double.NaN;
        if (sample.zeroFreezeTicks > 0) {
            sample.zeroFreezeTicks--;
        }
        if (sample.zeroCorrectIdleTicks < ZERO_FIT_IDLE_TICKS) {
            sample.zeroCorrectIdleTicks++;
        }
        Vec3 measured = sample.measuredAimRaw;
        if (requestedWorld == null || measured == null || measured.lengthSqr() <= 1.0E-8) {
            sample.trimRequested = requestedWorld;
            sample.trimMeasured = measured;
            sample.steadyTicks = 0;
            return;
        }
        double requestedYaw = publishedYawDegrees(requestedWorld);
        double requestedPitch = publishedPitchDegrees(requestedWorld);
        double measuredYaw = publishedYawDegrees(measured);
        double measuredPitch = publishedPitchDegrees(measured);
        Vec3 measuredInBody = sample.measuredAimBody;
        double measuredBodyYaw = measuredInBody == null ? measuredYaw : publishedYawDegrees(measuredInBody);
        double measuredBodyPitch = measuredInBody == null ? measuredPitch : publishedPitchDegrees(measuredInBody);
        boolean settled = sample.trimRequested != null
                && sample.trimMeasured != null
                && Math.abs(Mth.wrapDegrees((float) (requestedYaw - publishedYawDegrees(sample.trimRequested))))
                        <= TRIM_SETTLE_DEGREES_PER_TICK
                && Math.abs(requestedPitch - publishedPitchDegrees(sample.trimRequested))
                        <= TRIM_SETTLE_DEGREES_PER_TICK
                && Math.abs(Mth.wrapDegrees((float) (measuredYaw - publishedYawDegrees(sample.trimMeasured))))
                        <= TRIM_SETTLE_MEASURED_DEGREES_PER_TICK
                && Math.abs(measuredPitch - publishedPitchDegrees(sample.trimMeasured))
                        <= TRIM_SETTLE_MEASURED_DEGREES_PER_TICK
                && bearingSettled(controller, sample);
        boolean lockSwitched = sample.usingLockTarget != sample.lastUsingLock;
        boolean requestJumped = sample.trimRequested != null
                && (Math.abs(Mth.wrapDegrees((float) (requestedYaw - publishedYawDegrees(sample.trimRequested))))
                                > ZERO_FREEZE_JUMP_DEGREES
                        || Math.abs(requestedPitch - publishedPitchDegrees(sample.trimRequested))
                                > ZERO_FREEZE_JUMP_DEGREES);
        sample.lastUsingLock = sample.usingLockTarget;
        if (lockSwitched || requestJumped) {
            sample.zeroFreezeTicks = ZERO_FREEZE_TICKS;
            clearSignReference(sample, true);
            clearSignReference(sample, false);
        }
        sample.trimRequested = requestedWorld;
        sample.trimMeasured = measured;
        if (!settled) {
            sample.steadyTicks = 0;
            return;
        }
        double residualStructureYaw = Mth.wrapDegrees((float) (measuredYaw - sample.publishedYawDegrees));
        double residualWorldYaw = Mth.wrapDegrees((float) (measuredBodyYaw - sample.publishedYawDegrees));
        if (shouldSwitchAimBasis(sample, residualStructureYaw, residualWorldYaw)) {
            sample.measuredAimInStructureFrame = !sample.measuredAimInStructureFrame;
            sample.aimBasisProven = false;
            sample.frameSwitchTicks = 0;
            sample.steadyTicks = 0;
            sample.yawSamples.clear();
            sample.pitchSamples.clear();
        }
        double aimYaw = sample.measuredAimInStructureFrame ? measuredYaw : measuredBodyYaw;
        double aimPitch = sample.measuredAimInStructureFrame ? measuredPitch : measuredBodyPitch;
        double residualYaw = Mth.wrapDegrees((float) (aimYaw - sample.publishedYawDegrees));
        double residualPitch = aimPitch - sample.publishedPitchDegrees;
        sample.aimBasisProven = sample.aimBasisProven
                || (Math.abs(residualYaw) <= FRAME_SWITCH_GOOD_RESIDUAL_DEGREES
                        && Math.abs(residualPitch) <= FRAME_SWITCH_GOOD_RESIDUAL_DEGREES);
        sample.trimResidualYaw = residualYaw;
        sample.trimResidualPitch = residualPitch;
        sample.trimResidualAlternateYaw =
                sample.measuredAimInStructureFrame ? residualWorldYaw : residualStructureYaw;
        sample.steadyTicks++;
        boolean learning = sample.zeroFreezeTicks <= 0;
        if (learning) {
            updateSignFromReadback(sample, true, aimYaw);
            updateSignFromReadback(sample, false, aimPitch);
        }
        boolean largeResidualYaw = Math.abs(residualYaw) > ZERO_REIDENTIFY_DEGREES;
        boolean largeResidualPitch = Math.abs(residualPitch) > ZERO_REIDENTIFY_DEGREES;
        if (largeResidualYaw || largeResidualPitch) {
            if (++sample.badFitTicks > ZERO_REIDENTIFY_TICKS) {
                sample.badFitTicks = 0;
                sample.steadyTicks = 0;
                sample.yawSamples.clear();
                sample.pitchSamples.clear();
                sample.zeroFreezeTicks = ZERO_FREEZE_TICKS;
                return;
            }
        } else {
            sample.badFitTicks = 0;
        }
        if (learning && sample.signKnownYaw) {
            correctZeroFromAim(sample, true, residualYaw);
        }
        if (learning && sample.signKnownPitch) {
            correctZeroFromAim(sample, false, residualPitch);
        }
        if (learning) {
            sample.yawSamples.observe(
                    commandDegrees(sample, true), aimYaw, bearingFeedback(controller, sample, true));
            sample.pitchSamples.observe(
                    commandDegrees(sample, false), aimPitch, bearingFeedback(controller, sample, false));
        }
        if (++sample.fitTicks >= ZERO_FIT_INTERVAL_TICKS) {
            sample.fitTicks = 0;
            refitZero(sample.yawSamples, sample, true);
            refitZero(sample.pitchSamples, sample, false);
        }
    }

    /**
     * Reads the bearing angle the circuit wired into {@code bearing_yaw} or
     * {@code bearing_pitch}, in degrees, or NaN while that input has not been written
     * recently. Both inputs are optional: without them the port falls back to watching
     * the reported muzzle direction for the same information.
     */
    private static double bearingFeedback(
            StabilizerControllerBlockEntity controller, SeatViewSample sample, boolean yaw) {
        if (!(yaw ? sample.bearingYawLive : sample.bearingPitchLive)) {
            return Double.NaN;
        }
        Level level = controller.getLevel();
        long now = level == null ? Long.MIN_VALUE : level.getGameTime();
        long written = yaw ? sample.bearingYawTick : sample.bearingPitchTick;
        if (now == Long.MIN_VALUE
                || written == Long.MIN_VALUE
                || now - written > BEARING_FEEDBACK_STALE_TICKS) {
            return Double.NaN;
        }
        return yaw ? sample.bearingYawDegrees : sample.bearingPitchDegrees;
    }

    /**
     * A readback only counts once it has been written at least once and has been seen to
     * move: a plant input that is declared but wired to nothing still receives its
     * default value every tick, and that constant must not be mistaken for a bearing
     * standing still.
     */
    private static void updateBearingLiveness(SeatViewSample sample) {
        sample.bearingYawLive = bearingLive(
                sample.bearingYawSeen,
                sample.bearingYawLive,
                sample.bearingYawDegrees,
                sample.lastBearingYawDegrees);
        sample.bearingPitchLive = bearingLive(
                sample.bearingPitchSeen,
                sample.bearingPitchLive,
                sample.bearingPitchDegrees,
                sample.lastBearingPitchDegrees);
    }

    private static boolean bearingLive(
            boolean seen, boolean live, double current, double previous) {
        if (!seen || !Double.isFinite(current)) {
            return false;
        }
        return live
                || (Double.isFinite(previous)
                        && Math.abs(Mth.wrapDegrees((float) (current - previous)))
                                > BEARING_LIVE_MOVE_DEGREES);
    }

    /**
     * While the bearing readback is wired only ticks where the mount has come to rest
     * are worth learning from, and the readback says so directly instead of through the
     * noisier reported aim.
     */
    private static boolean bearingSettled(
            StabilizerControllerBlockEntity controller, SeatViewSample sample) {
        double yaw = bearingFeedback(controller, sample, true);
        if (Double.isFinite(yaw)
                && Double.isFinite(sample.lastBearingYawDegrees)
                && Math.abs(Mth.wrapDegrees((float) (yaw - sample.lastBearingYawDegrees)))
                        > BEARING_SETTLE_DEGREES_PER_TICK) {
            return false;
        }
        double pitch = bearingFeedback(controller, sample, false);
        return !Double.isFinite(pitch)
                || !Double.isFinite(sample.lastBearingPitchDegrees)
                || Math.abs(Mth.wrapDegrees((float) (pitch - sample.lastBearingPitchDegrees)))
                        <= BEARING_SETTLE_DEGREES_PER_TICK;
    }

    /**
     * The angle the readback stands at, in degrees, or NaN while that input is idle or
     * was never seen to move. The circuit is expected to wire the driven axis back in the
     * sense the port commands it, so nothing is second guessed here: a circuit that
     * answers the other way round shows up as a vote against the direction the axis is
     * believed to answer in, and that verdict is the one the trim listens to.
     */
    private static double bearingAngle(SeatViewSample sample, boolean yaw) {
        if (!(yaw ? sample.bearingYawLive : sample.bearingPitchLive)) {
            return Double.NaN;
        }
        double angle = yaw ? sample.bearingYawDegrees : sample.bearingPitchDegrees;
        return Double.isFinite(angle) ? angle : Double.NaN;
    }

    /** Forgets what the readback has shown about the direction an axis answers in. */
    private static void clearSignReference(SeatViewSample sample, boolean yaw) {
        if (yaw) {
            sample.signRefAngleYaw = Double.NaN;
            sample.signRefAimYaw = Double.NaN;
            sample.signReadbackVotesYaw = 0;
        } else {
            sample.signRefAnglePitch = Double.NaN;
            sample.signRefAimPitch = Double.NaN;
            sample.signReadbackVotesPitch = 0;
        }
    }

    /** The angle the port is asking the mount to stand at, in the published sense. */
    private static double commandDegrees(SeatViewSample sample, boolean yaw) {
        return yaw
                ? Mth.wrapDegrees((float) (sample.signYaw * sample.publishedYawDegrees
                        + sample.zeroYawDegrees))
                : Mth.clamp(
                        sample.signPitch * sample.publishedPitchDegrees + sample.zeroPitchDegrees,
                        -90.0,
                        90.0);
    }

    /** How far the readback stands from the command, or NaN while there is no readback. */
    private static double bearingErrorDegrees(SeatViewSample sample, boolean yaw) {
        double angle = bearingAngle(sample, yaw);
        if (!Double.isFinite(angle)) {
            return Double.NaN;
        }
        return Mth.wrapDegrees((float) (angle - commandDegrees(sample, yaw)));
    }

    /**
     * Nudges the zero by the aim error the mount is holding. While the mount stands still
     * the angle its muzzle reached minus the angle the request asked for is a zero error
     * and nothing else - the servo lag that pollutes a moving mount is gone - so feeding
     * it back a step at a time lands the muzzle on the request. The same loop places a
     * request taken from the crosshair and one taken from a lock, so a lock cannot end up
     * on a different zero than free aim, which a readback-based solve could: there the
     * lag of the moment is written straight into the zero. The step is rate limited, and
     * skipped while the error is huge, because then the axis is being re-bound or is held
     * against a stop rather than trimmed, and chasing it would walk the request away from
     * the target.
     */
    private static void correctZeroFromAim(SeatViewSample sample, boolean yaw, double residualDegrees) {
        if (!Double.isFinite(residualDegrees)) {
            return;
        }
        if (Math.abs(residualDegrees) > ZERO_CORRECT_MAX_ERROR_DEGREES) {
            if (yaw) {
                sample.zeroSourceYaw = "hold";
            } else {
                sample.zeroSourcePitch = "hold";
            }
            return;
        }
        if (Math.abs(residualDegrees) <= ZERO_CORRECT_DEADBAND_DEGREES) {
            return;
        }
        double direction = yaw ? sample.signYaw : sample.signPitch;
        double correction = Mth.clamp(
                -ZERO_CORRECT_GAIN * direction * residualDegrees,
                -ZERO_CORRECT_MAX_STEP_DEGREES,
                ZERO_CORRECT_MAX_STEP_DEGREES);
        if (yaw) {
            sample.zeroYawDegrees = Mth.wrapDegrees((float) (sample.zeroYawDegrees + correction));
            sample.zeroSourceYaw = "aim";
        } else {
            sample.zeroPitchDegrees = Mth.clamp(
                    sample.zeroPitchDegrees + correction,
                    -ZERO_PITCH_LIMIT_DEGREES,
                    ZERO_PITCH_LIMIT_DEGREES);
            sample.zeroSourcePitch = "aim";
        }
        sample.zeroCorrectIdleTicks = 0;
    }

    /**
     * Reads the direction an axis answers in from the readback itself: over a move the
     * mount's angle and the aim it reaches change together while the axis answers in the
     * published sense, and against each other while it answers reversed. The two moves
     * are the same rotation, so a readback that swings the other way round - the mount
     * travelling through +-180 while the request went the short way - is thrown away
     * instead of voting. Three clear moves settle it, which arrives long before a fit has
     * the spread it needs for a slope, and the same evidence says that the aim being
     * compared is the mount's own pointing: an aim reported in another frame would not
     * follow the readback at all. Nothing is learned while the trim is frozen, so the
     * swing a lock or a re-bound causes cannot vote either.
     */
    private static void updateSignFromReadback(
            SeatViewSample sample, boolean yaw, double aimDegrees) {
        if (sample.zeroFreezeTicks > 0) {
            return;
        }
        double angle = bearingAngle(sample, yaw);
        if (!Double.isFinite(angle) || !Double.isFinite(aimDegrees)) {
            return;
        }
        double referenceAngle = yaw ? sample.signRefAngleYaw : sample.signRefAnglePitch;
        double referenceAim = yaw ? sample.signRefAimYaw : sample.signRefAimPitch;
        if (!Double.isFinite(referenceAngle) || !Double.isFinite(referenceAim)) {
            storeSignReference(sample, yaw, angle, aimDegrees);
            return;
        }
        double angleMove = Mth.wrapDegrees((float) (angle - referenceAngle));
        if (Math.abs(angleMove) < SIGN_READBACK_ANGLE_MOVE_DEGREES) {
            return;
        }
        double aimMove = Mth.wrapDegrees((float) (aimDegrees - referenceAim));
        storeSignReference(sample, yaw, angle, aimDegrees);
        int votes = yaw ? sample.signReadbackVotesYaw : sample.signReadbackVotesPitch;
        if (Math.abs(aimMove) < SIGN_READBACK_AIM_MOVE_DEGREES
                || Math.abs(Math.abs(angleMove) - Math.abs(aimMove))
                        > SIGN_READBACK_MISMATCH_DEGREES) {
            votes = 0;
        } else {
            double detected = (angleMove > 0.0) == (aimMove > 0.0) ? 1.0 : -1.0;
            double direction = yaw ? sample.signYaw : sample.signPitch;
            if (detected == direction) {
                votes = 0;
                markSignKnown(sample, yaw);
            } else if (++votes >= SIGN_READBACK_VOTES) {
                setSign(sample, yaw, detected);
                votes = 0;
            }
        }
        if (yaw) {
            sample.signReadbackVotesYaw = votes;
        } else {
            sample.signReadbackVotesPitch = votes;
        }
    }

    private static void storeSignReference(
            SeatViewSample sample, boolean yaw, double angle, double aim) {
        if (yaw) {
            sample.signRefAngleYaw = angle;
            sample.signRefAimYaw = aim;
        } else {
            sample.signRefAnglePitch = angle;
            sample.signRefAimPitch = aim;
        }
    }

    private static void markSignKnown(SeatViewSample sample, boolean yaw) {
        if (yaw) {
            sample.signKnownYaw = true;
        } else {
            sample.signKnownPitch = true;
        }
    }

    private static void setSign(SeatViewSample sample, boolean yaw, double direction) {
        if (yaw) {
            sample.signYaw = direction;
            sample.signKnownYaw = true;
            sample.yawSamples.clear();
        } else {
            sample.signPitch = direction;
            sample.signKnownPitch = true;
            sample.pitchSamples.clear();
        }
    }

    /**
     * Picks the frame the reported muzzle direction is written in by asking which one
     * leaves the mount aligned with the request. A basis only wins when it is clearly
     * better and clearly apart from the current one, and only after it has stayed that
     * way for a while.
     */
    private static boolean shouldSwitchAimBasis(
            SeatViewSample sample, double residualStructureYaw, double residualWorldYaw) {
        double current = sample.measuredAimInStructureFrame ? residualStructureYaw : residualWorldYaw;
        double alternate = sample.measuredAimInStructureFrame ? residualWorldYaw : residualStructureYaw;
        double separation = Math.abs(Mth.wrapDegrees((float) (residualStructureYaw - residualWorldYaw)));
        if (!sample.aimBasisProven
                || separation < FRAME_SWITCH_BASIS_SEPARATION_DEGREES
                || Math.abs(current) <= ZERO_REIDENTIFY_DEGREES
                || Math.abs(alternate) > FRAME_SWITCH_GOOD_RESIDUAL_DEGREES) {
            sample.frameSwitchTicks = 0;
            return false;
        }
        if (++sample.frameSwitchTicks < FRAME_SWITCH_TICKS) {
            return false;
        }
        sample.frameSwitchTicks = 0;
        return true;
    }

    /**
     * Least squares fit of the aim the mount reached against the angle the port asked
     * for. The slope tells which way the axis answers (a re-bound joint can answer the
     * other way) and the intercept tells where its zero sits, which is all the port
     * needs to place the request on the mount.
     *
     * <p>While the settled aim is being corrected directly the fit only watches the
     * direction the axis answers in: two loops writing the same zero would fight, and
     * the direct correction reaches the same place without needing a spread first. The
     * fit places the zero again whenever that correction has not written for a while.</p>
     */
    private static void refitZero(AxisSamples samples, SeatViewSample sample, boolean yaw) {
        int count = samples.count;
        if (count < ZERO_FIT_MIN_SAMPLES) {
            return;
        }
        double sumX = 0.0;
        double sumY = 0.0;
        double minX = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        for (int index = 0; index < count; index++) {
            double x = samples.commands[index];
            sumX += x;
            sumY += samples.aims[index];
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
        }
        double meanX = sumX / count;
        double meanY = sumY / count;
        double variance = 0.0;
        double covariance = 0.0;
        for (int index = 0; index < count; index++) {
            double offset = samples.commands[index] - meanX;
            variance += offset * offset;
            covariance += offset * (samples.aims[index] - meanY);
        }
        double direction = yaw ? sample.signYaw : sample.signPitch;
        int votes = yaw ? sample.signVoteYaw : sample.signVotePitch;
        if (maxX - minX >= ZERO_FIT_MIN_SPREAD_DEGREES && variance > 1.0E-6) {
            double slope = covariance / variance;
            if (Math.abs(slope) >= ZERO_FIT_MIN_SLOPE) {
                double detected = slope > 0.0 ? 1.0 : -1.0;
                markSignKnown(sample, yaw);
                if (detected == direction) {
                    votes = 0;
                } else if (++votes >= ZERO_FIT_SIGN_VOTES) {
                    direction = detected;
                    votes = 0;
                }
            }
        }
        double offset = meanY - direction * meanX;
        double target = -direction * offset;
        boolean fitZero = sample.zeroCorrectIdleTicks >= ZERO_FIT_IDLE_TICKS;
        if (yaw) {
            sample.signYaw = direction;
            sample.signVoteYaw = votes;
            if (fitZero) {
                sample.zeroYawDegrees = Mth.wrapDegrees((float) (sample.zeroYawDegrees
                        + ZERO_FIT_GAIN * Mth.wrapDegrees((float) (target - sample.zeroYawDegrees))));
                sample.zeroSourceYaw = "fit";
            }
        } else {
            sample.signPitch = direction;
            sample.signVotePitch = votes;
            if (fitZero) {
                sample.zeroPitchDegrees = Mth.clamp(
                        sample.zeroPitchDegrees + ZERO_FIT_GAIN * (target - sample.zeroPitchDegrees),
                        -ZERO_PITCH_LIMIT_DEGREES,
                        ZERO_PITCH_LIMIT_DEGREES);
                sample.zeroSourcePitch = "fit";
            }
        }
    }

    /**
     * Sliding window of settled samples per axis. Angles are unwrapped while they are
     * stored so a fit sees a straight line even when the bearing crosses +-180. A
     * sample where the aim did not move although the command did is thrown away: that
     * axis is parked against a stop, and its aim says nothing about its zero.
     *
     * <p>The independent value of a sample is the angle the port asked the axis to stand
     * at, never the readback: the regression then measures the aim against the one thing
     * the mount cannot lag behind unnoticed, and a readback wired to the wrong axis or
     * answering backwards cannot bend the zero the fit produces.</p>
     */
    private static final class AxisSamples {
        private final double[] commands = new double[ZERO_WINDOW_SAMPLES];
        private final double[] aims = new double[ZERO_WINDOW_SAMPLES];
        private int count;
        private int head;
        private double unwrappedX = Double.NaN;
        private double unwrappedAim = Double.NaN;
        private double lastX = Double.NaN;
        private double lastCommand = Double.NaN;
        private double lastAim = Double.NaN;
        private double lastFeedback = Double.NaN;

        private void clear() {
            this.count = 0;
            this.head = 0;
            this.unwrappedX = Double.NaN;
            this.unwrappedAim = Double.NaN;
            this.lastX = Double.NaN;
            this.lastCommand = Double.NaN;
            this.lastAim = Double.NaN;
            this.lastFeedback = Double.NaN;
        }

        private void observe(double command, double aim, double feedback) {
            double independent = command;
            boolean stuck;
            if (Double.isFinite(feedback) && Double.isFinite(this.lastFeedback)) {
                stuck = Math.abs(Mth.wrapDegrees((float) (feedback - this.lastFeedback)))
                                < ZERO_STUCK_MOVE_DEGREES
                        && Math.abs(Mth.wrapDegrees((float) (command - this.lastCommand)))
                                > ZERO_STUCK_COMMAND_DEGREES;
            } else {
                stuck = Double.isFinite(this.lastAim)
                        && Math.abs(Mth.wrapDegrees((float) (aim - this.lastAim))) < ZERO_STUCK_MOVE_DEGREES
                        && Math.abs(Mth.wrapDegrees((float) (command - this.lastCommand)))
                                > ZERO_STUCK_COMMAND_DEGREES;
            }
            if (!Double.isFinite(this.unwrappedX)) {
                this.unwrappedX = independent;
                this.unwrappedAim = aim;
            } else {
                this.unwrappedX += Mth.wrapDegrees((float) (independent - this.lastX));
                this.unwrappedAim += Mth.wrapDegrees((float) (aim - this.lastAim));
            }
            this.lastX = independent;
            this.lastCommand = command;
            this.lastAim = aim;
            this.lastFeedback = feedback;
            if (stuck) {
                return;
            }
            this.commands[this.head] = this.unwrappedX;
            this.aims[this.head] = this.unwrappedAim;
            this.head = (this.head + 1) % ZERO_WINDOW_SAMPLES;
            if (this.count < ZERO_WINDOW_SAMPLES) {
                this.count++;
            }
        }
    }

    private static double publishedYawDegrees(Vec3 direction) {
        return -Math.toDegrees(Math.atan2(-direction.x, direction.z));
    }

    /**
     * Seat view angles are body coordinates: the player looking straight ahead on a
     * turned vehicle still reports a small yaw, while the aim geometry (muzzle,
     * scope, lock target, ray hits) are all world coordinates. Every direction that
     * crosses between the two has to pass through the body orientation, otherwise
     * the mount aims correctly only while the structure happens to face world north.
     */
    private static Vec3 bodyToWorld(PhysicsBodyView body, Vec3 direction) {
        Vector3d source = new Vector3d(direction.x, direction.y, direction.z);
        Vector3d rotated = body.bodyToWorldDirection(source, new Vector3d());
        return new Vec3(rotated.x, rotated.y, rotated.z);
    }

    private static Vec3 worldToBody(PhysicsBodyView body, Vec3 direction) {
        Vector3d source = new Vector3d(direction.x, direction.y, direction.z);
        Vector3d rotated = body.worldToBodyDirection(source, new Vector3d());
        return new Vec3(rotated.x, rotated.y, rotated.z);
    }

    private static double publishedPitchDegrees(Vec3 direction) {
        double horizontal = Math.sqrt(direction.x * direction.x + direction.z * direction.z);
        return Math.toDegrees(Math.atan2(-direction.y, horizontal));
    }

    private static double angleBetweenDegrees(Vec3 first, Vec3 second) {
        double product = first.length() * second.length();
        if (product <= 1.0E-9) {
            return Double.MAX_VALUE;
        }
        return Math.toDegrees(Math.acos(Mth.clamp(first.dot(second) / product, -1.0, 1.0)));
    }

    private record ViewAnglesDegrees(
            float yaw,
            float pitch,
            Vec3 requestedWorld,
            Double hitDistance,
            Boolean usingLockTarget) {
    }

    private static final class SeatViewSample {
        private long sampledTick = Long.MIN_VALUE;
        private long debugLoggedTick = Long.MIN_VALUE;
        private double yawRadians;
        private double pitchRadians;
        private Vec3 requestedWorld;
        private Vec3 rawView;
        private double hitDistance = Double.NaN;
        private String aimSource = "view";
        private double aimDistance = Double.NaN;
        private long aimDistanceTick = Long.MIN_VALUE;
        private boolean usingLockTarget;
        private double zeroYawDegrees;
        private double zeroPitchDegrees;
        private double signYaw = 1.0;
        private double signPitch = 1.0;
        private final AxisSamples yawSamples = new AxisSamples();
        private final AxisSamples pitchSamples = new AxisSamples();
        private int signVoteYaw;
        private int signVotePitch;
        private int fitTicks;
        private int badFitTicks;
        private double rawYawRadians;
        private double rawPitchRadians;
        private int steadyTicks;
        private double trimResidualYaw = Double.NaN;
        private double trimResidualPitch = Double.NaN;
        private double trimResidualAlternateYaw = Double.NaN;
        private Vec3 trimRequested;
        private Vec3 trimMeasured;
        private double publishedYawDegrees;
        private double publishedPitchDegrees;
        private Vec3 measuredAimRaw;
        private Vec3 measuredAimBody;
        private boolean measuredAimInStructureFrame = true;
        private boolean aimBasisProven;
        private int frameSwitchTicks;
        private Vec3 lockTargetRaw;
        private Vec3 lockTargetUsed;
        private boolean lockTargetProjected;
        private Boolean lockTargetWorldFrame;
        private int lockFrameWorldVotes;
        private int lockFrameLocalVotes;
        private double lockFrameAngleWorld = Double.NaN;
        private double lockFrameAngleLocal = Double.NaN;
        private double bearingYawDegrees = Double.NaN;
        private double bearingPitchDegrees = Double.NaN;
        private long bearingYawTick = Long.MIN_VALUE;
        private long bearingPitchTick = Long.MIN_VALUE;
        private double lastBearingYawDegrees = Double.NaN;
        private double lastBearingPitchDegrees = Double.NaN;
        private boolean bearingYawSeen;
        private boolean bearingYawLive;
        private boolean bearingPitchSeen;
        private boolean bearingPitchLive;
        private int zeroFreezeTicks;
        private int zeroCorrectIdleTicks = ZERO_FIT_IDLE_TICKS;
        private boolean lastUsingLock;
        private String zeroSourceYaw = "fit";
        private String zeroSourcePitch = "fit";
        private boolean signKnownYaw;
        private boolean signKnownPitch;
        private double signRefAngleYaw = Double.NaN;
        private double signRefAimYaw = Double.NaN;
        private int signReadbackVotesYaw;
        private double signRefAnglePitch = Double.NaN;
        private double signRefAimPitch = Double.NaN;
        private int signReadbackVotesPitch;

        /** Back to "undecided", so the next lock works out its own frame from scratch. */
        private void forgetLockFrame() {
            lockTargetWorldFrame = null;
            lockFrameWorldVotes = 0;
            lockFrameLocalVotes = 0;
            lockFrameAngleWorld = Double.NaN;
            lockFrameAngleLocal = Double.NaN;
        }
    }

    private static final class SeatViewPlantPort implements GameThreadPlantPort {
        private static final ComponentTypeId TYPE =
                ComponentTypeId.of("firecontrolcompat:plant/fire_control_seat_view");
        private static final ComponentSchema SCHEMA = ComponentSchema.of(
                List.of(
                        PortDef.input("bearing_yaw", SignalType.REAL),
                        PortDef.input("bearing_pitch", SignalType.REAL)),
                List.of(
                        PortDef.output("view_yaw", SignalType.REAL),
                        PortDef.output("view_pitch", SignalType.REAL),
                        PortDef.output("raw_yaw", SignalType.REAL),
                        PortDef.output("raw_pitch", SignalType.REAL)));

        private final StabilizerControllerBlockEntity controller;
        private final EndpointId endpointId;
        private final String deviceName;

        private SeatViewPlantPort(
                StabilizerControllerBlockEntity controller,
                EndpointId endpointId,
                String givenName) {
            this.controller = controller;
            this.endpointId = endpointId;
            this.deviceName =
                    givenName != null && !givenName.isBlank()
                            ? givenName
                            : "Fire Control Computer";
        }

        @Override
        public EndpointId endpointId() {
            return this.endpointId;
        }

        @Override
        public EndpointAddress address() {
            Level level = this.controller.getLevel();
            BlockEntity blockEntity = this.controller;
            return level == null
                    ? EndpointAddress.of(Level.OVERWORLD, blockEntity.getBlockPos())
                    : EndpointAddress.of(level, blockEntity.getBlockPos())
                            .withBody(SynaxisPhysics.bodyAt(level, blockEntity.getBlockPos()));
        }

        @Override
        public String deviceName() {
            return this.deviceName;
        }

        @Override
        public ComponentTypeId componentType() {
            return TYPE;
        }

        @Override
        public ComponentSchema schema() {
            return SCHEMA;
        }

        @Override
        public boolean supportsDomain(ExecutionDomain domain) {
            return domain == ExecutionDomain.GAME_TICK;
        }

        @Override
        public void applyInput(String port, SignalValue value) {
            if (port == null || !(value instanceof SignalValue.Real real)) {
                return;
            }
            double radians = real.value();
            if (!Double.isFinite(radians)) {
                return;
            }
            Level level = this.controller.getLevel();
            long now = level == null ? Long.MIN_VALUE : level.getGameTime();
            SeatViewSample sample = sample(this.controller);
            switch (port) {
                case "bearing_yaw" -> {
                    sample.bearingYawDegrees = Mth.wrapDegrees((float) Math.toDegrees(radians));
                    sample.bearingYawTick = now;
                    sample.bearingYawSeen = true;
                }
                case "bearing_pitch" -> {
                    sample.bearingPitchDegrees = Math.toDegrees(radians);
                    sample.bearingPitchTick = now;
                    sample.bearingPitchSeen = true;
                }
                default -> {
                }
            }
        }

        @Override
        public SignalValue readOutput(String port) {
            SeatViewSample sample = sample(this.controller);
            return switch (port) {
                case "view_yaw" -> new SignalValue.Real(sample.yawRadians);
                case "view_pitch" -> new SignalValue.Real(sample.pitchRadians);
                case "raw_yaw" -> new SignalValue.Real(sample.rawYawRadians);
                case "raw_pitch" -> new SignalValue.Real(sample.rawPitchRadians);
                default -> null;
            };
        }
    }
}

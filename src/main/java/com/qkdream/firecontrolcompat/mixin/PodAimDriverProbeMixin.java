package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.qkdream.firecontrolcompat.FireControlCompat;
import java.util.Locale;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Names whoever is turning the pod.
 *
 * <p>A crew reported the pod's view disagreeing with the pod's model. Measuring
 * both showed the view standing still while the pod's own aim kept moving - so
 * something was turning the pod, and the question became what. This logs the aim
 * written into the pod, together with the first few frames of the call that wrote
 * it, once a second. Nothing has to be reproduced by a second player: sitting in
 * the pod view for ten seconds is enough to see whether the pod is being turned
 * while the gunner is doing nothing, and by whom.</p>
 */
@Mixin(StabilizerControllerBlockEntity.class)
public abstract class PodAimDriverProbeMixin {

    /** Last time the driver was reported, so the log stays readable. */
    @Unique
    private static long firecontrolcompat$lastDriverLog;

    /** Ignored frames: the pack's own plumbing and this mod's. */
    @Unique
    private static final String[] IGNORED = {"java.", "org.spongepowered", "com.qkdream.firecontrolcompat.mixin"};

    /**
     * The one that matters: this is what the pod's own direction is set from, and
     * what a pod showing the wrong way is showing. The target setter next to it is
     * watched too, because a flow may reach the direction through it.
     */
    @Inject(method = "setDesiredPodWorldDirection", at = @At("HEAD"), require = 0)
    private void firecontrolcompat$tracePodDirection(Vec3 direction, CallbackInfo ci) {
        firecontrolcompat$report(direction);
    }

    @Inject(method = "setDesiredWorldDirection", at = @At("HEAD"), require = 0)
    private void firecontrolcompat$traceWorldDirection(Vec3 direction, CallbackInfo ci) {
        firecontrolcompat$report(direction);
    }

    @Inject(method = "setDesiredPodWorldTarget", at = @At("HEAD"), require = 0)
    private void firecontrolcompat$tracePodDriver(Vec3 target, CallbackInfo ci) {
        firecontrolcompat$report(target);
    }

    /** Logs one written direction, throttled, with the frames that wrote it. */
    private void firecontrolcompat$report(Vec3 target) {
        try {
            if (target == null) {
                return;
            }
            StabilizerControllerBlockEntity self = (StabilizerControllerBlockEntity) (Object) this;
            if (self.getLevel() == null || self.getLevel().isClientSide()) {
                return;
            }
            long now = self.getLevel().getGameTime();
            if (now - firecontrolcompat$lastDriverLog < 20L) {
                return;
            }
            firecontrolcompat$lastDriverLog = now;
            StringBuilder frames = new StringBuilder();
            int taken = 0;
            for (StackTraceElement frame : new Throwable().getStackTrace()) {
                String name = frame.getClassName();
                boolean ignored = false;
                for (String prefix : IGNORED) {
                    if (name.startsWith(prefix)) {
                        ignored = true;
                        break;
                    }
                }
                if (ignored) {
                    continue;
                }
                if (taken > 0) {
                    frames.append(" <- ");
                }
                frames.append(name).append('.').append(frame.getMethodName())
                        .append(':').append(frame.getLineNumber());
                if (++taken >= 3) {
                    break;
                }
            }
            double yaw = Math.toDegrees(Math.atan2(-target.x, target.z));
            double pitch = Math.toDegrees(Math.atan2(-target.y, Math.hypot(target.x, target.z)));
            FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] pod aim written yaw={} pitch={} by {}",
                    String.format(Locale.ROOT, "%.1f", yaw),
                    String.format(Locale.ROOT, "%.1f", pitch),
                    frames.length() == 0 ? "unknown" : frames.toString());
        } catch (RuntimeException | LinkageError ignored) {
        }
    }
}

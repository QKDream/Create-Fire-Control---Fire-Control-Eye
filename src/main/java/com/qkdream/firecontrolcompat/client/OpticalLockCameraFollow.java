package com.qkdream.firecontrolcompat.client;

import com.hooya.stabilizedturret.client.ClientAimState;
import com.hooya.stabilizedturret.client.ClientTelevisionGuidanceState;
import com.qkdream.firecontrolcompat.FireControlCompat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * Keeps the locked optical target pinned to the centre of the screen while a
 * lock is held, for a player who is not on the controls himself.
 *
 * <p>Two rules keep this from fighting the mount's own aiming - which is what a
 * crew sees from outside as the pod's model pointing one way while the sight
 * points another:</p>
 *
 * <ul>
 *   <li><b>Never while a post is being steered.</b> A gunner at a bound
 *       controller is looking through the pack's own sight, and the pack steers
 *       its own model there. Taking the camera over on top of that leaves the
 *       camera on the lock and the model on the gunner's hand, which is the
 *       disagreement itself. This is for the spotter holding the lock beside the
 *       mount, not for the gunner.</li>
 *   <li><b>The turn is made in the tick, not in the frame.</b> The facing is
 *       written on the client tick, before the frame and before the aim that
 *       goes to the server is read, so the camera, the model and the aim packet
 *       all read one angle. Writing it only at render time was what let them
 *       drift apart.</li>
 * </ul>
 */
@EventBusSubscriber(modid = FireControlCompat.MOD_ID, value = Dist.CLIENT)
public final class OpticalLockCameraFollow {

    private OpticalLockCameraFollow() {
    }

    /**
     * Whether this player is a spotter holding a lock rather than a gunner on
     * the controls: only then may the camera be taken over.
     */
    private static boolean mayFollowLock() {
        if (!com.qkdream.firecontrolcompat.PodLinkSwitch.enabled()
                || !com.qkdream.firecontrolcompat.PodLinkSwitch.lockCameraEnabled()) {
            // Off unless the config asks for it: holding the camera after the pod
            // view ends is what left a crew stuck looking at one bearing.
            return false;
        }
        if (!ClientAimState.isOpticalLockActive() || ClientAimState.isPodView()) {
            return false;
        }
        if (ClientAimState.isSeatedAtBoundController() || ClientAimState.isScoped()) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.player != null
                && minecraft.level != null
                && !ClientTelevisionGuidanceState.isActive();
    }

    /** The angles that put the locked target in the centre of the screen. */
    private static float[] lockAngles(float partialTick) {
        Vec3 target = ClientAimState.getOpticalLockDisplayPoint(partialTick);
        if (target == null) {
            return null;
        }
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        Vec3 direction = target.subtract(camera.getPosition());
        if (direction.lengthSqr() < 1.0E-8D) {
            return null;
        }
        double horizontal = Math.sqrt(direction.x * direction.x + direction.z * direction.z);
        float yaw = Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(-direction.x, direction.z)));
        float pitch = Mth.clamp(
                Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(-direction.y, horizontal))), -90.0F, 90.0F);
        return new float[] {yaw, pitch};
    }

    /** The turn itself, on the client tick: camera, model and aim read one angle. */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!mayFollowLock()) {
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        float[] angles = lockAngles(1.0F);
        if (angles != null) {
            apply(player, angles[0], angles[1]);
        }
    }

    /**
     * Turns the pod view a half turn as it is drawn.
     *
     * <p>The pod's model faces one way and the view the pack draws out of it faced
     * the other; a crew measured the difference at a half turn. Doing it here
     * rather than on the direction the pack keeps is deliberate: the pack caches
     * that direction and restores it when the pod is entered, so turning the value
     * made every other entry open on the previous visit's view. A turn applied to
     * the drawn camera holds no state and cannot drift a visit behind.</p>
     */
    /** Last time the view angles were reported, so the log stays readable. */
    private static long lastViewProbe;

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPodViewHalfTurn(ViewportEvent.ComputeCameraAngles event) {
        if (com.qkdream.firecontrolcompat.PodLinkSwitch.enabled()
                && ClientAimState.isPodView()
                && Minecraft.getInstance().level != null) {
            long now = Minecraft.getInstance().level.getGameTime();
            if (now - lastViewProbe >= 20L) {
                lastViewProbe = now;
                float packYaw = event.getYaw();
                float packPitch = event.getPitch();
                // Reported before the correction is applied, so the two readings can
                // be compared: what the pack draws, and what is drawn in the end.
                // The pod's own aim and the block's facing, so one line carries
                // everything needed to work out the correction: what the pack draws,
                // what the pod is actually pointed at, and which way it is mounted.
                String wanted = "none";
                String facing = "none";
                try {
                    var controller = com.qkdream.firecontrolcompat.laser.LaserSpots.nearestController(
                            Minecraft.getInstance().level, Minecraft.getInstance().player.position(), 32.0D);
                    if (controller != null) {
                        net.minecraft.world.phys.Vec3 look = controller.getPodDesiredWorldDirection();
                        if (look != null) {
                            wanted = String.format(java.util.Locale.ROOT, "yaw=%.1f pitch=%.1f",
                                    Math.toDegrees(Math.atan2(-look.x, look.z)),
                                    Math.toDegrees(Math.atan2(-look.y, Math.hypot(look.x, look.z))));
                        }
                        var state = controller.getBlockState();
                        for (var property : state.getProperties()) {
                            if (property instanceof net.minecraft.world.level.block.state.properties.DirectionProperty dir
                                    && property.getName().equals("facing")) {
                                facing = state.getValue(dir).getName();
                            }
                        }
                    }
                } catch (RuntimeException | LinkageError ignored) {
                }
                float shownYaw = packYaw + 180.0F + (float) com.qkdream.firecontrolcompat.PodCameraOffset.yawOffset();
                // The client's own aim state, next to the angles the pack draws: a
                // camera standing still while its own aim moves is a rendering
                // question, and an aim standing still is an input one. The two look
                // the same from outside, which is why both are printed together.
                String aimState = "n/a";
                try {
                    net.minecraft.world.phys.Vec3 headAim = ClientAimState.getHeadAimTargetDirection();
                    aimState = String.format(java.util.Locale.ROOT,
                            "active=%s seated=%s headAim=%s lockFirstPerson=%s headAimDir=%s",
                            ClientAimState.isActive(),
                            ClientAimState.isSeatedAtBoundController(),
                            ClientAimState.isHeadAimEnabled(),
                            ClientAimState.shouldLockFirstPerson(),
                            headAim == null ? "none" : String.format(java.util.Locale.ROOT,
                                    "yaw=%.1f pitch=%.1f",
                                    Math.toDegrees(Math.atan2(-headAim.x, headAim.z)),
                                    Math.toDegrees(Math.atan2(-headAim.y, Math.hypot(headAim.x, headAim.z)))));
                } catch (RuntimeException | LinkageError ignored) {
                }
                com.qkdream.firecontrolcompat.FireControlCompat.LOGGER.info(
                        "[firecontrolcompat] pod view probe packYaw={} packPitch={} shownYaw={} podWanted={} facing={} aimState=[{}] flip={} yawOffset={} pitchOffset={}",
                        String.format(java.util.Locale.ROOT, "%.1f", packYaw),
                        String.format(java.util.Locale.ROOT, "%.1f", packPitch),
                        String.format(java.util.Locale.ROOT, "%.1f", shownYaw),
                        wanted,
                        facing,
                        aimState,
                        com.qkdream.firecontrolcompat.PodCameraOffset.flipAxis(),
                        com.qkdream.firecontrolcompat.PodCameraOffset.yawOffset(),
                        com.qkdream.firecontrolcompat.PodCameraOffset.pitchOffset());
            }
        }
        if (!com.qkdream.firecontrolcompat.PodCameraOffset.flips()
                || !com.qkdream.firecontrolcompat.PodLinkSwitch.enabled()
                || com.qkdream.firecontrolcompat.PodLinkSwitch.flipOnState()) {
            return;
        }
        if (!ClientAimState.isPodView()) {
            return;
        }
        float yaw = event.getYaw() + 180.0F;
        if (com.qkdream.firecontrolcompat.PodCameraOffset.flipsPitch()) {
            // The pitch turns over with the yaw, the way a direction turned about
            // the world's vertical axis behaves - which is what a crew's two
            // measurements (a half turn level, a quarter turn pitched) describe.
            event.setPitch(net.minecraft.util.Mth.clamp(-event.getPitch(), -90.0F, 90.0F));
        }
        if (com.qkdream.firecontrolcompat.PodCameraOffset.offsets()) {
            // Fine correction, for the case where a half turn alone does not line
            // the view up: the amount depends on how the pod is mounted, so it is
            // written in the config rather than guessed at here.
            yaw += (float) com.qkdream.firecontrolcompat.PodCameraOffset.yawOffset();
            event.setPitch(net.minecraft.util.Mth.clamp(
                    event.getPitch() + (float) com.qkdream.firecontrolcompat.PodCameraOffset.pitchOffset(),
                    -90.0F, 90.0F));
        }
        event.setYaw(net.minecraft.util.Mth.wrapDegrees(yaw));
    }

    /** Last time the client's own copy of the pods was reported. */
    private static long lastClientHeartbeat;

    /**
     * Prints what this client believes each nearby pod is pointed at.
     *
     * <p>The pod's model is drawn from the copy the client holds, not from the
     * server's own value. If the two disagree the model shows a direction the pod
     * is not actually pointing in - which is what a crew sees as the pod facing the
     * wrong way, with nobody at the controls and no camera involved at all.</p>
     */
    @SubscribeEvent
    public static void onClientHeartbeat(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        try {
            net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
            if (minecraft.level == null || minecraft.player == null) {
                return;
            }
            long now = minecraft.level.getGameTime();
            if (now - lastClientHeartbeat < 100L) {
                return;
            }
            lastClientHeartbeat = now;
            int seen = 0;
            // The tracker is a server-side list, so this client walks the blocks
            // around the player instead: the pod whose model is being looked at is
            // by definition nearby.
            net.minecraft.core.BlockPos around = minecraft.player.blockPosition();
            for (net.minecraft.core.BlockPos pos : net.minecraft.core.BlockPos.betweenClosed(
                    around.offset(-16, -8, -16), around.offset(16, 8, 16))) {
                if (!(minecraft.level.getBlockEntity(pos)
                        instanceof com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity controller)) {
                    continue;
                }
                if (controller.isRemoved()) {
                    continue;
                }
                seen++;
                net.minecraft.world.phys.Vec3 look = controller.getPodDesiredWorldDirection();
                com.qkdream.firecontrolcompat.FireControlCompat.LOGGER.info(
                        "[firecontrolcompat] client pod copy at {} view={} aim={}",
                        controller.getBlockPos(),
                        controller.isPodViewEnabled(),
                        look == null ? "none" : String.format(java.util.Locale.ROOT, "yaw=%.1f pitch=%.1f",
                                Math.toDegrees(Math.atan2(-look.x, look.z)),
                                Math.toDegrees(Math.atan2(-look.y, Math.hypot(look.x, look.z)))));
            }
            if (seen == 0) {
                com.qkdream.firecontrolcompat.FireControlCompat.LOGGER.info(
                        "[firecontrolcompat] client pod copy: no controller known to this client within reach");
            }
        } catch (RuntimeException | LinkageError ignored) {
        }
    }

    /** The camera is held on the same angles the tick wrote, so nothing drifts. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onComputeCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (!mayFollowLock()) {
            return;
        }
        float[] angles = lockAngles((float) event.getPartialTick());
        if (angles == null) {
            return;
        }
        event.setYaw(angles[0]);
        event.setPitch(angles[1]);
        event.setRoll(0.0F);
    }

    /** Writes a facing onto the player, body and head together. */
    private static void apply(LocalPlayer player, float yaw, float pitch) {
        player.setYRot(yaw);
        player.setXRot(pitch);
        player.setYHeadRot(yaw);
        player.yBodyRot = yaw;
        player.yRotO = yaw;
        player.xRotO = pitch;
        player.yHeadRotO = yaw;
        player.yBodyRotO = yaw;
    }
}

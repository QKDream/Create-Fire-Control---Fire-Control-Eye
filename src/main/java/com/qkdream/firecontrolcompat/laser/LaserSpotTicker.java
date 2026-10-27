package com.qkdream.firecontrolcompat.laser;

import com.qkdream.firecontrolcompat.FireControlCompat;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Runs the laser spot collection once a server tick.
 *
 * <p>The spots live in {@link LaserSpots} and are read by every laser-guided
 * round as it flies; this is the only thing that keeps them current.</p>
 */
@EventBusSubscriber(modid = FireControlCompat.MOD_ID)
public final class LaserSpotTicker {

    private LaserSpotTicker() {
    }

    /**
     * Prints what every loaded computer's pod is pointed at, whether or not anyone
     * is sitting at it.
     *
     * <p>Every probe before this one needed a gunner in the seat to say anything, and
     * the readings it gave were the observer's own camera next to the pod's aim -
     * two different things, which is why they never agreed. This asks the pod
     * itself, once every five seconds, so a crew can simply stand and look at the
     * model without sitting anywhere.</p>
     */
    private static void podHeartbeat(net.minecraft.server.MinecraftServer server) {
        try {
            long now = server.overworld().getGameTime();
            if (now % 100L != 0L) {
                return;
            }
            for (com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity controller
                    : com.hooya.stabilizedturret.content.controller.ControllerTracker.loadedControllers()) {
                if (controller == null || controller.isRemoved()) {
                    continue;
                }
                net.minecraft.world.phys.Vec3 look = controller.getPodDesiredWorldDirection();
                net.minecraft.world.phys.Vec3 measured = controller.getMeasuredAimDirection();
                boolean podView = controller.isPodViewEnabled();
                // Two directions are printed because the mod uses two: the pod's own
                // aim drives the sight and the rounds, while the mount's measured
                // direction is what the model is built from. A crew that sees the
                // model pointing one way and the sight another is seeing these two
                // disagree.
                FireControlCompat.LOGGER.info(
                        "[firecontrolcompat] pod heartbeat at {} view={} aim={} measured={}",
                        controller.getBlockPos(),
                        podView,
                        describe(look),
                        describe(measured));
            }
        } catch (RuntimeException | LinkageError ignored) {
        }
    }

    /** A direction as yaw and pitch in degrees, or "none". */
    private static String describe(net.minecraft.world.phys.Vec3 direction) {
        if (direction == null) {
            return "none";
        }
        return String.format(java.util.Locale.ROOT, "yaw=%.1f pitch=%.1f",
                Math.toDegrees(Math.atan2(-direction.x, direction.z)),
                Math.toDegrees(Math.atan2(-direction.y, Math.hypot(direction.x, direction.z))));
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server == null) {
            return;
        }
        ServerLevel overworld = server.overworld();
        LaserSpots.serverTick(overworld == null ? 0L : overworld.getGameTime());
        LaserRoundTickets.tick();
        // The spot entities live one tick each, so one is dropped every tick.
        PodLaserPointer.pulse();
        podHeartbeat(server);
    }
}

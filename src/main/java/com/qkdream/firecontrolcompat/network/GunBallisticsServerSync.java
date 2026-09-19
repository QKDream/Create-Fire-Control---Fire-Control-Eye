package com.qkdream.firecontrolcompat.network;

import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.content.controller.BindingSlot;
import com.hooya.stabilizedturret.content.controller.CannonBallisticsCompat;
import com.hooya.stabilizedturret.content.controller.CannonMountCompat;
import com.hooya.stabilizedturret.content.controller.ControllerTracker;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.qkdream.firecontrolcompat.FireControlCompat;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Streams the bound cannon's ballistic profile to the player who is operating
 * the fire control computer, so the HUD lead indicator can compensate the
 * shell drop. The profile is read with the fire control mod's own cannon
 * analysis ({@code CannonBallisticsCompat.analyze}), which already accounts
 * for the loaded projectile, the propellant charge, the barrel length and the
 * platform's own motion.
 *
 * <p>Only the first bound assembled cannon mount is used, matching the mod's
 * own aiming order. When nothing usable is bound an invalid payload is sent so
 * the client clears its copy and falls back to the straight-line indicator.</p>
 */
@EventBusSubscriber(modid = FireControlCompat.MOD_ID)
public final class GunBallisticsServerSync {

    /** Refresh period in ticks; the client treats a profile older than 4s as stale. */
    private static final int SYNC_INTERVAL = 10;

    /** Last payload sent to each player, used to keep the log quiet. */
    private static final Map<UUID, GunBallisticsPayload> LAST_SENT = new HashMap<>();

    private GunBallisticsServerSync() {
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
                || (player.tickCount % SYNC_INTERVAL) != 0
                || !ModList.get().isLoaded("create_fire_control")) {
            return;
        }
        GunBallisticsPayload payload = firecontrolcompat$profile(player);
        GunBallisticsPayload previous = LAST_SENT.put(player.getUUID(), payload);
        if (!firecontrolcompat$same(previous, payload)) {
            FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] gun ballistics player={} valid={} speed={} gravity={} drag={} quadratic={} maxTicks={}",
                    player.getName().getString(),
                    payload.valid(),
                    payload.muzzleSpeed(),
                    payload.gravity(),
                    payload.drag(),
                    payload.quadraticDrag(),
                    payload.maxFlightTicks());
        }
        PacketDistributor.sendToPlayer(player, payload);
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        LAST_SENT.remove(event.getEntity().getUUID());
    }

    private static GunBallisticsPayload firecontrolcompat$profile(ServerPlayer player) {
        try {
            StabilizerControllerBlockEntity controller = ControllerTracker.findForPlayerAim(player);
            if (controller == null) {
                controller = ControllerTracker.findForVehicle(player.level(), player.getVehicle());
            }
            if (controller == null || controller.isRemoved() || controller.getLevel() == null) {
                return firecontrolcompat$none();
            }
            Level level = controller.getLevel();
            BindingRef mount = controller.getBinding(BindingSlot.CANNON_MOUNT);
            if (mount == null || !CannonMountCompat.hasAssembledCannon(level, mount)) {
                return firecontrolcompat$none();
            }
            CannonBallisticsCompat.ShotProfile profile = CannonBallisticsCompat.analyze(level, mount);
            if (profile == null) {
                return firecontrolcompat$none();
            }
            double speed = profile.muzzleSpeed();
            double gravity = profile.gravity();
            double drag = profile.drag();
            if (!Double.isFinite(speed) || speed <= 1.0E-4D
                    || !Double.isFinite(gravity) || !Double.isFinite(drag) || drag < 0.0D) {
                return firecontrolcompat$none();
            }
            Vec3 carrier = profile.carrierVelocity();
            return new GunBallisticsPayload(
                    true,
                    speed,
                    gravity,
                    drag,
                    profile.quadraticDrag(),
                    Math.max(1, Math.min(profile.maxFlightTicks(), 600)),
                    carrier == null ? Vec3.ZERO : carrier);
        } catch (Throwable failure) {
            FireControlCompat.LOGGER.debug(
                    "[firecontrolcompat] gun ballistics unavailable: {}", failure.toString());
            return firecontrolcompat$none();
        }
    }

    private static GunBallisticsPayload firecontrolcompat$none() {
        return new GunBallisticsPayload(false, 0.0D, 0.0D, 0.0D, false, 0, Vec3.ZERO);
    }

    private static boolean firecontrolcompat$same(GunBallisticsPayload first, GunBallisticsPayload second) {
        if (first == null) {
            return false;
        }
        return first.valid() == second.valid()
                && Double.compare(first.muzzleSpeed(), second.muzzleSpeed()) == 0
                && Double.compare(first.gravity(), second.gravity()) == 0
                && Double.compare(first.drag(), second.drag()) == 0
                && first.quadraticDrag() == second.quadraticDrag()
                && first.maxFlightTicks() == second.maxFlightTicks();
    }
}
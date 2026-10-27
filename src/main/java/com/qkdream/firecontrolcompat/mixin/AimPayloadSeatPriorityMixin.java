package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.network.AimDirectionPayload;
import com.qkdream.firecontrolcompat.FireControlCompat;
import com.qkdream.firecontrolcompat.SeatPriority;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drops the aim packet of a relief post.
 *
 * <p>This is the one uplink every way of steering goes through - free look,
 * head aim, the pod camera and the optical search all arrive as an aim
 * direction - and the handler picks its controller with the pack's seat check,
 * which answers for every bound seat alike. Refusing the packet while the posts
 * in front are manned is therefore what actually keeps a relief post from
 * taking the mount.</p>
 */
@Mixin(AimDirectionPayload.class)
public abstract class AimPayloadSeatPriorityMixin {

    /** Last time each player was told his aim was dropped, so the log stays quiet. */
    private static final Map<UUID, Long> LAST_DROP_LOG = new HashMap<>();

    @Inject(method = "handle", at = @At("HEAD"), cancellable = true)
    private static void firecontrolcompat$reliefPostCannotAim(
            AimDirectionPayload payload, IPayloadContext context, CallbackInfo ci) {
        if (context.player() instanceof ServerPlayer player
                && com.qkdream.firecontrolcompat.PodLinkSwitch.enabled()
                && !SeatPriority.maySteer(player)) {
            // A dropped aim packet is the one thing this mod does that can make
            // the mount stop following its gunner, so it is logged: if a pod's
            // animation ever disagrees with the view, this line says whether the
            // aim ever reached the server at all.
            long now = player.level().getGameTime();
            Long last = LAST_DROP_LOG.get(player.getUUID());
            if (last == null || now - last >= 20L) {
                LAST_DROP_LOG.put(player.getUUID(), now);
                FireControlCompat.LOGGER.info(
                        "[firecontrolcompat] aim packet dropped player={} vehicle={} pos={}",
                        player.getName().getString(),
                        player.getVehicle() == null ? "none" : player.getVehicle().getType(),
                        player.blockPosition());
            }
            ci.cancel();
        }
    }
}
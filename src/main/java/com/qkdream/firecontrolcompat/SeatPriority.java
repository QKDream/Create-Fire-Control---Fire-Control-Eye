package com.qkdream.firecontrolcompat;

import com.hooya.stabilizedturret.content.controller.ControllerTracker;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * Who is allowed to steer a fire control post.
 *
 * <p>The pack answers "may this player move the mount" with its own seat check,
 * and that check knows one seat only. The chain of command this mod adds makes
 * every bound seat a seat of the same post, so without a rule here a player on a
 * relief post drives the mount the moment he sits down: the aim packets of every
 * seat reach the computer and the pack applies whichever arrived last, which is
 * what a crew sees as the gun being taken away from them.</p>
 *
 * <p>A post therefore only steers while the posts in front of it are empty, the
 * same rule the gunner lookup and the missile view already follow.</p>
 */
public final class SeatPriority {

    private SeatPriority() {
    }

    /** Whether that player may move the mount; true for anyone not on a relief post. */
    public static boolean maySteer(ServerPlayer player) {
        if (player == null || player.level() == null) {
            return true;
        }
        Entity vehicle = player.getVehicle();
        if (vehicle == null) {
            return true;
        }
        try {
            StabilizerControllerBlockEntity controller = ControllerTracker.findForVehicle(player.level(), vehicle);
            if (!(controller instanceof ControllerSeatOrder order)) {
                return true;
            }
            int index = order.firecontrolcompat$seatIndex(player);
            if (index <= 0) {
                return true;
            }
            for (int ahead = 0; ahead < index; ahead++) {
                if (order.firecontrolcompat$seatManned(ahead)) {
                    return false;
                }
            }
            return true;
        } catch (Throwable ignored) {
            return true;
        }
    }
}
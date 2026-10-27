package com.qkdream.firecontrolcompat;

import com.hooya.stabilizedturret.content.controller.BindingRef;
import java.util.List;
import net.minecraft.world.entity.Entity;

/**
 * The operator seats of one fire control computer, in binding order.
 *
 * <p>The pack binds one seat to a computer, and the crew that sits on it is
 * the gunner. This view widens that: every seat bound after the first one is a
 * relief post, and the order they were bound in is the order of command - the
 * first seat that is actually manned controls the mount, and the posts behind
 * it take over the moment its crew leaves or dies.</p>
 *
 * <p>Implemented by the mixin on the controller, so a cast from the block
 * entity is the only coupling.</p>
 */
public interface ControllerSeatOrder {

    /** Bound seats, highest priority first; never null. */
    List<BindingRef> firecontrolcompat$orderedSeats();

    /** Index of the bound seat the given rider sits on, -1 when it is not one. */
    int firecontrolcompat$seatIndex(Entity rider);

    /** Everyone sitting on the seat at that index, players and crew alike. */
    List<Entity> firecontrolcompat$seatOccupants(int index);

    /** Whether anyone at all sits on the seat at that index. */
    boolean firecontrolcompat$seatManned(int index);
}
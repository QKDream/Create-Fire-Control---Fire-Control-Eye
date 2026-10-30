package com.qkdream.firecontrolcompat.entity;

import net.minecraft.world.entity.Entity;

/**
 * The one piece of fire control's long-range proxy state that both sides read as plain data.
 *
 * <p>Fire control does not keep a missile's far-away picture on the server: it broadcasts a
 * payload and the client marks the stand-in entity it spawns for it with
 * {@link #TAG_REMOTE_VISUAL} in its persistent data. Anything that has to tell "the round" from
 * "the picture of the round" therefore only has to read that flag - no rendering, no client
 * classes.</p>
 *
 * <p>This lived in {@code client/MissileVisualSupport} until 1.70.6, which made the cook-off
 * logic (a server side explosion listener) depend on a client class: on a dedicated server the
 * listener could not even be linked. The predicate is pure data and belongs here; the client
 * class now delegates so every renderer keeps the same answer.</p>
 */
public final class RemoteVisualState {

    /** Persistent-data key fire control sets on the client-side stand-in entity. */
    public static final String TAG_REMOTE_VISUAL = "CreateFireControlRemoteVisual";

    private RemoteVisualState() {
    }

    /**
     * Whether this entity is fire control's long-range proxy rather than a server-ticked round.
     *
     * <p>Same answer on both sides: off the server the flag is simply absent, which reads as
     * {@code false}. Never throws for a null entity.</p>
     */
    public static boolean isRemoteVisual(Entity entity) {
        return entity != null && entity.getPersistentData().getBoolean(TAG_REMOTE_VISUAL);
    }
}

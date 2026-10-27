package com.qkdream.firecontrolcompat;

/**
 * Whether this mod's sonar group really made it into the fire control screen's input list.
 *
 * <p>Kept in an ordinary class rather than a mixin field: a mixin may not carry a non-private
 * static member, and putting the flag there is what stopped the game from booting.</p>
 */
public final class SonarGroupState {

    /** Set once when the group is added; read by the fallback row block. */
    public static volatile boolean added;

    private SonarGroupState() {
    }
}

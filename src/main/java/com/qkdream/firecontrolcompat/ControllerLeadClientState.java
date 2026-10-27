package com.qkdream.firecontrolcompat;

import java.util.UUID;

import net.minecraft.core.BlockPos;

/**
 * Client side mirror of the lead settings of the fire control computer whose
 * settings page is open.
 *
 * <p>The computer answers a moment after the page is built - the server reads
 * the block entity, so it cannot be asked while the screen is being laid out -
 * so the page reads the pair from here each frame instead of from a field it
 * filled at construction time.</p>
 *
 * <p>The position is part of the answer: a page whose computer is not the one
 * that answered must not take those numbers for its own, which is what happens
 * with two computers and two pages opened one after the other.</p>
 */
public final class ControllerLeadClientState {

    /** The computer the last answer came from, or null while nothing has arrived. */
    private static volatile BlockPos controllerPos;
    private static volatile UUID controllerSubLevel;
    private static volatile LeadCannonKind kind;
    private static volatile int charge = -1;

    private ControllerLeadClientState() {
    }

    /** Called on the client when a computer answers with its own pair. */
    public static void apply(BlockPos pos, UUID subLevel, LeadCannonKind cannonKind, int bigCharge) {
        controllerPos = pos;
        controllerSubLevel = subLevel;
        kind = cannonKind;
        charge = bigCharge;
        try {
            FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] lead-sync recv pos={} sub={} kind={} charge={}",
                    pos, subLevel, cannonKind, bigCharge);
        } catch (Throwable ignored) {
        }
    }

    /**
     * True when this answer belongs to the given computer.
     *
     * <p>The position is the identity that matters: two computers are never in the
     * same place. The structure is compared as well, but a null on either side is
     * accepted, because the answer carries the structure the server resolved while
     * the page carries the one it was opened with, and a structure re-assembled in
     * between changes its id without the computer having moved.</p>
     */
    public static boolean matches(BlockPos pos, UUID subLevel) {
        if (pos == null || controllerPos == null || !controllerPos.equals(pos)) {
            return false;
        }
        if (subLevel == null || controllerSubLevel == null) {
            return true;
        }
        return subLevel.equals(controllerSubLevel);
    }

    /** Family the computer answered with, or null when it has not answered yet. */
    public static LeadCannonKind kind() {
        return kind;
    }

    /** Powder charge the computer answered with, or -1 when it has not answered yet. */
    public static int charge() {
        return charge;
    }
}

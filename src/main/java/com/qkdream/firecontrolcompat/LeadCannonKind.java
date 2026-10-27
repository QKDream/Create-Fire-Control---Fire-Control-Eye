package com.qkdream.firecontrolcompat;

/**
 * Which cannon the lead computer solves for.
 *
 * <p>One at a time, picked in this mod's own settings: the three families have very different muzzle
 * speeds and drop, and solving all of them at once would only blur the mark.</p>
 *
 * <p>There is no machine-gun entry. Fire control 0.9.3 computes a dynamic lead circle for the
 * autocannon from the actual muzzle, the ammunition's ballistics and both sides' motion, which is
 * strictly better than this mod's fixed machine-gun preset - and the two of them together would put
 * two different aim points on one screen. The autocannon is therefore left entirely to the pack, and
 * this setting only covers the three cannon families it does not solve for.</p>
 */
public enum LeadCannonKind {
    /** Military Supplement single cannons. Also what an old machine-gun setting falls back to. */
    SMALL_CANNON,
    /** Medium cannons. */
    MEDIUM_CANNON,
    /** Create Big Cannons big cannons, whose speed follows the charge setting. */
    BIG_CANNON;

    /**
     * Reads a stored name, mapping the retired machine-gun value onto the smallest cannon.
     *
     * <p>A world saved before this mod dropped the machine-gun entry still carries its name; it must
     * open as a usable setting rather than as a failed read, and the smallest cannon is the closest
     * thing to it.</p>
     */
    public static LeadCannonKind fromName(String name) {
        if (name == null || name.isEmpty()) {
            return SMALL_CANNON;
        }
        if ("MACHINE_GUN".equals(name)) {
            return SMALL_CANNON;
        }
        try {
            return LeadCannonKind.valueOf(name);
        } catch (IllegalArgumentException unknown) {
            return SMALL_CANNON;
        }
    }
}

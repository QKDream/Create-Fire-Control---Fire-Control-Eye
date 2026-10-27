package com.qkdream.firecontrolcompat.compat.yuushya;

/**
 * The cannon types a modelling block can be turned into.
 *
 * <p>This is the shared list of both mods: the modelling block of this mod adds
 * the nethersteel big cannon barrel of Create Big Cannons, the fire control mod
 * adds its nethersteel medium cannon barrel. The order of the constants is the
 * order the button in the block screen cycles through.</p>
 */
public enum YuushyaCannonType {

    /** A plain modelling block, no cannon at all. */
    NONE,

    /** The nethersteel medium cannon barrel of the fire control mod. */
    MEDIUM,

    /** The nethersteel big cannon barrel of Create Big Cannons. */
    BIG,

    // The single-barrel cannon families of CBC Military Supplement. Every one of them gets a
    // modelling type, so a modelled barrel can stand in for any single cannon the pack offers
    // rather than only the two the mod started with. The blockstate each one is built from is the
    // pack's own barrel for that family, which is what makes the stand-in a real cannon part.
    SINGLE_SLATE_ALLOY,
    SINGLE_BRASS,
    SINGLE_STEEL,
    LARGE_SINGLE_SLATE_ALLOY,
    LARGE_SINGLE_BRASS,
    LARGE_SINGLE_STEEL,
    MILITARY_SLATE_ALLOY,
    LARGE_MILITARY_SLATE_ALLOY;

    /** The type of the given wire value, never null. */
    public static YuushyaCannonType byOrdinal(int ordinal) {
        YuushyaCannonType[] types = values();
        return ordinal >= 0 && ordinal < types.length ? types[ordinal] : NONE;
    }
}
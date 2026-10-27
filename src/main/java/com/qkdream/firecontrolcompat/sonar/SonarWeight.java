package com.qkdream.firecontrolcompat.sonar;

/**
 * How loud a noise is: a footfall in the water barely carries, a gun going off
 * carries a long way. The display paints a light source pale, a medium one in
 * the middle of the ramp and a heavy one deep, so the picture reads at a
 * glance without any numbers on it.
 */
public enum SonarWeight {

    LIGHT(0xFFB4E4FF),
    MEDIUM(0xFF3E9BFF),
    HEAVY(0xFF0B2A96);

    private final int color;

    SonarWeight(int color) {
        this.color = color;
    }

    /** ARGB mark colour of a source of this weight. */
    public int color() {
        return this.color;
    }

    /** Translation key of the tag printed in the legend. */
    public String translationKey() {
        return "gui.firecontrolcompat.sonar_weight." + this.name().toLowerCase(java.util.Locale.ROOT);
    }

    /** The weight behind an ordinal that came in over the wire. */
    public static SonarWeight byOrdinal(int ordinal) {
        SonarWeight[] values = values();
        return ordinal < 0 || ordinal >= values.length ? LIGHT : values[ordinal];
    }
}

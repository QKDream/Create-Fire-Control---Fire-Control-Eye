package com.qkdream.firecontrolcompat.sonar;

/**
 * What a sonar heard, and therefore how loud it is.
 *
 * <p>Living things and world noises are light, a hull or a torpedo under way is
 * medium, and a gun going off - or another set pinging - is heavy.</p>
 */
public enum SonarKind {

    /** A living thing on the move: footfalls and breathing. */
    MOB(SonarWeight.LIGHT),
    /** Ordnance: the motor of a munition under way, or its echo in active mode. */
    AMMO(SonarWeight.LIGHT),
    /** The world itself: a block going down, a door, a bell, a lever. */
    WORLD(SonarWeight.LIGHT),
    /** A hull, a vehicle or a structure being pushed through the water. */
    BODY(SonarWeight.MEDIUM),
    /** Machinery at work: a burner, a gearbox, running gear. */
    MACHINE(SonarWeight.MEDIUM),
    /** A torpedo swimming. */
    TORPEDO(SonarWeight.MEDIUM),
    /** A guided munition in the air. */
    MISSILE(SonarWeight.MEDIUM),
    /** A gun going off. */
    GUNFIRE(SonarWeight.HEAVY),
    /** Another set's ping. An active sonar is heard by everyone. */
    PING(SonarWeight.HEAVY);

    private final SonarWeight weight;

    SonarKind(SonarWeight weight) {
        this.weight = weight;
    }

    public SonarWeight weight() {
        return this.weight;
    }

    /** The kind behind an ordinal that came in over the wire. */
    public static SonarKind byOrdinal(int ordinal) {
        SonarKind[] values = values();
        return ordinal < 0 || ordinal >= values.length ? BODY : values[ordinal];
    }
}

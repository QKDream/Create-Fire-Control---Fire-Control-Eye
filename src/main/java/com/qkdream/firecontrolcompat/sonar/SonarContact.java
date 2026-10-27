package com.qkdream.firecontrolcompat.sonar;

import net.minecraft.world.phys.Vec3;

/**
 * One mark on the sonar picture: what was heard, where it was heard, and how
 * strong the echo off it was.
 *
 * <p>The strength is only ever filled in for a hull: a ping coming back off a
 * ship says as much about the size of her as it does about where she is, and a
 * set draws the mark the bigger for it.</p>
 */
public record SonarContact(SonarKind kind, Vec3 position, int echo) {

    /** A source whose size says nothing: a creature, a gun, a warhead. */
    public SonarContact(SonarKind kind, Vec3 position) {
        this(kind, position, 0);
    }
}

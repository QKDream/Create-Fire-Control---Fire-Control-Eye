package com.qkdream.firecontrolcompat;

/**
 * Interface the optical lock mixin adds to the fire control controller, so
 * the sync code can ask a block entity whether it currently holds a
 * main-world block lock without touching its private fields.
 */
public interface OpticalBlockLockAccess {

    boolean firecontrolcompat$hasBlockLock();
}

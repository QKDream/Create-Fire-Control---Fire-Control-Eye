package net.createmod.ponder.api;

/**
 * Compile-only stub for Create's ponder API interface referenced by
 * SmartBlockEntity. This pack's create jar references the interface without
 * shipping it (the JVM resolves it lazily), but javac needs it to complete
 * the SmartBlockEntity hierarchy when TAOV/synaxis classes are compiled
 * against. The stub is compiled into a scratch directory that is never
 * packaged into the mod jar.
 */
public interface VirtualBlockEntity {
}

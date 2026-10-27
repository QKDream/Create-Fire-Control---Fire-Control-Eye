package com.qkdream.firecontrolcompat;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The bodies welded to one platform.
 *
 * <p>The structure toolgun keeps every weld it makes in its own registry - the
 * ones made by hand, the ones a blueprint asks for while it is being placed,
 * and the ones restored from a save. Sable's connected chain does not carry
 * them, so a plate that was welded onto a hull still looked like a stranger:
 * a missile could treat the very structure it was fired from as a target, and
 * the markings of the hull did not reach the welded part. Reading the toolgun's
 * registry here closes that gap.</p>
 *
 * <p>The toolgun is a soft dependency and the registry is reached through
 * reflection: its jar is not something this build can read at compile time, and
 * that way a pack without the toolgun still runs this mod untouched. The
 * lookups are done once; a missing mod or a renamed class simply leaves the
 * group as it was before.</p>
 */
public final class ToolgunWelds {

    /** Mod id of the toolgun mod. */
    public static final String MOD_ID = "create_aeronautics_toolgun";

    private static final String REPOSITORY =
            "com.envx.aeronauticsstructuretool.toolgun.constraint.ConstraintRuntimeRepository";
    private static final String CONSTRAINT =
            "com.envx.aeronauticsstructuretool.toolgun.constraint.TrackedConstraint";

    private static boolean resolved;
    private static Method forSubLevel;
    private static Method firstSubLevelId;
    private static Method secondSubLevelId;

    private ToolgunWelds() {
    }

    /** Adds every body welded to the given one, in any constraint mode. */
    public static void addWeldPartners(Collection<UUID> out, UUID subLevelId) {
        if (out == null || subLevelId == null || !resolve()) {
            return;
        }
        try {
            Object found = forSubLevel.invoke(null, subLevelId);
            if (!(found instanceof List<?> constraints)) {
                return;
            }
            for (Object constraint : constraints) {
                if (constraint == null) {
                    continue;
                }
                UUID first = (UUID) firstSubLevelId.invoke(constraint);
                UUID second = (UUID) secondSubLevelId.invoke(constraint);
                if (first == null || second == null) {
                    continue;
                }
                if (subLevelId.equals(first)) {
                    out.add(second);
                } else if (subLevelId.equals(second)) {
                    out.add(first);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** Looks the registry up once; false means welds stay invisible. */
    private static boolean resolve() {
        if (resolved) {
            return forSubLevel != null;
        }
        resolved = true;
        if (!FireControlCompat.isModLoaded(MOD_ID)) {
            return false;
        }
        try {
            Class<?> repository = Class.forName(REPOSITORY);
            Class<?> constraint = Class.forName(CONSTRAINT);
            Method lookup = repository.getMethod("forSubLevel", UUID.class);
            Method first = constraint.getMethod("firstSubLevelId");
            Method second = constraint.getMethod("secondSubLevelId");
            forSubLevel = lookup;
            firstSubLevelId = first;
            secondSubLevelId = second;
        } catch (Throwable ignored) {
            forSubLevel = null;
        }
        return forSubLevel != null;
    }
}

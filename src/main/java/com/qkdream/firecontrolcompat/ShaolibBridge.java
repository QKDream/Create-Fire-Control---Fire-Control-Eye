package com.qkdream.firecontrolcompat;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/**
 * Reflection bridge to Shaolib (taov_weapons). Tau and Hellfire are not
 * Minecraft entities: they live in Shaolib's global projectile registry as
 * {@code ProjectileInstance}s. Everything here is reflective so this mod
 * compiles and runs even without taov_weapons / shaolib installed.
 *
 * <p>Each in-flight Tau/Hellfire is exposed to fire-control as a synthetic
 * UUID built from {@code new UUID(SHAOLIB_MARKER, instanceId)} so it can be
 * stored in the existing radar contact / lock pipelines unchanged.</p>
 */
public final class ShaolibBridge {

    /** ASCII bytes of "SHAOLIB" packed into the most significant bits of synthetic UUIDs. */
    public static final long SHAOLIB_MARKER = 0x5348414F4C49424CL;

    private static final String PROJECTILES = "com.verr1.shaolib.api.projectile.ShaolibProjectiles";
    private static final String INSTANCE = "com.verr1.shaolib.api.projectile.ProjectileInstance";
    private static final String TYPE = "com.verr1.shaolib.api.projectile.ProjectileType";
    private static final String HANDLE = "com.verr1.shaolib.api.projectile.ProjectileHandle";
    private static final String GUIDED_STATE = "com.verr1.shaolib.munitions.projectile.guided.GuidedMissileState";

    private static volatile boolean resolved;
    private static Class<?> guidedStateClass;
    private static Method activeProjectiles;
    private static Method instanceIsAlive;
    private static Method instanceIsDiscarded;
    private static Method instanceDimensionId;
    private static Method instanceType;
    private static Method instancePosition;
    private static Method instancePreviousPosition;
    private static Method instanceId;
    private static Method typeId;
    private static Method getHandle;
    private static Method handleState;
    private static Method handleDiscard;
    private static Method guidedRequestDetonate;

    private ShaolibBridge() {
    }

    public static synchronized void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        try {
            ClassLoader loader = loader();
            Class<?> projectsClass = Class.forName(PROJECTILES, false, loader);
            Class<?> instanceClass = Class.forName(INSTANCE, false, loader);
            Class<?> typeClass = Class.forName(TYPE, false, loader);
            Class<?> handleClass = Class.forName(HANDLE, false, loader);
            try {
                guidedStateClass = Class.forName(GUIDED_STATE, false, loader);
            } catch (Throwable ignored) {
                guidedStateClass = null;
            }

            activeProjectiles = projectsClass.getMethod("activeProjectiles");
            instanceIsAlive = instanceClass.getMethod("isAlive");
            instanceIsDiscarded = instanceClass.getMethod("isDiscarded");
            instanceDimensionId = instanceClass.getMethod("dimensionId");
            instanceType = instanceClass.getMethod("type");
            instancePosition = instanceClass.getMethod("position");
            instancePreviousPosition = instanceClass.getMethod("previousPosition");
            instanceId = instanceClass.getMethod("id");
            typeId = typeClass.getMethod("id");
            getHandle = projectsClass.getMethod("getHandle", long.class);
            handleState = handleClass.getMethod("state");
            handleDiscard = handleClass.getMethod("discard", String.class);
            if (guidedStateClass != null) {
                guidedRequestDetonate = guidedStateClass.getMethod("requestDetonateIfArmed");
            }
            FireControlCompat.LOGGER.info("[firecontrolcompat] Shaolib bridge resolved (taov tau/hellfire tracking enabled)");
        } catch (Throwable t) {
            activeProjectiles = null;
            FireControlCompat.LOGGER.info("[firecontrolcompat] Shaolib bridge unavailable, TAOV tracking disabled: {}", t.toString());
        }
    }

    private static ClassLoader loader() {
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        ClassLoader own = ShaolibBridge.class.getClassLoader();
        ClassLoader system = ClassLoader.getSystemClassLoader();
        for (ClassLoader candidate : new ClassLoader[]{context, own, system}) {
            if (candidate != null) {
                return candidate;
            }
        }
        return ClassLoader.getSystemClassLoader();
    }

    public static boolean available() {
        return activeProjectiles != null;
    }

    @SuppressWarnings("unchecked")
    public static Collection<Object> activeInstances() {
        if (activeProjectiles == null) {
            return java.util.List.of();
        }
        try {
            Object result = activeProjectiles.invoke(null);
            return result instanceof Collection<?> collection ? (Collection<Object>) collection : java.util.List.of();
        } catch (Throwable t) {
            return java.util.List.of();
        }
    }

    public static boolean isTauOrHellfire(Object instance) {
        try {
            if (instanceType == null || typeId == null) {
                return false;
            }
            Object type = instanceType.invoke(instance);
            if (type == null) {
                return false;
            }
            Object id = typeId.invoke(type);
            if (!(id instanceof ResourceLocation location)) {
                return false;
            }
            if (!"taov_weapons".equals(location.getNamespace())) {
                return false;
            }
            String path = location.getPath();
            return "tau_missile".equals(path) || "hellfire_missile".equals(path) || "hell_fire_missile".equals(path);
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isAlive(Object instance) {
        try {
            if (instanceIsAlive == null) {
                return false;
            }
            Object result = instanceIsAlive.invoke(instance);
            if (!(result instanceof Boolean alive) || !alive) {
                return false;
            }
            if (instanceIsDiscarded != null) {
                Object discarded = instanceIsDiscarded.invoke(instance);
                if (discarded instanceof Boolean isDiscarded && isDiscarded) {
                    return false;
                }
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static String dimensionId(Object instance) {
        try {
            if (instanceDimensionId == null) {
                return null;
            }
            Object result = instanceDimensionId.invoke(instance);
            return result == null ? null : result.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    public static long idOf(Object instance) {
        try {
            if (instanceId == null) {
                return -1L;
            }
            Object result = instanceId.invoke(instance);
            return result instanceof Number number ? number.longValue() : -1L;
        } catch (Throwable t) {
            return -1L;
        }
    }

    public static Vec3 position(Object instance) {
        try {
            if (instancePosition == null) {
                return null;
            }
            Object result = instancePosition.invoke(instance);
            return result instanceof Vec3 position ? position : null;
        } catch (Throwable t) {
            return null;
        }
    }

    public static Vec3 velocity(Object instance) {
        Vec3 current = position(instance);
        Vec3 previous = null;
        try {
            if (instancePreviousPosition != null) {
                Object result = instancePreviousPosition.invoke(instance);
                if (result instanceof Vec3 position) {
                    previous = position;
                }
            }
        } catch (Throwable ignored) {
        }
        if (current == null) {
            return Vec3.ZERO;
        }
        return previous == null ? Vec3.ZERO : current.subtract(previous);
    }

    public static UUID uuidFor(Object instance) {
        return uuidForId(idOf(instance));
    }

    public static UUID uuidForId(long id) {
        return new UUID(SHAOLIB_MARKER, id);
    }

    public static boolean isShaolibId(UUID id) {
        return id != null && id.getMostSignificantBits() == SHAOLIB_MARKER;
    }

    public static long idFromUuid(UUID id) {
        return id == null ? -1L : id.getLeastSignificantBits();
    }

    /** Finds the live Tau/Hellfire instance carrying the given synthetic UUID. */
    public static Object findInstance(UUID id) {
        if (!isShaolibId(id)) {
            return null;
        }
        long wanted = idFromUuid(id);
        for (Object instance : activeInstances()) {
            if (instance == null) {
                continue;
            }
            if (idOf(instance) == wanted && isTauOrHellfire(instance)) {
                return instance;
            }
        }
        return null;
    }

    public static boolean isAlive(UUID id) {
        Object instance = findInstance(id);
        return instance != null && isAlive(instance);
    }

    public static Vec3 position(UUID id) {
        Object instance = findInstance(id);
        return instance == null ? null : position(instance);
    }

    /**
     * Kills the Tau/Hellfire identified by a synthetic UUID. Requests an armed
     * detonation when possible and discards the handle, mirroring how
     * cbcmsmwcompat cooks off the same munitions.
     */
    public static void detonate(UUID id) {
        if (!isShaolibId(id) || getHandle == null) {
            return;
        }
        try {
            Object handleOptional = getHandle.invoke(null, idFromUuid(id));
            if (!(handleOptional instanceof Optional<?> optional) || optional.isEmpty()) {
                return;
            }
            Object handle = optional.get();
            if (handleState != null && guidedStateClass != null && guidedRequestDetonate != null) {
                try {
                    Object state = handleState.invoke(handle);
                    if (guidedStateClass.isInstance(state)) {
                        guidedRequestDetonate.invoke(state);
                    }
                } catch (Throwable ignored) {
                }
            }
            if (handleDiscard != null) {
                handleDiscard.invoke(handle, "firecontrolcompat:intercepted");
            }
        } catch (Throwable t) {
            FireControlCompat.LOGGER.debug("[firecontrolcompat] Shaolib detonate failed for {}", id, t);
        }
    }
}

package com.qkdream.firecontrolcompat;

import com.verr1.shaolib.api.projectile.ProjectileHandle;
import com.verr1.shaolib.api.projectile.ProjectileInstance;
import com.verr1.shaolib.api.projectile.ShaolibProjectiles;
import com.verr1.shaolib.munitions.projectile.guided.GuidedMissileState;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;

/**
 * Bridge to Shaolib (taov_weapons) through its public projectile API. Tau and
 * Hellfire are not Minecraft entities: they live in Shaolib's global
 * projectile registry as {@code ProjectileInstance}s. Presence is detected
 * with {@link ModList} so this mod still loads without taov_weapons / shaolib
 * installed, and every accessor falls back to a safe default when the API is
 * unavailable.
 *
 * <p>Each in-flight Tau/Hellfire is exposed to fire-control as a synthetic
 * UUID built from {@code new UUID(SHAOLIB_MARKER, instanceId)} so it can be
 * stored in the existing radar contact / lock pipelines unchanged.</p>
 *
 * <p>Every Shaolib typed access lives in the nested {@link Access} holder,
 * which is only loaded once the library is known to be installed. This class
 * itself therefore links and verifies fine when shaolib is missing.</p>
 */
public final class ShaolibBridge {

    /** ASCII bytes of "SHAOLIB" packed into the most significant bits of synthetic UUIDs. */
    public static final long SHAOLIB_MARKER = 0x5348414F4C49424CL;

    private static volatile boolean resolved;
    private static volatile boolean available;

    private ShaolibBridge() {
    }

    public static synchronized void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        available = ModList.get().isLoaded("shaolib");
        if (available) {
            FireControlCompat.LOGGER.info("[firecontrolcompat] Shaolib detected (taov tau/hellfire tracking enabled)");
        } else {
            FireControlCompat.LOGGER.info("[firecontrolcompat] Shaolib not installed, TAOV tracking disabled");
        }
    }

    public static boolean available() {
        return available;
    }

    /** Live Shaolib projectiles, or an empty collection when shaolib is absent. */
    public static Collection<?> activeInstances() {
        return available ? Access.activeInstances() : List.of();
    }

    public static boolean isTauOrHellfire(Object instance) {
        return available && instance != null && Access.isTauOrHellfire(instance);
    }

    public static boolean isAlive(Object instance) {
        return available && instance != null && Access.isAlive(instance);
    }

    public static String dimensionId(Object instance) {
        return available && instance != null ? Access.dimensionId(instance) : null;
    }

    public static long idOf(Object instance) {
        return available && instance != null ? Access.idOf(instance) : -1L;
    }

    public static Vec3 position(Object instance) {
        return available && instance != null ? Access.position(instance) : null;
    }

    public static Vec3 velocity(Object instance) {
        return available && instance != null ? Access.velocity(instance) : Vec3.ZERO;
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
        return available && isShaolibId(id) ? Access.findInstance(idFromUuid(id)) : null;
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
        if (available && isShaolibId(id)) {
            Access.detonate(idFromUuid(id), id);
        }
    }

    /** Shaolib typed access, loaded only while shaolib is installed. */
    private static final class Access {

        private Access() {
        }

        static Collection<ProjectileInstance> activeInstances() {
            try {
                return ShaolibProjectiles.activeProjectiles();
            } catch (Throwable t) {
                return List.of();
            }
        }

        static boolean isTauOrHellfire(Object instance) {
            if (!(instance instanceof ProjectileInstance projectile)) {
                return false;
            }
            try {
                ResourceLocation location = projectile.type().id();
                if (!"taov_weapons".equals(location.getNamespace())) {
                    return false;
                }
                String path = location.getPath();
                return "tau_missile".equals(path) || "hellfire_missile".equals(path) || "hell_fire_missile".equals(path);
            } catch (Throwable t) {
                return false;
            }
        }

        static boolean isAlive(Object instance) {
            if (!(instance instanceof ProjectileInstance projectile)) {
                return false;
            }
            try {
                return projectile.isAlive() && !projectile.isDiscarded();
            } catch (Throwable t) {
                return false;
            }
        }

        static String dimensionId(Object instance) {
            if (!(instance instanceof ProjectileInstance projectile)) {
                return null;
            }
            try {
                return projectile.dimensionId();
            } catch (Throwable t) {
                return null;
            }
        }

        static long idOf(Object instance) {
            if (!(instance instanceof ProjectileInstance projectile)) {
                return -1L;
            }
            try {
                return projectile.id();
            } catch (Throwable t) {
                return -1L;
            }
        }

        static Vec3 position(Object instance) {
            if (!(instance instanceof ProjectileInstance projectile)) {
                return null;
            }
            try {
                return projectile.position();
            } catch (Throwable t) {
                return null;
            }
        }

        static Vec3 velocity(Object instance) {
            Vec3 current = position(instance);
            Vec3 previous = null;
            if (instance instanceof ProjectileInstance projectile) {
                try {
                    previous = projectile.previousPosition();
                } catch (Throwable ignored) {
                }
            }
            if (current == null) {
                return Vec3.ZERO;
            }
            return previous == null ? Vec3.ZERO : current.subtract(previous);
        }

        static Object findInstance(long wanted) {
            for (ProjectileInstance instance : activeInstances()) {
                if (instance != null && instance.id() == wanted && isTauOrHellfire(instance)) {
                    return instance;
                }
            }
            return null;
        }

        static void detonate(long id, UUID uuid) {
            try {
                Optional<ProjectileHandle<?>> handleOptional = ShaolibProjectiles.getHandle(id);
                if (handleOptional.isEmpty()) {
                    return;
                }
                ProjectileHandle<?> handle = handleOptional.get();
                try {
                    Object state = handle.state();
                    if (state instanceof GuidedMissileState guided) {
                        guided.requestDetonateIfArmed();
                    }
                } catch (Throwable ignored) {
                }
                handle.discard("firecontrolcompat:intercepted");
            } catch (Throwable t) {
                FireControlCompat.LOGGER.debug("[firecontrolcompat] Shaolib detonate failed for {}", uuid, t);
            }
        }
    }
}

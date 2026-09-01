package com.qkdream.firecontrolcompat;

import com.verr1.shaolib.api.projectile.ProjectileHandle;
import com.verr1.shaolib.api.projectile.ProjectileServerState;
import com.verr1.shaolib.api.projectile.ProjectileType;
import com.verr1.shaolib.api.projectile.ProjectileTypes;
import com.verr1.shaolib.api.projectile.ShaolibProjectiles;
import com.verr1.taov.weapons.air.TaovWeaponPayloadItems;
import com.verr1.taov.weapons.air.shaolib.TaovShaolibPayloadMapper;
import com.verr1.taov.weapons.air.shaolib.TaovShaolibPayloadMapper.MappedProjectile;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import rbasamoyai.createbigcannons.munitions.autocannon.AbstractAutocannonProjectile;
import rbasamoyai.createbigcannons.munitions.autocannon.AutocannonRoundItem;

/**
 * Bridges native CBC autocannon rounds (CBC Modern Warfare and CBC
 * Autocannon Revolution) into TAOV's gun payload pipeline.
 *
 * <p>TAOV's {@code TaovShaolibPayloadMapper.machineGun} only maps machine gun
 * bullets, CBC AP rounds and CBC flak rounds to generic Shaolib projectiles.
 * Everything else in the round item hierarchy fell through: CBC Modern
 * Warfare rounds could not even be inserted and the Autocannon Revolution
 * rounds that fired through the generic mappings lost their special effects.
 * Instead of re-implementing those behaviours, this mapper spawns the real
 * projectile entity that the round item itself creates.</p>
 *
 * <p>TAOV's launcher expects a Shaolib {@code ProjectileHandle} back from the
 * payload spawner to count the shot as successful, so a short-lived,
 * zero-velocity marker projectile is registered at the muzzle. It expires
 * through its own max-lifetime so the Shaolib runtime cleans up its transient
 * store. The marker type is also registered on the client so the transient
 * spawn/remove packets never hit an unknown-type lookup.</p>
 */
public final class CbcAutocannonPayloads {

    /** Payload id fed through TAOV's reaction lookup; falls back to the gun's default reaction. */
    public static final ResourceLocation CBC_AUTOCANNON_ID = ResourceLocation.fromNamespaceAndPath("firecontrolcompat", "cbc_autocannon");

    private static final ResourceLocation MARKER_ID = ResourceLocation.fromNamespaceAndPath("firecontrolcompat", "cbc_round_marker");
    private static final int PROJECTILE_LIFETIME_TICKS = 800;
    private static final float SINGLE_BARREL_CHARGE_POWER = 1.0F;

    private static volatile ProjectileType<ProjectileServerState.None> MARKER_TYPE;

    private CbcAutocannonPayloads() {
    }

    /**
     * Whether the payload resolves to a native CBC round added by CBC Modern
     * Warfare or CBC Autocannon Revolution. Cartridges are unwrapped to their
     * inner projectile first. CBC's own AP/flak/machine gun rounds are left to
     * TAOV's original mappings.
     */
    public static boolean isCbcRound(ItemStack payload) {
        if (payload == null || payload.isEmpty()) {
            return false;
        }
        ItemStack effective = TaovWeaponPayloadItems.effectiveProjectile(payload);
        if (effective.isEmpty()) {
            return false;
        }
        Item item = effective.getItem();
        if (!(item instanceof AutocannonRoundItem)) {
            return false;
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        if (id != null && ("cbcaddon".equals(id.getNamespace()) || "cbcmodernwarfare".equals(id.getNamespace()))) {
            return true;
        }
        String className = item.getClass().getName();
        return className.startsWith("com.cbcaddon.") || className.startsWith("riftyboi.cbcmodernwarfare.");
    }

    /**
     * Builds a TAOV launch payload that spawns the payload stack's own CBC
     * projectile. Empty when the payload is not a native CBC round.
     */
    public static Optional<MappedProjectile> map(ItemStack payload) {
        if (!isCbcRound(payload)) {
            return Optional.empty();
        }
        return Optional.of(new MappedProjectile(
                CBC_AUTOCANNON_ID.toString(),
                (level, position, velocity, angularVelocity, direction) -> fire(payload, level, position, velocity)));
    }

    private static Optional<ProjectileHandle<?>> fire(ItemStack payload, ServerLevel level, Vec3 position, Vec3 velocity) {
        ItemStack stack = TaovWeaponPayloadItems.effectiveProjectile(payload);
        if (stack.isEmpty() || !(stack.getItem() instanceof AutocannonRoundItem round)) {
            return Optional.empty();
        }
        AbstractAutocannonProjectile projectile = round.getAutocannonProjectile(stack, level);
        if (projectile == null) {
            return Optional.empty();
        }
        Vec3 motion = velocity == null ? Vec3.ZERO : velocity;
        if (motion.lengthSqr() < 1.0E-8) {
            projectile.discard();
            return Optional.empty();
        }
        projectile.setPos(position);
        projectile.setChargePower(SINGLE_BARREL_CHARGE_POWER);
        projectile.setTracer(TaovShaolibPayloadMapper.traits(payload).tracer());
        projectile.setLifetime(PROJECTILE_LIFETIME_TICKS);
        projectile.setDeltaMovement(motion);
        float yRot = (float) (Mth.atan2(motion.x, motion.z) * 180.0D / Math.PI);
        float xRot = (float) (Mth.atan2(motion.y, motion.horizontalDistance()) * 180.0D / Math.PI);
        projectile.setYRot(yRot);
        projectile.setXRot(xRot);
        projectile.yRotO = yRot;
        projectile.xRotO = xRot;
        level.addFreshEntity(projectile);

        ProjectileHandle<?> marker = ShaolibProjectiles.spawn(level, markerType(), position, Vec3.ZERO);
        if (marker == null) {
            projectile.discard();
            return Optional.empty();
        }
        return Optional.of(marker);
    }

    /**
     * Registers (or resolves) the instant-lifetime marker type. Safe to call
     * on both physical sides and multiple times.
     */
    public static ProjectileType<ProjectileServerState.None> markerType() {
        ProjectileType<ProjectileServerState.None> type = MARKER_TYPE;
        if (type == null) {
            synchronized (CbcAutocannonPayloads.class) {
                type = MARKER_TYPE;
                if (type == null) {
                    Optional<ProjectileType<?>> existing = ProjectileTypes.get(MARKER_ID);
                    if (existing.isPresent()) {
                        @SuppressWarnings("unchecked")
                        ProjectileType<ProjectileServerState.None> cast = (ProjectileType<ProjectileServerState.None>) existing.get();
                        type = cast;
                    } else {
                        type = ShaolibProjectiles.register(ProjectileType.builder(MARKER_ID).maxLifetimeTicks(1).build());
                    }
                    MARKER_TYPE = type;
                }
            }
        }
        return type;
    }
}

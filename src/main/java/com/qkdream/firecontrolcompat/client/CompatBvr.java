package com.qkdream.firecontrolcompat.client;

import com.qkdream.firecontrolcompat.FireControlCompat;
import com.qkdream.firecontrolcompat.entity.BeamRidingMissileEntity;
import java.util.Locale;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

/**
 * Whether this mod rides fire control's own beyond-visual-range rendering chain.
 *
 * <h2>The chain, as it actually is in 0.9.4</h2>
 *
 * <p>The pack does not draw distant missiles with a special shader of its own. It draws them
 * <b>outside the level's own entity pass</b>, in {@code RenderLevelStageEvent.Stage.AFTER_LEVEL}:
 *</p>
 *
 * <ol>
 *   <li>the server sends {@code MissileRemoteStatePayload} (uuid, type, position, velocity,
 *       quaternion, tick) to everyone within twenty thousand blocks
 *       ({@code MianbaoMissileRemoteSync#broadcast});</li>
 *   <li>{@code MissileLongRangeEffects#acceptRemoteState} creates a client-side proxy of the same
 *       entity type - negative id, {@code CreateFireControlRemoteVisual} marker. When the type is
 *       one it knows how to draw at range, the proxy is <b>not added to the client level</b>
 *       ({@code detachedFromClientChunks}) and lives only in its {@code REMOTE_VISUALS} table;</li>
 *   <li>{@code ClientMissileRenderer#render} walks those detached proxies every frame and,
 *       for the anti-air family, calls {@code renderOrdinaryAa}: it pushes
 *       {@code RenderSystem.setShaderFogStart(1.0E6)} / {@code setShaderFogEnd(2.0E6)}
 *       (the fog is what paints a far round in the sky colour), <b>rebuilds the projection matrix</b>
 *       so the far plane reaches past the round ({@code m22}/{@code m32} recomputed),
 *       skips the draw when foreground terrain clips the line, and then renders the proxy through
 *       {@code EntityRenderDispatcher} - i.e. through <b>this mod's own renderer and texture</b>.</li>
 * </ol>
 *
 * <p>That last point is why this is the right chain to be on and not a workaround around it: the
 * fog and the far plane are handled <b>once</b>, by the pack, for every round it carries, and the
 * airframe is still ours.</p>
 *
 * <h2>How a round of ours gets on it</h2>
 *
 * <p>The branch a detached proxy is drawn by is decided by one predicate,
 * {@code OrdinaryAirDefenseGuidance#isOrdinaryType(EntityType)}, which names a single
 * {@code mianbaos_modernwarfare} entity id. {@link com.qkdream.firecontrolcompat.mixin.OrdinaryAaBvrMixin}
 * widens it with {@link #isBvrRound}, so our five flying types are carried by the same code - one
 * chain, no parallel implementation.</p>
 *
 * <h2>Fallback</h2>
 *
 * <p>{@link #available()} is false when the pack does not have this chain (the 0.7.1 jar in the
 * other instance, or a future version that moved the classes). In that case
 * {@link MissileFarMarker} - this mod's own fog-free marker - still answers, so a round is never
 * invisible just because the channel is missing. The marker is otherwise switched off, because two
 * pictures of one round is exactly the "phantom" defect.</p>
 */
public final class CompatBvr {

    private static volatile Boolean available;

    private static volatile boolean reported;

    private CompatBvr() {
    }

    /**
     * Whether the pack's BVR chain is present and this mod is on it.
     *
     * <p>Resolved once by class presence - the three classes below are the whole chain, and a pack
     * that has them has the {@code AFTER_LEVEL} renderer this mod hooks. Class presence is not
     * proof that the injection landed, so the first answer is also written to the log:
     * {@code ir-atgm bvr: channel=pack}. If that line is followed by our renderer never being
     * called at range, the channel was lost, not the round.</p>
     */
    public static boolean available() {
        Boolean known = available;
        if (known != null) {
            return known;
        }
        boolean present = classPresent("com.hooya.stabilizedturret.client.MissileLongRangeEffects")
                && classPresent("com.hooya.stabilizedturret.content.missile.client.ClientMissileRenderer")
                && classPresent("com.hooya.stabilizedturret.content.controller.OrdinaryAirDefenseGuidance");
        available = present;
        return present;
    }

    private static boolean classPresent(String name) {
        try {
            Class.forName(name, false, CompatBvr.class.getClassLoader());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Registry paths of this mod's flying rounds.
     *
     * <p>Kept as ids rather than as the {@code DeferredHolder}s on purpose: this class is asked
     * during client rendering, and touching a {@code DeferredHolder} there would be the one place a
     * client class reached into registration. The set is the same five types
     * {@code BeamMissileRegistry} registers, and {@code CompatBvr.isBvrRound(Entity)} - the overload
     * for an actual entity - is the authoritative answer for anything a holder does not cover.</p>
     */
    private static final java.util.Set<String> BVR_PATHS = java.util.Set.of(
            "ir_atgm_tanshe", "infrared_tanshe", "loitering_tanshe", "heavy_aa_tanshe", "neva_tanshe");

    private static final String BVR_NAMESPACE = "firecontrolcompat";

    /**
     * Whether that entity type is one of this mod's flying rounds.
     *
     * <p><b>Not</b> asked through {@code EntityType#getBaseClass()}: that method returns
     * {@code Entity.class} on every type built with {@code EntityType.Builder} (disassembled:
     * {@code ldc Entity; areturn}), so it answers "no" for our rounds as readily as for anyone
     * else's. The registry key is the stable answer - the type is the type wherever it was spawned,
     * including a Sable sub-level - and it costs one registry key lookup.</p>
     */
    public static boolean isBvrRound(EntityType<?> type) {
        if (type == null) {
            return false;
        }
        try {
            net.minecraft.resources.ResourceLocation key =
                    net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(type);
            return key != null
                    && BVR_NAMESPACE.equals(key.getNamespace())
                    && BVR_PATHS.contains(key.getPath());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Whether this very entity is one of this mod's rounds - by class first, then by type id. */
    public static boolean isBvrRound(Entity entity) {
        if (entity == null) {
            return false;
        }
        if (entity instanceof BeamRidingMissileEntity) {
            return true;
        }
        return isBvrRound(entity.getType());
    }

    /**
     * One line, once, saying which channel is carrying our rounds.
     *
     * <p>Under the criterion "the pack's own remote renderer is drawing our round" this is the
     * statement to look for first, because it is the only one that can be read before any round is
     * in the air.</p>
     */
    public static void report() {
        if (reported) {
            return;
        }
        reported = true;
        boolean pack = available();
        FireControlCompat.LOGGER.info(
                "ir-atgm bvr: channel={} detached={} renderer={} fog=pack farplane=pack fallback={}",
                pack ? "pack" : "none",
                pack,
                pack ? "ClientMissileRenderer#renderOrdinaryAa->EntityRenderDispatcher(ours)" : "-",
                pack ? "none" : String.format(Locale.ROOT, "MissileFarMarker(lead=%.1f)", MissileFarMarker.NEAR_LIMIT));
        // One line per flying type. This is the answer to "is that type even on the channel", which
        // until now could only be inferred from a round being drawn: a type missing from this list -
        // or answering ordinary=false - is never handed to renderOrdinaryAa, so its airframe is
        // drawn by nobody while the trail (emitted before the model) still appears.
        for (String path : BVR_PATHS) {
            try {
                net.minecraft.resources.ResourceLocation id =
                        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(BVR_NAMESPACE, path);
                EntityType<?> type = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.get(id);
                boolean known = type != null
                        && net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(type) != null;
                boolean ordinary = false;
                if (known) {
                    try {
                        ordinary = com.hooya.stabilizedturret.content.controller.OrdinaryAirDefenseGuidance
                                .isOrdinaryType(type);
                    } catch (Throwable ignored) {
                        ordinary = false;
                    }
                }
                FireControlCompat.LOGGER.info(
                        "ir-atgm bvr: type={} channel={} registered={} ordinary={} detached={} ok={}",
                        path, pack ? "pack" : "none", known, ordinary, known && ordinary,
                        pack && known && ordinary);
            } catch (Throwable failure) {
                FireControlCompat.LOGGER.info("ir-atgm bvr: type={} probe failed: {}", path,
                        String.valueOf(failure));
            }
        }
    }
}

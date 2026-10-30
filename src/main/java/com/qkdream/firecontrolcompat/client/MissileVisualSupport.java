package com.qkdream.firecontrolcompat.client;

import com.hooya.stabilizedturret.client.MissileLongRangeEffects;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.HashMap;
import java.util.Map;
import net.mcreator.myfirstmod.init.MianbaosModernwarfareModParticleTypes;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Client-side long-range visual plumbing for our missiles, mirroring fire control's
 * {@link MissileLongRangeEffects}.
 *
 * <h2>One object per round, at every distance</h2>
 *
 * <p>Fire control answers a {@code MissileRemoteStatePayload} (which this mod broadcasts every tick
 * from {@code BeamRidingMissileEntity#broadcastRemoteState}) by creating a <b>second</b> client
 * entity of the same type - a proxy that it positions from the payload and extrapolates between
 * payloads (<code>MissileLongRangeEffects.acceptRemoteState</code> / <code>RemoteVisual</code>). The
 * tracked entity is still there, so for as long as both are drawn one round appears twice:
 *
 * <ul>
 *   <li>the <b>tracked</b> round, at the position the server actually ticked (exact);</li>
 *   <li>the <b>proxy</b>, at an extrapolation of the last payload - several blocks behind on a
 *       straight run and, because the extrapolation continues along the last velocity, still flying
 *       the <em>previous</em> heading for a tick or two after the round turns. That is the
 *       "phantom with the particle trail, pointing where the round used to go" report.</li>
 * </ul>
 *
 * <p>The two are therefore split by distance instead of both being drawn: inside
 * {@link #horizon(Entity)} the tracked round is the picture and the proxy is not drawn; outside it
 * the proxy is the picture and the tracked round steps aside. Exactly one of the two is ever drawn,
 * which is what makes {@code draws=1} in {@code ir-atgm render:} hold.</p>
 *
 * <p>The horizon is <b>not</b> a bare number kept here: it is the smaller of the entity type's own
 * {@code clientTrackingRange} (chunks, times sixteen - past it the tracked round is not on this
 * client at all) and the client's fog wall, past which no entity-drawn picture of any kind reaches
 * the eye (see {@link MissileFarMarker}).</p>
 */
public final class MissileVisualSupport {

    private static final double NEAR_TRAIL_DISTANCE_SQR = 1024.0;

    /** Margin so the switch-over can never leave a band where both are skipped. */
    private static final double HANDOVER_MARGIN = 8.0D;

    private static final Map<Integer, Long> LAST_TRAIL_TICK = new HashMap<>();
    private static final Map<Integer, Vec3> LAST_TRAIL_POS = new HashMap<>();

    private MissileVisualSupport() {
    }

    /**
     * Whether this entity is fire control's long-range proxy rather than a server-ticked round.
     *
     * <p>Delegates to the common {@link com.qkdream.firecontrolcompat.entity.RemoteVisualState}:
     * the predicate is pure persistent-data, and the cook-off listener (a server-side explosion
     * handler) needs the same answer, so it can no longer live in a client class. Behaviour and the
     * marker's meaning are unchanged - the key is still {@code CreateFireControlRemoteVisual}.</p>
     */
    public static boolean isRemoteVisual(Entity entity) {
        return com.qkdream.firecontrolcompat.entity.RemoteVisualState.isRemoteVisual(entity);
    }

    /**
     * How far out the picture changes from the tracked round to fire control's proxy, in blocks.
     *
     * <h2>The horizon is the fog wall, not the tracking range (2026-10-26 second correction)</h2>
     *
     * <p>It used to be the entity type's {@code clientTrackingRange} alone (1024 blocks since the
     * range was raised). The user reported the round still invisible at long range anyway, and the
     * reason is not tracking at all: the entity <b>is</b> received and drawn at six hundred blocks
     * ({@code ir-atgm render: .. dist=611.3 limit=1024 draws=1} in their log), but the entity shaders
     * end in {@code linear_fog(..)} and their {@code renderDistance} is ten chunks, so past a hundred
     * and sixty blocks the round is painted in the fog colour - the sky. The far plane is only
     * {@code renderDistance * 4} = 640 blocks, so a thousand block shot is clipped away entirely.</p>
     *
     * <p>So the split follows what the client can actually <b>see</b>: inside the fog the tracked
     * round is exact and visible and is the picture; past it the tracked round is not visible at all,
     * and fire control's proxy - which exists at every distance, because the payload is broadcast to
     * everyone within twenty thousand blocks - answers for the round instead. The proxy's own renderer
     * is just as fogged, so what the player actually sees out there is
     * {@link MissileFarMarker}'s fog-free mark; the handover is still what keeps the count at one
     * object, one trail and one mark. The tracking range stays as a ceiling: past it the tracked
     * round is not on this client in the first place.</p>
     */
    public static double horizon(Entity entity) {
        double tracked = 128.0D;
        try {
            int chunks = entity.getType().clientTrackingRange();
            if (chunks > 0) {
                tracked = chunks * 16.0D;
            }
        } catch (Throwable ignored) {
            // an unreadable type falls through to the conservative figure above
        }
        return Math.min(tracked, MissileFarMarker.handoverDistance());
    }

    /**
     * Whether this object should <b>not</b> be drawn, because the other half of the pair is the
     * picture at this distance.
     *
     * <p>Called from every one of this mod's missile renderers before anything is drawn (so the
     * trail is skipped with it, and one round never produces two trails).</p>
     *
     * <h2>Which object is the picture</h2>
     *
     * <p>On fire control's BVR chain ({@link CompatBvr#available()}) the proxy is drawn by
     * {@code ClientMissileRenderer} in the {@code AFTER_LEVEL} stage with the fog pushed away and
     * the projection rebuilt, while the tracked round can only ever be drawn by the level's own
     * entity pass - which is fogged, and clipped by the far plane. The proxy is therefore the
     * picture at <b>every</b> distance and the tracked round steps aside as soon as one exists.
     * There is no distance threshold left to get wrong, and no band where both are drawn (that band
     * is what the "phantom" report was) or neither is.</p>
     *
     * <p>Without the chain - an older pack - the old distance handover still applies, and
     * {@link MissileFarMarker} draws the fog-free mark past the fog wall.</p>
     */
    public static boolean shouldSuppressNative(Entity entity) {
        if (entity == null) {
            return false;
        }
        if (CompatBvr.available()) {
            // The proxy itself is never suppressed: it IS the picture.
            if (isRemoteVisual(entity)) {
                return false;
            }
            // Exactly the object the pack has taken over. Read per frame so a round whose payloads
            // stopped comes back rather than vanishing for good.
            return MissileLongRangeEffects.getRemoteVisual(entity.getUUID()) != null;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.gameRenderer == null
                || minecraft.gameRenderer.getMainCamera() == null) {
            return false;
        }
        double distance = Math.sqrt(minecraft.gameRenderer.getMainCamera().getPosition()
                .distanceToSqr(entity.position()));
        double horizon = horizon(entity);
        if (isRemoteVisual(entity)) {
            // The proxy answers for the round only past the tracking horizon: closer than that the
            // tracked round is both exact and already here, so the proxy is the duplicate.
            return distance <= horizon - HANDOVER_MARGIN;
        }
        // The tracked round steps aside only when fire control really is drawing a proxy for it.
        return MissileLongRangeEffects.getRemoteVisual(entity.getUUID()) != null && distance > horizon;
    }

    /**
     * Whether fire control is already drawing this object's trail, in which case this mod must not
     * add a second one.
     *
     * <p>{@code MissileLongRangeEffects#onMissileRendered} is called from its own client tick for
     * every detached visual, and emits the trail particle for the round - so a proxy on the BVR
     * chain gets a trail with no help from here. Adding this mod's own on top would give one round
     * two plumes from two different particle sets, which reads as a missile with a smoke leak.</p>
     */
    public static boolean packDrawsTrail(Entity entity) {
        return entity != null && CompatBvr.available() && isRemoteVisual(entity);
    }

    /** One render type per texture, so a per-frame call never mints a new one. */
    private static final Map<ResourceLocation, RenderType> OWN_TYPES = new HashMap<>();

    /**
     * The buffer source a round should be drawn into, given the one the caller handed us.
     *
     * <p>When fire control draws a round beyond the far plane it does not hand over its normal
     * buffer source: it hands a <b>fixed</b> one
     * ({@code ClientMissileRenderer#renderOrdinaryAa}, {@code type -> buffers.getBuffer(
     * DistantOrdinaryAaRenderType.TYPE)}) whose render type pins the pack's own anti-air texture and
     * turns the depth test off. Obeying it would paint <b>our</b> airframe with <b>their</b>
     * texture.</p>
     *
     * <p>This answers a buffer source that honours the request for our own texture and the real
     * buffer source behind it, so the geometry is ours end to end. Depth testing is left on: the
     * far plane has already been extended by the caller, so the depth buffer is valid out there, and
     * a round behind a ridge is still behind it.</p>
     */
    public static MultiBufferSource bufferFor(Entity entity, MultiBufferSource requested, ResourceLocation texture) {
        if (entity == null || requested == null || texture == null) {
            return requested;
        }
        if (!CompatBvr.available() || !isRemoteVisual(entity)) {
            return requested;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.renderBuffers() == null) {
            return requested;
        }
        RenderType ours = OWN_TYPES.computeIfAbsent(texture, RenderType::entityCutoutNoCull);
        MultiBufferSource real = minecraft.renderBuffers().bufferSource();
        return type -> realBuffer(real, ours);
    }

    private static VertexConsumer realBuffer(MultiBufferSource real, RenderType type) {
        return real.getBuffer(type);
    }

    /**
     * Draws the rocket flame and smoke trail straight into the local client
     * particle engine. Called from the renderer every frame; throttled to
     * once per entity per game tick. Skipped for native entities close to the
     * camera because the server-side trail particles already cover them.
     */
    public static void spawnLongRangeTrail(Entity missile) {
        if (packDrawsTrail(missile)) {
            // Fire control emits this round's trail from its own tick (see packDrawsTrail).
            return;
        }
        if (com.qkdream.firecontrolcompat.missile.MissileTrailParticles.isTrailRound(missile)) {
            // The server owns this round's plume now (MissileTrailParticles, four particles a tick
            // for every player within five hundred blocks). Adding the client copy on top would be
            // a second, differently coloured plume from the same nozzle.
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            return;
        }
        Camera camera = minecraft.gameRenderer.getMainCamera();
        if (camera == null) {
            return;
        }
        Vec3 position = missile.position();
        boolean remote = isRemoteVisual(missile);
        if (!remote && camera.getPosition().distanceToSqr(position) <= NEAR_TRAIL_DISTANCE_SQR) {
            return;
        }
        long tick = level.getGameTime();
        if (LAST_TRAIL_TICK.getOrDefault(missile.getId(), -1L) == tick) {
            return;
        }
        LAST_TRAIL_TICK.put(missile.getId(), tick);
        if (LAST_TRAIL_TICK.size() > 512) {
            LAST_TRAIL_TICK.entrySet().removeIf(entry -> entry.getValue() < tick - 40L);
            LAST_TRAIL_POS.keySet().retainAll(LAST_TRAIL_TICK.keySet());
        }

        ParticleOptions flame = MianbaosModernwarfareModParticleTypes.ROCKETMISSILETRAIL_3.get();
        ParticleOptions smoke = MianbaosModernwarfareModParticleTypes.NEWSMOKE_2.get();
        Vec3 velocity = missile.getDeltaMovement();
        double speed = velocity.length();
        int samples = Mth.clamp((int) Math.ceil(speed / 8.0), 1, 6);
        Vec3 lastPosition = LAST_TRAIL_POS.get(missile.getId());
        boolean continuous = lastPosition != null && lastPosition.distanceToSqr(position) <= 400.0;
        RandomSource random = level.random;
        for (int i = 0; i < samples; i++) {
            double fraction = (i + 0.25) / samples;
            Vec3 sample = continuous ? lastPosition.lerp(position, fraction) : position;
            level.addAlwaysVisibleParticle(flame, true, sample.x, sample.y, sample.z, 0.0, 0.0, 0.0);
            level.addAlwaysVisibleParticle(
                    smoke,
                    true,
                    sample.x,
                    sample.y,
                    sample.z,
                    (random.nextDouble() - 0.5) * 0.35,
                    (random.nextDouble() - 0.5) * 0.35,
                    (random.nextDouble() - 0.5) * 0.35);
        }
        LAST_TRAIL_POS.put(missile.getId(), position);
    }
}

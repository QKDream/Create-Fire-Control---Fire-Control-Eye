package com.qkdream.firecontrolcompat.client;

import com.hooya.stabilizedturret.content.controller.MianbaoBallisticMissileCompat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.qkdream.firecontrolcompat.BeamMissileRegistry;
import com.qkdream.firecontrolcompat.FireControlCompat;
import com.qkdream.firecontrolcompat.entity.BeamRidingMissileEntity;
import com.qkdream.firecontrolcompat.entity.InfraredMissileEntity;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Draws the infrared anti-tank round with the <b>Java item model</b> itself.
 *
 * <h2>Why an item model and not a geo</h2>
 *
 * <p>The user's airframe is a Java/Blockbench {@code elements} export
 * (``spiker_nlos.json``: thirteen cubes, six textures, per-face UV, per-cube Z rotations).
 * The Java model is the source format, so it is used as-is:
 * {@code assets/firecontrolcompat/models/item/ir_atgm.json}, baked by the normal model loader and
 * drawn here through {@link net.minecraft.client.renderer.entity.ItemRenderer#renderStatic}.</p>
 *
 * <h2>Does {@code renderStatic} rotate the model twice? (checked, at bytecode level)</h2>
 *
 * <p><b>No, for {@link ItemDisplayContext#NONE}.</b> The NeoForge-patched
 * {@code net/minecraft/client/renderer/entity/ItemRenderer#render} is
 * {@code ClientHooks.handleCameraTransforms(pose, model, context, leftHand)} then
 * {@code pose.translate(-0.5, -0.5, -0.5)} then the quad lists
 * ({@code neoforge-21.1.228-client.jar}, javap {@code ItemRenderer#render}: the call sites are the
 * only thing that happens before the model is drawn). {@code handleCameraTransforms} is
 * {@code BakedModel#applyTransform} ({@code ClientHooks}, javap: one interface call), which is
 * {@code getTransforms().getTransform(context).apply(leftHand, pose)}
 * ({@code IBakedModelExtension}, javap). {@code ItemTransforms#getTransform} is a
 * {@code tableswitch} over the eight real contexts with <b>{@code default: ItemTransform.NO_TRANSFORM}</b>
 * (javap: the eight field loads then {@code 152: getstatic ItemTransform.NO_TRANSFORM}), and
 * {@code NONE} is the only ordinal that reaches that default. So with {@code NONE} the only thing
 * {@code renderStatic} adds is the half-block centering translate - no rotation, no scale:
 *
 * <ul>
 *   <li>{@code models/item/ir_atgm.json}'s {@code ground} display is {@code [0,0,0]} rotation, scale
 *       0.5, translation {@code [0,1.5,0]}; {@code fixed} is {@code [0,45,0]}; {@code gui} is
 *       {@code [30,225,0]}; {@code firstperson_*}/{@code thirdperson_*} carry yaw and roll. <b>None of
 *       them is applied on this path</b> - they exist for the hand, the ground and the icon only;</li>
 *   <li>the pose this renderer builds is therefore the whole orientation, and it can be checked
 *       against the model geometry instead of trusted: the airframe's nose is authored towards
 *       <b>-Z</b> (the seeker cube is at {@code z = -4.6..-3.6}, the nozzle at {@code z = 17.6..20.6}
 *       in the same 0..16 space), so the vanilla mob pair {@code YP(180 - yaw)} then
 *       {@code XP(-pitch)} puts {@code (0,0,-1)} on {@code (-sin y Cos p, -sin p, Cos y Cos p)},
 *       which is exactly a mob's {@code getViewVector} - 0.0000 degrees of deviation
 *       ({@code _work/check_render_math.py}, nine yaw x five pitch cases).</li>
 * </ul>
 *
 * <h2>Then why was the nose still not on the flight path?</h2>
 *
 * <p>Because <b>two different objects</b> were drawn for one round, and they were oriented by two
 * different inputs. This renderer is also handed fire control's long-range proxy
 * ({@code MissileLongRangeEffects.RemoteVisual}, spawned from the
 * {@code MissileRemoteStatePayload} this mod broadcasts). For that proxy the rotation arrives as a
 * quaternion, and the pack's convention everywhere - {@code MianbaoMissileRemoteSync#broadcast},
 * {@code MianbaoBallisticMissileRemoteVisualMixin#orientRemoteProxy},
 * {@code ClientMissileRenderer#renderNativeModel} - is
 * <b>{@code new Quaternionf().rotationTo(+Y, direction)}</b>: it maps model space <b>+Y</b> onto the
 * flight vector. The sender on this side ({@code BeamRidingMissileEntity#broadcastRemoteState}) built
 * it with {@code rotationTo(+Z, direction)} instead, so {@code remote * XP(90) * (0,0,-1)} - the nose
 * this branch draws - came out a <b>right angle away</b> from the direction the round was flying.
 * And because the quaternion branch reported no {@code off} at all ({@code off=-} in the log), the
 * check that was supposed to catch it was never run for the one branch that was wrong.</p>
 *
 * <p>Both ends are fixed: the sender now uses {@code +Y} (the pack's convention), and this renderer
 * <b>measures</b> the quaternion branch instead of trusting it - the nose is computed from the
 * pose that was actually applied, compared with the observed travel direction, and when the two
 * disagree by more than {@link #QUATERNION_MISMATCH_DEGREES} the quaternion is dropped in favour of
 * the travel direction (which a watching client always has, and which is what the round visibly
 * did). {@code src=quaternion-motion} in the log is that fallback firing.</p>
 *
 * <h2>Diagnostics</h2>
 *
 * <p>{@code ir-atgm render: mode=.. src=.. nose=.. dir=.. off=.. applied=.. desired=.. draws=..
 * proxy=.. dist=.. limit=..}= one line per round per ten ticks. {@code nose} is the nose of the
 * pose that was applied (the airframe's own -Z axis through {@code applied}), {@code dir} is what the
 * round actually did, {@code off} is the angle between them - <b>{@code off} is the acceptance
 * criterion</b>, and it is computed from the applied rotation, never from the inputs.
 * {@code applied} and {@code desired} are the rotations as quaternions: {@code desired} is the one
 * that would put the nose on {@code dir} ({@code rotationTo((0,0,-1), dir)}), {@code applied} is the
 * one that was multiplied into the pose. Equal {@code nose}/{@code dir} is what "the same" means -
 * the two quaternions may differ by a roll about the flight axis, which the round's silhouette does
 * not show. {@code draws} is how many airframes of this type the previous frame drew (1 = exactly
 * one object per round per frame, which is the phantom check), {@code proxy} says whether this very
 * object is fire control's long-range proxy, and {@code dist}/{@code limit} are the camera distance
 * and the distance this entity type is tracked out to.</p>
 */
public class IrAtgmModelRenderer extends EntityRenderer<InfraredMissileEntity> {

    /** The airframe item whose model is the round's body. Resolved once, at client setup. */
    private final ItemStack airframe;

    /** The airframe texture, for the (unused) base-class contract and the hitbox debug name. */
    private final ResourceLocation texture =
            ResourceLocation.fromNamespaceAndPath("firecontrolcompat", "textures/item/ir_atgm_t2.png");

    /**
     * How far the rotation that arrived over the wire may be from the flight path before it is
     * treated as wrong.
     *
     * <p>A correct pair differs by the numerical noise of a normalised quaternion and the
     * per-frame step of the position, which is a degree or two at most; a wrong convention is a
     * right angle or a half turn. Forty degrees is far above the noise and far below either
     * mistake, so this never fires on a healthy round.</p>
     */
    private static final double QUATERNION_MISMATCH_DEGREES = 40.0D;

    public IrAtgmModelRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.airframe = new ItemStack(BeamMissileRegistry.IR_ATGM.get());
        this.shadowRadius = 0.0F;
    }

    @Override
    public void render(InfraredMissileEntity entity, float entityYaw, float partialTicks, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight) {
        if (MissileVisualSupport.shouldSuppressNative(entity)) {
            return;
        }
        firecontrolcompat$countDraw(entity);
        MissileVisualSupport.spawnLongRangeTrail(entity);
        Tracker tracker = firecontrolcompat$track(entity);
        poseStack.pushPose();
        this.firecontrolcompat$orient(entity, poseStack, partialTicks, tracker);
        // Fire control hands a fixed-texture buffer source to rounds it draws past the far plane
        // (ClientMissileRenderer#renderOrdinaryAa); bufferFor keeps our own texture and the real
        // buffer there, so the airframe is drawn as ours end to end.
        MultiBufferSource target = MissileVisualSupport.bufferFor(
                entity, buffer, net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS);
        Minecraft.getInstance().getItemRenderer().renderStatic(this.airframe,
                ItemDisplayContext.NONE, packedLight, OverlayTexture.NO_OVERLAY, poseStack, target,
                entity.level(), entity.getId());
        poseStack.popPose();
        firecontrolcompat$report(entity, tracker);
        super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight);
    }

    /**
     * The model-space orientation, and the bookkeeping that lets the log prove it.
     *
     * <p>Order of authority, most to least specific: a round lying on a rail (drawn from the rail's
     * own yaw, never laid onto a flight vector), the observed travel direction (the one reading a
     * watching client always has, and the one that covers a mathematical visual teleported every
     * payload), and fire control's remote quaternion - honoured only while it agrees with the travel
     * direction, because that is the input that was wrong by ninety degrees when this round's nose
     * was reported as "still not right".</p>
     */
    private void firecontrolcompat$orient(InfraredMissileEntity entity, PoseStack poseStack, float partialTick,
                                          Tracker tracker) {
        if (entity instanceof BeamRidingMissileEntity missile && missile.firecontrolcompat$isMounted()) {
            tracker.mode = "rail";
            tracker.source = "rail";
            tracker.applied = new Quaternionf().rotationY((float) Math.toRadians(180.0D - entity.getYRot()));
            tracker.nose = firecontrolcompat$noseOf(tracker.applied);
            poseStack.mulPose(Axis.YP.rotationDegrees(-entity.getYRot() + 180.0F));
            return;
        }
        Vec3 direction = tracker.direction;
        Quaternionf remote = MianbaoBallisticMissileCompat.remoteRenderRotation(entity);
        if (remote != null) {
            Quaternionf applied = new Quaternionf(remote).mul(new Quaternionf().rotationX((float) (Math.PI / 2.0D)));
            Vec3 nose = firecontrolcompat$noseOf(applied);
            double off = direction == null ? 0.0D : firecontrolcompat$angle(nose, direction);
            if (direction == null || off <= QUATERNION_MISMATCH_DEGREES) {
                tracker.mode = "quaternion";
                tracker.source = "quaternion";
                tracker.applied = applied;
                tracker.desired = direction == null ? null
                        : new Quaternionf().rotationTo(new Vector3f(0.0F, 0.0F, -1.0F),
                                new Vector3f((float) direction.x, (float) direction.y, (float) direction.z));
                tracker.nose = nose;
                poseStack.mulPose(remote);
                poseStack.mulPose(Axis.XP.rotationDegrees(90.0F));
                return;
            }
            // The rotation that arrived does not describe this round's flight path: draw the path
            // instead, and keep the mismatch on the record. Not silent on purpose - a stale proxy
            // from an older jar is exactly how this was reported twice.
            tracker.mismatch = off;
        }
        float yaw;
        float pitch;
        if (direction != null) {
            double horizontal = Math.sqrt(direction.x * direction.x + direction.z * direction.z);
            yaw = (float) (Mth.atan2(-direction.x, direction.z) * (180.0 / Math.PI));
            pitch = (float) (-Mth.atan2(direction.y, horizontal) * (180.0 / Math.PI));
            tracker.mode = "entity";
            tracker.source = remote != null
                    ? "quaternion-motion"
                    : (tracker.step ? "motion" : "velocity");
        } else if (remote == null) {
            yaw = Mth.lerp(partialTick, entity.yRotO, entity.getYRot());
            pitch = Mth.lerp(partialTick, entity.xRotO, entity.getXRot());
            tracker.mode = "entity";
            tracker.source = "rotation";
        } else {
            // A quaternion that disagreed and no travel direction to replace it with: keep the
            // quaternion rather than freeze the round facing whatever it was spawned with.
            tracker.mode = "quaternion";
            tracker.source = "quaternion-kept";
            tracker.applied = new Quaternionf(remote).mul(new Quaternionf().rotationX((float) (Math.PI / 2.0D)));
            tracker.nose = firecontrolcompat$noseOf(tracker.applied);
            poseStack.mulPose(remote);
            poseStack.mulPose(Axis.XP.rotationDegrees(90.0F));
            return;
        }
        tracker.yaw = yaw;
        tracker.pitch = pitch;
        tracker.applied = new Quaternionf().rotationY((float) Math.toRadians(180.0D - yaw))
                .mul(new Quaternionf().rotationX((float) Math.toRadians(-pitch)));
        tracker.desired = direction == null ? null
                : new Quaternionf().rotationTo(new Vector3f(0.0F, 0.0F, -1.0F),
                        new Vector3f((float) direction.x, (float) direction.y, (float) direction.z));
        tracker.nose = firecontrolcompat$noseOf(tracker.applied);
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - yaw));
        poseStack.mulPose(Axis.XP.rotationDegrees(-pitch));
    }

    /** The airframe's nose (model -Z) after a rotation: what the silhouette actually points at. */
    private static Vec3 firecontrolcompat$noseOf(Quaternionf rotation) {
        Vector3f nose = rotation.transform(new Vector3f(0.0F, 0.0F, -1.0F));
        return new Vec3(nose.x, nose.y, nose.z);
    }

    private static double firecontrolcompat$angle(Vec3 first, Vec3 second) {
        if (first == null || second == null || first.lengthSqr() < 1.0E-9 || second.lengthSqr() < 1.0E-9) {
            return 0.0D;
        }
        double dot = Mth.clamp(first.normalize().dot(second.normalize()), -1.0D, 1.0D);
        return Math.toDegrees(Math.acos(dot));
    }

    /**
     * The direction this round is travelling, and the bookkeeping that gets it.
     *
     * <p>Read from the position step between two renders of two different ticks: that is the one
     * quantity a watching client always has, whatever the entity's synced rotation says. The delta
     * movement is the second reading (exact for fire control's mathematical visuals, which are
     * written a velocity every payload).</p>
     */
    private Tracker firecontrolcompat$track(InfraredMissileEntity entity) {
        Tracker tracker = TRACKED.computeIfAbsent(entity, key -> new Tracker());
        Vec3 now = entity.position();
        int tick = entity.tickCount;
        if (tracker.tick != tick) {
            if (tracker.tick != Integer.MIN_VALUE) {
                Vec3 step = now.subtract(tracker.position);
                if (step.lengthSqr() > 1.0E-6) {
                    tracker.direction = step.normalize();
                    tracker.step = true;
                }
            }
            tracker.tick = tick;
            tracker.position = now;
        }
        if (!tracker.step || tracker.direction == null) {
            Vec3 velocity = entity.getDeltaMovement();
            if (velocity != null && velocity.lengthSqr() > 1.0E-6) {
                tracker.direction = velocity.normalize();
                tracker.step = false;
            }
        }
        tracker.proxy = MissileVisualSupport.isRemoteVisual(entity);
        return tracker;
    }

    // -------------------------------------------------------------------------------------------
    // "Exactly one object per round per frame" counter
    // -------------------------------------------------------------------------------------------

    /** Client level tick the draw counter is currently counting. */
    private static long firecontrolcompat$frameTick = Long.MIN_VALUE;

    /** Airframes of this type drawn so far in {@link #firecontrolcompat$frameTick}. */
    private static int firecontrolcompat$frameDraws;

    /** Airframes drawn in the last complete client tick: 1 means one object per round. */
    private static int firecontrolcompat$lastFrameDraws;

    /**
     * Counts one draw and rolls the counter over when the client level ticks.
     *
     * <p>{@code draws=} in the log is the number of {@code ir_atgm_tanshe} airframes the previous
     * frame drew. Two of them for one round is the "phantom" report - the tracked round plus fire
     * control's long-range proxy - and it is how that was measured rather than guessed.</p>
     */
    private static void firecontrolcompat$countDraw(InfraredMissileEntity entity) {
        long tick;
        try {
            tick = entity.level() == null ? 0L : entity.level().getGameTime();
        } catch (Throwable ignored) {
            tick = 0L;
        }
        if (tick != firecontrolcompat$frameTick) {
            if (firecontrolcompat$frameTick != Long.MIN_VALUE) {
                firecontrolcompat$lastFrameDraws = firecontrolcompat$frameDraws;
            }
            firecontrolcompat$frameTick = tick;
            firecontrolcompat$frameDraws = 0;
        }
        firecontrolcompat$frameDraws++;
    }

    /** How far this entity type is tracked, in blocks - the limit a client can be told about it. */
    private static int firecontrolcompat$trackingLimit(InfraredMissileEntity entity) {
        try {
            return entity.getType().clientTrackingRange() * 16;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /**
     * One line per round per ten ticks, so a wrong nose can be read off the log instead of reported
     * as "it looks wrong".
     *
     * <p>{@code off} is measured from the pose that was applied, against the observed travel
     * direction: the nose of the airframe after {@code applied} is computed by rotating
     * {@code (0,0,-1)} through it, so a wrong input cannot hide behind a self-consistent formula.
     * {@code applied}/{@code desired} print the same two rotations as quaternions.</p>
     */
    private static void firecontrolcompat$report(InfraredMissileEntity entity, Tracker tracker) {
        int tick = entity.tickCount;
        if (tick - tracker.logged < 10 && !tracker.forced) {
            return;
        }
        tracker.logged = tick;
        tracker.forced = false;
        double off = firecontrolcompat$angle(tracker.nose, tracker.direction);
        if (tracker.nose == null || tracker.direction == null) {
            off = Double.NaN;
        }
        double distance;
        try {
            distance = Math.sqrt(Minecraft.getInstance().gameRenderer.getMainCamera().getPosition()
                    .distanceToSqr(entity.position()));
        } catch (Throwable ignored) {
            distance = Double.NaN;
        }
        int limit = firecontrolcompat$trackingLimit(entity);
        int chunks = MissileFarMarker.renderDistanceChunks();
        double fog = MissileFarMarker.fogReach();
        // `visible` is the question the report is really about: can the player see this object at
        // all? Two things stop an entity from reaching the eye whatever the renderer does - the
        // fog wall (past it the entity shaders paint the round in the fog colour, which is the sky)
        // and the far plane (GameRenderer#getDepthFar is renderDistance * 4 blocks). Past the fog
        // wall this mod's fog-free mark draws the round instead, so `visible=true` there means "the
        // mark carries it", not "this airframe is readable".
        // `visible` is the question the report is really about: can the player see this object at
        // all? Two things stop an entity from reaching the eye whatever the renderer does - the
        // fog wall (past it the entity shaders paint the round in the fog colour, which is the sky)
        // and the far plane (GameRenderer#getDepthFar is renderDistance * 4 blocks).
        //
        // On fire control's BVR chain neither applies: this object is a detached proxy drawn in the
        // AFTER_LEVEL stage, where the pack pushes the fog out to a million blocks and rebuilds the
        // projection to reach the round (ClientMissileRenderer#renderOrdinaryAa). So a proxy on that
        // chain is visible at any distance, which is what `channel=pack` says.
        boolean packChannel = CompatBvr.available() && MissileVisualSupport.isRemoteVisual(entity);
        // Without the chain, past the fog wall this mod's own fog-free mark draws the round, so
        // `visible=true` there means "the mark carries it", not "this airframe is readable".
        boolean visible = packChannel
                || Double.isNaN(distance)
                || distance <= fog
                || MissileFarMarker.covers(distance);
        double px = Double.isNaN(distance) ? 0.0D
                : MissileFarMarker.pixels(MissileFarMarker.angularSize(distance));
        FireControlCompat.LOGGER.info(
                "[firecontrolcompat] ir-atgm render: mode={} src={} nose={} dir={} off={} applied={}"
                        + " desired={} draws={} proxy={} dist={} limit={} visible={} rd={} fog={}"
                        + " px={} channel={} pos={} tick={}",
                tracker.mode, tracker.source,
                firecontrolcompat$vec(tracker.nose),
                firecontrolcompat$vec(tracker.direction),
                Double.isNaN(off) ? "-" : String.format(Locale.ROOT, "%.1f", off),
                firecontrolcompat$quat(tracker.applied),
                firecontrolcompat$quat(tracker.desired),
                firecontrolcompat$lastFrameDraws,
                tracker.proxy,
                Double.isNaN(distance) ? "-" : String.format(Locale.ROOT, "%.1f", distance),
                limit,
                visible,
                chunks,
                String.format(Locale.ROOT, "%.0f", fog),
                String.format(Locale.ROOT, "%.1f", px),
                packChannel ? "pack" : "own",
                entity.position(), tick);
    }

    private static String firecontrolcompat$vec(Vec3 value) {
        if (value == null) {
            return "-";
        }
        return String.format(Locale.ROOT, "(%.3f,%.3f,%.3f)", value.x, value.y, value.z);
    }

    /**
     * A rotation as a quaternion, in the canonical sign ({@code w >= 0}).
     *
     * <p>{@code q} and {@code -q} are the same rotation, and {@code rotationTo} and the
     * yaw/pitch composition can land on either representative, so the sign is normalised before
     * printing: then {@code applied} and {@code desired} read identically whenever they are the
     * same rotation, which is what makes {@code off=0.0} checkable by eye as well. They can still
     * differ by a roll about the flight axis (the airframe is very nearly rotationally symmetric
     * about it), so the nose comparison remains the criterion.</p>
     */
    private static String firecontrolcompat$quat(Quaternionf value) {
        if (value == null) {
            return "-";
        }
        float sign = value.w < 0.0F ? -1.0F : 1.0F;
        return String.format(Locale.ROOT, "(%.3f,%.3f,%.3f,%.3f)",
                value.x * sign, value.y * sign, value.z * sign, value.w * sign);
    }

    /**
     * Per-round render bookkeeping: the previous tick's position, the direction that step gave, the
     * pose that was applied (so the log can measure the nose against the path), and the log cursor.
     */
    private static final class Tracker {
        private int tick = Integer.MIN_VALUE;
        private Vec3 position = Vec3.ZERO;
        private Vec3 direction;
        /** Whether {@link #direction} came from an observed position step rather than a velocity. */
        private boolean step;
        /** Tick of the last line printed. Never {@code Integer.MIN_VALUE}: it would overflow below. */
        private int logged = -1_000_000;
        /** Set for the very first report, so a round that is only seen once still says how it is drawn. */
        private boolean forced = true;
        private String mode = "-";
        private String source = "-";
        private float yaw = Float.NaN;
        private float pitch = Float.NaN;
        /** The rotation that was multiplied into the pose, and the nose that it gives. */
        private Quaternionf applied;
        /** The rotation that would put the nose exactly on {@link #direction}. */
        private Quaternionf desired;
        private Vec3 nose;
        /** How far a wire quaternion was from the flight path when it was rejected, else NaN. */
        private double mismatch = Double.NaN;
        /** Whether this object is fire control's long-range proxy rather than the tracked round. */
        private boolean proxy;
    }

    /** Client-side only, keyed weakly so a round that leaves the level leaves nothing behind. */
    private static final Map<InfraredMissileEntity, Tracker> TRACKED = new WeakHashMap<>();

    /**
     * The airframe is 1.575 blocks long but the entity box is 0.6, so frustum culling against the
     * box drops the round while its nose or tail is still on screen. A missile is one cheap model
     * per client; it is always drawn.
     */
    @Override
    public boolean shouldRender(InfraredMissileEntity entity, Frustum frustum, double camX, double camY,
                                double camZ) {
        return true;
    }

    @Override
    public ResourceLocation getTextureLocation(InfraredMissileEntity entity) {
        return this.texture;
    }
}

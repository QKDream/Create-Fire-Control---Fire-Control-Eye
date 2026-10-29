package com.qkdream.firecontrolcompat;

import com.hooya.stabilizedturret.content.controller.MianbaoMissileCompat;
import com.qkdream.firecontrolcompat.entity.HeavyAirDefenseMissileEntity;
import com.qkdream.firecontrolcompat.entity.NevaAirDefenseMissileEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Per-round flight readout for the two rounds fire control's own air-defense guidance flies:
 * the heavy 48N6 and the super-heavy Neva.
 *
 * <h2>Why a line of this mod's own was needed</h2>
 *
 * <p>{@code MianbaoAirDefenseGuidanceHandler} adopts a round, locks a <b>sub-level</b> as its
 * contact, and then flies it in world coordinates through {@code ActiveRadarSeeker}. When the
 * operator reports "the heavy round does not hit anything on a Sable structure" the log had
 * <b>no line at all</b> for that chain: the round's own guidance is silent (fire control owns the
 * steering), the pack prints nothing per round, and the only trace was the round's eventual
 * {@code missile remove}. That is the same blind spot the infrared anti-tank round had before
 * {@code ir-atgm nav:} existed - "it is aimed at the wrong point" and "it is aimed at nothing"
 * looked identical.</p>
 *
 * <p>This line names both ends of the vector, the space each end is expressed in, and the distance
 * between them, so a next session log answers in one glance:</p>
 *
 * <pre>
 * heavy-nav: type=heavy own=aa pos=(x,y,z) target=(x,y,z) space=world|plot|mixed
 *            fix=none|target&gt;world|round&gt;world dist=.. speed=.. age=.. armed=.. tgt=..
 * </pre>
 *
 * <p>Readings:</p>
 * <ul>
 *   <li>{@code own=} which of the pack's two drivers holds the round: {@code aa} (computer),
 *       {@code display} (connected display), {@code crew} (a manual release), or {@code none} -
 *       and {@code none} with {@code reason=not-adopted} is the answer to "it never even tried":
 *       the computer's AA guidance refuses to adopt a round it cannot tie to a bound launcher
 *       within five blocks, or that was launched while its radar had no locked sub-level.</li>
 *   <li>{@code target=} is the contact the pack's seeker currently carries
 *       ({@code CreateFireControlActiveRadarSeeker} in the round's persistent data, written by the
 *       pack itself) - not this mod's guess.</li>
 *   <li>{@code space=} is <b>per end</b>: a stored (plot) coordinate and a world coordinate differ
 *       by about twenty million blocks, and a distance measured across the two is meaningless.
 *       {@code mixed} means one end had to be projected to make the pair comparable, and
 *       {@code fix=} says which end was moved. A {@code dist} that shrinks every line is the whole
 *       acceptance criterion for "it flies at the target"; a {@code dist} stuck in the millions is
 *       the space fault, and {@code spatial=} then names both raw readings.</li>
 * </ul>
 *
 * <p>Rate limited by a counter in the round's own persistent data (the first line of a round always
 * prints, then one every {@link #EVERY} ticks). Never by a subtraction from {@code Long.MIN_VALUE}:
 * that wraps and silences the line forever, which has already cost this project one false negative.</p>
 */
public final class HeavyAaNav {

    /** How often a round reports, in ticks. */
    private static final int EVERY = 20;

    /** Counter tag; {@code 0} means "this round has not printed yet". */
    private static final String COUNT_TAG = "firecontrolcompat:nav_count";

    /** A pair of readings further apart than this is two different spaces, not a long shot. */
    private static final double SPACE_SUSPECT = 20000.0;

    private HeavyAaNav() {
    }

    /** Whether this round is one of the two the pack's air-defense guidance flies. */
    public static boolean isAaRound(Entity entity) {
        return entity instanceof HeavyAirDefenseMissileEntity
                || entity instanceof NevaAirDefenseMissileEntity;
    }

    /** One throttled line for the round, if it is one of ours. Never throws. */
    public static void report(Entity round) {
        if (!isAaRound(round) || round == null || round.level() == null || round.level().isClientSide()) {
            return;
        }
        try {
            CompoundTag data = round.getPersistentData();
            int count = data.getInt(COUNT_TAG) + 1;
            if (count > 1 && Integer.remainderUnsigned(count, EVERY) != 0) {
                return;
            }
            data.putInt(COUNT_TAG, count);

            Vec3 rawPos = round.position();
            Vec3 worldPos = MianbaoMissileCompat.getWorldPosition(round);
            if (worldPos == null || !finite(worldPos)) {
                worldPos = rawPos;
            }
            boolean posPlot = SubLevelPositions.inPlotSpace(round.level(), rawPos);

            // The contact the pack's seeker carries, straight out of its own saved snapshot.
            Vec3 storedTarget = null;
            String targetId = "-";
            CompoundTag seeker = data.getCompound("CreateFireControlActiveRadarSeeker");
            if (seeker.getBoolean("HasMemory")) {
                storedTarget = new Vec3(seeker.getDouble("PX"), seeker.getDouble("PY"),
                        seeker.getDouble("PZ"));
            }
            if (data.hasUUID("CreateFireControlAaTarget")) {
                targetId = data.getUUID("CreateFireControlAaTarget").toString().substring(0, 8);
            }

            // Make the pair comparable: try the contact as it is, then projected out of the plot it
            // may have been stored in. Whichever reading is nearer the round is the one that meant
            // something; a reading twenty million blocks away can never win.
            String fix = "none";
            Vec3 target = storedTarget;
            if (storedTarget != null && finite(storedTarget)) {
                Vec3 asIs = storedTarget;
                Vec3 projected = SubLevelPositions.worldPosition(round.level(), storedTarget);
                double asIsDist = asIs.distanceTo(worldPos);
                double projectedDist = projected.distanceTo(worldPos);
                if (projected != null && finite(projected) && projectedDist < asIsDist) {
                    target = projected;
                    fix = "target>world";
                } else {
                    target = asIs;
                    if (asIsDist > SPACE_SUSPECT && posPlot) {
                        // The round itself is the end that is stored: nothing to project on the
                        // target side, so say so and measure from the world reading of the round,
                        // which is what fire control's own guidance uses.
                        fix = "round>world";
                    }
                }
            }
            boolean targetPlot = target != null && finite(target)
                    && SubLevelPositions.inPlotSpace(round.level(), target);
            String space = posPlot ? (targetPlot ? "plot" : "mixed") : (targetPlot ? "mixed" : "world");

            String own = "none";
            String reason = "-";
            if (data.getBoolean("CreateFireControlAaGuided")) {
                own = "aa";
            } else if (data.getBoolean("CreateFireControlDisplayGuided")) {
                own = "display";
            } else if (data.getBoolean("CreateFireControlGuided")) {
                own = "crew";
            } else if (round.tickCount > 20) {
                reason = "not-adopted";
            }

            double speed = round.getDeltaMovement() == null ? 0.0 : round.getDeltaMovement().length();
            String dist = target == null ? "-"
                    : String.format(Locale.ROOT, "%.1f", target.distanceTo(worldPos));
            FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] heavy-nav: type={} own={} reason={} pos={} raw={} space={} fix={}"
                            + " target={} dist={} speed={} age={} armed={} tgt={}",
                    nameOf(round), own, reason, format(worldPos), format(rawPos), space, fix,
                    target == null ? "-" : format(target), dist,
                    String.format(Locale.ROOT, "%.2f", speed), round.tickCount,
                    armed(round) ? "true" : "false", targetId);
        } catch (Throwable failure) {
            FireControlCompat.LOGGER.info("[firecontrolcompat] heavy-nav: line skipped: {}",
                    String.valueOf(failure));
        }
    }

    /** Whether the round carries the safety fuse armed, without reaching into its internals. */
    private static boolean armed(Entity round) {
        try {
            return com.qkdream.firecontrolcompat.entity.BeamRidingMissileEntity
                    .safetyFuseArmed(round);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String nameOf(Entity round) {
        if (round instanceof NevaAirDefenseMissileEntity) {
            return "neva";
        }
        if (round instanceof HeavyAirDefenseMissileEntity) {
            return "heavy";
        }
        try {
            ResourceLocation key = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                    .getKey(round.getType());
            return key == null ? "?" : key.getPath();
        } catch (Throwable ignored) {
            return "?";
        }
    }

    private static String format(Vec3 vector) {
        return String.format(Locale.ROOT, "%.1f,%.1f,%.1f", vector.x, vector.y, vector.z);
    }

    private static boolean finite(Vec3 vector) {
        return vector != null && Double.isFinite(vector.x) && Double.isFinite(vector.y)
                && Double.isFinite(vector.z);
    }

    /**
     * The levels the fuze of a round has to look in.
     *
     * <p>A round launched off a structure lives in that structure's plot level for as long as fire
     * control's mathematical flight has not moved it out, and a round it <em>has</em> moved lives in
     * the main level. A contact can be in either, and {@code Level#getEntities} only ever answers for
     * one of them, so the fuze asks both. The main level is always the last entry, because that is
     * where fire control's own proximity fuze looks and where the contact of a structure target is
     * resolved.</p>
     */
    public static List<ServerLevel> fuzeLevels(Entity round) {
        List<ServerLevel> levels = new ArrayList<>(2);
        try {
            if (round.level() instanceof ServerLevel own) {
                levels.add(own);
            }
        } catch (Throwable ignored) {
            // no own level: the root below is still worth asking
        }
        try {
            ServerLevel root = MianbaoMissileCompat.getRootServerLevel(round);
            if (root != null && !levels.contains(root)) {
                levels.add(root);
            }
        } catch (Throwable ignored) {
            // no root: the own level above is all there is
        }
        return levels;
    }
}

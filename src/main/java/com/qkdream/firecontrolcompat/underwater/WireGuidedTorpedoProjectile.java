package com.qkdream.firecontrolcompat.underwater;

import com.qkdream.firecontrolcompat.FireControlCompat;
import com.qkdream.firecontrolcompat.PhysicsGroups;
import com.qkdream.firecontrolcompat.sonar.SonarLog;
import dev.ryanhcode.sable.sublevel.SubLevel;
import java.util.UUID;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The wire-guided torpedo: the acoustic round's hull, swim and fuze, steered by
 * a wire instead of by its own ears.
 *
 * <p>Where the passive round hunts the loudest thing it can hear, this one is
 * put on a wire the moment it leaves the tube: the source lying on the line the
 * shot was aimed down - the one the layer was pointing at - is taken then and
 * there, and the run drives at it and at nothing else. Fresh noises from that
 * same source keep the bearing good, so a ship under way is followed; a noise
 * from anywhere else is ignored, however loud it is, because the wire only
 * leads to one place.</p>
 *
 * <p>The wire is also the round's life. A boat that stops making noise is a
 * boat the wire can no longer steer to, and a torpedo that has lost its wire
 * with a live warhead in it is not left swimming: it scuttles itself, which is
 * what a boat's crew would do with it. The same goes for a shot put into empty
 * water - a launch with nothing on the line is a launch with nothing to lead
 * it, and the run goes down by its own hand.</p>
 */
public class WireGuidedTorpedoProjectile extends AcousticTorpedoProjectile {

    /**
     * How far off the sight line a source may sit and still be taken. The wire is
     * laid on what the aim is <em>on</em> and not on what is merely near it: a
     * noise has to lie on the line to the block, or the line has to land on the
     * hull the noise was made aboard.
     */
    private static final double WIRE_LINE_GAP = 1.5D;
    /** Ticks after the launch the wire may still find the source it was aimed at. */
    private static final int WIRE_ACQUIRE_TICKS = 40;
    /** Ticks a wired source may stay quiet before the run scuttles itself. */
    private static final int WIRE_SILENCE_TICKS = 100;
    /** A fresh noise this close to the last bearing is the same source in open water. */
    private static final double WIRE_SAME_SOURCE = 12.0D;
    /** How far a noise may sit from a hull and still be filed as that hull's. */
    private static final double WIRE_HULL_INFLATE = 2.0D;
    /**
     * A noise this close to the launch is the shot's own bang, or the boat that
     * fired it: the wire is never laid to either of them.
     */
    private static final double WIRE_LAUNCH_CLEARANCE = 10.0D;
    /**
     * How far the wire can reach: a thousand blocks of water, which is the whole
     * sea a shot can be laid across from a fire control computer. The passive
     * round's own ears stop at three hundred; a wired run is told where to go.
     */
    private static final double WIRE_RANGE = 1000.0D;

    /** The course the round was thrown down - the line the aim ran along. */
    private Vec3 aimLine;
    /** The source the wire leads to: where it was last heard, and the hull it sits on. */
    private Vec3 wireAt;
    private UUID wireBody;
    /** Tick of the last noise from that source, so silence can be measured. */
    private int wireTick;
    /** Set once the wire has parted and the run is going down. */
    private boolean wireCut;
    /** Set when the wire was laid by an aiming computer rather than by ear. */
    private boolean wireDesignated;

    /**
     * The mark a round carries when it is standing in for 面包学's own visually
     * guided round: a CBCMS tube only throws CBCMS rounds, so the round that is
     * in the tube is one of ours, and this is what tells it to hand over.
     */
    public static final String VISUAL_TAG = "firecontrolcompat:visual_torpedo";
    /** Whose eyes the round that takes over swims towards. */
    public static final String VISUAL_OWNER_TAG = "firecontrolcompat:visual_owner";

    /** Whether this round is a carrier for 面包学's visually guided round. */
    private boolean visualRound;
    /** The player the round that takes over answers to, as a uuid, or empty. */
    private String visualOwner = "";

    public WireGuidedTorpedoProjectile(EntityType<? extends WireGuidedTorpedoProjectile> type, Level level) {
        super(type, level);
    }

    /**
     * Makes this round the carrier for 面包学's visually guided round: the first
     * tick of its swim hands that round over instead of swimming itself.
     */
    public void carryVisualRound(String owner) {
        this.visualRound = true;
        this.visualOwner = owner == null ? "" : owner;
    }

    /** The eyes the round that takes over swims towards, as a uuid, or empty. */
    public String visualOwner() {
        return this.visualOwner;
    }

    @Override
    public void tick() {
        if (this.visualRound) {
            if (!this.level().isClientSide) {
                try {
                    VisualTorpedoRelease.release(this);
                } catch (Throwable ignored) {
                    this.discard();
                }
            }
            return;
        }
        super.tick();
    }

    /**
     * Lays the wire before the round is even swimming, on the hull the aiming
     * computer has locked. The tube answers to that computer, so the round is
     * handed its target instead of having to find one by ear - and a hull that
     * never makes a sound, sitting still in harbour, is still run down.
     */
    public void designate(UUID body, Vec3 at) {
        if (at == null || this.wireCut) {
            return;
        }
        this.wireAt = at;
        this.wireBody = body;
        this.wireDesignated = true;
        this.wireTick = this.tickCount;
        this.contact = at;
        FireControlCompat.LOGGER.info("[firecontrolcompat] wire-guided torpedo laid on the target its computer locked, {} blocks ahead",
                Math.round(Math.sqrt(at.distanceToSqr(this.position()))));
    }

    /** Whether the wire was laid by a computer rather than found by ear. */
    public boolean designated() {
        return this.wireDesignated;
    }

    @Override
    protected String logName() {
        return "wire-guided torpedo";
    }

    /**
     * Whether the hydrophones are on yet. A round that already has a wire has
     * something to steer on from its first tick and never waits for an arming
     * run; one hunting by ear follows the same arming distance the passive round
     * does, so it does not take its own launch for a contact.
     */
    @Override
    protected boolean listening() {
        return this.wireAt != null || super.listening();
    }

    /**
     * The bearing the run steers on: the source on the wire, kept fresh by the
     * noises that source makes, and never swapped for a louder one elsewhere.
     */
    @Override
    protected Vec3 contact() {
        if (this.wireCut) {
            return null;
        }
        if (this.tickCount % SEARCH_PERIOD != 0) {
            return this.contact;
        }
        if (this.wireAt == null) {
            return this.acquire();
        }
        Vec3 fresh = this.follow();
        if (fresh != null) {
            this.wireTick = this.tickCount;
            this.wireAt = fresh;
            this.contact = fresh;
            return this.contact;
        }
        if (this.wireDesignated) {
            // A wire laid by the aiming computer leads to a hull that is being
            // watched rather than to a noise that has to keep sounding, so she
            // is read off the world where she stands. The run is never cut
            // short for silence: a boat that has stopped her engines, or never
            // started them, is still the boat on the wire.
            Vec3 live = this.liveHull();
            if (live != null) {
                this.wireAt = live;
            }
            this.contact = this.wireAt;
            return this.contact;
        }
        if (this.tickCount - this.wireTick > WIRE_SILENCE_TICKS) {
            this.scuttle("the source went quiet");
            return null;
        }
        this.contact = this.wireAt;
        return this.contact;
    }

    /**
     * Puts the run on the wire: of everything the water is carrying, the source
     * the sight line is actually on is the one taken - a noise lying on the line
     * itself, or one made aboard a hull the line lands on. A line with nothing on
     * it leaves the round with nothing to steer on, and once the chance to
     * acquire has passed the run scuttles itself rather than swim on at random.
     */
    private Vec3 acquire() {
        Vec3 line = this.aimLine();
        if (line == null) {
            return null;
        }
        Vec3 launchFrom = this.launchPoint == null ? this.position() : this.launchPoint;
        Vec3 best = null;
        double bestGap = Double.MAX_VALUE;
        for (SonarLog.Pulse pulse : SonarLog.recent(this.level(), HEARD_WINDOW_TICKS, MAX_HEARD)) {
            if (this.getUUID().equals(pulse.owner()) || this.isOwnPlatform(pulse.position())) {
                continue;
            }
            Vec3 at = pulse.position();
            double distance = at.distanceTo(this.position());
            if (distance < 1.0D || distance > WIRE_RANGE) {
                continue;
            }
            if (at.distanceTo(launchFrom) < WIRE_LAUNCH_CLEARANCE) {
                continue;
            }
            double gap = this.gapFromLine(launchFrom, line, at);
            if (gap < 0.0D || gap >= bestGap) {
                continue;
            }
            bestGap = gap;
            best = at;
        }
        if (best == null) {
            if (this.tickCount > WIRE_ACQUIRE_TICKS) {
                this.scuttle("nothing on the wire");
            }
            return null;
        }
        this.wireAt = best;
        this.wireBody = this.bodyAt(best);
        this.wireTick = this.tickCount;
        this.contact = best;
        FireControlCompat.LOGGER.info("[firecontrolcompat] wire-guided torpedo took the source {} blocks ahead, {} blocks off the sight line{}",
                Math.round(Math.sqrt(best.distanceToSqr(this.position()))),
                Math.round(bestGap * 10.0D) / 10.0D,
                bestGap <= WIRE_LINE_GAP ? "" : " (aboard the hull the sight is on)");
        return this.contact;
    }

    /**
     * How far the source lies off the sight line, or a negative number when the
     * sight is not on it at all. Nothing behind the muzzle counts, and nothing
     * merely near the line does either: a noise has to lie on the line to within
     * {@link #WIRE_LINE_GAP} blocks, or the line has to land on the hull the
     * noise was made aboard - which is what pointing at her means for a noise
     * made deep inside a ship.
     */
    private double gapFromLine(Vec3 launchFrom, Vec3 line, Vec3 at) {
        Vec3 bearing = at.subtract(launchFrom);
        if (bearing.lengthSqr() < 1.0E-6D || bearing.dot(line) <= 0.0D) {
            return -1.0D;
        }
        double gap = bearing.cross(line).length();
        if (gap <= WIRE_LINE_GAP) {
            return gap;
        }
        AABB hull = this.hullBoxAt(at);
        if (hull == null || hull.clip(launchFrom, launchFrom.add(line.scale(WIRE_RANGE))).isEmpty()) {
            return -1.0D;
        }
        return gap;
    }

    /** The box the hull a noise was made aboard stands in, or null in open water. */
    private AABB hullBoxAt(Vec3 at) {
        try {
            for (UUID body : PhysicsGroups.bodiesContaining(this.level(), at, WIRE_HULL_INFLATE)) {
                SubLevel hull = body == null ? null : PhysicsGroups.resolve(this.level(), body);
                if (hull != null) {
                    return hull.boundingBox().toMojang();
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** The newest noise from the source on the wire, or null while it is quiet. */
    private Vec3 follow() {
        Vec3 best = null;
        double nearest = Double.MAX_VALUE;
        for (SonarLog.Pulse pulse : SonarLog.recent(this.level(), HEARD_WINDOW_TICKS, MAX_HEARD)) {
            if (this.getUUID().equals(pulse.owner()) || this.isOwnPlatform(pulse.position())) {
                continue;
            }
            Vec3 at = pulse.position();
            if (at.distanceTo(this.position()) > WIRE_RANGE || !this.sameSource(at)) {
                continue;
            }
            double gap = at.distanceToSqr(this.wireAt);
            if (gap < nearest) {
                nearest = gap;
                best = at;
            }
        }
        return best;
    }

    /**
     * Whether a fresh noise is the source the wire holds. A noise on the same
     * hull is the same quarry however far she has run; a source in open water -
     * a swimmer, another torpedo - is followed by where the wire last heard it.
     */
    private boolean sameSource(Vec3 at) {
        if (this.wireBody != null) {
            return this.wireBody.equals(this.bodyAt(at));
        }
        return at.distanceToSqr(this.wireAt) <= WIRE_SAME_SOURCE * WIRE_SAME_SOURCE;
    }

    /** The hull a noise sits on, or null when it was made in open water. */
    private UUID bodyAt(Vec3 at) {
        try {
            for (UUID body : PhysicsGroups.bodiesContaining(this.level(), at, WIRE_HULL_INFLATE)) {
                if (body != null) {
                    return body;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Where the hull the computer named stands now, or null once she is gone. */
    private Vec3 liveHull() {
        if (this.wireBody == null) {
            return null;
        }
        try {
            SubLevel hull = PhysicsGroups.resolve(this.level(), this.wireBody);
            return hull == null ? null : hull.boundingBox().toMojang().getCenter();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** The course the round was thrown down: the line the aim ran along. */
    private Vec3 aimLine() {
        if (this.aimLine != null) {
            return this.aimLine;
        }
        Vec3 motion = this.getDeltaMovement();
        if (motion.lengthSqr() > 1.0E-6D) {
            this.aimLine = motion.normalize();
        }
        return this.aimLine;
    }

    /**
     * Cuts the wire. The warhead is live and there is nothing left to steer it
     * at, so the run takes itself down where it stands instead of swimming on.
     */
    private void scuttle(String why) {
        if (this.wireCut) {
            return;
        }
        this.wireCut = true;
        this.contact = null;
        FireControlCompat.LOGGER.info("[firecontrolcompat] wire-guided torpedo scuttled itself: {}", why);
        try {
            this.detonate(this.position());
        } catch (Throwable ignored) {
        }
        this.discard();
    }

    @Override
    public BlockState getRenderedBlockState() {
        return WireGuidedTorpedoes.WIRE_GUIDED_TORPEDO.get().defaultBlockState()
                .setValue(BlockStateProperties.FACING, Direction.NORTH);
    }
}

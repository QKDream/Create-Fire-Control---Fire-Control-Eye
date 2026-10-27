package com.qkdream.firecontrolcompat;

import com.qkdream.firecontrolcompat.entity.InfraredMissileEntity;
import com.qkdream.firecontrolcompat.laser.LaserSpots;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Position;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * The infrared anti-tank round: fired at a locked ground point, flown over the top of it, and
 * finished with the same shaped charge this mod's HEAT round uses.
 *
 * <h2>What it is</h2>
 *
 * <p>The user asked for a round that is <b>loaded into 面包学's anti-tank launcher</b>, that
 * <b>cannot be fired without an electro-optical lock</b>, that <b>climbs over its target and dives
 * onto the roof</b>, and whose <b>damage is the HEAT family this mod already has</b>. The airframe is
 * the user's own {@code spiker_nlos} model (NLOS = non line of sight, which is exactly the
 * over-the-top profile), so the flight profile and the warhead are the only things that had to be
 * built.</p>
 *
 * <h2>The lock</h2>
 *
 * <p>The lock is the mod's existing designation chain rather than a second one:
 * {@link LaserSpots} is what a pod paints when the crew designates something through the optical
 * head, and {@link #paintedPoint} asks it for the spot nearest the launcher. No spot means no shot -
 * the trigger is not just wasted, it is refused, because a round that is fired blind would climb over
 * nothing and dive on nothing.</p>
 *
 * <h2>The profile</h2>
 *
 * <p>Climb until the round is over the target or close enough to break over, then dive:
 * the aim point is {@code (x, y + CLIMB_HEIGHT, z)} of the locked point while the horizontal range
 * is longer than {@link #PULLDOWN_RANGE} and the round is still below the ceiling, and the locked
 * point itself afterwards. That is the user's own rule - "climb until horizontal distance &lt; X or
 * height &gt; Y, then dive" - with X and Y as the two constants here.</p>
 *
 * <h2>The warhead</h2>
 *
 * <p>HEAT, and the mod's own HEAT at that: {@code MetalJetProjectile} is the shaped-charge jet the
 * 破甲 round fires, so the round spawns one along its own flight direction when it detonates. That
 * reuses the penetration, the mass and the post-penetration burn that family was tuned for instead
 * of inventing a second damage model next to it. The call is made reflectively because that class
 * lives in the CBC Modern Warfare compat package and this mod has to keep loading without it.</p>
 */
public final class IrAtgm {

    private static final String PREFIX = "[firecontrolcompat] ";

    /** Marker on a round that was armed as this weapon. */
    public static final String TOP_ATTACK_TAG = "firecontrolcompat:ir_atgm";

    /**
     * The one name this round is shown under, on every slot it can occupy.
     *
     * <p>Both weapon lines 0.9.4 draws are named after the <b>launcher</b> rather than the round -
     * the ground anti-tank slot reads "反坦克导弹" and the aircraft air-to-ground slot reads
     * "普通空对地导弹" - so a rack loaded with this round used to announce the wrong weapon in both
     * cockpits. Keeping the string here means the ground slot
     * ({@code SeekerHudServerSync}) and the air slot ({@code AtgmAirRack}) can never drift apart, and
     * the item's own name ({@code item.firecontrolcompat.ir_atgm}) says the same thing.</p>
     */
    public static final String DISPLAY_NAME = "红外反坦克导弹";

    /** The locked ground point the round was released against. */
    private static final String TARGET_X = "firecontrolcompat:atgm_x";
    private static final String TARGET_Y = "firecontrolcompat:atgm_y";
    private static final String TARGET_Z = "firecontrolcompat:atgm_z";

    /** Whether the round has already broken over and is diving. */
    private static final String DIVING_TAG = "firecontrolcompat:atgm_diving";

    /** The flight phase this round is in, as a {@link Phase} code, and how many ticks it has held it. */
    private static final String PHASE_TAG = "firecontrolcompat:atgm_phase";
    private static final String PHASE_TICKS_TAG = "firecontrolcompat:atgm_phase_ticks";

    /** How high above the locked point the round crosses the target, in blocks. */
    public static final double CLIMB_HEIGHT = 26.0D;

    /** Horizontal range at which the round stops climbing and dives, in blocks. */
    public static final double PULLDOWN_RANGE = 22.0D;

    /** Horizontal range inside which the seeker takes over for the last correction, in blocks. */
    public static final double TERMINAL_RANGE = 14.0D;

    /** The pitch of the pop-up leg, in degrees above the horizon. */
    public static final double CLIMB_PITCH_DEG = 65.0D;

    /** How far ahead of the round the climb leg's aim point sits, in blocks. */
    private static final double CLIMB_LEAD = 24.0D;

    /**
     * How fast this round may swing its nose round, in degrees per tick, per leg.
     *
     * <h2>Why a cap on top of the overload law</h2>
     *
     * <p>The base class steers by turning the velocity vector at most
     * {@code overloadG * 9.8 / 400 / speed} radians a tick, which is a sensible law for a Mach two
     * beam rider: at seventeen blocks a tick it is about a degree a tick. This round flies at
     * <b>one to two and a half</b> blocks a tick, and the same formula at that speed divides by a
     * tenth of the speed, so it allows tens of degrees a tick - and the wide-authority branch
     * multiplies that by eight again when the heading error passes twenty degrees. The result is
     * exactly what the user photographed: the round snaps from a sixty five degree climb onto the
     * dive in a couple of ticks and leaves a corner in the sky instead of an arc.</p>
     *
     * <p>So the leg's own rate limit is applied <b>on top</b> of the overload law: whatever the law
     * allows, no tick turns this round by more than the number below. Nothing about the aim points
     * changes; a leg change no longer teleports the heading either, because the delta is simply flown
     * out over the next few ticks. The user then asked for the steering to be <b>halved</b> again -
     * 8/6/10 was still snappier than a real round - so the limits are now half of what they were:</p>
     *
     * <ul>
     *   <li>{@link #CLIMB_TURN_DEGREES} - the pop-up; 8 -> <b>4</b> degrees a tick, so the sixty five
     *       degree climb is flown in about sixteen ticks instead of eight;</li>
     *   <li>{@link #CRUISE_TURN_DEGREES} - the level run in, the gentlest leg; 6 -> <b>3</b>;</li>
     *   <li>{@link #DIVE_TURN_DEGREES} - the dive, allowed to be crisper so the terminal correction
     *       still lands on the roof; 10 -> <b>5</b>.</li>
     * </ul>
     */
    public static final double CLIMB_TURN_DEGREES = 4.0D;

    /** Turn limit on the level run in, in degrees per tick. */
    public static final double CRUISE_TURN_DEGREES = 3.0D;

    /** Turn limit in the dive and the terminal correction, in degrees per tick. */
    public static final double DIVE_TURN_DEGREES = 5.0D;

    /** Ticks between two steering reports. */
    private static final int STEER_REPORT_INTERVAL = 10;

    /** Ticks this round has counted for its own steering report. */
    private static final String STEER_TICKS_TAG = "firecontrolcompat:atgm_steer_ticks";

    /** Ticks the climb leg may run before it breaks over whatever its height, in ticks. */
    public static final int CLIMB_TIMEOUT_TICKS = 200;

    /** Ticks between two "still flying this phase" lines once the transitions are past. */
    private static final int PHASE_REPORT_INTERVAL = 40;

    /**
     * Speed of each leg, in blocks per tick.
     *
     * <p>The profile was first slowed to 0.9 / 1.2 / 1.6 because the round inherited the infrared
     * round's Mach 1 (17 blocks/tick), which crossed a two hundred block shot in a dozen ticks and
     * made an over-the-top attack impossible to watch. The user then asked for the speed <b>back
     * up</b>, roughly in the same proportion on every leg, so the round is not a crawl at long
     * range - a thousand block lock has to be flown in a time a crew will wait for:</p>
     *
     * <ul>
     *   <li>{@link #CLIMB_SPEED} - the pop-up, the slowest leg; 0.9 -> <b>1.4</b> (x1.56);</li>
     *   <li>{@link #CRUISE_SPEED} - the run in over the target; 1.2 -> <b>2.0</b> (x1.67);</li>
     *   <li>{@link #DIVE_SPEED} - the dive, still the quickest leg; 1.6 -> <b>2.6</b> (x1.63).</li>
     * </ul>
     *
     * <p>Reach is checked against {@code IR_ATGM_LIFETIME_TICKS = 1200}: the round's whole lock
     * range is {@link #LOCK_RANGE} (1000 blocks), and a cruise leg alone covers 2.0 x 1200 = 2400
     * blocks, so a round released at the outer edge of the lock can still chase a target that kept
     * moving - the lifetime is deliberately <b>kept</b> rather than shortened, because shortening it
     * is what would cut a long pursuit short.</p>
     */
    public static final double CLIMB_SPEED = 1.4D;

    /** Run-in speed on the ceiling, in blocks per tick. */
    public static final double CRUISE_SPEED = 2.0D;

    /** Dive speed, in blocks per tick. */
    public static final double DIVE_SPEED = 2.6D;

    /** Ticks between two speed reports while the phase is held. */
    private static final int SPEED_REPORT_INTERVAL = 10;

    /**
     * Distance beyond which a lock cannot be real, in blocks.
     *
     * <p>Nothing this round can shoot at is this far away, so a distance above it means the round and
     * its aim point are stored in <b>different spaces</b>: a launcher standing on a Sable structure
     * keeps its blocks, and the round it spawns, in the structure's plot (about twenty million blocks
     * from where the structure is seen), while a lock read from the pod or the computer can be in
     * world coordinates - or the exact opposite. The guidance normalises the difference into a
     * heading, which lands nowhere near either point, so the round leaves the rail and flies off on a
     * line that has nothing to do with the target. This is the threshold that catches that, and it is
     * generous: the lock range is four figures and a mathematical flight is a few thousand blocks at
     * most.</p>
     */
    public static final double SPACE_SUSPECT_DISTANCE = 20000.0D;

    /** Ticks between two "where am I, where is it, which way am I pointing" lines. */
    private static final int NAV_REPORT_INTERVAL = 20;

    /** Ticks this round has counted for its own navigation report. */
    private static final String NAV_TICKS_TAG = "firecontrolcompat:atgm_nav_ticks";

    /** The last space correction applied, so a correction is logged the moment it happens. */
    private static final String NAV_FIX_TAG = "firecontrolcompat:atgm_nav_fix";

    /**
     * How far from the launcher a painted spot is still a lock, in blocks.
     *
     * <p>Raised from four hundred to a thousand at the user's request, and <b>only for this round</b>:
     * the constant is read by {@link #paintedSpot} alone, so the anti-air rounds, the beam rider and
     * everything else keep their own reach. A pod painting a target from a cockpit is routinely
     * several hundred blocks out - the user's own report had {@code nearest=622.7} - and the old
     * figure refused exactly those shots.</p>
     */
    public static final double LOCK_RANGE = 1000.0D;

    /**
     * Distance from which a watching client answers for this round through fire control's remote
     * proxy and this mod's fog-free mark rather than through the airframe itself, in blocks.
     *
     * <p>It is the floor of the handover in {@code client/MissileVisualSupport#horizon}, which takes
     * the smaller of it (risen to the client's fog wall) and the entity type's tracking range: a
     * round nearer than this is drawn by its own renderer, one further away is drawn as a mark
     * ({@code client/MissileFarMarker}). It lives here, on the common side, because the server logs
     * which of the two paths a shot is on ({@code ir-atgm remote: .. via=}) and cannot see a
     * client-only class.</p>
     */
    public static final double PROXY_HANDOVER_FLOOR = 96.0D;

    /** Damage of the round's own contact burst, before the jet is added. */
    public static final float BURST_STRENGTH = 2.0F;

    /**
     * The legs of the over-the-top flight, in the order they are flown.
     *
     * <p>The user's complaint was that the attack <b>did not look like an attack</b>: the round
     * went out flat and, near the target, tipped over. The cause was the profile itself - it aimed
     * at the point <em>above</em> the target from the first tick, so a shot at something three
     * hundred blocks away asked for a climb of twenty-six blocks over three hundred, which is a
     * five degree line: level flight with a wobble.</p>
     *
     * <p>So the flight is four legs now, and each one has its own aim:</p>
     *
     * <ol>
     *   <li><b>CLIMB</b> - straight after release the round pulls up to
     *       {@link #CLIMB_PITCH_DEG} degrees and holds that pitch, so the pop-up is a pop-up
     *       whatever the range is;</li>
     *   <li><b>CRUISE</b> - it levels off on the ceiling it just reached
     *       ({@code targetY + CLIMB_HEIGHT}) and runs in over the target;</li>
     *   <li><b>DIVE</b> - inside {@link #PULLDOWN_RANGE} it puts the nose on the lock itself and
     *       comes down on the roof;</li>
     *   <li><b>TERMINAL</b> - inside {@link #TERMINAL_RANGE} the seeker's own track is blended in
     *       so a target that drove during the climb is still met.</li>
     * </ol>
     */
    public enum Phase {
        /** Pulling up to the pop-up pitch, nose on the sky. */
        CLIMB,
        /** Level on the ceiling, running in over the target. */
        CRUISE,
        /** Nose on the locked point, coming down. */
        DIVE,
        /** Last correction, seeker and lock blended. */
        TERMINAL;

        /** The code stored on the round; {@code 0} means "not decided yet". */
        public int code() {
            return ordinal() + 1;
        }

        /** The phase a stored code names, or {@code null} when it names none. */
        public static Phase of(int code) {
            if (code <= 0 || code > values().length) {
                return null;
            }
            return values()[code - 1];
        }
    }

    private IrAtgm() {
    }

    /** Whether this round is the infrared anti-tank round. */
    public static boolean isTopAttack(Entity round) {
        try {
            return round != null && round.getPersistentData().getBoolean(TOP_ATTACK_TAG);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * The spot a pod has painted for this launcher right now, or null when nothing is designated.
     *
     * <p>{@link LaserSpots#find} with code {@code 0} accepts the nearest spot whatever its code: a
     * round released from a launcher bound to no computer still has to find the pod that is painting
     * beside it, which is the same rule the mod's laser rounds follow.</p>
     */
    public static Vec3 paintedPoint(Level level, BlockPos launcherPos, Vec3 muzzle) {
        LaserSpots.Spot spot = paintedSpot(level, launcherPos, muzzle);
        return spot == null ? null : spot.position();
    }

    /**
     * The painted spot itself, platform and all.
     *
     * <p>The anti-tank round has to tell a pod on a ground vehicle from a pod on an aircraft: the
     * first fires it off the launcher it is loaded in, the second hands it to the aircraft weapon
     * chain. Position alone cannot say which, so the whole {@link LaserSpots.Spot} is handed back
     * and the caller reads the platform off it.</p>
     */
    public static LaserSpots.Spot paintedSpot(Level level, BlockPos launcherPos, Vec3 muzzle) {
        if (level == null || muzzle == null) {
            return null;
        }
        try {
            int code = launcherPos == null ? 0 : LaserSpots.codeNear(level, Vec3.atCenterOf(launcherPos), 64.0D);
            LaserSpots.Spot spot = LaserSpots.find(code, muzzle, LOCK_RANGE);
            if (spot == null) {
                spot = LaserSpots.find(0, muzzle, LOCK_RANGE);
            }
            if (spot == null || spot.position() == null || spot.blocked()) {
                return null;
            }
            return spot;
        } catch (Throwable failure) {
            FireControlCompat.LOGGER.info("{}ir-atgm: lock lookup failed: {}", PREFIX,
                    failure.toString());
            return null;
        }
    }

    /** Writes the locked point and the warhead onto a freshly created round. */
    public static void armAtgm(InfraredMissileEntity round, Vec3 target) {
        if (round == null || target == null) {
            return;
        }
        CompoundTag data = round.getPersistentData();
        data.putBoolean(TOP_ATTACK_TAG, true);
        data.putDouble(TARGET_X, target.x);
        data.putDouble(TARGET_Y, target.y);
        data.putDouble(TARGET_Z, target.z);
        data.putBoolean(DIVING_TAG, false);
        // A round that is re-armed (or a round type re-used) starts its flight again from the
        // pop-up, so the phase state travels with the release and never with the entity.
        data.remove(PHASE_TAG);
        data.remove(PHASE_TICKS_TAG);
        data.remove(NAV_TICKS_TAG);
        data.remove(NAV_FIX_TAG);
        data.remove(STEER_TICKS_TAG);
        // The close-target decision is made once, here, from the release geometry: it is the only
        // moment the distance between the rail and the lock is known before the round moves.
        noteReleaseRange(round, target);
        round.setBaseDamage(round.getBaseDamage() + 6.0F);
    }

    /**
     * Horizontal distance inside which a top attack is not worth flying, in blocks.
     *
     * <h2>Why a short shot must not pop up</h2>
     *
     * <p>The four legs exist to put the round <b>above</b> the target and come down on its roof: the
     * pop-up climbs {@link #CLIMB_HEIGHT} (twenty-six blocks) and only then runs in. That is a good
     * shape for a long shot and a bad one for a close one. At twenty blocks the climb is steeper than
     * the range, the round spends its whole flight going up, and by the time it breaks over it is
     * past the target with the seeker looking at empty ground - which is what "it flew up and
     * missed" is. A shot this short is simply a direct one: point the nose at the lock and fly.</p>
     *
     * <p>Measured <b>horizontally</b>, not as a straight line: what makes a pop-up pointless is
     * having no run-in, and a launcher on a hill two hundred blocks above its target still has none
     * however long the straight line is.</p>
     *
     * <p><b>Why the limit is one hundred blocks (2026-10-27).</b> It was thirty. The user's report
     * was that anything but a knife-range shot still climbed, and a hundred blocks is where the
     * pop-up starts paying for itself: the climb is {@link #CLIMB_HEIGHT} (twenty-six blocks), so
     * below a hundred the round is still climbing for a quarter of the run-in and the terminal dive
     * is a short one. Everything from a hundred out flies the four legs exactly as before.</p>
     */
    public static final double CLOSE_TARGET_RANGE = 100.0D;

    /**
     * Height the close shot holds above the lock, tapering to nothing at the target.
     *
     * <p>The direct leg still gets a small lift: a flat line from a rail a metre off the ground to a
     * point on it clips terrain on the way and gives the fuze no room. Lifting by
     * {@code min(this, 0.35 * range)} — about four blocks at thirty, one and a bit at three — makes
     * the flight a shallow descent onto the target instead of a scrape along the ground, and it is
     * still nothing like a climb.</p>
     */
    private static final double CLOSE_LIFT_HEIGHT = 4.0D;

    /** Tag: this round was released inside {@link #CLOSE_TARGET_RANGE} and flies the direct leg. */
    private static final String CLOSE_TARGET_TAG = "firecontrolcompat:atgm_close";

    /** Tag: the horizontal release range, kept for the report line. */
    private static final String CLOSE_RANGE_TAG = "firecontrolcompat:atgm_close_range";

    /**
     * Decides the direct leg for a short shot, and says so once.
     *
     * <p>Called from {@link #armAtgm} only. A round that is re-armed for a long shot clears the flag
     * again, so the same entity type used for both cases cannot inherit the last release's shape.</p>
     */
    private static void noteReleaseRange(Entity round, Vec3 target) {
        try {
            Vec3 at = round.position();
            double horizontal = Math.hypot(target.x - at.x, target.z - at.z);
            boolean close = horizontal < CLOSE_TARGET_RANGE;
            CompoundTag data = round.getPersistentData();
            data.putBoolean(CLOSE_TARGET_TAG, close);
            data.putDouble(CLOSE_RANGE_TAG, horizontal);
            if (close) {
                FireControlCompat.LOGGER.info(
                        "{}ir-atgm: top-attack skipped reason=close-target dist={} limit={} target={} from={}",
                        PREFIX, String.format(Locale.ROOT, "%.1f", horizontal), CLOSE_TARGET_RANGE,
                        target, at);
            }
        } catch (Throwable ignored) {
            // a round that will not take the flag flies the four-leg shape, which is the old behaviour
        }
    }

    /** Whether this round was released close enough to skip the pop-up. */
    public static boolean isCloseTarget(Entity round) {
        try {
            return round != null && round.getPersistentData().getBoolean(CLOSE_TARGET_TAG);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** The locked point this round was released against, or null when it carries none. */
    public static Vec3 targetOf(Entity round) {
        if (!isTopAttack(round)) {
            return null;
        }
        CompoundTag data = round.getPersistentData();
        if (!data.contains(TARGET_X) || !data.contains(TARGET_Y) || !data.contains(TARGET_Z)) {
            return null;
        }
        Vec3 point = new Vec3(data.getDouble(TARGET_X), data.getDouble(TARGET_Y), data.getDouble(TARGET_Z));
        return finite(point) ? point : null;
    }

    /**
     * Moves the locked point this round was released against, without touching anything else.
     *
     * <p>Used when the round is handed to fire control's mathematical flight: that mode flies in
     * <b>world</b> coordinates, while a round released from a structure is spawned - and its point
     * worked out - in the structure's own stored coordinates. Both have to move together, or the
     * climb-and-dive maths would be comparing a point twenty million blocks away from the round.</p>
     */
    public static void retarget(Entity round, Vec3 target) {
        if (round == null || target == null || !finite(target)) {
            return;
        }
        try {
            CompoundTag data = round.getPersistentData();
            data.putDouble(TARGET_X, target.x);
            data.putDouble(TARGET_Y, target.y);
            data.putDouble(TARGET_Z, target.z);
        } catch (Throwable ignored) {
            // a round that will not take the write keeps the point it had
        }
    }

    /**
     * The aim point for one tick of this round's flight, with the top attack applied.
     *
     * <p>{@code base} is whatever the round's own seeker would fly at - the tracked contact, or
     * nothing at all while it is still searching. Both are superseded here: the locked point is the
     * whole reason the round was released, and it is a ground point a heat seeker cannot see at all,
     * so the round flies the lock and the seeker only refines it once the dive has begun.</p>
     *
     * <p>The phase is advanced here, once per tick, and each phase answers with its own aim point -
     * see {@link Phase} for why a single "fly at the point above the target" answer was not enough
     * to make the attack visible.</p>
     *
     * @return the point to steer at, or null to let the round keep its course
     */
    public static Vec3 topAttackPoint(Entity round, Vec3 base) {
        Aim aim = aimOf(round);
        if (aim == null || !aim.sane()) {
            // A lock that cannot be brought into the round's own space is not flown at: normalising
            // twenty million blocks of difference into a heading is how a round leaves the rail and
            // heads off into the map. Keeping the course is the safe answer, and the navigation line
            // says so on every report.
            return base;
        }
        Vec3 lock = aim.point();
        Phase previous = phaseOf(round);
        double dx = lock.x - round.getX();
        double dz = lock.z - round.getZ();
        double horizontal = Math.hypot(dx, dz);
        double ceiling = lock.y + CLIMB_HEIGHT;
        int held = phaseTicks(round, previous);
        if (isCloseTarget(round)) {
            // A short shot flies the direct leg: no pop-up, no run-in, no ceiling. The phase is
            // pinned to DIVE rather than decided tick by tick, so a round released at
            // twenty-nine blocks can never decide mid-flight that it has room to climb - the shape
            // is fixed at the rail, where the distance is known.
            Phase phase = Phase.DIVE;
            reportNav(round, aim, phase, horizontal);
            storePhase(round, phase, phase == previous ? held + 1 : 0);
            if (phase != previous || held % PHASE_REPORT_INTERVAL == 0) {
                reportPhase(round, phase, previous, lock, horizontal, ceiling);
            }
            return closeAim(lock, horizontal);
        }
        Phase phase = nextPhase(previous, round, lock, horizontal, ceiling, held);
        reportNav(round, aim, phase, horizontal);
        storePhase(round, phase, phase == previous ? held + 1 : 0);
        if (phase != previous) {
            reportPhase(round, phase, previous, lock, horizontal, ceiling);
        } else if (held % PHASE_REPORT_INTERVAL == 0) {
            // A phase that lasts must still be visible in the log: "the round never climbed" and
            // "the round climbed and the log only recorded the transition" have to be told apart.
            reportPhase(round, phase, previous, lock, horizontal, ceiling);
        }
        return aimFor(phase, round, lock, base, dx, dz, horizontal, ceiling);
    }

    /**
     * The lock, brought into the space the round is flying in.
     *
     * <p>A round released off a launcher standing on a Sable structure is spawned in the structure's
     * plot and then moved out to the world (or the other way round, depending on which side Sable
     * finishes on), while the lock it was armed with was read in whichever space the pod or the
     * computer happened to report. The two answers are twenty million blocks apart, and a heading
     * normalised out of that difference points nowhere: the round leaves the rail, turns onto it and
     * flies away from everything. That is the whole of the user's "it fires but it does not fly at
     * the target".</p>
     *
     * <p>So the point is offered in three readings - as stored, projected out of the sub-level, and
     * inverted into the sub-level the round itself stands in - and the one nearest the round wins,
     * which is the same rule the designation chain already uses. A point that is already in the
     * round's space is never moved (its other readings are twenty million blocks away and lose), so
     * this cannot corrupt a good lock. When a reading does win it is written back onto the round, so
     * the conversion happens once instead of on every tick.</p>
     */
    private static Aim aimOf(Entity round) {
        if (round == null) {
            return null;
        }
        Vec3 raw = targetOf(round);
        if (raw == null) {
            return null;
        }
        Vec3 position = round.position();
        double distance = raw.distanceTo(position);
        Vec3 point = raw;
        String fix = "none";
        try {
            Level level = round.level();
            if (level != null && distance > SPACE_SUSPECT_DISTANCE) {
                Vec3 world = SubLevelPositions.worldPosition(level, raw);
                Vec3 stored = SubLevelPositions.plotPosition(level, raw, position);
                double best = distance;
                if (world != null && world.distanceTo(position) < best - 1.0D) {
                    point = world;
                    best = world.distanceTo(position);
                    fix = "world";
                }
                if (stored != null && stored.distanceTo(position) < best - 1.0D) {
                    point = stored;
                    best = stored.distanceTo(position);
                    fix = "stored";
                }
                if (point != raw) {
                    distance = best;
                    retarget(round, point);
                }
            }
        } catch (Throwable ignored) {
            // a round that will not take the correction is flown on what it has
        }
        String space = "world";
        try {
            if (SubLevelPositions.inPlotSpace(round.level(), point)) {
                space = "sublevel";
            }
        } catch (Throwable ignored) {
            // the label is cosmetic; the distance is what decides
        }
        return new Aim(point, space, fix, distance <= SPACE_SUSPECT_DISTANCE);
    }

    /**
     * One navigation line, and the reason this session needed one.
     *
     * <p>The previous rounds of this feature were diagnosed from the phase line alone, which prints
     * how far the round still has to go horizontally but not <b>where the point it is aiming at
     * is</b>. A lock left in the wrong space therefore looked like "a round with a long way to go"
     * rather than "a round aiming at nothing". This line prints the round, its aim point, its own
     * heading and the space the aim point was read in, so the next report can be read at a glance:
     * a {@code dist} in the tens of millions with {@code fix=none} and {@code sane=false} is the
     * space bug, and a {@code dist} that falls by about a block a tick is a round on its way in.</p>
     *
     * <p>Throttled by a counter stored on the round, never by a subtraction from
     * {@code Long.MIN_VALUE}: the first tick of every round prints, then one line every
     * {@link #NAV_REPORT_INTERVAL} ticks, plus one line the moment a space correction lands.</p>
     */
    private static void reportNav(Entity round, Aim aim, Phase phase, double horizontal) {
        try {
            CompoundTag data = round.getPersistentData();
            int ticks = data.getInt(NAV_TICKS_TAG) + 1;
            data.putInt(NAV_TICKS_TAG, ticks);
            String previousFix = data.getString(NAV_FIX_TAG);
            boolean changed = !aim.fix().equals(previousFix);
            data.putString(NAV_FIX_TAG, aim.fix());
            if (!changed && ticks % NAV_REPORT_INTERVAL != 0) {
                return;
            }
            Vec3 heading = round.getDeltaMovement();
            FireControlCompat.LOGGER.info(
                    "{}ir-atgm nav: phase={} pos={} target={} dir={} space={} dist={} horiz={} fix={} sane={}",
                    PREFIX, phase == null ? "NONE" : phase.name(), round.position(), aim.point(),
                    heading.lengthSqr() < 1.0E-9D ? Vec3.ZERO : heading.normalize(),
                    aim.space(),
                    String.format(Locale.ROOT, "%.1f", aim.point().distanceTo(round.position())),
                    String.format(Locale.ROOT, "%.1f", horizontal),
                    aim.fix(), aim.sane());
        } catch (Throwable ignored) {
            // a diagnostic line is never worth a crash
        }
    }

    /** The aim point with the space it was read in, whether it had to be moved and whether it is usable. */
    private record Aim(Vec3 point, String space, String fix, boolean sane) {
    }

    /** Which of the four legs this tick belongs to. */
    private static Phase nextPhase(Phase previous, Entity round, Vec3 lock, double horizontal,
            double ceiling, int held) {
        double y = round.getY();
        boolean overTheTop = y >= ceiling;
        boolean closeEnough = horizontal <= PULLDOWN_RANGE;
        if (previous == null) {
            // First tick of the flight: a ground release starts with the pop-up; a release that is
            // already above its ceiling (a bomb bay at altitude) starts level.
            if (closeEnough) {
                return Phase.DIVE;
            }
            return overTheTop ? Phase.CRUISE : Phase.CLIMB;
        }
        switch (previous) {
            case CLIMB -> {
                if (closeEnough) {
                    return Phase.DIVE;
                }
                if (overTheTop || held >= CLIMB_TIMEOUT_TICKS) {
                    return Phase.CRUISE;
                }
                return Phase.CLIMB;
            }
            case CRUISE -> {
                if (closeEnough || y <= lock.y + 2.0D) {
                    return Phase.DIVE;
                }
                return Phase.CRUISE;
            }
            default -> {
                if (horizontal <= TERMINAL_RANGE || y <= lock.y + 3.0D) {
                    return Phase.TERMINAL;
                }
                return Phase.DIVE;
            }
        }
    }

    /**
     * The aim point of a close shot: the lock, held a little above it so the flight is a shallow
     * descent onto the target rather than a scrape along the ground.
     *
     * <p>The lift tapers with the remaining range ({@code min(CLOSE_LIFT_HEIGHT, 0.35 * range)}),
     * so a round released at twenty-nine blocks approaches at about eight degrees and one released
     * at three blocks approaches at almost nothing - and both arrive at the lock itself, which is
     * the point the pilot designated.</p>
     */
    private static Vec3 closeAim(Vec3 lock, double horizontal) {
        double lift = Math.min(CLOSE_LIFT_HEIGHT, Math.max(0.0D, horizontal) * 0.35D);
        return new Vec3(lock.x, lock.y + lift, lock.z);
    }

    /** The point each leg asks for. */
    private static Vec3 aimFor(Phase phase, Entity round, Vec3 lock, Vec3 base, double dx, double dz,
            double horizontal, double ceiling) {
        if (phase == null) {
            return base;
        }
        switch (phase) {
            case CLIMB -> {
                double pitch = Math.toRadians(CLIMB_PITCH_DEG);
                double horizontalShare = Math.cos(pitch);
                double hx;
                double hz;
                if (horizontal > 1.0E-3D) {
                    hx = dx / horizontal;
                    hz = dz / horizontal;
                } else {
                    // Straight overhead: keep the heading the round already has rather than pick one.
                    Vec3 velocity = round.getDeltaMovement();
                    double flat = Math.hypot(velocity.x, velocity.z);
                    hx = flat > 1.0E-6D ? velocity.x / flat : 0.0D;
                    hz = flat > 1.0E-6D ? velocity.z / flat : 1.0D;
                }
                return new Vec3(round.getX() + hx * horizontalShare * CLIMB_LEAD,
                        round.getY() + Math.sin(pitch) * CLIMB_LEAD,
                        round.getZ() + hz * horizontalShare * CLIMB_LEAD);
            }
            case CRUISE -> {
                // Level on the ceiling the pop-up reached, running in over the target.
                return new Vec3(lock.x, ceiling, lock.z);
            }
            case DIVE -> {
                return lock;
            }
            default -> {
                // Last correction: the lock, pulled toward the seeker's track so a moving target is
                // still hit.
                return base != null ? blend(lock, base) : lock;
            }
        }
    }

    /** The phase this round is flying, or {@code null} before the first tick decided it. */
    public static Phase phaseOf(Entity round) {
        try {
            return round == null ? null : Phase.of(round.getPersistentData().getInt(PHASE_TAG));
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Ticks the round has held {@code phase}, or {@code 0} when it has just entered it. */
    private static int phaseTicks(Entity round, Phase phase) {
        if (round == null || phase == null) {
            return 0;
        }
        try {
            return Math.max(0, round.getPersistentData().getInt(PHASE_TICKS_TAG));
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** Files the tick's phase on the round. */
    private static void storePhase(Entity round, Phase phase, int ticks) {
        if (round == null || phase == null) {
            return;
        }
        try {
            CompoundTag data = round.getPersistentData();
            data.putInt(PHASE_TAG, phase.code());
            data.putInt(PHASE_TICKS_TAG, Math.max(0, ticks));
            data.putBoolean(DIVING_TAG, phase == Phase.DIVE || phase == Phase.TERMINAL);
        } catch (Throwable ignored) {
            // a round that will not take the tag simply re-decides next tick
        }
    }

    /**
     * One line per leg, in the shape the crew reads it: which leg, how steep it is pointed, how high
     * the round is and how far it still has to go.
     */
    private static void reportPhase(Entity round, Phase phase, Phase previous, Vec3 lock,
            double horizontal, double ceiling) {
        try {
            double y = round.getY();
            double pitch;
            if (phase == Phase.CLIMB) {
                pitch = CLIMB_PITCH_DEG;
            } else if (phase == Phase.CRUISE) {
                pitch = Math.toDegrees(Math.atan2(ceiling - y, Math.max(horizontal, 1.0E-3D)));
            } else {
                pitch = Math.toDegrees(Math.atan2(lock.y - y, Math.max(horizontal, 1.0E-3D)));
            }
            FireControlCompat.LOGGER.info(
                    "{}ir-atgm: top-attack phase={} pitch={} y={} targetY={} ceiling={} dist={} from={} speed={} at={}",
                    PREFIX, phase, String.format(Locale.ROOT, "%+.1f", pitch),
                    String.format(Locale.ROOT, "%.1f", y),
                    String.format(Locale.ROOT, "%.1f", lock.y),
                    String.format(Locale.ROOT, "%.1f", ceiling),
                    String.format(Locale.ROOT, "%.1f", horizontal),
                    previous == null ? "-" : previous.name(),
                    String.format(Locale.ROOT, "%.2f", round.getDeltaMovement().length()),
                    round.position());
        } catch (Throwable ignored) {
            // a diagnostic line is never worth a crash
        }
    }

    /** Diving: the lock, pulled toward the seeker's track so a moving target is still hit. */
    private static Vec3 blend(Vec3 lock, Vec3 track) {
        return lock.add(track.subtract(lock).scale(0.5D));
    }

    /** The speed this round should be flying, from the leg it is on, in blocks per tick. */
    public static double speedFor(Entity round) {
        return speedFor(phaseOf(round));
    }

    /** The speed a leg is flown at, in blocks per tick. */
    public static double speedFor(Phase phase) {
        if (phase == null) {
            return CLIMB_SPEED;
        }
        return switch (phase) {
            case CLIMB -> CLIMB_SPEED;
            case CRUISE -> CRUISE_SPEED;
            case DIVE, TERMINAL -> DIVE_SPEED;
        };
    }

    /**
     * The turn this round may make in one tick, in degrees, or a non-positive number when the
     * entity is not this round.
     *
     * <p>Answered per leg, and only for a round carrying {@link #TOP_ATTACK_TAG}: every other round
     * of this mod's family keeps the base class's overload law untouched, so a change here can never
     * make an air-defence round or a beam rider turn worse. The caller clamps its own limit with this
     * one - never replaces it - so the tighter of the two always wins.</p>
     */
    public static double turnLimitDegrees(Entity round) {
        if (!isTopAttack(round)) {
            return -1.0D;
        }
        Phase phase = phaseOf(round);
        if (phase == null) {
            return CLIMB_TURN_DEGREES;
        }
        return switch (phase) {
            case CLIMB -> CLIMB_TURN_DEGREES;
            case CRUISE -> CRUISE_TURN_DEGREES;
            case DIVE, TERMINAL -> DIVE_TURN_DEGREES;
        };
    }

    /**
     * One steering line: the limit, the heading it turned from, the heading it turned to and how much
     * of the requested turn it was actually allowed.
     *
     * <p>This is the line that makes "no more corners" verifiable from a log rather than by eye: every
     * {@code turned} value is at or below its own {@code limit}, and a {@code wanted} far above the
     * limit with {@code turned} pinned at the limit is a leg change being flown out smoothly instead
     * of snapped. Throttled by a per-round counter (never by a subtraction from
     * {@code Long.MIN_VALUE}).</p>
     */
    public static void reportSteer(Entity round, Vec3 from, Vec3 to, double wantedDegrees,
            double limitDegrees) {
        if (round == null) {
            return;
        }
        try {
            CompoundTag data = round.getPersistentData();
            int ticks = data.getInt(STEER_TICKS_TAG) + 1;
            data.putInt(STEER_TICKS_TAG, ticks);
            if (ticks % STEER_REPORT_INTERVAL != 0) {
                return;
            }
            double turned = Math.toDegrees(Math.atan2(
                    from.cross(to).length(), Math.max(-1.0D, Math.min(1.0D, from.dot(to)))));
            FireControlCompat.LOGGER.info(
                    "{}ir-atgm steer: phase={} limit={}deg from={} to={} turned={}deg wanted={}deg at={}",
                    PREFIX, phaseOf(round) == null ? "NONE" : phaseOf(round).name(),
                    String.format(Locale.ROOT, "%.1f", limitDegrees), from, to,
                    String.format(Locale.ROOT, "%.2f", turned),
                    String.format(Locale.ROOT, "%.1f", wantedDegrees), round.position());
        } catch (Throwable ignored) {
            // a diagnostic line is never worth a crash
        }
    }

    /**
     * Puts the round on the speed of its leg, and reports it.
     *
     * <p>The speed is <b>set</b> rather than ramped: the base class's acceleration ramp only ever
     * speeds a round up, and this round is slower than the round it is derived from, so a ramp
     * would leave it coasting at the infrared round's launch speed for a hundred ticks. Setting the
     * magnitude each tick also holds the dive speed through the fire control's own drag.</p>
     *
     * <p>The report is <b>per leg</b>: a line every {@link #SPEED_REPORT_INTERVAL} ticks plus one on
     * every change, so a log can be read as "this shot climbed at nine tenths of a block a tick and
     * came down at one and six tenths", which is the whole point of the request.</p>
     */
    public static void applySpeed(Entity round) {
        if (round == null) {
            return;
        }
        try {
            Phase phase = phaseOf(round);
            double wanted = speedFor(phase);
            Vec3 velocity = round.getDeltaMovement();
            double measured = velocity.length();
            if (measured > 1.0E-4D) {
                round.setDeltaMovement(velocity.scale(wanted / measured));
            }
            int held = phaseTicks(round, phase);
            if (held % SPEED_REPORT_INTERVAL == 0) {
                FireControlCompat.LOGGER.info("{}ir-atgm speed: phase={} v={} real={} y={} at={}",
                        PREFIX, phase == null ? "NONE" : phase.name(),
                        String.format(Locale.ROOT, "%.2f", wanted),
                        String.format(Locale.ROOT, "%.2f", measured),
                        String.format(Locale.ROOT, "%.1f", round.getY()), round.position());
            }
        } catch (Throwable ignored) {
            // a round that will not take a speed keeps the one it has
        }
    }

    /**
     * The shaped charge, along the round's own flight direction.
     *
     * <p>Reflective on purpose: {@code MetalJetProjectile} is ours but it extends a CBC Modern
     * Warfare class, so a world without that mod must not fail to load this one. A missing jet is
     * reported and the round's own burst (already detonated by the caller) stands on its own.</p>
     */
    public static void heatWarhead(Entity round, Vec3 position) {
        if (round == null || position == null || !(round.level() instanceof ServerLevel level)) {
            return;
        }
        Vec3 nose = round.getDeltaMovement();
        if (nose == null || nose.lengthSqr() < 1.0E-6) {
            nose = new Vec3(0.0D, -1.0D, 0.0D);
        }
        try {
            Class<?> jet = Class.forName(
                    "com.qkdream.firecontrolcompat.compat.cbcmodernwarfare.entity.MetalJetProjectile");
            java.lang.reflect.Method spawn = jet.getMethod("spawnFrom", Level.class, Position.class,
                    Vec3.class, Entity.class);
            Object created = spawn.invoke(null, level, position, nose.normalize(), round);
            FireControlCompat.LOGGER.info(
                    "{}ir-atgm: damage=heat jet={} at={} dir={} target={}",
                    PREFIX, created != null, position,
                    nose.normalize(), targetOf(round));
        } catch (Throwable failure) {
            FireControlCompat.LOGGER.info(
                    "{}ir-atgm: damage=heat jet=unavailable at={} reason={}",
                    PREFIX, position, failure.toString());
        }
    }

    /** One line per release, so a shot that never climbs can be told from one that never fired. */
    public static void reportLaunch(Entity round, Vec3 target, boolean topAttack) {
        FireControlCompat.LOGGER.info(
                "{}ir-atgm: locked=true target={} launch={} top-attack={} damage=heat round={}",
                PREFIX, target, round == null ? null : round.position(), topAttack,
                round == null ? "-" : Integer.toString(round.getId()));
    }

    private static boolean finite(Vec3 vector) {
        return Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }
}

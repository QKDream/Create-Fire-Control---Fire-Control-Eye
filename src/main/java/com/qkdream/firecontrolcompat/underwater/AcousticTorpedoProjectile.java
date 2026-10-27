package com.qkdream.firecontrolcompat.underwater;

import com.qkdream.firecontrolcompat.FireControlCompat;
import com.qkdream.firecontrolcompat.FireControlLeadSettings;
import com.qkdream.firecontrolcompat.PhysicsGroups;
import com.qkdream.firecontrolcompat.SonicPulse;
import com.qkdream.firecontrolcompat.sonar.SonarKind;
import com.qkdream.firecontrolcompat.sonar.SonarLog;
import com.cainiao1053.cbcmoreshells.index.CBCMSMunitionPropertiesHandlers;
import com.cainiao1053.cbcmoreshells.munitions.big_cannon.FuzedCannonTorpedoProjectile;
import com.cainiao1053.cbcmoreshells.munitions.big_cannon.config.TorpedoProjectilePropertiesComponent;
import com.cainiao1053.cbcmoreshells.munitions.big_cannon.config.TorpedoProperties;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Position;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import rbasamoyai.createbigcannons.CreateBigCannons;
import rbasamoyai.createbigcannons.config.CBCConfigs;
import rbasamoyai.createbigcannons.munitions.ProjectileContext;
import rbasamoyai.createbigcannons.munitions.ShellExplosion;
import rbasamoyai.createbigcannons.munitions.big_cannon.config.BigCannonFuzePropertiesComponent;
import rbasamoyai.createbigcannons.munitions.config.components.BallisticPropertiesComponent;
import rbasamoyai.createbigcannons.munitions.config.components.EntityDamagePropertiesComponent;

/**
 * The passive acoustic homing torpedo: a big cannon round that becomes a
 * listening torpedo once it is in the water.
 *
 * <p>Out of the water it is an ordinary shell - CBC's ballistics, CBC's fuze,
 * CBC's explosion - so it is fired, fuzed and flown by the gun that already
 * knows how to do all of that. Under the surface it swims: the motor pulls the
 * run up to its swimming speed, the water takes the vertical speed out of it
 * and the round is left very slightly light, so it holds its depth instead of
 * sinking. The steering gear then guides it at whatever the hydrophones are
 * holding on to.</p>
 *
 * <p>What it listens to is the same water the sonar sets listen to: every
 * noise this mod can hear - burners, running gear, gunfire, hulls pushing
 * water, other munitions under way - is filed in {@link SonarLog} with the
 * place it happened, and a round only has to read that list back. A source
 * counts as loud as its weight says and as faint as the square of its distance
 * says, so a ship running past close by is taken over a gun going off far away
 * and the other way round. The set reaches three hundred blocks, a whole bay's
 * worth of water: a gun on the far side of one is heard, so a run put in
 * anywhere in the same sea as its quarry has something to steer on. The one
 * thing it never chases is the platform it was fired from.</p>
 */
public class AcousticTorpedoProjectile extends FuzedCannonTorpedoProjectile {

    /** Speed the motor holds the run at under the surface, in blocks a tick. */
    private static final double SWIM_SPEED = 0.85D;
    /** How hard the motor and the water pull the run towards that speed. */
    private static final double MOTOR_DRAG = 0.07D;
    /** What the water takes out of the vertical speed, so the run keeps its depth. */
    private static final double DEPTH_DAMP = 0.16D;
    /** What is left of the round's weight in the water: it creeps towards the surface. */
    private static final double BUOYANCY = 0.0054D;

    /** How far the hydrophones reach: three hundred blocks of water around the run. */
    protected static final double HEARING_RANGE = 300.0D;
    /** A noise older than this has faded: two seconds of water. */
    protected static final int HEARD_WINDOW_TICKS = 40;
    /** Noises read back in one search. */
    protected static final int MAX_HEARD = 128;
    /** Ticks between two bearings: a source does not move faster than that. */
    protected static final int SEARCH_PERIOD = 2;
    /** How far the rudder swings the run around in one tick, hard over. */
    private static final double TURN_PER_TICK = Math.toRadians(6.0D);
    /** The bearing error the rudder is fully over for. Below it, helm eases off. */
    private static final double RUDDER_FULL_ANGLE = Math.toRadians(25.0D);
    /** The least helm a living bearing error is answered with. */
    private static final double RUDDER_MIN_FRACTION = 0.25D;
    /** The steepest climb or dive the depth gear asks of the run. */
    private static final double MAX_SLOPE = 0.20D;
    /** How much louder a heavier noise counts, by the weight of a sound. */
    private static final double[] LOUDNESS = {1.0D, 4.0D, 16.0D};
    /** Bodies within this of the launch are the platform the round came from, in blocks. */
    private static final double LAUNCH_NEIGHBOURHOOD = 6.0D;
    /** How far a noise may sit from a hull and still be filed as that hull's. */
    private static final double HULL_INFLATE = 2.0D;
    /** Ticks the motor runs; the run is over after that. */
    private static final int FUEL_TICKS = 1200;
    /** Ticks a bearing is held after the last noise: a gun fires in bursts. */
    private static final int CONTACT_MEMORY_TICKS = 200;
    /** How hard the depth gear lifts an idle run, and what damps the climb. */
    private static final double FLOAT_LIFT = 0.018D;
    private static final double FLOAT_DAMP = 0.22D;
    /** How hard the depth gear answers an error against the depth it is holding. */
    private static final double DEPTH_GAIN = 0.01D;
    /** The most the depth gear pulls up or down while cruising on station. */
    private static final double DEPTH_HOLD_MAX = 0.015D;
    /** How far above the run the top of the water is looked for, in blocks. */
    private static final int SURFACE_SCAN = 48;

    /** Where the round left its platform, and the hulls of that platform. */
    protected Vec3 launchPoint;
    private Set<UUID> platformBodies = Set.of();
    /** Top of the water over the run, read every few ticks while cruising. */
    private double waterTop = Double.NaN;
    private int waterTopTick = Integer.MIN_VALUE;
    /** The source the hydrophones are holding on to, or null while they hear nothing. */
    protected Vec3 contact;
    /** Whether a noise was ever heard, and when the latest one arrived. */
    private boolean heardNoise;
    private int lastHeardTick;
    /** The fuze the round was handed, so the launch can report whether it is armed. */
    private String fittedFuze = "";
    /**
     * What the computer this round answers to files for it: whether an idle run
     * holds a depth, how deep, and how far it swims before its ears are on. The
     * plain values stand in until a launch hands over the computer's own.
     */
    private boolean holdDepth = FireControlLeadSettings.torpedoHoldDepth();
    private double cruiseDepth = FireControlLeadSettings.torpedoDepth();
    private double arming = FireControlLeadSettings.torpedoActivationDistance();

    public AcousticTorpedoProjectile(EntityType<? extends AcousticTorpedoProjectile> type, Level level) {
        super(type, level);
        // The tube hands its own flight time over through setChargePower; a round
        // that never went through a tube - one blown up out of its block, say -
        // still gets the motor's full run instead of expiring on its first tick.
        this.ageRemaining = FUEL_TICKS;
    }

    /** One noise worth chasing, as the water carried it. */
    private record Source(Vec3 position, SonarKind kind) {
    }

    /**
     * Takes the swim the launcher read off the computer it answers to. A round
     * handed nothing - one blown out of its block, or fired by a tube that is
     * wired to no computer - keeps the plain values it was built with.
     */
    public void applyLaunchSettings(TorpedoLaunchSettings.Values values) {
        if (values == null) {
            return;
        }
        this.holdDepth = values.holdDepth();
        this.cruiseDepth = FireControlLeadSettings.clampTorpedoDepth(values.depth());
        this.arming = FireControlLeadSettings.clampTorpedoActivation(values.activation());
    }

    /**
     * Takes the fuze the gun read off the round and remembers it, so the run
     * reports what it carries on its first tick: a round that leaves a tube
     * with no fuze at all swims to the end of its fuel and never goes off, and
     * the launch line says which of the two happened.
     */
    /**
     * What the run calls itself in the log. A round that steers on its own wire
     * says so, so a test run can be read for which of the two went swimming.
     */
    protected String logName() {
        return "acoustic torpedo";
    }

    @Override
    public void setFuze(ItemStack stack) {
        super.setFuze(stack);
        this.fittedFuze = stack == null || stack.isEmpty() ? "" : stack.getHoverName().getString();
    }

    @Override
    public void tick() {
        if (!this.level().isClientSide) {
            this.run();
        }
        if (this.submerged()) {
            this.faceTravel();
        }
        super.tick();
        if (this.tickCount > 0) {
            SonicPulse.projectile(this);
        }
    }

    /**
     * One tick of the run: remember the platform on the way out, then swing the
     * run towards whatever the hydrophones hear until the motor runs out. The
     * bearings are read even while the round is bobbing on the surface, so an
     * idle run dives again the moment something swims past.
     */
    private void run() {
        if (this.tickCount == 0) {
            this.rememberPlatform();
            return;
        }
        if (this.tickCount == 1) {
            FireControlCompat.LOGGER.info("[firecontrolcompat] {} launched with {}",
                    this.logName(), this.fittedFuze.isEmpty() ? "no fuze" : this.fittedFuze);
        }
        if (this.tickCount > FUEL_TICKS) {
            this.discard();
            return;
        }
        if (!this.listening()) {
            // The ears are not on yet: the run swims straight out on its motor
            // for the arming distance picked in the sonar settings. Without it a
            // torpedo let go beside a working hull takes that hull's noise for a
            // target, which is the ship that fired it.
            this.contact = null;
            return;
        }
        Vec3 target = this.contact();
        if (target == null || !this.submerged()) {
            return;
        }
        this.steer(target);
    }

    /**
     * Whether the hydrophones are on yet. A run is deaf until it has covered the
     * arming distance filed by the computer that fired it, and switched on from the
     * first tick when that distance is zero. The distance is what keeps a round let
     * go beside a working hull from taking its own launch for a target.
     */
    protected boolean listening() {
        double distance = this.arming;
        if (distance <= 0.0D || this.launchPoint == null) {
            return true;
        }
        return this.position().distanceTo(this.launchPoint) >= distance;
    }

    /** Whether the round is in water or any other fluid deep enough to swim in. */
    private boolean submerged() {
        try {
            return this.isInWater() || !this.level().getFluidState(this.blockPosition()).isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Files the platform the round was fired from. A gun is bolted to a ship,
     * and that ship is by far the loudest thing next to a fresh round, so the
     * hulls collected here are the ones the steering gear always ignores.
     */
    private void rememberPlatform() {
        try {
            this.launchPoint = this.position();
            this.platformBodies = PhysicsGroups.motherBody(
                    this.level(), null, this.launchPoint, LAUNCH_NEIGHBOURHOOD);
        } catch (Throwable ignored) {
            this.platformBodies = Set.of();
        }
    }

    /** Turns the run towards the source the hydrophones are holding. */
    private void steer(Vec3 target) {
        Vec3 velocity = this.getDeltaMovement();
        double speed = velocity.length();
        if (speed < 1.0E-4D) {
            return;
        }
        Vec3 aim = target.subtract(this.position());
        if (aim.lengthSqr() < 1.0E-8D) {
            return;
        }
        double flat = Math.sqrt(aim.x * aim.x + aim.z * aim.z);
        Vec3 course = flat < 1.0E-3D
                ? aim.normalize()
                : new Vec3(aim.x / flat, Mth.clamp(aim.y / flat, -MAX_SLOPE, MAX_SLOPE), aim.z / flat).normalize();
        Vec3 heading = velocity.scale(1.0D / speed);
        this.setDeltaMovement(turnTowards(heading, course, this.rudder(heading, course)).scale(speed));
        this.faceTravel();
    }

    /**
     * Points the round - and so its model - down the course it is actually
     * swimming.
     *
     * <p>The renderer draws the round along the orientation CBC keeps for it,
     * and CBC only works that out from the round's own motion. A round steered
     * by hand has to say so itself, and has to mark the change as an impulse,
     * or the client keeps to the course the round had when it was fired and the
     * model is left pointing the old way while the run curves away from it.</p>
     */
    private void faceTravel() {
        try {
            Vec3 motion = this.getDeltaMovement();
            if (motion.lengthSqr() < 1.0E-6D) {
                return;
            }
            this.setOrientation(motion);
            this.hasImpulse = true;
        } catch (Throwable ignored) {
        }
    }

    /**
     * How far the rudder is put over this tick.
     *
     * <p>A rudder is not a switch. A bearing error of a few degrees is answered
     * with a touch of helm and a wide one with the full swing, so the run eases
     * onto a new course and settles on it instead of pivoting at the same hard
     * rate however little it has left to do.</p>
     */
    private double rudder(Vec3 heading, Vec3 course) {
        double dot = Mth.clamp(heading.dot(course), -1.0D, 1.0D);
        double error = Math.acos(dot);
        double fraction = Mth.clamp(error / RUDDER_FULL_ANGLE, RUDDER_MIN_FRACTION, 1.0D);
        return TURN_PER_TICK * fraction;
    }

    /**
     * The bearing the round is steering on, refreshed every few ticks.
     *
     * <p>A gun fires in bursts and a ship goes quiet between them, so a bearing
     * is held for a few seconds after the last noise: the run keeps heading for
     * the place it heard instead of giving up between two shots. Once the memory
     * runs out the round has nothing to chase, and that is when the depth gear
     * brings it up to wait on the surface.</p>
     */
    protected Vec3 contact() {
        if (this.tickCount % SEARCH_PERIOD != 0) {
            return this.contact;
        }
        Source found = this.listen();
        if (found != null) {
            this.heardNoise = true;
            this.lastHeardTick = this.tickCount;
            if (this.contact == null || found.position().distanceToSqr(this.contact) > 4.0D) {
                FireControlCompat.LOGGER.info("[firecontrolcompat] {} hears {} {} blocks away",
                        this.logName(), found.kind(),
                        Math.round(Math.sqrt(found.position().distanceToSqr(this.position()))));
            }
            this.contact = found.position();
            return this.contact;
        }
        if (this.heardNoise && this.tickCount - this.lastHeardTick <= CONTACT_MEMORY_TICKS) {
            return this.contact;
        }
        this.contact = null;
        return null;
    }

    /**
     * What the hydrophones find in the water: the noisiest source inside the
     * three hundred blocks the set listens to, weighted by how loud its kind of
     * noise is and by how close it is. A hull working two hundred blocks off is
     * taken over anything quieter nearer in, and what is past the three hundred
     * is not heard at all, so the run still has to be put into the same water
     * as its quarry.
     */
    private Source listen() {
        Source best = null;
        double bestScore = 0.0D;
        Vec3 here = this.position();
        try {
            for (SonarLog.Pulse pulse : SonarLog.recent(this.level(), HEARD_WINDOW_TICKS, MAX_HEARD)) {
                if (this.getUUID().equals(pulse.owner())) {
                    continue;
                }
                SonarKind kind = pulse.kind();
                Vec3 at = pulse.position();
                double distance = at.distanceTo(here);
                if (distance < 1.0D || distance > HEARING_RANGE) {
                    continue;
                }
                int weight = Mth.clamp(kind.weight().ordinal(), 0, LOUDNESS.length - 1);
                double score = LOUDNESS[weight] / (distance * distance);
                if (score <= bestScore || this.isOwnPlatform(at)) {
                    continue;
                }
                bestScore = score;
                best = new Source(at, kind);
            }
        } catch (Throwable ignored) {
        }
        return best;
    }

    /** Whether a noise was made by the hulls the round was fired from. */
    protected boolean isOwnPlatform(Vec3 at) {
        if (this.platformBodies.isEmpty()) {
            return false;
        }
        try {
            for (UUID body : PhysicsGroups.bodiesContaining(this.level(), at, HULL_INFLATE)) {
                if (this.platformBodies.contains(body)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** Swings one heading towards another, no further than the rudder allows. */
    private static Vec3 turnTowards(Vec3 heading, Vec3 course, double maxAngle) {
        double dot = Mth.clamp(heading.dot(course), -1.0D, 1.0D);
        if (dot >= Math.cos(maxAngle)) {
            return course;
        }
        Vec3 side = course.subtract(heading.scale(dot));
        if (side.lengthSqr() < 1.0E-8D) {
            side = new Vec3(0.0D, 1.0D, 0.0D).cross(heading);
            if (side.lengthSqr() < 1.0E-8D) {
                side = new Vec3(1.0D, 0.0D, 0.0D);
            }
        }
        return heading.scale(Math.cos(maxAngle)).add(side.normalize().scale(Math.sin(maxAngle))).normalize();
    }

    /**
     * What the water does to the round. In the air CBC's own ballistics apply;
     * under the surface the motor, the drag and the last of the weight are
     * swapped in, which is what turns the shell into a torpedo. With nothing to
     * chase the screws stay turning and the depth gear blows the ballast, so
     * the run comes up to the surface and keeps patrolling on the engine.
     */
    @Override
    protected Vec3 getForces(Vec3 position, Vec3 velocity) {
        if (!this.submerged()) {
            return super.getForces(position, velocity);
        }
        double speed = velocity.length();
        Vec3 along = speed < 1.0E-6D ? Vec3.ZERO : velocity.scale(1.0D / speed);
        Vec3 thrust = along.scale(-MOTOR_DRAG * (speed - SWIM_SPEED));
        if (this.contact == null) {
            if (this.holdDepth) {
                return new Vec3(thrust.x, thrust.y + this.cruiseLift(position, velocity.y), thrust.z);
            }
            double lift = this.atSurface() ? BUOYANCY : FLOAT_LIFT;
            return new Vec3(thrust.x, thrust.y + lift - FLOAT_DAMP * velocity.y, thrust.z);
        }
        return new Vec3(thrust.x, thrust.y + BUOYANCY - DEPTH_DAMP * velocity.y, thrust.z);
    }

    /** Whether the column of water above the run has already run out. */
    private boolean atSurface() {
        try {
            return this.level().getFluidState(this.blockPosition().above()).isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * The lift the depth gear asks for while cruising with nothing to chase: the
     * run is taken to the depth filed by the computer that fired it, measured down
     * from the top of the water, and held there. One above that depth is pulled down
     * and one below it is lifted, so an idle torpedo waits under the surface
     * instead of coming up to run along it.
     */
    private double cruiseLift(Vec3 position, double verticalSpeed) {
        double top = this.waterTop();
        if (!Double.isFinite(top)) {
            return BUOYANCY - DEPTH_DAMP * verticalSpeed;
        }
        double error = top - this.cruiseDepth - position.y;
        double lift = Mth.clamp(error * DEPTH_GAIN, -DEPTH_HOLD_MAX, DEPTH_HOLD_MAX);
        return lift - DEPTH_DAMP * verticalSpeed;
    }

    /** The top of the water over the run, re-read every few ticks, or NaN in air. */
    private double waterTop() {
        if (Double.isFinite(this.waterTop) && this.tickCount - this.waterTopTick < 5) {
            return this.waterTop;
        }
        this.waterTopTick = this.tickCount;
        double top = Double.NaN;
        try {
            BlockPos here = this.blockPosition();
            for (int up = 0; up <= SURFACE_SCAN; up++) {
                BlockPos at = here.above(up);
                if (this.level().getFluidState(at).isEmpty()) {
                    top = at.getY();
                    break;
                }
            }
        } catch (Throwable ignored) {
        }
        this.waterTop = top;
        return top;
    }

    /** The surface is not a wall to a torpedo: it goes in and keeps swimming. */
    @Override
    protected boolean onImpactFluid(ProjectileContext context, BlockState blockState, FluidState fluidState,
                                    Vec3 impactPos, BlockHitResult fluidHitResult) {
        return false;
    }

    @Override
    protected void detonate(Position position) {
        TorpedoProperties properties = this.getAllProperties();
        ShellExplosion explosion = new ShellExplosion(
                this.level(),
                this,
                this.indirectArtilleryFire(false),
                position.x(),
                position.y(),
                position.z(),
                properties.explosion().blockDamagePower(),
                properties.explosion().entityDamagePower(),
                false,
                CBCConfigs.server().munitions.damageRestriction.get().explosiveInteraction());
        CreateBigCannons.handleCustomExplosion(this.level(), explosion);
    }

    @Override
    public BlockState getRenderedBlockState() {
        return AcousticTorpedoes.ACOUSTIC_TORPEDO.get().defaultBlockState()
                .setValue(BlockStateProperties.FACING, Direction.NORTH);
    }

    @Override
    protected BigCannonFuzePropertiesComponent getFuzeProperties() {
        return this.getAllProperties().fuze();
    }

    @Override
    protected TorpedoProjectilePropertiesComponent getBigCannonProjectileProperties() {
        return this.getAllProperties().torpedoProperties();
    }

    /** The tube sizes the run here: the round carries its motor's fuel, not the gun's. */
    @Override
    public void setChargePower(float power) {
        float maxCharges = this.getAllProperties().maxCharges();
        this.tooManyCharges = maxCharges >= 0.0F && power > maxCharges;
        this.ageRemaining = FUEL_TICKS;
    }

    @Override
    public EntityDamagePropertiesComponent getDamageProperties() {
        return this.getAllProperties().damage();
    }

    @Override
    protected BallisticPropertiesComponent getBallisticProperties() {
        return this.getAllProperties().ballistics();
    }

    protected TorpedoProperties getAllProperties() {
        return (TorpedoProperties) CBCMSMunitionPropertiesHandlers.TORPEDO_PROJECTILE.getPropertiesOf(this);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (this.launchPoint != null) {
            tag.putDouble("LaunchX", this.launchPoint.x);
            tag.putDouble("LaunchY", this.launchPoint.y);
            tag.putDouble("LaunchZ", this.launchPoint.z);
        }
        tag.putLongArray("PlatformBodies", pack(this.platformBodies));
        tag.putBoolean("TorpedoHoldDepth", this.holdDepth);
        tag.putDouble("TorpedoCruiseDepth", this.cruiseDepth);
        tag.putDouble("TorpedoArming", this.arming);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("LaunchX")) {
            this.launchPoint = new Vec3(tag.getDouble("LaunchX"), tag.getDouble("LaunchY"), tag.getDouble("LaunchZ"));
        }
        this.platformBodies = unpack(tag.getLongArray("PlatformBodies"));
        this.holdDepth = tag.getBoolean("TorpedoHoldDepth");
        this.cruiseDepth = FireControlLeadSettings.clampTorpedoDepth(
                tag.contains("TorpedoCruiseDepth") ? tag.getDouble("TorpedoCruiseDepth") : this.cruiseDepth);
        this.arming = FireControlLeadSettings.clampTorpedoActivation(
                tag.contains("TorpedoArming") ? tag.getDouble("TorpedoArming") : this.arming);
    }

    private static long[] pack(Set<UUID> bodies) {
        long[] raw = new long[bodies.size() * 2];
        int index = 0;
        for (UUID body : bodies) {
            raw[index++] = body.getMostSignificantBits();
            raw[index++] = body.getLeastSignificantBits();
        }
        return raw;
    }

    private static Set<UUID> unpack(long[] raw) {
        if (raw == null || raw.length < 2) {
            return Set.of();
        }
        Set<UUID> bodies = new java.util.LinkedHashSet<>();
        for (int index = 0; index + 1 < raw.length; index += 2) {
            bodies.add(new UUID(raw[index], raw[index + 1]));
        }
        return bodies;
    }
}

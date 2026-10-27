package com.qkdream.firecontrolcompat.compat.cbcmodernwarfare.entity;

import javax.annotation.Nullable;

import com.qkdream.firecontrolcompat.compat.cbcmodernwarfare.CbcModernWarfareCompat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Position;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile;
import rbasamoyai.createbigcannons.munitions.ProjectileContext;
import riftyboi.cbcmodernwarfare.index.CBCModernWarfareItem;

/**
 * The molten copper jet of a shaped charge, thrown out of the point of
 * detonation of every armour piercing round this pack fires.
 *
 * <p>It is not ammunition and has no item of its own: it is only ever spawned
 * by the shell that forged it, which is why it is kept out of the creative
 * menu. It is an inert medium cannon dart - the same round model and texture
 * the armour piercing discarding sabot uses - fired along the nose of the shell
 * that made it at {@link #JET_SPEED} blocks a tick (140, which is 2800 m/s). It carries no drag, so
 * it keeps that speed instead of bleeding it off like a shell, and only a little
 * gravity, so a long shot still bends down a touch. The first thing it touches
 * takes fire: the block face it lands on is set alight, anything standing there
 * is set burning, and then the jet is spent.</p>
 */
public class MetalJetProjectile extends IncendiaryApdsMediumcannonProjectile {

    /**
     * Speed the jet leaves the detonation at, in blocks per tick. 140 blocks a tick is 2800 m/s.
     *
     * <p>Raised in three steps at the user's request: 25 (the original), 75 (x3), now <b>140</b>
     * (another x1.87). A shaped-charge jet out of a modern anti-tank round really does travel at
     * several kilometres a second, and 25 blocks a tick is a seventh of that; at 140 the jet crosses
     * a tank's whole length fifty times inside a single tick. The whole step is still swept -
     * {@code AbstractCannonProjectile#tick} calls {@code clipAndDamage()} with the full velocity
     * before it moves, and that clips block by block along the segment - so the faster jet does not
     * tunnel past what it hits.</p>
     *
     * <p><b>This constant is shared.</b> {@code MetalJetTrigger} and every APDS round of the pack
     * that is forged through it use the same jet, so raising it strengthens <b>every</b> armour
     * piercing discarding sabot jet in the pack, not only the anti-tank round's. That was already
     * true of the x3 step and is called out here so the blast radius of this change is not a
     * surprise. {@code JET_MASS} is deliberately <b>not</b> touched this round: the request was
     * about speed, and Create Big Cannons weighs a hit as mass times speed, so the speed alone is
     * already the whole penetration increase.</p>
     */
    public static final double JET_SPEED = 140.0;

    /**
     * Mass of the jet, in the same unit Create Big Cannons calls a projectile's
     * durability mass.
     *
     * <p>Tripled from ten to thirty at the user's request, together with the speed: Create Big
     * Cannons weighs a hit as mass times the speed along the normal, so both factors multiply the
     * penetration the jet was already chosen for. The dart it is drawn from has a mass of five and
     * the HEAT rounds it is forged from carry one or two, so a jet of thirty is a stream of metal
     * denser than anything else in the air: it keeps going through what a shell of the same gun
     * would stop in. This is the whole point of a shaped charge, and it is what makes the jet a
     * weapon rather than a sparkler.</p>
     */
    public static final float JET_MASS = 30.0F;

    /**
     * Downward pull on the jet, in blocks per tick squared. A shaped charge jet
     * does stretch and droop, but only a little, so this is a fraction of the
     * gravity a shell of the same gun sees.
     */
    private static final double JET_GRAVITY = 0.03;

    /** Ticks the jet is allowed to fly before it is given up on. */
    private static final int MAX_LIFETIME_TICKS = 40;

    /**
     * How much of the path one tick's tracer covers, in blocks.
     *
     * <p>The jet moves {@link #JET_SPEED} blocks in a single tick, so a tracer drawn around its nose
     * would be a single dot. This is the length of the streak laid behind it each tick - short enough
     * that three particles read as a line and not as a dashed one. It is scaled with the speed (about
     * a quarter of one tick's travel): at the old 75 a tick a streak of 18 was a quarter of the step,
     * and at 140 the same share is 35.</p>
     */
    private static final double TRAIL_STEP_BLOCKS = 35.0;

    /** Seconds a creature the jet touches burns for. */
    private static final float IGNITE_SECONDS = 8.0F;

    /** Blocks around the point of impact that are set burning. */
    private static final double IGNITE_RADIUS = 1.5;

    /** How many of the surrounding block faces a single jet may set alight. */
    private static final int MAX_FIRES = 3;

    /** Fires laid by this jet, reported in the log so a shot can be followed end to end. */
    private int firecontrolcompat$firesLaid;

    public MetalJetProjectile(EntityType<? extends MetalJetProjectile> type, Level level) {
        super(type, level);
        // The tracer glow is what makes a round this fast readable in flight.
        this.setTracer(true);
    }

    /**
     * Throws one jet out of a detonating shell.
     *
     * <p>Called from the shells themselves, once, at the point where they go
     * off. The direction is the nose of the shell - the way its own velocity
     * points - because that is the direction the shaped charge's cone faces. A
     * shell that goes off with no velocity left to speak of (a dud sitting in
     * the ground, a fuze that fired on the spot) keeps its own orientation, and
     * a shell with neither simply makes no jet.</p>
     *
     * @param level     level the shell went off in
     * @param position  point of detonation
     * @param direction nose of the shell; scaled to anything but not zero
     * @param owner     the shell that made the jet, so it cannot hit itself
     * @return the jet, already added to the level, or null when nothing was spawned
     */
    @Nullable
    public static MetalJetProjectile spawnFrom(Level level, Position position, Vec3 direction,
            @Nullable Entity owner) {
        if (level == null || level.isClientSide() || position == null || direction == null) {
            return null;
        }
        if (!isFinite(direction) || direction.lengthSqr() < 1.0E-6) {
            return null;
        }
        MetalJetProjectile jet = CbcModernWarfareCompat.METAL_JET.get().create(level);
        if (jet == null) {
            com.qkdream.firecontrolcompat.FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] metal-jet NOT created (entity type missing) at {}", position);
            return null;
        }
        Vec3 nose = direction.normalize();
        // A shell that goes off inside a Sable structure carries plot coordinates; the jet has to be
        // born where the crew can see it, so it is projected out the way the gun's own missiles are.
        Vec3 origin = new Vec3(position.x(), position.y(), position.z());
        try {
            if (level instanceof ServerLevel server) {
                Vec3 projected = dev.ryanhcode.sable.Sable.HELPER.projectOutOfSubLevel(server, origin);
                if (projected != null && isFinite(projected)) {
                    origin = projected;
                }
            }
        } catch (Throwable ignored) {
            // Nothing to project out of: the point is already world space.
        }
        // Born clear of the shell's own body, so its first tick is not its last.
        Vec3 spawn = origin.add(nose.scale(0.75D));
        jet.setPos(spawn.x, spawn.y, spawn.z);
        jet.setDeltaMovement(nose.scale(JET_SPEED));
        jet.hasImpulse = true;
        jet.setOrientation(nose);
        if (owner != null) {
            jet.setOwner(owner);
        }
        firecontrolcompat$flash(level, spawn);
        level.addFreshEntity(jet);
        com.qkdream.firecontrolcompat.FireControlCompat.LOGGER.info(
                "[firecontrolcompat] metal-jet spawn from={} at={} dir={} speed={} mass={} owner={}",
                origin, spawn, nose, JET_SPEED, JET_MASS,
                owner == null ? "none" : owner.getType().toShortString());
        // The one line a shot is graded by: the two numbers the user asked to be tripled, and the
        // tracer flag, in the shape the request named.
        com.qkdream.firecontrolcompat.FireControlCompat.LOGGER.info(
                "[firecontrolcompat] metal-jet: mass={} speed={} tracer={} lifetime={} owner={}",
                String.format(java.util.Locale.ROOT, "%.1f", JET_MASS),
                String.format(java.util.Locale.ROOT, "%.1f", JET_SPEED), true, MAX_LIFETIME_TICKS,
                owner == null ? "none" : owner.getType().toShortString());
        return jet;
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide()) {
            return;
        }
        if (this.tickCount > MAX_LIFETIME_TICKS) {
            this.discard();
            return;
        }
        this.firecontrolcompat$trail();
    }

    /**
     * The tracer: a few particles laid along the tail every tick, with a different mix on alternate
     * ticks so the streak has a hot core, smoke and spark in it.
     *
     * <p>Four particles a tick is the whole budget. Three of them are spread along the stretch of path
     * the jet just covered - at 140 blocks a tick a single point per tick would read as a dotted line -
     * and the fourth alternates between {@link ParticleTypes#SMOKE} and
     * {@link ParticleTypes#END_ROD}. They are sent with {@link ServerLevel#sendParticles} so every
     * client in range is drawn the jet's own path instead of each client inventing a trail.</p>
     */
    private void firecontrolcompat$trail() {
        if (!(this.level() instanceof ServerLevel server)) {
            return;
        }
        Vec3 position = this.position();
        Vec3 back = this.getOrientation().normalize();
        // The jet covers its whole travel in one tick, so the trail is laid along that step rather
        // than around its tail: at 140 blocks a tick three particles within four blocks of the nose
        // would be one dot. The step is capped so the streak stays readable instead of turning into
        // a dotted line across the whole map.
        double covered = Math.min(TRAIL_STEP_BLOCKS, this.getDeltaMovement().length());
        for (int step = 0; step < 3; step++) {
            Vec3 at = position.subtract(back.scale(covered * (0.35 + 0.3 * step)));
            server.sendParticles(ParticleTypes.FLAME, at.x, at.y, at.z, 1, 0.02, 0.02, 0.02, 0.01);
        }
        Vec3 tail = position.subtract(back.scale(0.6));
        if (this.tickCount % 2 == 0) {
            server.sendParticles(ParticleTypes.SMOKE, tail.x, tail.y, tail.z, 1, 0.02, 0.02, 0.02, 0.01);
        } else {
            server.sendParticles(ParticleTypes.END_ROD, tail.x, tail.y, tail.z, 1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    /**
     * The flash at the point of detonation the jet is thrown out of: one burst of hot particles where
     * the shaped charge went off, so the crew sees the jet being born instead of a trail that starts
     * in mid-air a few blocks away from the hit.
     */
    private static void firecontrolcompat$flash(Level level, Vec3 at) {
        if (!(level instanceof ServerLevel server)) {
            return;
        }
        server.sendParticles(ParticleTypes.FLAME, at.x, at.y, at.z, 10, 0.12, 0.12, 0.12, 0.03);
        server.sendParticles(ParticleTypes.END_ROD, at.x, at.y, at.z, 6, 0.10, 0.10, 0.10, 0.05);
    }

    /**
     * No drag, only a little gravity: the jet is a stream of metal, not a shell
     * with a ballistic coefficient to lose its speed to.
     */
    @Override
    protected Vec3 getForces(Vec3 position, Vec3 velocity) {
        return new Vec3(0.0, -JET_GRAVITY, 0.0);
    }

    /**
     * The jet's mass, which is what its penetration is made of.
     *
     * <p>Create Big Cannons weighs a hit as mass times the speed along the normal,
     * so a jet that is both heavier and faster than the dart its model comes from
     * goes through armour that stops the shell that fired it. The mass is fixed
     * rather than taken from the round the jet is modelled on, and it is not
     * eroded by the blocks it passes through: a shaped charge jet is already
     * spent by the time it stops, which is what the short lifetime expresses.</p>
     */
    @Override
    public float getProjectileMass() {
        return JET_MASS;
    }

    /** Sets fire to whatever the jet lands on, then is spent. */
    @Override
    protected boolean onImpact(HitResult hitResult, AbstractCannonProjectile.ImpactResult impactResult,
            ProjectileContext projectileContext) {
        if (hitResult != null) {
            this.firecontrolcompat$ignitePoint(hitResult);
        }
        super.onImpact(hitResult, impactResult, projectileContext);
        // A jet burns through and is gone; it never bounces or sails on.
        return true;
    }

    /** Anything the jet sweeps through is set burning. */
    @Override
    protected boolean onHitEntity(Entity target, ProjectileContext projectileContext) {
        boolean remove = super.onHitEntity(target, projectileContext);
        if (!this.level().isClientSide() && target != null && !target.fireImmune()) {
            if (target instanceof LivingEntity living) {
                living.igniteForSeconds(IGNITE_SECONDS);
            } else {
                target.igniteForSeconds(IGNITE_SECONDS);
            }
        }
        return remove;
    }

    /**
     * Sets the impact point alight: the face the jet struck, and a couple of the
     * neighbouring faces so a shot into a wall leaves a patch of flame rather
     * than a single burning pixel.
     */
    private void firecontrolcompat$ignitePoint(HitResult hitResult) {
        if (this.level().isClientSide()) {
            return;
        }
        Level level = this.level();
        Vec3 hit = hitResult.getLocation();
        if (hit != null && isFinite(hit)) {
            int placed = 0;
            if (hitResult instanceof BlockHitResult blockHit) {
                Direction face = blockHit.getDirection();
                BlockPos start = blockHit.getBlockPos();
                placed = this.firecontrolcompat$tryFire(start.relative(face));
                for (Direction side : Direction.values()) {
                    if (placed >= MAX_FIRES) {
                        break;
                    }
                    if (side == face || side == face.getOpposite()) {
                        continue;
                    }
                    placed += this.firecontrolcompat$tryFire(start.relative(side));
                }
            }
            if (placed == 0) {
                this.firecontrolcompat$tryFire(BlockPos.containing(hit));
            }
        }
        AABB area = new AABB(hit.x - IGNITE_RADIUS, hit.y - IGNITE_RADIUS, hit.z - IGNITE_RADIUS,
                hit.x + IGNITE_RADIUS, hit.y + IGNITE_RADIUS, hit.z + IGNITE_RADIUS);
        int burning = 0;
        for (Entity entity : level.getEntities(this, area)) {
            if (entity != null && !entity.fireImmune()) {
                entity.igniteForSeconds(IGNITE_SECONDS);
                burning++;
            }
        }
        com.qkdream.firecontrolcompat.FireControlCompat.LOGGER.info(
                "[firecontrolcompat] metal-jet hit at {} fires={} entities={} tick={}",
                hit, this.firecontrolcompat$firesLaid, burning, this.tickCount);
    }

    /** Lights one block if fire may stand there; answers 1 when it did. */
    private int firecontrolcompat$tryFire(BlockPos pos) {
        Level level = this.level();
        if (pos == null || !level.hasChunkAt(pos)) {
            return 0;
        }
        if (!level.getBlockState(pos).isAir()) {
            return 0;
        }
        if (!BaseFireBlock.canBePlacedAt(level, pos, Direction.UP)) {
            return 0;
        }
        level.setBlockAndUpdate(pos, Blocks.FIRE.defaultBlockState());
        this.firecontrolcompat$firesLaid++;
        return 1;
    }

    @Override
    public ItemStack getItem() {
        return CBCModernWarfareItem.APDS_MEDIUMCANNON_ROUND.asStack();
    }

    private static boolean isFinite(Vec3 vector) {
        return Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }
}

package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.content.controller.MianbaoAirDefenseCompat;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.hooya.stabilizedturret.content.iff.FireControlMissileOwnership;
import com.qkdream.firecontrolcompat.BeamMissileCompat;
import com.qkdream.firecontrolcompat.FireControlCompat;
import com.qkdream.firecontrolcompat.IrSeekerSession;
import com.qkdream.firecontrolcompat.MianbaoAaFireCompat;
import java.lang.reflect.Field;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Fires a bound air-defense launcher without a radar lock, and without a target.
 *
 * <h2>The fault this method used to have (session log 2026-10-25 14:36)</h2>
 *
 * <p>Every press printed a satisfied gate line and nothing else:</p>
 * <pre>
 *   aa-press: selected=AIR_DEFENSE aaLaunchers=4 level=ok
 *   aa-gate: launchers=4 loaded=4 accepted=3 selected=AIR_DEFENSE round=ordinary
 *            radar=false lock=false guidance=true realign=none
 *   aa-loop: walk done launchers=4 cursor=0 selected=AIR_DEFENSE
 * </pre>
 * <p>with <b>no</b> {@code aa-fireone:} line, <b>no</b> {@code aa-loop: operator message=}, and
 * the fire cursor still at zero. That combination can only come from the body of
 * {@code fireNextAaMissile} returning before it walked anything: an
 * {@code @Overwrite} of this mod's own stood here, it opened with
 * {@code if (this.hasInputConfigured(4) …)}, and the crew's fire key is channel <b>3</b>.
 * 面包学's dispatcher ({@code fireSelectedGroundWeapon}) accepts action 3 <em>or</em> 4 before it
 * calls this method - so the method was reached, the override then refused on action 4 alone, and
 * the shot vanished with no message at all. The radar and lock requirements below that line never
 * even ran.</p>
 *
 * <h2>What it does now</h2>
 *
 * <p>The gate the crew cannot satisfy is <b>gone</b>: a ground launcher of 面包学's is a tube aimed
 * by its own facing and by the computer's beam, so neither an active radar nor a locked Sable
 * sub-level is a precondition for releasing one. The walk therefore:</p>
 *
 * <ol>
 *   <li>opens the two-step infrared seeker when the rail on the cursor carries this mod's IR round
 *       (that press is spent on the seeker, exactly as before);</li>
 *   <li>otherwise asks 面包学 for the shot through the <b>three-argument</b> {@code fireOne}, which
 *       is the one the pack itself calls;</li>
 *   <li>if the pack answers {@code false} - its ordinary-round launch resolves its spawn level with
 *       {@code server.getLevel(level.dimension())}, which a Sable sub-level does not answer - the
 *       round is launched by {@link MianbaoAaFireCompat} in the level the rail really stands in;</li>
 *   <li>prints {@code aa-fireone: forced … reason=<no-radar|no-target|ok>} before the shot and
 *       {@code aa-fireone: ok=…} after it, so a press that still does nothing names its reason.</li>
 * </ol>
 *
 * <p>Aiming keeps the pack's own order: a locked Sable sub-level first, else the nearest radar
 * contact (which is adopted as the lock when the radar is running), else the computer's own
 * measured aim - the beam the operator is already laying. That last case deliberately performs no
 * guidance test: the round leaves the rail where the sight points, which is what a ground battery
 * is for.</p>
 */
@Mixin(StabilizerControllerBlockEntity.class)
public abstract class ControllerFireAaMixin {

    /** The field the pack's own {@code setRadarLockedTarget} writes, looked up once. */
    private static Field firecontrolcompat$lockedSubLevelField;

    private static boolean firecontrolcompat$lockedSubLevelLooked;

    @Shadow
    private int aaMissileFireCursor;

    @Shadow
    private boolean hasInputConfigured(int action) {
        throw new AbstractMethodError();
    }

    @Shadow
    private void removeInvalidBindings() {
        throw new AbstractMethodError();
    }

    @Shadow
    private void notifyMissileOperator(Component message) {
        throw new AbstractMethodError();
    }

    /**
     * @reason A ground air-defense rail fires without a radar lock; the pack's own release is
     *         retried in the rail's real level when it refuses.
     * @author firecontrolcompat
     */
    @Overwrite
    private void fireNextAaMissile() {
        StabilizerControllerBlockEntity self = (StabilizerControllerBlockEntity) (Object) this;
        try {
            Level level = self.getLevel();
            if (level == null) {
                return;
            }
            // 面包学's dispatcher (:547) already required action 3 or 4 before it called this
            // method. The old override asked for 4 alone, which is why a crew firing on channel 3
            // got silence; the pair is kept here so a direct caller still cannot fire on nothing.
            if (!this.hasInputConfigured(3) && !this.hasInputConfigured(4)) {
                return;
            }
            this.removeInvalidBindings();
            List<BindingRef> launchers = self.getAaMissileLaunchers();
            if (launchers.isEmpty()) {
                this.notifyMissileOperator(Component.literal("防空导弹发射器：未绑定"));
                return;
            }
            boolean radar = firecontrolcompat$radarActive(self);
            UUID lock = firecontrolcompat$lockedTarget(self);
            String reason = !radar ? "no-radar" : (lock == null ? "no-target" : "ok");
            // "Locked entity → that one; radar contact → the nearest one; neither → straight down
            // the sight." Only the middle case needs a write: adopting the nearest contact as the
            // lock is what makes the pack's own guidance fly at it.
            if (radar && lock == null) {
                UUID nearest = firecontrolcompat$adoptNearestContact(self);
                if (nearest != null) {
                    reason = "ok";
                }
            }
            int size = launchers.size();
            int start = Math.floorMod(this.aaMissileFireCursor, size);
            for (int offset = 0; offset < size; offset++) {
                int index = (start + offset) % size;
                BindingRef launcher = launchers.get(index);
                int ammo = MianbaoAirDefenseCompat.getAmmoCount(level, launcher);
                if (ammo <= 0) {
                    continue;
                }
                if (this.firecontrolcompat$releaseIrSeeker(self, launcher)) {
                    // The press only opened the seeker: the tube keeps its round and the cursor
                    // stays put so the next press reaches the same seeker.
                    return;
                }
                this.aaMissileFireCursor = (index + 1) % size;
                Set<UUID> before = firecontrolcompat$missilesAround(level, launcher);
                boolean fired = MianbaoAirDefenseCompat.fireOne(level, launcher, self);
                if (!fired) {
                    fired = MianbaoAaFireCompat.tryFire(level, launcher, self);
                }
                self.setChanged();
                Entity round = firecontrolcompat$newRound(level, launcher, before);
                // Every round this computer puts in the air carries its identity, whichever path
                // launched it. 面包学 marks the rounds its own guidance adopts and this mod marks the
                // ones it launches itself, but a round thrown by a launcher's own block procedure is
                // marked by nobody - and an unmarked round is read back as an unidentified contact,
                // so a crewed mount turns on the round it has just fired and empties every tube
                // (session log 2026-10-25 15:46: four rounds per rail in five seconds). The mark is
                // exactly the one the pack's own launch writes, so nothing else has to know of it.
                boolean marked = false;
                if (round != null) {
                    try {
                        FireControlMissileOwnership.markController(round, self);
                        marked = true;
                    } catch (Throwable ignored) {
                        // the round still flies; it is only unidentified
                    }
                }
                FireControlCompat.LOGGER.info(
                        "[firecontrolcompat] aa-fireone: forced ref={} ammo={} entity={} reason={} iff={}",
                        launcher.pos(), ammo,
                        round == null ? "none" : String.valueOf(round.getType()),
                        reason, marked ? "friendly" : (round == null ? "-" : "unmarked"));
                FireControlCompat.LOGGER.info("[firecontrolcompat] aa-fireone: ok={} ref={}",
                        fired, launcher.pos());
                if (!fired) {
                    this.notifyMissileOperator(Component.literal("防空导弹发射失败"));
                }
                return;
            }
            this.notifyMissileOperator(Component.literal("防空导弹已用尽"));
        } catch (Throwable failure) {
            FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] aa-fireone: forced launch threw reason={}", failure.toString());
        }
    }

    /** {@code isRadarActive}, behind a guard so a pack rename cannot abort a shot. */
    @Unique
    private static boolean firecontrolcompat$radarActive(StabilizerControllerBlockEntity self) {
        try {
            return self.isRadarActive();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** The lock, from a Sable sub-level or from an entity contact, whichever the computer holds. */
    @Unique
    private static UUID firecontrolcompat$lockedTarget(StabilizerControllerBlockEntity self) {
        try {
            if (self.getRadarLockedSubLevel() != null) {
                return self.getRadarLockedSubLevelId();
            }
            UUID id = self.getRadarLockedSubLevelId();
            return id != null && self.getRadarContact(id) != null ? id : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Takes the nearest radar contact as the lock, which is the "radar contact → nearest one"
     * step of the aim order.
     *
     * <p>The field is written reflectively: {@code setRadarLockedTarget} is private and a
     * {@code @Shadow} of a renamed member aborts the whole mixin at load time, while a failed
     * lookup here only loses the adoption.</p>
     */
    @Unique
    private static UUID firecontrolcompat$adoptNearestContact(StabilizerControllerBlockEntity self) {
        try {
            List<StabilizerControllerBlockEntity.RadarContact> contacts = self.getRadarContacts();
            if (contacts == null || contacts.isEmpty()) {
                return null;
            }
            Field field = firecontrolcompat$lockedSubLevelField();
            if (field == null) {
                return null;
            }
            UUID nearest = contacts.get(0).subLevelId();
            if (nearest == null) {
                return null;
            }
            field.set(self, nearest);
            self.setChanged();
            FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] aa-fireone: aim radar-contact contact={} of={}",
                    nearest, contacts.size());
            return nearest;
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Unique
    private static Field firecontrolcompat$lockedSubLevelField() {
        if (!firecontrolcompat$lockedSubLevelLooked) {
            firecontrolcompat$lockedSubLevelLooked = true;
            try {
                Field field = StabilizerControllerBlockEntity.class
                        .getDeclaredField("radarLockedSubLevelId");
                field.setAccessible(true);
                firecontrolcompat$lockedSubLevelField = field;
            } catch (Throwable ignored) {
                firecontrolcompat$lockedSubLevelField = null;
            }
        }
        return firecontrolcompat$lockedSubLevelField;
    }

    /**
     * The air-defense rounds standing near a rail right now.
     *
     * <p>Taken before the shot so the line after it can name the round that actually entered the
     * world. Both the level the computer names and the level the rail stands in are searched, and
     * both the stored block position and the projected muzzle are used as centres: a rail inside a
     * Sable sub-level spawns its round in the plot's parent level at the projected point, which is
     * tens of millions of blocks away from the position the rail is stored at.</p>
     */
    @Unique
    private static Set<UUID> firecontrolcompat$missilesAround(Level level, BindingRef ref) {
        Set<UUID> found = new LinkedHashSet<>();
        for (Entity entity : firecontrolcompat$nearbyMissiles(level, ref)) {
            found.add(entity.getUUID());
        }
        return found;
    }

    /** The same scan a second time, answered with the round that was not there before. */
    @Unique
    private static Entity firecontrolcompat$newRound(Level level, BindingRef ref, Set<UUID> before) {
        for (Entity entity : firecontrolcompat$nearbyMissiles(level, ref)) {
            if (!before.contains(entity.getUUID())) {
                return entity;
            }
        }
        return null;
    }

    @Unique
    private static List<Entity> firecontrolcompat$nearbyMissiles(Level level, BindingRef ref) {
        java.util.ArrayList<Entity> found = new java.util.ArrayList<>();
        if (level == null || ref == null) {
            return found;
        }
        try {
            Set<Level> levels = new LinkedHashSet<>();
            levels.add(level);
            java.util.List<Vec3> centres = new java.util.ArrayList<>();
            centres.add(Vec3.atCenterOf(ref.pos()));
            Vec3 muzzle = MianbaoAirDefenseCompat.getLauncherMuzzlePosition(level, ref);
            if (muzzle != null) {
                centres.add(muzzle);
            }
            for (Level candidate : levels) {
                for (Vec3 centre : centres) {
                    AABB box = new AABB(centre, centre).inflate(24.0D);
                    for (Entity entity : candidate.getEntitiesOfClass(Entity.class, box)) {
                        if (MianbaoAirDefenseCompat.isMissile(entity)) {
                            found.add(entity);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // a diagnostic scan is never worth a shot
        }
        return found;
    }

    /**
     * Runs the two step infrared release for the launcher this volley points at.
     * Returns true when the trigger press was spent on the seeker instead of a
     * launch, which is also what keeps automatic anti-missile defense firing at
     * once: it reaches the launcher without going through a trigger press.
     */
    @Unique
    private boolean firecontrolcompat$releaseIrSeeker(StabilizerControllerBlockEntity self, BindingRef launcher) {
        Level level = self.getLevel();
        if (level == null || !BeamMissileCompat.hasIrAntiAirAmmo(level, launcher)
                || !(level instanceof ServerLevel serverLevel)) {
            return false;
        }
        IrSeekerSession.Lock lock = IrSeekerSession.press(serverLevel, launcher.pos(), self.getBlockPos(),
                BeamMissileCompat.antiAirMuzzle(level, launcher.pos()), BeamMissileCompat.facing(level, launcher.pos()));
        if (lock == null) {
            this.notifyMissileOperator(Component.literal(IrSeekerSession.searchHint(serverLevel)));
            return true;
        }
        IrSeekerSession.handOver(serverLevel, launcher.pos(), lock);
        return false;
    }
}

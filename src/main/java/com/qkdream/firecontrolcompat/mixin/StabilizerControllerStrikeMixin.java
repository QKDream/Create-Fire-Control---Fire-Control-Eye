package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.content.controller.MianbaoAircraftCompat;
import com.hooya.stabilizedturret.content.controller.MianbaoMissileCompat;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.qkdream.firecontrolcompat.BombRackCompat;
import com.qkdream.firecontrolcompat.FireControlCompat;
import com.qkdream.firecontrolcompat.StrikePoints;
import com.qkdream.firecontrolcompat.laser.LaserSpots;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes the point written on a computer by the strike board count as a ground
 * target.
 *
 * <p>The computer only releases an air-to-ground round at a target it has
 * locked, and the air-to-ground guidance only flies its own missiles at a
 * structure it can resolve. A strike point is neither: it is a place, so it is
 * answered here as a one-block target of its own - under a reserved name, so the
 * guidance finds the same point when it looks it up - and the air-to-ground
 * target line reads it whenever the pod is not being looked through. The pod
 * keeps precedence: while a player is looking through it the point is ignored
 * and the lock in the pod is what the computer uses.</p>
 *
 * <p>The release itself is here too: a computer holding bomb racks and a strike
 * point releases at the point without waiting for the pod, since that is the
 * whole point of writing the place down first.</p>
 */
@Mixin(StabilizerControllerBlockEntity.class)
public abstract class StabilizerControllerStrikeMixin {

    /** The reserved name of the strike point when it is looked up as a target. */
    private static final UUID FIRE_CONTROL_COMPAT_STRIKE = UUID.fromString("6f6b4c1e-51a9-4c6f-9f39-2a5a1b8f0d11");

    @Inject(method = "getAirToGroundTargetSnapshot", at = @At("RETURN"), cancellable = true, require = 0)
    private void firecontrolcompat$strikePointAsLock(CallbackInfoReturnable<StabilizerControllerBlockEntity.GroundTargetSnapshot> cir) {
        StabilizerControllerBlockEntity self = (StabilizerControllerBlockEntity) (Object) this;
        if (cir.getReturnValue() != null || self.isPodViewEnabled()) {
            return;
        }
        Vec3 point = StrikePoints.pointOf(self);
        if (point == null) {
            return;
        }
        cir.setReturnValue(firecontrolcompat$snapshot(point));
    }

    /** The guidance looking the point up again finds the same place. */
    @Inject(method = "resolveGroundTarget", at = @At("HEAD"), cancellable = true, require = 0)
    private void firecontrolcompat$resolveStrikePoint(UUID representative, Set<UUID> members,
            CallbackInfoReturnable<StabilizerControllerBlockEntity.GroundTargetSnapshot> cir) {
        if (representative == null || !FIRE_CONTROL_COMPAT_STRIKE.equals(representative)) {
            return;
        }
        StabilizerControllerBlockEntity self = (StabilizerControllerBlockEntity) (Object) this;
        Vec3 point = StrikePoints.pointOf(self);
        if (point != null) {
            cir.setReturnValue(firecontrolcompat$snapshot(point));
        }
    }

    /**
     * Releases a bomb rack on the fire input.
     *
     * <p>A bomb rack is not one of the computer's own air-to-ground rails: it has
     * no seeker and no launcher to read a lock from, so the stock release, which
     * waits for the pod to hold a target, can never let one go. The racks that
     * steer to a place the computer wrote down (the glide bombs, the JDAMs, the
     * penetrating bombs) are released once a strike point is set; the ones that
     * simply fall are let go straight away, because there is nothing for them to
     * be aimed at.</p>
     *
     * <p>Rails of 面包学's own - real air-to-ground missiles - are left to the
     * stock release, as is a guided round with no place written down, so the crew
     * still gets the mod's own line about what is missing.</p>
     */
    /**
     * Steps the bomb selection on when the weapon cycle lands on the ground line.
     *
     * <p>面包学's cycle counts weapon types, and every bomb rack answers as one type, so
     * a load of two different bombs had a single entry between them. Each cycle press
     * that arrives at the ground line moves the pointer to the next loaded rack instead,
     * which is what lets a crew choose the second kind without dropping the first.</p>
     */
    /**
     * Advanced at the <b>head</b> of the pack's own cycle, not at its end.
     *
     * <p>The pack sets the weapon and then asks for its name inside the same call, so an
     * advance made at the end left the name of the kind before last on screen while the
     * trigger had already moved on: the crew read one kind and the next shot was the other
     * one. Moving the advance first makes the name and the trigger the same kind.</p>
     */
    @Inject(method = "cycleAircraftWeapon", at = @At("HEAD"), require = 0)
    private void firecontrolcompat$cycleBombSelection(CallbackInfo ci) {
        StabilizerControllerBlockEntity self = (StabilizerControllerBlockEntity) (Object) this;
        Level level = self.getLevel();
        if (level == null
                || self.getSelectedAircraftWeapon()
                        != MianbaoAircraftCompat.WeaponType.AIR_TO_GROUND_DIRECT) {
            return;
        }
        FireControlCompat.LOGGER.info(
                "[firecontrolcompat] weapon cycle probe selected={} racks={}",
                self.getSelectedAircraftWeapon(), self.getAircraftMissileRacks().size());
        BombRackCompat.cycleSelection(level, self.getBlockPos(), self.getAircraftMissileRacks());
    }

    @Inject(method = "fireNextAircraftMissile", at = @At("HEAD"), cancellable = true, require = 0)
    private void firecontrolcompat$releaseBombRack(CallbackInfo ci) {
        StabilizerControllerBlockEntity self = (StabilizerControllerBlockEntity) (Object) this;
        Level level = self.getLevel();
        if (level == null) {
            return;
        }
        List<BindingRef> racks = self.getAircraftMissileRacks();
        boolean carrying = false;
        for (BindingRef rack : racks) {
            if (BombRackCompat.isLoadedBombRack(level, rack)) {
                carrying = true;
                break;
            }
        }
        if (!carrying) {
            // Nothing of ours on this computer, so 面包学's own rails keep the
            // stock release. The racks are named anyway: an empty line here used
            // to mean "no rack of ours is bound", which is the one case that had
            // no evidence at all when a rack would not release.
            FireControlCompat.LOGGER.info("[firecontrolcompat] bomb release idle racks={} ids={} selected={}",
                    racks.size(), BombRackCompat.describeRacks(level, racks),
                    self.getSelectedAircraftWeapon());
            return;
        }
        MianbaoAircraftCompat.WeaponType selected = self.getSelectedAircraftWeapon();
        // 0.9.4 names every bomb family with a weapon type of its own (GLIDE_BOMB, JDAM_BOMB,
        // LASER_BOMB, ...). Its own release then walks the racks by that type and never reaches this
        // layer, so a glide bomb left the aircraft with no ground point written onto its rack: the
        // rail threw it, nothing steered it. Those types are this mod's release now, exactly like
        // AIR_TO_GROUND_DIRECT. The pack's own air-to-ground missiles keep their stock release.
        boolean nativeBomb = BombRackCompat.isNativeBombSelection(selected);
        if (selected != MianbaoAircraftCompat.WeaponType.AIR_TO_GROUND_DIRECT && !nativeBomb) {
            FireControlCompat.LOGGER.info("[firecontrolcompat] bomb release skipped selected={} racks={}",
                    selected, racks.size());
            return;
        }
        // A place written on the chart, or a spot the computer's own pod is
        // painting: a laser-guided round released at a painted spot needs no
        // chart point at all, because its own seeker follows the spot down.
        // "Charted" has to mean what the computer actually holds, not only a point written
        // with the strike terminal: a pod lock on a structure's ground fills the same
        // air-to-ground snapshot, and the cruise and penetrating rounds fly to that one.
        // Reading only the terminal's own point held the release back whenever the target
        // had been locked instead of written - which is what broke a cruise missile's lock
        // on a Sable structure.
        // Three sources, kept apart because the rounds differ in what they accept: the
        // point written with the strike terminal, the computer's own pod lock on a place
        // (a Sable structure's ground included), and this mod's laser spot.
        boolean terminal = StrikePoints.isSet(self);
        boolean pod = BombRackCompat.hasPodLock(self);
        boolean lasing = LaserSpots.paintingFrom(self.getBlockPos());
        boolean aimed = terminal || pod || lasing;
        // Always, and only, the kind the trigger is on - one press, one round.
        //
        // This used to run only when two kinds were loaded, and with a single kind the
        // loop below took over. That loop walks every rack of the type, so a symmetric
        // load released a pair at once (a salvo the crew did not ask for) and which rack
        // went first was the rack order's business, not the selection's - which is how a
        // crew could pick one kind and watch another leave the aircraft.
        if (!BombRackCompat.loadedEntries(level, racks).isEmpty()) {
            // A kind the pack names itself is released from the rails that kind names - one press,
            // one round of the kind on the trigger - and only falls back to this mod's own kind
            // pointer when the pack cannot answer for these rails (an unloaded sub-level chunk
            // makes weaponAt answer null).
            List<BindingRef> named = nativeBomb
                    ? BombRackCompat.racksForSelection(level, racks, selected) : List.of();
            boolean released = named.isEmpty()
                    ? BombRackCompat.fireSelected(level, self.getBlockPos(), racks, terminal, pod, lasing)
                    : BombRackCompat.fireOneOf(level, named, terminal, pod, lasing);
            FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] ground release selected-only released={} selected={} named={}"
                            + " terminal={} pod={} lasing={} racks={}",
                    released, selected, named.size(), terminal, pod, lasing, racks.size());
            ci.cancel();
            return;
        }
        int steering = 0;
        for (BindingRef rack : racks) {
            if (MianbaoAircraftCompat.weaponAt(level, rack)
                    != MianbaoAircraftCompat.WeaponType.AIR_TO_GROUND_DIRECT) {
                continue;
            }
            if (!BombRackCompat.isLoadedBombRack(level, rack)) {
                // A rail of 面包学's own: it is not ours to throw.
                continue;
            }
            if (!(terminal || pod) && BombRackCompat.needsGroundPoint(level, rack)) {
                // A steering round with nowhere written down to go, but a rack
                // behind it may still be one that simply falls.
                steering++;
                continue;
            }
            boolean released = BombRackCompat.fire(level, rack);
            FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] bomb release rack={} released={} aimed={} terminal={} pod={} lasing={} racks={}",
                    rack.pos(), released, aimed, terminal, pod, lasing, racks.size());
            if (released) {
                ci.cancel();
            }
            return;
        }
        FireControlCompat.LOGGER.info(
                "[firecontrolcompat] bomb release held steering={} racks={} terminal={} pod={} lasing={}",
                steering, racks.size(), terminal, pod, lasing);
    }

    private static StabilizerControllerBlockEntity.GroundTargetSnapshot firecontrolcompat$snapshot(Vec3 point) {
        AABB bounds = new AABB(point.x - 1.0D, point.y - 1.0D, point.z - 1.0D,
                point.x + 1.0D, point.y + 1.0D, point.z + 1.0D);
        return new StabilizerControllerBlockEntity.GroundTargetSnapshot(
                FIRE_CONTROL_COMPAT_STRIKE, point, bounds, Set.of(FIRE_CONTROL_COMPAT_STRIKE));
    }
}

package com.qkdream.firecontrolcompat.underwater;

import com.hooya.stabilizedturret.content.controller.ControllerTracker;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.qkdream.firecontrolcompat.FireControlCompat;
import com.qkdream.firecontrolcompat.PhysicsGroups;
import com.qkdream.firecontrolcompat.sonar.SonarLockStore;
import dev.ryanhcode.sable.sublevel.SubLevel;
import java.util.UUID;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import rbasamoyai.createbigcannons.cannon_control.contraption.AbstractMountedCannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;

/**
 * The target a fire control computer lays a torpedo on.
 *
 * <p>A boat does not have to be heard to be shot at: when the tube that is
 * firing answers to a computer, the thing on that computer's screen is written
 * onto the round as the round leaves, and the wire then leads to that and to
 * nothing else. The wire comes from one of two places - the tube's own memory
 * of the computer it was wired to, or the computer that has the cannon mount
 * the tube is assembled on - so a tube can be wired up either before or after
 * it is put on its mount.</p>
 *
 * <p>The mark is filed as the shot is being built - inside the contraption that
 * fires, from the head of the shot - and picked up by the round's own block
 * while that same shot is being assembled, so two tubes firing at once can
 * never take each other's target.</p>
 *
 * <p>Only a round that swims on a wire is laid on a mark. The passive round
 * hunts by ear and is handed no bearing at all - what the computer has locked
 * would otherwise be followed instead of what the water carries, which is the
 * one thing that round is built not to do. Its swim settings still come from the
 * same computer.</p>
 */
public final class TorpedoDesignation {

    /** The mark the round being built right now carries, if any. */
    private static Mark pending;

    /** The lock a shot was laid on, with the swim of the computer that fired it. */
    private record Mark(Vec3 at, UUID body, TorpedoLaunchSettings.Values settings) {
    }

    private TorpedoDesignation() {
    }

    /**
     * Reads the computer of the gun that is about to fire and files what it has
     * locked, for the round that gun is building to pick up.
     */
    public static void arm(Level level, PitchOrientedContraptionEntity gun) {
        pending = null;
        if (level == null || level.isClientSide || gun == null) {
            return;
        }
        try {
            TorpedoLaunchSettings.Values settings = settingsFor(level, gun);
            // A tube wired to nobody still throws a round that knows how to
            // swim; there is simply no lock to lay it on.
            Mark mark = markFor(level, controllerFor(gun));
            pending = new Mark(mark.at(), mark.body(), settings);
            if (mark.at() != null) {
                FireControlCompat.LOGGER.info("[firecontrolcompat] torpedo tube laid its wire on {}",
                        mark.body() == null ? "the locked point" : "hull " + mark.body());
            }
        } catch (Throwable ignored) {
        }
    }

    /** Hands the round leaving a gun the mark it was laid on, once. */
    public static void apply(WireGuidedTorpedoProjectile torpedo) {
        Mark mark = take();
        if (mark == null || torpedo == null) {
            return;
        }
        torpedo.applyLaunchSettings(mark.settings());
        if (mark.at() != null) {
            torpedo.designate(mark.body(), mark.at());
        }
    }

    /**
     * Hands a round that hunts by ear the swim the computer that fired it files.
     * There is no wire on such a round and no bearing either: the computer still
     * decides whether it patrols on the surface or waits deep, and how long it
     * stays deaf, but what it has locked is not passed on - the round finds its
     * own target by ear.
     */
    public static void apply(AcousticTorpedoProjectile torpedo) {
        Mark mark = take();
        if (mark == null || torpedo == null) {
            return;
        }
        torpedo.applyLaunchSettings(mark.settings());
    }

    /**
     * What the computer has under its cursor, in the order the operator works
     * it: the mark picked off the sonar picture first - the key that picks it is
     * what the tubes are laid on - then the hull its radar has locked, and last
     * whatever its optics are holding.
     */
    private static Mark markFor(Level level, StabilizerControllerBlockEntity controller) {
        if (controller == null) {
            return new Mark(null, null, null);
        }
        SonarLockStore.Lock picked = SonarLockStore.of(level, controller);
        if (picked != null) {
            Vec3 at = picked.position();
            // A mark on a boat is a hull to run down wherever she goes; a mark in
            // open water is only a bearing to swim on.
            return new Mark(at, picked.hull() ? bodyAt(level, at) : null, null);
        }
        UUID body = controller.getRadarLockedSubLevelId();
        Vec3 at = hullCentre(level, body);
        if (at == null) {
            AABB bounds = controller.getOpticalLockBounds();
            if (bounds != null) {
                at = bounds.getCenter();
                if (body == null) {
                    body = bodyAt(level, at);
                }
            }
        }
        return new Mark(at, body, null);
    }

    /**
     * Lays a round that goes straight out of a tube standing in the world -
     * the 面包学 tube never passes through a cannon mount - on what that tube's
     * computer has picked. Only the wire-guided round is laid on anything: one
     * that hunts by ear is handed nothing and finds its own target, and a tube
     * wired to nobody hands over nothing either way.
     */
    public static void designateFromTube(Level level, BlockEntity tube, AcousticTorpedoProjectile torpedo) {
        if (level == null || tube == null || !(torpedo instanceof WireGuidedTorpedoProjectile wire)) {
            return;
        }
        try {
            Mark mark = markFor(level, TorpedoTubeBinding.controller(tube));
            if (mark.at() == null) {
                return;
            }
            wire.designate(mark.body(), mark.at());
        } catch (Throwable ignored) {
        }
    }

    /** Takes the mark filed for the round being built, so it is handed out once. */
    private static Mark take() {
        Mark mark = pending;
        pending = null;
        return mark;
    }

    /** Forgets a mark when the shot came to nothing. */
    public static void clear() {
        pending = null;
    }

    /**
     * The computer that laid this shot: the tube's own wire first, and failing
     * that the computer that has the mount the tube stands on. Two computers on
     * one gun leave the choice to the player rather than guessing.
     */
    /** The swim the computer this tube answers to files, or the plain values. */
    private static TorpedoLaunchSettings.Values settingsFor(Level level, PitchOrientedContraptionEntity gun) {
        try {
            StabilizerControllerBlockEntity controller = controllerFor(gun);
            if (controller == null) {
                return TorpedoLaunchSettings.plain();
            }
            return TorpedoLaunchSettings.forController(level, controller);
        } catch (Throwable ignored) {
            return TorpedoLaunchSettings.plain();
        }
    }

    /** The tube the shot is being built in, or null when the gun carries none of ours. */
    private static BlockEntity tubeOf(PitchOrientedContraptionEntity gun) {
        try {
            if (gun.getContraption() instanceof AbstractMountedCannonContraption cannon) {
                return TorpedoTubeBinding.boundTube(cannon.presentBlockEntities.values());
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static StabilizerControllerBlockEntity controllerFor(PitchOrientedContraptionEntity gun) {
        BlockEntity tube = tubeOf(gun);
        if (tube != null) {
            StabilizerControllerBlockEntity wired = TorpedoTubeBinding.controller(tube);
            if (wired != null) {
                return wired;
            }
        }
        StabilizerControllerBlockEntity found = null;
        for (StabilizerControllerBlockEntity controller : ControllerTracker.loadedControllers()) {
            if (controller == null || controller.isRemoved() || controller.getLevel() == null) {
                continue;
            }
            try {
                if (!controller.controlsCannonContraption(gun)) {
                    continue;
                }
            } catch (Throwable ignored) {
                continue;
            }
            if (found != null && found != controller) {
                return null;
            }
            found = controller;
        }
        return found;
    }

    /** Where a hull stands now, or null when she is gone or was never named. */
    private static Vec3 hullCentre(Level level, UUID body) {
        if (body == null) {
            return null;
        }
        try {
            SubLevel hull = PhysicsGroups.resolve(level, body);
            return hull == null ? null : hull.boundingBox().toMojang().getCenter();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** The hull a point stands on, or null when it stands in open water. */
    private static UUID bodyAt(Level level, Vec3 at) {
        try {
            for (UUID body : PhysicsGroups.bodiesContaining(level, at, 0.5D)) {
                if (body != null) {
                    return body;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}

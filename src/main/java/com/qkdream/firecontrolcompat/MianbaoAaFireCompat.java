package com.qkdream.firecontrolcompat;

import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.content.controller.ControllerTracker;
import com.hooya.stabilizedturret.content.controller.MianbaoAircraftCompat;
import com.hooya.stabilizedturret.content.controller.MianbaoAirDefenseCompat;
import com.hooya.stabilizedturret.content.controller.MianbaoMissileCompat;
import com.hooya.stabilizedturret.content.controller.OrdinaryAirDefenseGuidance;
import com.hooya.stabilizedturret.content.controller.OrdinaryAirDefenseLauncher;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.hooya.stabilizedturret.content.iff.FireControlMissileOwnership;
import net.mcreator.myfirstmod.init.MianbaosModernwarfareModEntities;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Fires a bound 面包学 air-defense launcher ourselves when the pack refuses the shot.
 *
 * <h2>Why this exists</h2>
 *
 * <p>0.9.4 declares <b>two</b> {@code MianbaoAirDefenseCompat.fireOne} overloads:</p>
 * <pre>
 *   fireOne(Level, BindingRef)                                     // one-line delegate
 *   fireOne(Level, BindingRef, StabilizerControllerBlockEntity)    // the implementation
 * </pre>
 * <p>{@code StabilizerControllerBlockEntity.fireNextAaMissile} (:693) calls the <b>three-argument</b>
 * one. This mod's probe handlers used to sit on the two-argument one only, which is why a session log
 * could show a fully satisfied {@code aa-gate} line and no {@code aa fire:} line at all: the delegate
 * was never on the path. Both overloads are probed now (see {@code MianbaoAirDefenseCompatMixin}).</p>
 *
 * <p>The step after that is the pack's own ordinary-round launch,
 * {@code OrdinaryAirDefenseLauncher.fire}, and it has one branch that fails silently on a launcher
 * standing inside a Sable sub-level: it resolves its spawn level with
 * {@code server.getLevel(level.dimension())}, which answers null for a structure's own plot, so a
 * loaded launcher on a moving craft refuses the shot with nothing in the log. This class repeats that
 * launch with the level the launcher really lives in, step by step, and prints which step refused -
 * so the next session log says whether the shot is the pack's, ours, or still impossible.</p>
 */
public final class MianbaoAaFireCompat {

    private MianbaoAaFireCompat() {
    }

    /**
     * The launch 面包学 would have performed, tried again with a spawn level that is known good.
     *
     * @return whether a round was put in the air by this call
     */
    public static boolean tryFire(Level level, BindingRef ref, StabilizerControllerBlockEntity computer) {
        if (level == null || ref == null) {
            return false;
        }
        try {
            BlockEntity launcher = launcherAt(level, ref);
            if (launcher == null) {
                report(ref, "no-launcher");
                return false;
            }
            // The computer this rail answers to. A crewed mount hands its shot over through the
            // two-argument {@code fireOne}, which carries no computer at all - and with none, the
            // pack's guidance refuses and nothing writes the ownership mark. A round without that
            // mark is read by our radars as an unidentified contact, so a gunner engages the round
            // she has just fired and empties every tube. Resolved here so both exist.
            StabilizerControllerBlockEntity owner = computer != null ? computer : ownerOf(level, ref);
            boolean ordinary = OrdinaryAirDefenseLauncher.hasRound(launcher);
            int ammo = slotCount(launcher);
            Vec3 muzzle = MianbaoAirDefenseCompat.getLauncherMuzzlePosition(level, ref);
            Vec3 direction = MianbaoAirDefenseCompat.getLauncherDirection(level, ref);
            if (muzzle == null || direction == null) {
                report(ref, "no-muzzle muzzle=" + (muzzle != null) + " dir=" + (direction != null));
                return false;
            }
            if (!ordinary) {
                // A slot of 面包学's own high-speed round is thrown by the launcher block's own
                // procedure, which the pack has already run and reported as refused. Nothing of ours
                // to add there - the line above says the pack's path is the one to look at.
                report(ref, "pack-native-round");
                return false;
            }
            Entity missile = spawn(level, launcher, muzzle);
            if (missile == null) {
                return false;
            }
            Vec3 carrier = carryVelocity(level, ref, launcher);
            Vec3 velocity = direction.scale(2.0D).add(carrier);
            if (velocity.length() > 17.015D) {
                velocity = velocity.normalize().scale(17.015D);
            }
            MianbaoMissileCompat.setWorldVelocity(missile, velocity);
            missile.getPersistentData().putBoolean("MianbaoCarrierMotionApplied", true);
            missile.setSilent(true);
            missile.setNoGravity(true);
            if (missile instanceof AbstractArrow arrow) {
                arrow.setBaseDamage(15.0D);
                arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
            }
            // The identity, written first and on its own: this is the tag our radars read as
            // "friendly" ({@code IffRadar#mark} -> {@code FireControlMissileOwnership#isFriendly}),
            // and it is the difference between a round a gunner ignores and a round she fires at.
            boolean owned = false;
            if (owner != null) {
                try {
                    FireControlMissileOwnership.markController(missile, owner);
                    owned = true;
                } catch (RuntimeException | LinkageError ignored) {
                    // the guidance below writes the same tag; this is the early copy
                }
            }
            try {
                OrdinaryAirDefenseGuidance.adopt(missile, owner);
                owned = owned || owner != null;
            } catch (RuntimeException | LinkageError ignored) {
                // guidance is the pack's: a refusal there still leaves a round in the air
            }
            ServerLevel spawnLevel = (ServerLevel) missile.level();
            if (!spawnLevel.addFreshEntity(missile)) {
                try {
                    // The pack's cancelLaunch is package-private; dropping the round is all it does
                    // to the entity itself, and the guidance entry it also clears was never added
                    // because the round never entered the level.
                    missile.discard();
                } catch (RuntimeException | LinkageError ignored) {
                }
                report(ref, "add-fresh-entity-refused level=" + spawnLevel.dimension().location());
                return false;
            }
            takeOne(launcher);
            FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] aa-fireone: fired by us ref={} ammo {}->{} entity={} level={} muzzle={}"
                            + " iff={} owner={}",
                    ref.pos(), ammo, slotCount(launcher), missile.getType(),
                    spawnLevel.dimension().location(), muzzle,
                    owned ? "friendly" : "unmarked",
                    owner == null ? "-" : owner.getBlockPos().toShortString());
            return true;
        } catch (Throwable failure) {
            FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] aa-fireone: our launch failed ref={} reason={}",
                    ref.pos(), failure.toString());
            return false;
        }
    }

    /**
     * The computer that holds this rail, for a caller that did not carry one.
     *
     * <p>Matched by the rail's own position against every loaded computer's bound anti-air
     * launchers - the same test the pack uses when it walks the rails.</p>
     */
    private static StabilizerControllerBlockEntity ownerOf(Level level, BindingRef ref) {
        try {
            for (StabilizerControllerBlockEntity controller : ControllerTracker.loadedControllers()) {
                if (controller == null || controller.getLevel() == null) {
                    continue;
                }
                for (BindingRef bound : controller.getAaMissileLaunchers()) {
                    if (bound == ref || (bound.pos() != null && bound.pos().equals(ref.pos()))
                            || bound.matches(level, ref.pos())) {
                        return controller;
                    }
                }
            }
        } catch (RuntimeException | LinkageError ignored) {
            // no owner: the round still flies, it is just not identifiable
        }
        return null;
    }

    /**
     * Creates the round inside the level the launcher actually stands in.
     *
     * <p>The pack resolves that level with {@code server.getLevel(level.dimension())}. A sub-level's
     * dimension is not one the server holds, so that call comes back null and the pack returns
     * {@code false} before spawning anything. When the level handed to us <em>is</em> the launcher's
     * own server level, it is used directly.</p>
     */
    private static Entity spawn(Level level, BlockEntity launcher, Vec3 muzzle) {
        EntityType<?> type = null;
        try {
            type = MianbaosModernwarfareModEntities.PORTABLEANTIAIRMISSILETANSHE.get();
        } catch (RuntimeException | LinkageError ignored) {
            type = null;
        }
        if (type == null) {
            FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] aa-fireone: 面包学 does not register the portable anti-air round here");
            return null;
        }
        for (ServerLevel candidate : levelsOf(level, launcher)) {
            Entity missile = type.create(candidate);
            if (missile == null) {
                continue;
            }
            missile.setPos(muzzle.x, muzzle.y, muzzle.z);
            return missile;
        }
        FireControlCompat.LOGGER.info(
                "[firecontrolcompat] aa-fireone: no server level to spawn the round in (level={})",
                level == null ? "-" : level.dimension().location());
        return null;
    }

    /**
     * Every server level the round could be spawned in, most likely first.
     *
     * <p>The muzzle and the direction are both read through
     * {@code Sable.HELPER.projectOutOfSubLevel} (see
     * {@code MianbaoAirDefenseCompat#getLauncherMuzzlePosition}), so they are <b>world</b>
     * coordinates - and a world coordinate only means something in the level that hosts the
     * sub-level's plot, which is exactly what {@code SubLevel#getLevel} answers. That level is
     * therefore tried before the rail's own, which for a structure's block is the plot's level.</p>
     */
    private static java.util.List<ServerLevel> levelsOf(Level level, BlockEntity launcher) {
        java.util.LinkedHashSet<ServerLevel> found = new java.util.LinkedHashSet<>();
        try {
            dev.ryanhcode.sable.sublevel.SubLevel subLevel = launcher == null || launcher.getLevel() == null
                    ? null
                    : dev.ryanhcode.sable.Sable.HELPER.getContaining(
                            launcher.getLevel(), launcher.getBlockPos());
            if (subLevel != null && !subLevel.isRemoved()
                    && subLevel.getLevel() instanceof ServerLevel parent) {
                found.add(parent);
            }
        } catch (Throwable ignored) {
        }
        try {
            Level own = launcher == null ? null : launcher.getLevel();
            if (own instanceof ServerLevel serverLevel) {
                found.add(serverLevel);
            }
        } catch (RuntimeException | LinkageError ignored) {
        }
        try {
            if (level instanceof ServerLevel serverLevel) {
                found.add(serverLevel);
            }
            MinecraftServer server = level == null ? null : level.getServer();
            ServerLevel byDimension = server == null || level == null
                    ? null : server.getLevel(level.dimension());
            if (byDimension != null) {
                found.add(byDimension);
            }
        } catch (RuntimeException | LinkageError ignored) {
        }
        return new java.util.ArrayList<>(found);
    }

    /** The launcher's own share of its carrier's speed, projected the way the pack does it. */
    private static Vec3 carryVelocity(Level level, BindingRef ref, BlockEntity launcher) {
        try {
            Vec3 local = Vec3.atCenterOf(launcher.getBlockPos()).add(0.0D, 0.0D, 0.0D);
            return MianbaoAircraftCompat.carrierVelocityAtPoint(level, ref, local);
        } catch (RuntimeException | LinkageError ignored) {
            return Vec3.ZERO;
        }
    }

    /** The launcher's block entity, resolved first and rebuilt-looked-up second. */
    private static BlockEntity launcherAt(Level level, BindingRef ref) {
        try {
            BlockEntity resolved = ref.resolve(level);
            if (resolved != null) {
                return resolved;
            }
        } catch (RuntimeException | LinkageError ignored) {
        }
        try {
            return ref.resolveRebuiltAirDefenseLauncher(level);
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static int slotCount(BlockEntity launcher) {
        try {
            if (launcher instanceof Container container && container.getContainerSize() > 0) {
                ItemStack stack = container.getItem(0);
                return stack == null || stack.isEmpty() ? 0 : stack.getCount();
            }
        } catch (RuntimeException | LinkageError ignored) {
        }
        return -1;
    }

    /** Takes the round off the rail the way the pack's own launch does. */
    private static void takeOne(BlockEntity launcher) {
        try {
            if (launcher instanceof Container container && container.getContainerSize() > 0) {
                container.removeItem(0, 1);
                launcher.setChanged();
                Level level = launcher.getLevel();
                if (level != null) {
                    level.sendBlockUpdated(launcher.getBlockPos(), launcher.getBlockState(),
                            launcher.getBlockState(), 3);
                }
            }
        } catch (RuntimeException | LinkageError ignored) {
        }
    }

    private static void report(BindingRef ref, String reason) {
        FireControlCompat.LOGGER.info("[firecontrolcompat] aa-fireone: refused by us ref={} reason={} block={}",
                ref == null ? "-" : ref.pos(), reason, blockName(ref));
    }

    private static String blockName(BindingRef ref) {
        try {
            return ref == null || ref.save() == null ? "-" : ref.save().getString("Block");
        } catch (RuntimeException | LinkageError ignored) {
            return "-";
        }
    }

    /** The item sitting on the rail, for the diagnostic line. */
    public static String roundName(BlockEntity launcher) {
        try {
            if (launcher instanceof Container container && container.getContainerSize() > 0) {
                ItemStack stack = container.getItem(0);
                if (stack != null && !stack.isEmpty()) {
                    return String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem()));
                }
            }
        } catch (RuntimeException | LinkageError ignored) {
        }
        return "none";
    }
}

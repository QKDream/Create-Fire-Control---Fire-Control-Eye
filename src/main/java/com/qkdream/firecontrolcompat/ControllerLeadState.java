package com.qkdream.firecontrolcompat;

import java.util.UUID;

import com.hooya.stabilizedturret.content.controller.ControllerTracker;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.qkdream.firecontrolcompat.network.ControllerLeadSyncPayload;

import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Vector3d;

/**
 * Lead settings of one fire control computer.
 *
 * <p>Which cannon family the sight solves for, and the powder charge of a big cannon shot, belong
 * to the computer the crew is actually using - two computers on the same field are usually sighting
 * two different guns. They are stored on the computer itself, in the block entity's own tag, and
 * also in the world's saved data under that computer's own key ({@link ControllerLeadStore}),
 * because a computer standing on a Sable structure is not saved through the ordinary chunk path.
 * The mod-level default is only read for a computer that has never been set, and this class never
 * writes it except as a logged last resort: a world-wide value would let two computers overwrite
 * each other.</p>
 *
 * <p>Every step that can fail says so in the log, with the source it settled on, so a report of
 * "the setting did not stay" can be read straight off the log instead of guessed at: did the page
 * send the pair, did the server find the computer, did the pair reach the tag and the store, and
 * did the answer travel back to the page.</p>
 */
public final class ControllerLeadState {

    /** Key the cannon family is stored under. */
    public static final String KIND_KEY = "firecontrolcompatLeadKind";
    /** Key the big cannon powder charge is stored under. */
    public static final String CHARGE_KEY = "firecontrolcompatBigCharge";

    private ControllerLeadState() {
    }

    /** The family this computer solves for, or the default when it has never been set. */
    public static LeadCannonKind kindOf(BlockEntity controller) {
        if (controller == null) {
            return FireControlLeadSettings.leadCannonKind();
        }
        CompoundTag tag = controller.getPersistentData();
        if (tag.contains(KIND_KEY)) {
            try {
                return LeadCannonKind.valueOf(tag.getString(KIND_KEY));
            } catch (IllegalArgumentException unknown) {
                // Falls through to the saved copy.
            }
        }
        LeadCannonKind filed = ControllerLeadStore.kindOf(controller.getLevel(),
                ControllerLeadStore.keyOf(controller.getLevel(), controller));
        return filed == null ? FireControlLeadSettings.leadCannonKind() : filed;
    }

    /** Stores the family on this computer. */
    public static void setKind(BlockEntity controller, LeadCannonKind kind) {
        if (controller == null || kind == null) {
            return;
        }
        controller.getPersistentData().putString(KIND_KEY, kind.name());
        controller.setChanged();
        ControllerLeadStore.put(controller.getLevel(),
                ControllerLeadStore.keyOf(controller.getLevel(), controller), kind, -1);
    }

    /** The powder charge this computer uses for big cannon shots. */
    public static int chargeOf(BlockEntity controller) {
        if (controller == null) {
            return FireControlLeadSettings.bigCannonCharge();
        }
        CompoundTag tag = controller.getPersistentData();
        if (tag.contains(CHARGE_KEY)) {
            return clampCharge(tag.getInt(CHARGE_KEY));
        }
        int filed = ControllerLeadStore.chargeOf(controller.getLevel(),
                ControllerLeadStore.keyOf(controller.getLevel(), controller));
        return filed < 0 ? FireControlLeadSettings.bigCannonCharge() : clampCharge(filed);
    }

    /** Stores the powder charge on this computer. */
    public static void setCharge(BlockEntity controller, int charge) {
        if (controller == null) {
            return;
        }
        int clamped = clampCharge(charge);
        controller.getPersistentData().putInt(CHARGE_KEY, clamped);
        controller.setChanged();
        ControllerLeadStore.put(controller.getLevel(),
                ControllerLeadStore.keyOf(controller.getLevel(), controller), null, clamped);
    }

    private static int clampCharge(int charge) {
        return Math.max(FireControlLeadSettings.MIN_BIG_CANNON_CHARGE,
                Math.min(FireControlLeadSettings.MAX_BIG_CANNON_CHARGE, charge));
    }

    /**
     * Writes the two values a settings page sent onto the computer it belongs to.
     *
     * <p>The pair is filed under the page's own key <em>before</em> the computer is
     * looked up, so a computer that is not loaded - an unloaded chunk, a structure
     * that is not ticked yet - still keeps what the crew set instead of dropping
     * it. The tag is written as well whenever the computer can be found.</p>
     */
    public static void applyFor(ServerPlayer player, BlockPos pos, UUID subLevel, String kind, int charge) {
        if (player == null || pos == null) {
            log("lead-save recv REJECTED player={} pos={}", player, pos);
            return;
        }
        LeadCannonKind parsed = null;
        try {
            parsed = kind == null || kind.isEmpty() ? null : LeadCannonKind.valueOf(kind);
        } catch (IllegalArgumentException unknown) {
            parsed = null;
        }
        log("lead-save recv pos={} sub={} kind={} charge={}", pos, subLevel, kind, charge);

        ServerLevel level = player.serverLevel();
        // Filed under the page's own key first: this cannot fail, so the pair is kept
        // even when the computer itself cannot be resolved below.
        ControllerLeadStore.put(level, ControllerLeadStore.keyOf(level, pos, subLevel), parsed,
                charge < 0 ? -1 : clampCharge(charge));

        StabilizerControllerBlockEntity controller = resolve(player, pos, subLevel, "save");
        if (controller == null) {
            // Last resort only, and said out loud in the log so a fallback value is
            // never mistaken for a computer's own answer.
            FireControlLeadSettings.applyLeadTarget(
                    parsed == null ? FireControlLeadSettings.leadCannonKind() : parsed,
                    clampCharge(charge < 0 ? FireControlLeadSettings.bigCannonCharge() : charge));
            log("lead-save RESOLVE-FAILED source=global-fallback kind={} charge={}", parsed, charge);
            return;
        }
        if (parsed != null) {
            setKind(controller, parsed);
        }
        setCharge(controller, charge);
        String resolvedKey = ControllerLeadStore.keyOf(controller.getLevel(), controller);
        if (!resolvedKey.equals(ControllerLeadStore.keyOf(level, pos, subLevel))) {
            ControllerLeadStore.put(controller.getLevel(), resolvedKey, parsed, clampCharge(charge));
        }
        log("lead-save written source=controller tagKind={} tagCharge={} readKind={} readCharge={}",
                controller.getPersistentData().getString(KIND_KEY),
                controller.getPersistentData().getInt(CHARGE_KEY),
                kindOf(controller), chargeOf(controller));
    }

    /** Tells one player what the computer it just opened carries. */
    public static void sendStatus(ServerPlayer player, BlockPos pos, UUID subLevel) {
        if (player == null || pos == null) {
            return;
        }
        StabilizerControllerBlockEntity controller = resolve(player, pos, subLevel, "status");
        if (controller == null) {
            // The computer itself could not be found, but the pair was filed under the
            // page's own key when it was saved, and that key needs no computer to read:
            // this is still that one computer's own answer, never the mod default, so
            // two computers cannot show each other's gun.
            String key = ControllerLeadStore.keyOf(player.serverLevel(), pos, subLevel);
            LeadCannonKind filed = ControllerLeadStore.kindOf(player.serverLevel(), key);
            int filedCharge = ControllerLeadStore.chargeOf(player.serverLevel(), key);
            if (filed == null && filedCharge < 0) {
                log("lead-sync SKIPPED source=none pos={} sub={}", pos, subLevel);
                return;
            }
            log("lead-sync send pos={} key={} kind={} charge={} source=store-by-key", pos, key, filed, filedCharge);
            PacketDistributor.sendToPlayer(player, new ControllerLeadSyncPayload(pos, subLevel,
                    (filed == null ? FireControlLeadSettings.leadCannonKind() : filed).name(),
                    filedCharge < 0 ? FireControlLeadSettings.bigCannonCharge() : clampCharge(filedCharge)));
            return;
        }
        LeadCannonKind kind = kindOf(controller);
        int charge = chargeOf(controller);
        log("lead-sync send pos={} key={} kind={} charge={} source={}", pos,
                ControllerLeadStore.keyOf(controller.getLevel(), controller), kind, charge,
                controller.getPersistentData().contains(KIND_KEY) ? "tag" : "store-or-default");
        PacketDistributor.sendToPlayer(player,
                new ControllerLeadSyncPayload(pos, subLevel, kind.name(), charge));
    }

    /**
     * Finds the computer a settings page belongs to.
     *
     * <p>Fire control's own tracker is tried first, exactly as the launch mode and
     * the call sign look a computer up. That lookup also matches the structure the
     * computer stands on, which is the first thing to fail after a structure has
     * been moved or rebuilt, so four cheaper fallbacks follow: the tracker again
     * without the structure, the level itself, the block entity actors of the
     * matching structure, and finally every structure's actors compared both in
     * plot space and in world space - a page can carry either, depending on
     * whether the crew opened it before or after the ship was assembled.</p>
     */
    public static StabilizerControllerBlockEntity resolve(ServerPlayer player, BlockPos pos, UUID subLevel,
            String reason) {
        if (player == null || pos == null) {
            log("lead-resolve reason={} source=none pos={}", reason, pos);
            return null;
        }
        try {
            StabilizerControllerBlockEntity exact = ControllerTracker.findLoadedForSettings(player, pos, subLevel);
            if (exact != null) {
                log("lead-resolve reason={} pos={} sub={} source=tracker", reason, pos, subLevel);
                return exact;
            }
        } catch (Throwable failure) {
            log("lead-resolve reason={} tracker threw {}", reason, failure.toString());
        }
        try {
            ServerLevel level = player.serverLevel();
            StabilizerControllerBlockEntity byPos = ControllerTracker.findLoaded(level, pos);
            if (byPos != null) {
                log("lead-resolve reason={} pos={} source=tracker-pos", reason, pos);
                return byPos;
            }
        } catch (Throwable failure) {
            log("lead-resolve reason={} tracker-pos threw {}", reason, failure.toString());
        }
        try {
            ServerLevel level = player.serverLevel();
            if (level != null) {
                BlockEntity direct = level.getBlockEntity(pos);
                if (direct instanceof StabilizerControllerBlockEntity controller) {
                    log("lead-resolve reason={} pos={} source=level", reason, pos);
                    return controller;
                }
            }
        } catch (Throwable failure) {
            log("lead-resolve reason={} level threw {}", reason, failure.toString());
        }
        try {
            StabilizerControllerBlockEntity byPlot = scanPlots(player.serverLevel(), pos, subLevel, reason);
            if (byPlot != null) {
                return byPlot;
            }
        } catch (Throwable failure) {
            log("lead-resolve reason={} plots threw {}", reason, failure.toString());
        }
        log("lead-resolve reason={} pos={} sub={} source=none", reason, pos, subLevel);
        return null;
    }

    /**
     * Looks through the block entity actors of every Sable structure, which is
     * where a computer standing on a ship actually lives.
     */
    private static StabilizerControllerBlockEntity scanPlots(ServerLevel level, BlockPos pos, UUID subLevel,
            String reason) {
        if (level == null) {
            return null;
        }
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return null;
        }
        StabilizerControllerBlockEntity plotMatch = null;
        int plotMatches = 0;
        StabilizerControllerBlockEntity worldMatch = null;
        int worldMatches = 0;
        for (SubLevel structure : container.getAllSubLevels()) {
            if (structure == null || structure.isRemoved()) {
                continue;
            }
            boolean sameStructure = subLevel == null || subLevel.equals(structure.getUniqueId());
            LevelPlot plot;
            try {
                plot = structure.getPlot();
            } catch (Throwable ignored) {
                continue;
            }
            if (plot == null) {
                continue;
            }
            for (BlockEntitySubLevelActor actor : plot.getBlockEntityActors()) {
                if (!(actor instanceof StabilizerControllerBlockEntity controller) || !sameStructure) {
                    continue;
                }
                BlockPos localPos = controller.getBlockPos();
                if (localPos.equals(pos)) {
                    plotMatch = controller;
                    plotMatches++;
                    continue;
                }
                try {
                    Vector3d world = new Vector3d(localPos.getX() + 0.5D, localPos.getY() + 0.5D,
                            localPos.getZ() + 0.5D);
                    structure.logicalPose().transformPosition(world);
                    if (BlockPos.containing(world.x, world.y, world.z).equals(pos)) {
                        worldMatch = controller;
                        worldMatches++;
                    }
                } catch (Throwable ignored) {
                    // A structure without a readable pose simply takes no part.
                }
            }
        }
        if (plotMatches == 1) {
            log("lead-resolve reason={} pos={} source=plot", reason, pos);
            return plotMatch;
        }
        if (worldMatches == 1) {
            log("lead-resolve reason={} pos={} source=plot-world", reason, pos);
            return worldMatch;
        }
        if (plotMatches > 1 || worldMatches > 1) {
            log("lead-resolve reason={} pos={} source=ambiguous plot={} world={}", reason, pos, plotMatches,
                    worldMatches);
        }
        return null;
    }

    private static void log(String message, Object... arguments) {
        try {
            FireControlCompat.LOGGER.info("[firecontrolcompat] " + message, arguments);
        } catch (Throwable ignored) {
            // A log line must never take the caller down.
        }
    }
}

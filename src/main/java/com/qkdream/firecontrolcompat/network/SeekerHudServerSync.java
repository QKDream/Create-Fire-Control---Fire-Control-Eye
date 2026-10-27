package com.qkdream.firecontrolcompat.network;

import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.content.controller.ControllerTracker;
import com.hooya.stabilizedturret.content.controller.MianbaoAircraftCompat;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.qkdream.firecontrolcompat.AtgmAirRack;
import com.qkdream.firecontrolcompat.BeamMissileCompat;
import com.qkdream.firecontrolcompat.BombRackCompat;
import com.qkdream.firecontrolcompat.FireControlCompat;
import com.qkdream.firecontrolcompat.IrAtgm;
import com.qkdream.firecontrolcompat.IrSeekerSession;
import com.qkdream.firecontrolcompat.SubLevelPositions;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Feeds the pre-launch seeker range frame. While the player sits in a
 * fire-control-bound seat, the seated controller's infrared readiness and the
 * launch geometry of the first ready launcher / rack are streamed to that
 * player, so the HUD can draw the frame along the missile's launch direction.
 */
@EventBusSubscriber(modid = FireControlCompat.MOD_ID)
public final class SeekerHudServerSync {

    private static int syncCounter;

    /**
     * The one name the anti-radiation round is reported under.
     *
     * <p>Kept in one place because three different hooks compare against it - the enum label, the
     * weapon-slot row and the bomb sight all decide "this is the anti-radiation round" by reading
     * the reported name back.</p>
     */
    public static final String ARM_LABEL = "反辐射导弹";

    private SeekerHudServerSync() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        // The air weapon slot is named from every loaded computer once a second, not only while
        // somebody is sitting in the aircraft: the crew that opens the input page or reads the
        // "weapon selected" message is on foot, and that is precisely when the old per-seat sync
        // left the slot on 面包学's stock wording.
        try {
            AtgmAirRack.scanLoadedControllers();
        } catch (Throwable ignored) {
            // no fire control computer loaded: nothing to name
        }
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
                || !ModList.get().isLoaded("create_fire_control")) {
            return;
        }
        StabilizerControllerBlockEntity controller =
                ControllerTracker.findForVehicle(player.level(), player.getVehicle());
        boolean seated = controller != null;
        if (controller == null) {
            // Standing at a computer that is bound to one of our air racks. Only the names of the
            // weapon slots are reported on this route - the seeker frames belong to a gunner who is
            // actually in the seat - so nothing is drawn and no launch geometry is sent.
            controller = nearbyController(player);
        }
        if (controller == null || controller.getLevel() == null) {
            return;
        }
        if (!seated && player.tickCount % 10 != 0) {
            // The on-foot route only carries slot names, and a name does not change ten times a
            // second: the walk over the loaded computers is done once a second per standing player
            // instead of every tick. (The name itself is refreshed by AtgmAirRack's own scan.)
            return;
        }
        boolean groundIrReady = false;
        boolean aircraftIrSelected = false;
        String aircraftGroundName = null;
        boolean searching = false;
        Vec3 worldOrigin = null;
        Vec3 worldDirection = null;
        Vec3 lockedTarget = null;
        Vec3 cuedTarget = null;
        Vec3 frameAnchor = null;
        Level level = controller.getLevel();
        ServerLevel serverLevel = level instanceof ServerLevel server ? server : null;

        // A seeker that is already watching owns the frame, and the contact it
        // settled on is what the HUD squares with the small lock frame. Only the gunner's own
        // controller is asked: a crew member standing at the computer is not looking down the sight,
        // and its frames must not appear over their screen.
        if (seated) {
            for (BindingRef launcher : groundLaunchers(controller)) {
                IrSeekerSession.Session session = serverLevel == null
                        ? null : IrSeekerSession.active(serverLevel, launcher.pos());
                if (session != null) {
                    searching = true;
                    groundIrReady = true;
                    worldOrigin = session.origin();
                    worldDirection = session.boresight();
                    lockedTarget = session.lockPosition();
                    cuedTarget = cuePosition(session);
                    frameAnchor = session.fovAnchor();
                    break;
                }
            }
        }
        if (seated && !searching
                && controller.getSelectedAircraftWeapon() == MianbaoAircraftCompat.WeaponType.CLOSE) {
            for (BindingRef rack : controller.getAircraftMissileRacks()) {
                IrSeekerSession.Session session = serverLevel == null
                        ? null : IrSeekerSession.active(serverLevel, rack.pos());
                if (session != null) {
                    searching = true;
                    aircraftIrSelected = true;
                    worldOrigin = session.origin();
                    worldDirection = session.boresight();
                    lockedTarget = session.lockPosition();
                    cuedTarget = cuePosition(session);
                    frameAnchor = session.fovAnchor();
                    break;
                }
            }
        }

        // With no seeker running nothing is drawn at all, not even the readiness
        // hint: both frames belong to a head that is actually watching, so they
        // appear on the first trigger press and vanish with the head.
        if (seated && !searching) {
            for (BindingRef launcher : groundLaunchers(controller)) {
                if (BeamMissileCompat.hasGroundIrAmmo(level, launcher)) {
                    groundIrReady = true;
                    break;
                }
            }
            if (controller.getSelectedAircraftWeapon() == MianbaoAircraftCompat.WeaponType.CLOSE) {
                for (BindingRef rack : controller.getAircraftMissileRacks()) {
                    if (BeamMissileCompat.isIrRackLoaded(level, rack)) {
                        aircraftIrSelected = true;
                        break;
                    }
                }
            }
        }
        // An anti-radiation round on a rail is named on the HUD as itself: the rail block has been
        // swapped for 面包学's loaded variant, so the close-range slot would otherwise print the
        // close-combat missile's name while the trigger is about to release a radiation seeker.
        //
        // The name is reported for the slot that is *selected*, never for both at once. It used to
        // be set whenever any bound rail carried an anti-radiation round, which also renamed the
        // air-to-ground slot - a computer with an anti-radiation rail beside a bomb rack drew the
        // radiation name over the bombs, on the HUD and in the bomb sight alike.
        // RETIRED(ir-arm-rounds): the anti-radiation scan and the slot rename that went with it are
        // gone with the round. 面包学 names its own rails, so nothing here replaces that name.
        int racks = controller.getAircraftMissileRacks().size();
        MianbaoAircraftCompat.WeaponType selectedAircraft = controller.getSelectedAircraftWeapon();
        // The infrared anti-tank round names the air-to-ground slot whenever it stands on a bound
        // rail - <b>whether or not that slot is the selected one</b>. The old gate on
        // {@code selected == AIR_TO_GROUND_DIRECT} was right for the flight HUD (which prints one
        // line) and wrong for the input page, which lists every aircraft slot at once: the row kept
        // 面包学's 「普通空对地导弹」 unless the crew happened to have that slot selected already.
        aircraftGroundName = AtgmAirRack.slotLabel(level, controller);
        if (aircraftGroundName == null
                && selectedAircraft == MianbaoAircraftCompat.WeaponType.AIR_TO_GROUND_DIRECT) {
            // The bomb and cruise families keep their own naming, which is a property of the
            // <em>selected</em> slot.
            aircraftGroundName = BombRackCompat.airToGroundSlotName(
                    level, controller.getAircraftMissileRacks(), controller.getBlockPos());
        }
        // The ground anti-tank line is named by the round that is loaded, not by the launcher block:
        // 0.9.4 prints GroundWeapon#displayName() ("反坦克导弹"), which is the launcher family, and the
        // infrared anti-tank round is a different weapon from the pack's own anti-tank missile. Only a
        // bound launcher that is actually holding this mod's round renames the line.
        String groundWeaponName = firecontrolcompat$groundSlotName(level, controller);
        PacketDistributor.sendToPlayer(player, new SeekerHudPayload(groundIrReady, aircraftIrSelected,
                aircraftGroundName, groundWeaponName, searching, worldOrigin, worldDirection, lockedTarget, frameAnchor));
        firecontrolcompat$racks = racks;
        if (syncCounter++ % 40 == 0) {
            FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] seeker hud sync player={} ground={} aircraft={} searching={} racks={}"
                            + " selected={} name={} groundName={} origin={} dir={} lock={} cue={}",
                    player.getName().getString(), groundIrReady, aircraftIrSelected, searching,
                    racks, selectedAircraft == null ? "none" : selectedAircraft.name(),
                    aircraftGroundName == null ? "none" : aircraftGroundName,
                    groundWeaponName == null ? "none" : groundWeaponName,
                    worldOrigin, worldDirection, lockedTarget, cuedTarget);
        }
    }

    /** Last ground-slot naming decision reported, so the diagnostic prints on a change, not per tick. */
    private static String firecontrolcompat$lastGroundName = "";

    /** How close a crew member has to stand to a computer for it to name their weapon rows. */
    private static final double NEARBY_CONTROLLER_RANGE = 64.0D;

    /**
     * The computer a crew member standing on foot is at, or {@code null}.
     *
     * <p>The input page, the "weapon selected" message and the bomb sight are all read by somebody
     * who is <b>not</b> sitting in the aircraft - the gunsmith at the computer, the pilot who stepped
     * out to load a rail. The names of the weapon rows therefore have to reach them, but the launch
     * geometry must not: only the names are put on the payload for this route, and the seeker frames
     * stay false so nothing is drawn. The distance is measured in the projected space
     * ({@link SubLevelPositions#worldCenter}) because a computer on an aircraft is stored in a Sable
     * plot twenty million blocks away from where the crew sees it.</p>
     */
    private static StabilizerControllerBlockEntity nearbyController(ServerPlayer player) {
        StabilizerControllerBlockEntity best = null;
        double bestDistance = NEARBY_CONTROLLER_RANGE * NEARBY_CONTROLLER_RANGE;
        try {
            for (StabilizerControllerBlockEntity controller : ControllerTracker.loadedControllers()) {
                if (controller == null || controller.isRemoved()) {
                    continue;
                }
                Level level = controller.getLevel();
                if (level == null || level.isClientSide
                        || level.getServer() != player.getServer()
                        || !level.dimension().equals(player.level().dimension())) {
                    continue;
                }
                Vec3 at = SubLevelPositions.worldCenter(level, controller.getBlockPos());
                if (at == null) {
                    continue;
                }
                double distance = at.distanceToSqr(player.position());
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = controller;
                }
            }
        } catch (RuntimeException | LinkageError ignored) {
            // no computer reachable
        }
        return best;
    }

    /**
     * The name the ground anti-tank line reads while one of the six bound launchers holds this mod's
     * round, or {@code null} to keep 火控学's own wording.
     *
     * <p>Read from the same slot zero the launcher is loaded through
     * ({@code BeamMissileCompat.irAtgmRounds}), so a launcher holding 面包学's own
     * {@code antitankmissile_1/_2} answers {@code null} and is <b>not</b> renamed - which is the one
     * thing the request was explicit about. The decision is logged whenever it changes, so
     * "the HUD still says 反坦克导弹" can be told apart from "the rename never reached the client".</p>
     */
    private static String firecontrolcompat$groundSlotName(Level level, StabilizerControllerBlockEntity controller) {
        String name = null;
        int launchers = 0;
        int loaded = 0;
        try {
            for (BindingRef launcher : controller.getMissileLaunchers()) {
                if (launcher == null) {
                    continue;
                }
                launchers++;
                if (BeamMissileCompat.irAtgmRounds(level, launcher.pos()) > 0) {
                    loaded++;
                }
            }
            if (loaded > 0) {
                name = IrAtgm.DISPLAY_NAME;
            }
        } catch (Throwable ignored) {
            // a computer that cannot be read keeps the pack's wording
        }
        String line = name + "/" + launchers + "/" + loaded;
        if (!line.equals(firecontrolcompat$lastGroundName)) {
            firecontrolcompat$lastGroundName = line;
            FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] ir-atgm name: slot=ground label={} launchers={} loaded={}"
                            + " selected={}",
                    name == null ? "native" : name, launchers, loaded,
                    controller.getSelectedGroundWeapon() == null ? "none"
                            : controller.getSelectedGroundWeapon().name());
        }
        return name;
    }

    /** How many rails were bound on the last tick, for the periodic line. */
    private static int firecontrolcompat$racks = -1;

    // RETIRED(ir-arm-rounds): the anti-radiation scan (`arm rack scan: racks= armed=`) lived here.
    // The round is 面包学's own from its next version on, so nothing is counted and nothing renamed.

    /**
     * The contact the fire control radar is holding, as the seeker sees it. Printed
     * with the rest of the geometry so a frame that fails to swing onto a radar lock
     * can be told apart from a lock the seeker never received.
     */
    private static Vec3 cuePosition(IrSeekerSession.Session session) {
        IrSeekerSession.Lock cued = session.cued();
        return cued == null ? null : cued.position();
    }

    /** Every launcher family that can hold a ground infrared round. */
    private static List<BindingRef> groundLaunchers(StabilizerControllerBlockEntity controller) {
        List<BindingRef> launchers = new ArrayList<>(controller.getAaMissileLaunchers());
        launchers.addAll(controller.getMissileLaunchers());
        return launchers;
    }
}



package com.qkdream.firecontrolcompat.underwater;

import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.qkdream.firecontrolcompat.FireControlLeadSettings;
import com.qkdream.firecontrolcompat.TorpedoSettingsStore;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * The swim settings a launch hands to a round, read off the fire control
 * computer the launcher answers to.
 *
 * <p>What an idle round does - patrol on the surface, or wait at a depth - and
 * how far it swims before its hydrophones come on are filed on the computer
 * rather than on the mod as a whole, so each rig runs its own rounds its own
 * way. A launch is resolved the way the wire is: the tube that fires is asked
 * which computer it answers to, and that computer's entry is what the round is
 * handed as it leaves.</p>
 *
 * <p>A launcher that answers to no computer - 面包学's tube, or a CBCMS tube
 * nobody wired up - keeps the plain values, so it behaves as it did before
 * there was anywhere to write them down.</p>
 */
public final class TorpedoLaunchSettings {

    /** What a round is handed as it leaves: hold a depth, how deep, how deaf. */
    public record Values(boolean holdDepth, double depth, double activation) {
    }

    private TorpedoLaunchSettings() {
    }

    /** The plain values, for a launcher that answers to no computer. */
    public static Values plain() {
        return new Values(
                FireControlLeadSettings.torpedoHoldDepth(),
                FireControlLeadSettings.torpedoDepth(),
                FireControlLeadSettings.torpedoActivationDistance());
    }

    /** What the tube at that block answers to, or the plain values when it answers to nobody. */
    public static Values forTube(Level level, BlockEntity tube) {
        if (tube == null) {
            return plain();
        }
        StabilizerControllerBlockEntity controller = TorpedoTubeBinding.controller(tube);
        return controller == null ? plain() : forController(level, controller);
    }

    /** What the computer files for every round its tubes fire. */
    public static Values forController(Level level, StabilizerControllerBlockEntity controller) {
        TorpedoSettingsStore.Entry filed = TorpedoSettingsStore.of(level, controller);
        return new Values(filed.holdDepth(), filed.depth(), filed.activation());
    }
}

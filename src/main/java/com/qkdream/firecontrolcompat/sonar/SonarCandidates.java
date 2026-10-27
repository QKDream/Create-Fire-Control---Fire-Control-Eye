package com.qkdream.firecontrolcompat.sonar;

import com.hooya.stabilizedturret.network.BindingSessionPayload;
import com.qkdream.firecontrolcompat.SubLevelPositions;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Sonar sets in the list of boxes a fire control computer paints while a
 * binding is armed.
 *
 * <p>The computer outlines every module that could be wired to it, so the
 * player can see what is in reach and which of them already answer to it. A set
 * is not one of those modules - it keeps its own memory of the computer it
 * reports to, which is what lets one computer carry several of them - so it has
 * to be put on that list from here. A set already wired to the computer being
 * armed comes out bound, which is the colour the computer paints its own
 * bound modules in.</p>
 */
public final class SonarCandidates {

    /** The reach the computer itself uses when it looks for modules. */
    private static final double REACH = 96.0D;

    /** The ceiling one binding session carries. */
    private static final int MAX_SESSION = 1024;

    private SonarCandidates() {
    }

    /** The extra candidates to put beside the ones the computer found itself. */
    public static List<BindingSessionPayload.Candidate> add(
            Level level, BlockPos controllerPos, List<BindingSessionPayload.Candidate> found) {
        List<BindingSessionPayload.Candidate> extra = new ArrayList<>();
        if (level == null || controllerPos == null || found == null) {
            return extra;
        }
        int room = MAX_SESSION - found.size();
        if (room <= 0) {
            return extra;
        }
        Vec3 centre = SubLevelPositions.worldCenter(level, controllerPos);
        Level root = SonarLog.rootLevel(level);
        for (SonarBlockEntity sonar : SonarTracker.all()) {
            if (extra.size() >= room) {
                break;
            }
            Level sonarLevel = sonar.getLevel();
            if (sonarLevel == null || sonar.isRemoved() || SonarLog.rootLevel(sonarLevel) != root) {
                continue;
            }
            BlockPos pos = sonar.getBlockPos().immutable();
            if (present(found, pos)) {
                continue;
            }
            Vec3 world = SubLevelPositions.worldCenter(sonarLevel, pos);
            if (world.distanceToSqr(centre) > REACH * REACH) {
                continue;
            }
            extra.add(new BindingSessionPayload.Candidate(
                    pos, SonarTracker.subLevelOf(sonar), sonar.isBoundTo(controllerPos, null)));
        }
        return extra;
    }

    private static boolean present(List<BindingSessionPayload.Candidate> found, BlockPos pos) {
        for (BindingSessionPayload.Candidate candidate : found) {
            if (candidate.pos().equals(pos)) {
                return true;
            }
        }
        return false;
    }
}

package com.qkdream.firecontrolcompat.sonar;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * The sonar sets that are loaded right now.
 *
 * <p>A set is bound to the fire control computer it answers to by the set
 * itself (it stores the platform it was pointed at), so the computer has to be
 * able to find its sets the other way round: everything that reports to this
 * computer. That is what this list is for - settings written on the fire
 * control screen land on the sets that belong to the computer on that screen,
 * and nothing else.</p>
 */
public final class SonarTracker {

    private static final Set<SonarBlockEntity> LOADED = new LinkedHashSet<>();

    private SonarTracker() {
    }

    public static void track(SonarBlockEntity sonar) {
        if (sonar == null) {
            return;
        }
        synchronized (LOADED) {
            LOADED.add(sonar);
        }
    }

    public static void forget(SonarBlockEntity sonar) {
        if (sonar == null) {
            return;
        }
        synchronized (LOADED) {
            LOADED.remove(sonar);
        }
    }

    /** Every loaded set; drops the ones that have been taken out of the world. */
    public static List<SonarBlockEntity> all() {
        List<SonarBlockEntity> found = new ArrayList<>();
        synchronized (LOADED) {
            LOADED.removeIf(SonarBlockEntity::isRemoved);
            found.addAll(LOADED);
        }
        return found;
    }

    /** The sets that report to the computer at this position. */
    public static List<SonarBlockEntity> forController(Level level, BlockPos controllerPos, UUID controllerSubLevel) {
        List<SonarBlockEntity> found = new ArrayList<>();
        if (controllerPos == null) {
            return found;
        }
        for (SonarBlockEntity sonar : all()) {
            if (sonar.isBoundTo(controllerPos, controllerSubLevel)) {
                found.add(sonar);
            }
        }
        return found;
    }

    /** The sub-level a block is stored on, or null when it stands in the world. */
    public static UUID subLevelOf(net.minecraft.world.level.block.entity.BlockEntity blockEntity) {
        if (blockEntity == null) {
            return null;
        }
        try {
            SubLevel containing = Sable.HELPER.getContaining(blockEntity);
            return containing == null ? null : containing.getUniqueId();
        } catch (Throwable ignored) {
            return null;
        }
    }
}

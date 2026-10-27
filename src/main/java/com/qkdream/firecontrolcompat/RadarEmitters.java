package com.qkdream.firecontrolcompat;

import com.hooya.stabilizedturret.content.controller.ControllerTracker;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.hooya.stabilizedturret.content.display.ConnectedDisplayBlockEntity;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Live index of the fire control computers that have just swept with a radar.
 *
 * <p>The RWR threat list only carries a position, so the emitter behind a
 * threat has to be resolved back to a computer. A radar that is painting
 * somebody is by definition one that scanned a moment ago, so every scan
 * registers its computer here and the lookup matches the threat position
 * against the registered radar origins.</p>
 */
public final class RadarEmitters {

    private static final Set<StabilizerControllerBlockEntity> SCANNED =
            Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>()));

    /** Displays that swept with their own radar, the second kind of emitter. */
    private static final Set<ConnectedDisplayBlockEntity> DISPLAYS =
            Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>()));

    private RadarEmitters() {
    }

    /**
     * Tick each computer last rebuilt its radar list on. The hand over position
     * is a snapshot of the sweep the list was built with, so a consumer that
     * needs the live position has to know how old that snapshot is.
     */
    private static final Map<StabilizerControllerBlockEntity, Long> SCAN_TICKS =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** Called by both radar scan hooks; keeps the index tied to live computers. */
    public static void remember(StabilizerControllerBlockEntity controller) {
        if (controller == null) {
            return;
        }
        try {
            if (!controller.isRemoved()) {
                SCANNED.add(controller);
            }
        } catch (Throwable ignored) {
        }
    }

    /** Records that this computer rebuilt its radar list on the given tick. */
    public static void rememberScan(StabilizerControllerBlockEntity controller, long gameTime) {
        if (controller == null) {
            return;
        }
        try {
            SCAN_TICKS.put(controller, gameTime);
        } catch (Throwable ignored) {
        }
    }

    /** Tick the computer's radar list was rebuilt on, {@code Long.MIN_VALUE} when it never swept. */
    public static long scanTick(StabilizerControllerBlockEntity controller) {
        if (controller == null) {
            return Long.MIN_VALUE;
        }
        Long tick = SCAN_TICKS.get(controller);
        return tick == null ? Long.MIN_VALUE : tick;
    }

    /** Called by the display scan hook; keeps the index tied to live displays. */
    public static void rememberDisplay(ConnectedDisplayBlockEntity display) {
        if (display == null) {
            return;
        }
        try {
            if (!display.isRemoved()) {
                DISPLAYS.add(display);
            }
        } catch (Throwable ignored) {
        }
    }

    /** Snapshot of the computers that swept with their radar, used by name lookups. */
    public static List<StabilizerControllerBlockEntity> controllers() {
        synchronized (SCANNED) {
            return new ArrayList<>(SCANNED);
        }
    }

    /** Snapshot of the displays that swept with their own radar, used by name lookups. */
    public static List<ConnectedDisplayBlockEntity> displays() {
        synchronized (DISPLAYS) {
            return new ArrayList<>(DISPLAYS);
        }
    }

    /**
     * Structure behind the radar at this position: the computer's own body, the
     * display's body, or whatever the position itself is inside. A platform
     * often paints with both a computer and a display, and both marks must
     * resolve to the same name.
     */
    public static UUID emittingStructure(Level level, Vec3 position) {
        if (level == null || position == null) {
            return null;
        }
        StabilizerControllerBlockEntity controller = at(level, position);
        if (controller != null) {
            try {
                UUID subLevel = ControllerTracker.controllerSubLevelId(controller);
                if (subLevel != null) {
                    return subLevel;
                }
            } catch (Throwable ignored) {
            }
        }
        ConnectedDisplayBlockEntity display = displayAt(level, position);
        if (display != null) {
            try {
                UUID subLevel = IffRadar.structureAt(level, Vec3.atCenterOf(display.getBlockPos()));
                if (subLevel != null) {
                    return subLevel;
                }
            } catch (Throwable ignored) {
            }
        }
        return IffRadar.structureAt(level, position);
    }

    /** The display whose radar origin sits on the given position, or null. */
    public static ConnectedDisplayBlockEntity displayAt(Level level, Vec3 position) {
        if (level == null || position == null) {
            return null;
        }
        List<ConnectedDisplayBlockEntity> snapshot;
        synchronized (DISPLAYS) {
            snapshot = new ArrayList<>(DISPLAYS);
        }
        ConnectedDisplayBlockEntity found = null;
        double best = 1.0D;
        for (ConnectedDisplayBlockEntity display : snapshot) {
            try {
                Level displayLevel = display == null ? null : display.getLevel();
                if (display == null
                        || display.isRemoved()
                        || displayLevel == null
                        || !displayLevel.dimension().equals(level.dimension())) {
                    continue;
                }
                double distance = display.getRadarWorldOrigin().distanceToSqr(position);
                if (distance < best) {
                    best = distance;
                    found = display;
                }
            } catch (Throwable ignored) {
            }
        }
        return found;
    }

    /** The computer whose radar origin sits on the given position, or null. */
    public static StabilizerControllerBlockEntity at(Level level, Vec3 position) {
        if (level == null || position == null) {
            return null;
        }
        List<StabilizerControllerBlockEntity> snapshot;
        synchronized (SCANNED) {
            snapshot = new ArrayList<>(SCANNED);
        }
        StabilizerControllerBlockEntity found = null;
        double best = 1.0D;
        for (StabilizerControllerBlockEntity controller : snapshot) {
            try {
                Level controllerLevel = controller.getLevel();
                if (controller == null
                        || controller.isRemoved()
                        || controllerLevel == null
                        || !controllerLevel.dimension().equals(level.dimension())) {
                    continue;
                }
                double distance = controller.getRadarOrigin().distanceToSqr(position);
                if (distance < best) {
                    best = distance;
                    found = controller;
                }
            } catch (Throwable ignored) {
            }
        }
        return found;
    }
}

package com.qkdream.firecontrolcompat.sonar;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * What the water is carrying right now.
 *
 * <p>Every noise this mod can hear - a burner turning over, tracks rolling, a
 * gun going off, a munition swimming - is dropped in here with the place it
 * happened and the weight of the sound. Sonar sets read the list back and
 * paint it, which keeps a hearing set from having to listen to each source
 * itself: one machine running is heard by every set in the world, and the
 * detection is the same no matter which set asks.</p>
 *
 * <p>Pulses are filed against the level the ships actually sail in, so a
 * machine ticking inside a Sable plot is found by a set standing in the world.
 * The list is a rolling buffer: a noise is worth nothing once it has faded,
 * so the oldest entries fall off the front.</p>
 */
public final class SonarLog {

    /** One noise: where it was, when, and what made it. */
    public record Pulse(Vec3 position, long tick, SonarKind kind, Object owner) {
    }

    /** How many noises are remembered per level before the oldest are dropped. */
    private static final int MAX_PULSES = 768;

    private static final Map<Level, Deque<Pulse>> PULSES = new WeakHashMap<>();

    private SonarLog() {
    }

    /** Notes a noise for every set in the level to hear. */
    public static void record(Level level, Vec3 position, SonarKind kind, Object owner) {
        if (level == null || position == null || kind == null) {
            return;
        }
        Level root = rootLevel(level);
        long tick = gameTime(root);
        synchronized (PULSES) {
            Deque<Pulse> queue = PULSES.computeIfAbsent(root, key -> new ArrayDeque<>());
            queue.addLast(new Pulse(position, tick, kind, owner));
            while (queue.size() > MAX_PULSES) {
                queue.removeFirst();
            }
        }
    }

    /** Noises from the last few ticks, oldest first. */
    public static List<Pulse> recent(Level level, int windowTicks, int limit) {
        Level root = rootLevel(level);
        long now = gameTime(root);
        if (now == Long.MIN_VALUE) {
            return List.of();
        }
        List<Pulse> heard = new ArrayList<>();
        synchronized (PULSES) {
            Deque<Pulse> queue = PULSES.get(root);
            if (queue == null) {
                return List.of();
            }
            for (Pulse pulse : queue) {
                if (now - pulse.tick() <= windowTicks) {
                    heard.add(pulse);
                }
            }
        }
        int size = heard.size();
        if (size > limit) {
            return new ArrayList<>(heard.subList(size - limit, size));
        }
        return heard;
    }

    /** The level a ship is really in, even when the caller holds its plot. */
    public static Level rootLevel(Level level) {
        if (level == null) {
            return null;
        }
        try {
            Level container = SubLevelContainer.getContainer(level).getLevel();
            return container == null ? level : container;
        } catch (Throwable ignored) {
            return level;
        }
    }

    private static long gameTime(Level level) {
        try {
            return level == null ? Long.MIN_VALUE : level.getGameTime();
        } catch (Throwable ignored) {
            return Long.MIN_VALUE;
        }
    }
}

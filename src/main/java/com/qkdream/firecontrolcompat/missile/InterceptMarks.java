package com.qkdream.firecontrolcompat.missile;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * Where this mod's guns have just brought a mathematical missile down.
 *
 * <p>An interception happens at a point in the world that nothing else records: the round has no
 * body, so the contact simply disappears from the radar and the crew has no way to see where it
 * was killed. The point is kept here for a short while, per level, so the radar page and any HUD
 * that wants to draw a kill marker can ask for it. Nothing is drawn from this class itself.</p>
 *
 * <p>The table is small and self-limiting: at most {@link #MAX_PER_LEVEL} marks per level, and a
 * read only returns the marks of the last {@link #KEEP_TICKS} ticks. A level that is unloaded takes
 * its marks with it (the map is weak on the level and never outlives it).</p>
 */
public final class InterceptMarks {

    private InterceptMarks() {
    }

    /** How long a mark stays readable, in ticks. */
    public static final int KEEP_TICKS = 100;

    /** How many marks are kept per level before the oldest is dropped. */
    public static final int MAX_PER_LEVEL = 16;

    /** One kill: where it happened and when. */
    public record Mark(Vec3 position, long tick) {
    }

    private static final Map<ServerLevel, Deque<Mark>> MARKS =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** Files a kill marker. */
    public static void remember(ServerLevel level, Vec3 position, long tick) {
        if (level == null || position == null) {
            return;
        }
        try {
            Deque<Mark> marks = MARKS.computeIfAbsent(level, key -> new ArrayDeque<>());
            synchronized (marks) {
                marks.addLast(new Mark(position, tick));
                while (marks.size() > MAX_PER_LEVEL) {
                    marks.removeFirst();
                }
            }
        } catch (Throwable ignored) {
            // A missing marker is a missing picture, never a missing interception.
        }
    }

    /** The marks of this level that are still worth drawing, oldest first. */
    public static List<Mark> recent(ServerLevel level) {
        if (level == null) {
            return List.of();
        }
        Deque<Mark> marks = MARKS.get(level);
        if (marks == null || marks.isEmpty()) {
            return List.of();
        }
        long now = level.getGameTime();
        List<Mark> live = new ArrayList<>();
        synchronized (marks) {
            for (Mark mark : marks) {
                if (now - mark.tick() <= KEEP_TICKS) {
                    live.add(mark);
                }
            }
        }
        return live;
    }

    /** Drops every mark of a level, on shutdown. */
    public static void clear(ServerLevel level) {
        if (level != null) {
            MARKS.remove(level);
        }
    }
}

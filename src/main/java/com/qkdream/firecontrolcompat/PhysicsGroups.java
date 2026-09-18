package com.qkdream.firecontrolcompat;

import dev.ryanhcode.sable.api.SubLevelHelper;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Groups the physics bodies that make up one platform.
 *
 * <p>Sable exposes exactly the two relations needed here, and its own
 * integrations fill them in: {@code SubLevelHelper.getConnectedChain} collects
 * whatever the block entities declare through
 * {@code BlockEntitySubLevelActor#sable$getConnectionDependencies} (fire control
 * computers and displays, universal joints, hydraulic heads, rail couplers,
 * physics bogeys, and every weld or joint Synaxis mirrors into that chain) and
 * {@code SubLevelHelper.getLoadingDependencyChain} adds the welded blueprint
 * dependencies. A ship either way is one mother body: a missile must never
 * treat a part of her own hull as a target, and ordnance must keep the markings
 * of the whole group.</p>
 *
 * <p>Everything is cached per game tick, so a battle with dozens of munitions
 * still walks the graph a handful of times per tick.</p>
 */
public final class PhysicsGroups {

    private static final int MAX_MEMBERS = 256;

    private static long cacheTick = Long.MIN_VALUE;
    private static final Map<UUID, Set<UUID>> CACHE = new HashMap<>();

    private PhysicsGroups() {
    }

    /** The given body plus every body connected, jointed or welded to it. */
    public static Set<UUID> group(Level level, UUID subLevelId) {
        if (level == null || subLevelId == null) {
            return Set.of();
        }
        long now = gameTime(level);
        if (cacheTick != now) {
            cacheTick = now;
            CACHE.clear();
        }
        Set<UUID> cached = CACHE.get(subLevelId);
        if (cached != null) {
            return cached;
        }
        Set<UUID> built = build(level, subLevelId);
        CACHE.put(subLevelId, built);
        return built;
    }

    /**
     * Mother body of a launch: the body that fired plus everything connected to
     * it, and every body whose bounding box holds the launch point (a launcher
     * often sits in a docked sub-body while the hull around it is a second one).
     */
    public static Set<UUID> motherBody(Level level, UUID launchSubLevelId, Vec3 launchPoint, double inflate) {
        Set<UUID> seeds = new LinkedHashSet<>();
        if (launchSubLevelId != null) {
            seeds.addAll(group(level, launchSubLevelId));
        }
        if (launchPoint != null) {
            seeds.addAll(bodiesContaining(level, launchPoint, inflate));
        }
        Set<UUID> body = new LinkedHashSet<>(seeds);
        for (UUID seed : seeds) {
            body.addAll(group(level, seed));
        }
        return body;
    }

    /** Every body whose bounding box holds the given point. */
    public static Set<UUID> bodiesContaining(Level level, Vec3 position, double inflate) {
        Set<UUID> found = new LinkedHashSet<>();
        if (level == null || position == null) {
            return found;
        }
        try {
            SubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) {
                return found;
            }
            for (SubLevel subLevel : container.getAllSubLevels()) {
                if (subLevel == null || subLevel.isRemoved()) {
                    continue;
                }
                if (subLevel.boundingBox().toMojang().inflate(inflate).contains(position)) {
                    found.add(subLevel.getUniqueId());
                }
            }
        } catch (Throwable ignored) {
        }
        return found;
    }

    /** Resolves a body id back to the live body, or null when it is gone. */
    public static SubLevel resolve(Level level, UUID subLevelId) {
        if (level == null || subLevelId == null) {
            return null;
        }
        try {
            SubLevelContainer container = SubLevelContainer.getContainer(level);
            SubLevel subLevel = container == null ? null : container.getSubLevel(subLevelId);
            return subLevel == null || subLevel.isRemoved() ? null : subLevel;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Set<UUID> build(Level level, UUID subLevelId) {
        Set<UUID> members = new LinkedHashSet<>();
        members.add(subLevelId);
        SubLevel root = resolve(level, subLevelId);
        if (root == null) {
            return Set.copyOf(members);
        }
        Deque<SubLevel> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty() && members.size() < MAX_MEMBERS) {
            SubLevel current = queue.poll();
            for (SubLevel linked : links(current)) {
                if (linked == null || linked.isRemoved()) {
                    continue;
                }
                if (members.add(linked.getUniqueId())) {
                    queue.add(linked);
                }
            }
        }
        return Set.copyOf(members);
    }

    private static List<SubLevel> links(SubLevel subLevel) {
        List<SubLevel> linked = new ArrayList<>(8);
        try {
            linked.addAll(SubLevelHelper.getConnectedChain(subLevel));
        } catch (Throwable ignored) {
        }
        try {
            if (subLevel instanceof ServerSubLevel server) {
                linked.addAll(SubLevelHelper.getLoadingDependencyChain(server));
            }
        } catch (Throwable ignored) {
        }
        return linked;
    }

    private static long gameTime(Level level) {
        try {
            return level.getGameTime();
        } catch (Throwable ignored) {
            return Long.MIN_VALUE;
        }
    }
}
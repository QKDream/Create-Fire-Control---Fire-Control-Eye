package com.qkdream.firecontrolcompat.missile;

import com.hooya.stabilizedturret.content.controller.MathematicalMissileManager;
import com.hooya.stabilizedturret.content.controller.MianbaoMissileCompat;
import com.qkdream.firecontrolcompat.FireControlCompat;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Resolves a lock identifier back to a round that fire control flies <b>mathematically</b>.
 *
 * <h2>The hole this fills</h2>
 *
 * <p>Since 0.9.3 a guided round can be flown as a record instead of an entity:
 * {@code MathematicalMissileManager.capture} takes the round on its way into the level, keeps it in
 * its own {@code ACTIVE} table and cancels the join. This mod flies its own rounds that way on
 * purpose ({@link MathGuidedMissiles}), so from the moment one is launched it is <b>not in the
 * level</b>.</p>
 *
 * <p>Every lock path in the pack is written as "id in, entity out": {@code hasOpticalLock} keeps a
 * {@code lockedEntityId}, {@code getOpticalLockTarget()} and {@code getOpticalLockBounds()} resolve
 * it with {@code serverLevel.getEntity(id)}, the radar lock and the anti-air guidance do the same,
 * and the optical acquisition raycast walks {@code level.getEntities(...)}. None of those can see a
 * round that never joined, so a mathematical round of ours could be painted as a radar contact (that
 * part this mod already appended) and still be **impossible to actually lock** - which is exactly
 * the report: fired the infrared anti-tank round, and neither the radar nor the optics would take
 * it.</p>
 *
 * <h2>What is exposed</h2>
 *
 * <ul>
 *   <li>{@link #isMathMissile} - the one predicate every hook uses, so "is this round in the pack's
 *       mathematical table" is answered in exactly one place;</li>
 *   <li>{@link #resolve} - the id-to-round lookup, reading the pack's own table
 *       ({@code MathematicalMissileManager.find}) rather than a copy that could drift;</li>
 *   <li>{@link #rounds} - the live rounds of one level, for the acquisition ray;</li>
 *   <li>{@link #reportSelectable} - the evidence line the acceptance criteria ask for:</li>
 * </ul>
 *
 * <pre>math lock: type=&lt;registry id&gt; uuid=&lt;id&gt; source=&lt;radar|optical&gt; selectable=true pos=&lt;x,y,z&gt;</pre>
 *
 * <p>Nothing here is cached: every answer is read from the pack at the moment it is asked, so a
 * round that has just detonated is gone from the answer in the same tick and no stale entity can be
 * handed to a guidance loop.</p>
 */
public final class MathLocks {

    private MathLocks() {
    }

    /** Quietest gap between two "selectable" lines for the same round, in ticks. */
    private static final long REPORT_GAP_TICKS = 20L;

    /** Rounds already reported, with the game time of the report. Bounded by {@link #REPORTED_MAX}. */
    private static final java.util.Map<UUID, Long> REPORTED = new java.util.HashMap<>();

    private static final int REPORTED_MAX = 256;

    /**
     * Whether that entity is a round the pack is flying mathematically.
     *
     * <p>Read from the pack's own table, so a round of ours, of the pack's, or of a bomb drive this
     * mod adopted all answer the same way - the question is "is it flying as a record", not "which
     * mod made it".</p>
     */
    public static boolean isMathMissile(Entity entity) {
        if (entity == null) {
            return false;
        }
        try {
            return MathematicalMissileManager.contains(entity);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * The level a mathematical round of this level actually lives on.
     *
     * <p>A round launched off a structure standing on a Sable sub-level is still flown on the root
     * level's manager, so the lookup has to be done against the root or it finds nothing.</p>
     */
        public static ServerLevel rootOf(Level level) {
        if (level == null) {
            return null;
        }
        try {
            Level root = SubLevelContainer.getContainer(level).getLevel();
            if (root instanceof ServerLevel serverLevel) {
                return serverLevel;
            }
        } catch (Throwable ignored) {
            // a level without a container is its own root
        }
        return level instanceof ServerLevel serverLevel ? serverLevel : null;
    }

    /**
     * The round a lock identifier points at, if it is one of the mathematically flown ones.
     *
     * <p>Deliberately <b>not</b> a fallback around {@code ServerLevel#getEntity}: callers that
     * already tried the level pass its answer in as {@code known} so a round that <em>is</em> in the
     * level is never shadowed by a same-id record.</p>
     */
        public static Entity resolve(Level level, UUID id) {
        if (level == null || id == null) {
            return null;
        }
        try {
            ServerLevel root = rootOf(level);
            if (root == null) {
                return null;
            }
            Entity round = MathematicalMissileManager.find(root, id);
            if (round == null || round.isRemoved() || !round.isAlive()) {
                return null;
            }
            return round;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Every round this level is flying mathematically, as live entity objects. */
    public static List<Entity> rounds(Level level) {
        if (level == null) {
            return List.of();
        }
        try {
            ServerLevel root = rootOf(level);
            if (root == null) {
                return List.of();
            }
            List<Entity> live = MathMissileMirror.missiles(root);
            return live.isEmpty() ? List.of() : new ArrayList<>(live);
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    /**
     * Whether <b>any</b> level is flying a mathematical round right now.
     *
     * <p>The guard on the {@code ServerLevel#getEntity} fallback. A server with no mathematical
     * round in the air - the overwhelmingly common case - pays one static call and one
     * {@code Map#size}, which is what keeps a lookup used all over the game from becoming a cost.</p>
     */
    public static boolean anyActive() {
        try {
            return MathematicalMissileManager.activeCount() > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Where the round is, in world space (the mathematical table already stores world positions). */
        public static Vec3 position(Entity round) {
        if (round == null) {
            return null;
        }
        try {
            Vec3 world = MianbaoMissileCompat.getWorldPosition(round);
            if (world != null) {
                return world;
            }
        } catch (Throwable ignored) {
            // fall through to the entity's own position
        }
        return round.position();
    }

    /** A box around the round, for the visibility test and the lock reticle. */
        public static AABB bounds(Entity round) {
        if (round == null) {
            return null;
        }
        try {
            AABB own = round.getBoundingBox();
            if (own != null && own.getSize() >= 0.0D) {
                return own.inflate(0.6D);
            }
        } catch (Throwable ignored) {
            // a round without a box still gets a synthetic one below
        }
        Vec3 at = position(round);
        return at == null ? null : new AABB(at, at).inflate(0.9D);
    }

    /** This round's registry id, for the evidence line; never throws. */
    public static String nameOf(Entity round) {
        if (round == null) {
            return "?";
        }
        try {
            Entity entity = round;
            ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
            return key == null ? String.valueOf(entity.getType()) : key.toString();
        } catch (Throwable ignored) {
            return "?";
        }
    }

    /**
     * One line per round per second, saying it can be locked and by which source.
     *
     * <p>{@code source=radar} is written when the contact is appended to a sweep - that is the
     * moment the radar list starts offering it - and {@code source=optical} when the sight actually
     * takes it. Both are the same question ("is this round selectable"), asked from the two ends of
     * the chain, which is why they share one line shape.</p>
     */
    public static void reportSelectable(Entity round, String source) {
        if (round == null) {
            return;
        }
        long now = 0L;
        try {
            Level level = round.level();
            now = level == null ? 0L : level.getGameTime();
        } catch (Throwable ignored) {
            // a round without a readable clock still gets reported once
        }
        UUID id = null;
        try {
            id = round.getUUID();
        } catch (Throwable ignored) {
            return;
        }
        if (id == null) {
            return;
        }
        Long previous = REPORTED.get(id);
        if (previous != null && now - previous < REPORT_GAP_TICKS) {
            return;
        }
        if (REPORTED.size() > REPORTED_MAX) {
            REPORTED.clear();
        }
        REPORTED.put(id, now);
        Vec3 at = position(round);
        FireControlCompat.LOGGER.info(
                "math lock: type={} uuid={} source={} selectable=true pos={}",
                nameOf(round), id, source,
                at == null ? "-" : String.format(Locale.ROOT, "%.2f,%.2f,%.2f", at.x, at.y, at.z));
    }

    /** Every distinct round id in a list, for de-duplicating a lock sweep. */
    public static Set<UUID> idsOf(List<Entity> rounds) {
        Set<UUID> ids = new LinkedHashSet<>();
        for (Entity round : rounds) {
            try {
                if (round != null) {
                    ids.add(round.getUUID());
                }
            } catch (Throwable ignored) {
                // a round whose id cannot be read simply does not contribute
            }
        }
        return ids;
    }
}

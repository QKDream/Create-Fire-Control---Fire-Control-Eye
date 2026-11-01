package com.qkdream.firecontrolcompat;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

/**
 * Terminal ballistics of the shaped-charge jet: how many fragments one jet throws.
 *
 * <h2>What was wrong</h2>
 *
 * <p>The jet is a {@code MetalJetProjectile}, which is built on the armour-piercing discarding sabot
 * round, so CBC Terminal Ballistics classifies it as an <b>APDS</b> shell and hands it that family's
 * profile: a fragment count modifier of {@code 1.2}, a damage modifier of {@code 1.3} and a narrow
 * {@code 0.5} spray cone. For a shell that is right. For a shaped charge it is not: the jet is
 * thirty units of copper arriving at two thousand eight hundred metres a second, and the thing it
 * punches through comes apart into far more than a sabot's worth of splinters. The user's report was
 * that a hit "破片明显偏少" - the hole was there, the shower behind it was not.</p>
 *
 * <h2>What this does</h2>
 *
 * <p>Three hooks answer for the jet round only, in exactly the shape the pack's own classifier has:
 * the count, the damage and the cone. The count is the one that changed - {@link #SPALL_COUNT_MODIFIER}
 * is double the APDS figure the jet was getting, so the same hit throws about twice the fragments -
 * and it is paired with a higher per-impact cap ({@link #SPALL_FRAGMENT_FLOOR}) because the pack's
 * default guard (28 on the test pack) would otherwise clamp the new number straight back to what it
 * already was. Nothing else is touched: every other APDS round in the pack keeps 1.2/1.3/0.5.</p>
 *
 * <h2>Why it cannot become a lag bomb</h2>
 *
 * <p>A fragment is an entity, so "more fragments" is a budget question, not a taste one. Two limits
 * bound it:</p>
 *
 * <ul>
 *   <li>{@link #SPALL_FRAGMENT_CEILING} - a hard ceiling on one impact, whatever the pack is
 *       configured for. The raise can never take a single jet past 64 fragments;</li>
 *   <li>{@link #TICK_FRAGMENT_BUDGET} - how many fragments <b>above the configured cap</b> all jets
 *       together may add inside one game tick. A salvo of ten jets landing on the same tick gets the
 *       first 160 extra fragments between them and then falls back to the plain configured cap, so
 *       the worst case is bounded by the pack's own guard rather than by this change.</li>
 * </ul>
 *
 * <p>The budget is spent against the cap, not against what the impact actually threw, so it can only
 * ever be conservative.</p>
 */
public final class MetalJetSpall {

    /** Entity id of the jet, as registered by the CBC Modern Warfare bridge. */
    private static final String ENTITY_ID = "firecontrolcompat:metal_jet";

    /**
     * Fragment count multiplier for the jet, applied before the pack's cap.
     *
     * <p>The APDS family the jet is classified under uses 1.2; this is 2.4, so the same hit throws
     * twice as many fragments. Chosen at the top of the requested x1.5~2 band because the jet is the
     * one round where the count was never tuned for a shaped charge in the first place.</p>
     */
    public static final double SPALL_COUNT_MODIFIER = 2.4;

    /** Fragment damage multiplier; unchanged from the APDS the jet is built on. */
    public static final double SPALL_DAMAGE_MODIFIER = 1.3;

    /** Fragment spray cone, unchanged from the APDS: a jet is a narrow, forward spray. */
    public static final double SPALL_CONE_MODIFIER = 0.5;

    /**
     * Fragment cap floor for one jet impact.
     *
     * <p>The pack's own guard is 28 on the test pack, which would clamp the doubled modifier back to
     * exactly the number it produced before. A configured cap above this floor still wins, up to
     * {@link #SPALL_FRAGMENT_CEILING}.</p>
     */
    public static final int SPALL_FRAGMENT_FLOOR = 56;

    /** Hard ceiling on one jet impact, in fragments, whatever the pack is configured for. */
    public static final int SPALL_FRAGMENT_CEILING = 64;

    /** Fragments above the configured cap that all jets together may add inside one game tick. */
    public static final int TICK_FRAGMENT_BUDGET = 160;

    /** Game tick the budget below is being spent in. */
    private static long budgetTick;

    /** Fragments above the configured cap already granted in {@link #budgetTick}. */
    private static int budgetSpent;

    /** Whether the budget has ever been opened, so the first tick is not mistaken for a stale one. */
    private static boolean budgetOpened;

    private MetalJetSpall() {
    }

    /** Whether the projectile is the shaped-charge jet. */
    public static boolean isMetalJet(Entity entity) {
        if (entity == null) {
            return false;
        }
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return id != null && ENTITY_ID.equals(id.toString());
    }

    /**
     * The fragment cap one jet impact may use.
     *
     * <p>Reads the pack's configured cap, lifts it to {@link #SPALL_FRAGMENT_FLOOR} but never past
     * {@link #SPALL_FRAGMENT_CEILING}, and pays for the lift out of {@link #TICK_FRAGMENT_BUDGET}.
     * The answer is never below the configured cap: when the budget is gone a jet impact simply gets
     * what every other round in the pack gets.</p>
     *
     * @param configured the pack's own cap for this impact
     * @param gameTime   the level's game time, so the budget is per tick and not per call
     * @return the cap to hand the pack's spall routine
     */
    public static int capFor(int configured, long gameTime) {
        int base = Math.max(configured, 0);
        int wanted = Math.min(SPALL_FRAGMENT_CEILING, Math.max(base, SPALL_FRAGMENT_FLOOR));
        int extra = Math.max(0, wanted - base);
        if (extra <= 0) {
            return base;
        }
        if (!budgetOpened || gameTime != budgetTick) {
            budgetOpened = true;
            budgetTick = gameTime;
            budgetSpent = 0;
        }
        int allowed = Math.min(extra, Math.max(0, TICK_FRAGMENT_BUDGET - budgetSpent));
        budgetSpent += allowed;
        return base + allowed;
    }

    /** Fragments above the configured cap granted in the current tick, for the log line. */
    public static int budgetSpent() {
        return budgetSpent;
    }
}

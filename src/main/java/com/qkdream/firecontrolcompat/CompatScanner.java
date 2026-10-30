package com.qkdream.firecontrolcompat;

import com.hooya.stabilizedturret.content.controller.MianbaoAircraftCompat;
import com.hooya.stabilizedturret.content.controller.MianbaoAirDefenseCompat;
import com.hooya.stabilizedturret.content.controller.MianbaoCruiseMissileCompat;
import com.hooya.stabilizedturret.content.controller.MianbaoMissileCompat;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.hooya.stabilizedturret.content.display.ConnectedDisplayBlockEntity;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Adds hostile ordnance entities (vestalihy / CBCMS / mianbaos) and Shaolib
 * Tau/Hellfire instances to the existing fire-control contact pipelines.
 */
public final class CompatScanner {

    /** Startup cost probe: opens the static initialisation window (see the closing block). */
    static {
        StartupProbe.staticBegin("CompatScanner");
    }

    private CompatScanner() {
    }

    /** 面包学's standard cruise missile. */
    private static final ResourceLocation CRUISE_MISSILE =
            ResourceLocation.fromNamespaceAndPath("mianbaos_modernwarfare", "cruisemissiletanshe");
    /** 面包学's low-altitude cruise missile of the same family. */
    private static final ResourceLocation CRUISE_LOW_MISSILE =
            ResourceLocation.fromNamespaceAndPath("mianbaos_modernwarfare", "cruisemissilelowtanshe");
    /**
     * Radar stealth of the two cruise missiles: a radar does not paint them
     * until they come inside this range, in blocks.
     */
    private static final double CRUISE_STEALTH_RANGE = 1000.0;

    /** True for either of the radar-stealth cruise missiles. */
    private static boolean isStealthCruiseMissile(Entity entity) {
        if (entity == null) {
            return false;
        }
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return CRUISE_MISSILE.equals(id) || CRUISE_LOW_MISSILE.equals(id);
    }

    /** A cruise missile stays invisible to radar while outside its stealth range. */
    private static boolean outsideCruiseStealth(Vec3 center, Vec3 origin, Entity entity) {
        return isStealthCruiseMissile(entity) && center.distanceTo(origin) > CRUISE_STEALTH_RANGE;
    }

    /**
     * Removes radar contacts for the stealth cruise missiles that fire
     * control's own native scans painted anyway. The native
     * {@code scanRadarMissilesInLevel} / {@code scan} pass adds the two
     * cruise missiles through {@code MianbaoCruiseMissileCompat} before this
     * mod's appended contacts run, so the stealth gate has to run on the
     * finished list rather than only on appended candidates.
     */
    public static void stripStealthCruiseContactsRadar(
            Level level,
            List<StabilizerControllerBlockEntity.RadarContact> contacts,
            Vec3 origin
    ) {
        if (level == null || origin == null || contacts == null || contacts.isEmpty()) {
            return;
        }
        contacts.removeIf(contact -> {
            Entity entity = contact == null ? null : firecontrolcompat$entityById(level, contact.subLevelId());
            return outsideCruiseStealth(entity, origin);
        });
    }

    /** Display-side twin of {@link #stripStealthCruiseContactsRadar}. */
    public static void stripStealthCruiseContactsDisplay(
            Level level,
            List<ConnectedDisplayBlockEntity.Contact> contacts,
            Vec3 origin
    ) {
        if (level == null || origin == null || contacts == null || contacts.isEmpty()) {
            return;
        }
        contacts.removeIf(contact -> {
            Entity entity = contact == null || contact.key() == null ? null : firecontrolcompat$entityById(level, contact.key().id());
            return outsideCruiseStealth(entity, origin);
        });
    }

    private static Entity firecontrolcompat$entityById(Level level, UUID id) {
        if (level == null || id == null) {
            return null;
        }
        if (level instanceof ServerLevel serverLevel) {
            return serverLevel.getEntity(id);
        }
        return null;
    }

    private static boolean outsideCruiseStealth(Entity entity, Vec3 origin) {
        if (entity == null || !isStealthCruiseMissile(entity)) {
            return false;
        }
        Vec3 center = MianbaoMissileCompat.getWorldPosition(entity);
        return center != null && center.distanceTo(origin) > CRUISE_STEALTH_RANGE;
    }
    /** Appends hostile-entity and Shaolib contacts to the connected display scan. */
    public static void appendDisplayContacts(
            ConnectedDisplayBlockEntity display,
            List<ConnectedDisplayBlockEntity.Contact> found,
            Vec3 origin,
            double radarRange,
            Vec3 radarVelocity
    ) {
        Level level = display.getLevel();
        if (level == null) {
            return;
        }
        Set<UUID> existing = new HashSet<>();
        for (ConnectedDisplayBlockEntity.Contact contact : found) {
            existing.add(contact.key().id());
        }
        Set<UUID> visited = new HashSet<>();

        scanEntities(level, origin, radarRange, entity -> {
            if (!visited.add(entity.getUUID()) || existing.contains(entity.getUUID())) {
                return;
            }
            if (isOwnFireControlOrdnance(entity)) {
                return;
            }
            boolean ownMissile = TargetClassifier.isCompatMissile(entity);
            if (!ownMissile && (MianbaoAirDefenseCompat.isMissile(entity)
                    || MianbaoMissileCompat.isGuidedMissile(entity)
                    || MianbaoCruiseMissileCompat.isRadarMissile(entity)
                    || MianbaoAircraftCompat.isAirToGroundMissile(entity))) {
                return;
            }
            if (!ownMissile) {
                return;
            }
            Vec3 center = MianbaoMissileCompat.getWorldPosition(entity);
            Vec3 velocity = MianbaoMissileCompat.getWorldVelocity(entity);
            double distance = center.distanceTo(origin);
            if (distance > radarRange || distance < 1.0
                    || (isStealthCruiseMissile(entity) && distance > CRUISE_STEALTH_RANGE)) {
                return;
            }
            Vec3 los = center.subtract(origin).normalize();
            double closing = -velocity.subtract(radarVelocity).dot(los) * 20.0;
            found.add(new ConnectedDisplayBlockEntity.Contact(
                    new ConnectedDisplayBlockEntity.TargetKey(entity.getUUID(), false),
                    Set.of(),
                    center,
                    velocity,
                    entity.getBoundingBox(),
                    distance,
                    closing,
                    true
            ));
        });

        appendShaolibDisplayContacts(level, found, origin, radarRange, radarVelocity, existing);
        // The display radar is a radar: it paints what its beam reaches, so it gets the same
        // screen the controller's sweep gets. It used to run none at all, which is why a display
        // kept showing hulls behind hills that the mount's own list had already dropped.
        stripTerrainBlockedDisplay(level, origin, radarRange, found);
    }

    /**
     * Sweeps between two full entity searches. Finding the candidates costs a
     * walk of every entity inside the radar range, while a candidate's position
     * and velocity are read fresh on every sweep, so the search only has to run
     * often enough to notice ordnance that appeared a moment ago.
     */
    private static final int SCAN_REFRESH_TICKS = 4;

    private static final Map<StabilizerControllerBlockEntity, CachedScan> SCAN_CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** Body of a recycled entity id could be anything, so the list keeps the entities themselves. */
    private record CachedScan(long tick, List<Entity> candidates) {
    }

    /** Appends hostile-entity and Shaolib contacts to the stabilizer controller radar scan. */
    public static void appendRadarContacts(StabilizerControllerBlockEntity controller, List<StabilizerControllerBlockEntity.RadarContact> contacts) {
        Level level = controller.getLevel();
        if (level == null) {
            return;
        }
        Vec3 origin = controller.getRadarOrigin();
        boolean aircraft = controller.isAircraftRadarMode();
        double range = aircraft ? controller.getAircraftRadarRange() : controller.getRadarRange();
        Set<UUID> existing = new HashSet<>();
        for (StabilizerControllerBlockEntity.RadarContact contact : contacts) {
            existing.add(contact.subLevelId());
        }
        Set<UUID> visited = new HashSet<>();

        // Every stage of the gate is counted, because "appended=0" on its own says nothing about
        // which gate emptied the list: no candidates at all, all of them already painted, all of
        // them friendly, or all of them outside the volume. The counters are reported once per
        // RADAR_APPEND_LOG_TICKS and immediately when anything was added.
        int candidates = 0;
        int friendly = 0;
        int notOrdnance = 0;
        int occluded = 0;
        int added = 0;
        int alreadyPainted = 0;
        for (Entity entity : firecontrolcompat$candidates(level, controller, origin, range)) {
            candidates++;
            if (!visited.add(entity.getUUID()) || existing.contains(entity.getUUID())) {
                alreadyPainted++;
                continue;
            }
            if (isOwnFireControlOrdnance(entity)) {
                friendly++;
                continue;
            }
            // The candidate list is already built from {@link #isOrdnance}, so this is the whole
            // family of flying ordnance, not only this mod's own rounds. It used to accept only
            // {@code TargetClassifier.isCompatMissile} here, which meant the pack's own guided
            // and cruise missiles were fetched by the sweep and then dropped on this line - the
            // one gate that could empty the append while everything around it looked healthy.
            // A contact the native sweep already painted is skipped by the UUID test above, so
            // letting the family through cannot paint a missile twice.
            //
            // A round the pack is flying in mathematical mode is accepted by name as well: its
            // entity never joins the level, so the sweep only ever sees it through this mod's own
            // candidate cache when the pack lists it, and this makes the acceptance explicit
            // rather than a side effect of which class the round happens to be.
            if (!isOrdnance(entity)
                    && !com.qkdream.firecontrolcompat.missile.MathMissileMirror.contains(entity)) {
                notOrdnance++;
                continue;
            }
            Vec3 center = MianbaoMissileCompat.getWorldPosition(entity);
            AABB bounds = entity.getBoundingBox();
            if (center == null || bounds == null) {
                // An ordnance class that cannot say where it is paints nothing; counted so a
                // family-wide position lookup failure is visible rather than silently zero.
                notOrdnance++;
                continue;
            }
            if (outsideCruiseStealth(center, origin, entity)) {
                occluded++;
                continue;
            }
            if (!inRadarVolume(controller, aircraft, origin, center, bounds, range)) {
                occluded++;
                continue;
            }
            contacts.add(new StabilizerControllerBlockEntity.RadarContact(
                    entity.getUUID(),
                    center,
                    MianbaoMissileCompat.getWorldVelocity(entity),
                    bounds,
                    Set.of(),
                    // Ordnance, not a structure: the radar list and everything
                    // built on it - interception priority, the diving hand
                    // over, the automatic defense - is decided by this flag.
                    true
            ));
            added++;
        }

        appendShaolibRadarContacts(level, contacts, existing, controller, aircraft, origin, range);
        appendMathRadarContacts(level, contacts, existing, origin, range);
        // Terrain culling runs over the whole list, native contacts included; what it removed is
        // reported so a sweep whose finds were all screened by ground is not read as "no data".
        // The screen itself is also the reason the report below carries a terrain count: something
        // that was found and then hidden is a different story from something that was never found.
        TerrainReport screen = stripTerrainBlocked(level, origin, range, contacts);
        int terrain = screen.occluded();
        long now = level.getGameTime();
        if (added > 0 || radarAppendLogAt == 0L || now - radarAppendLogAt >= RADAR_APPEND_LOG_TICKS) {
            radarAppendLogAt = now;
            FireControlCompat.LOGGER.info(
                    "radar append: entities={} iffallowed={} occluded={} added={} already={} friendly={} notordnance={} terrain={} contacts={} range={}",
                    candidates, candidates - friendly - notOrdnance - alreadyPainted, occluded, added,
                    alreadyPainted, friendly, notOrdnance, terrain, contacts.size(), range);
        }
    }

    /** Quietest gap between two "math contact injected" log lines, in ticks. */
    private static final int MATH_CONTACT_LOG_TICKS = 40;

    /**
     * Tick of the last "math radar" line, or {@code 0} before the first one.
     *
     * <p>The seed is tested directly. Seeding with {@code Long.MIN_VALUE} and asking
     * {@code now - stamp >= 100} overflows - {@code now + 2^63} wraps to a negative number for
     * every positive game time - so the comparison stayed false forever and the line was never
     * printed, which read as "no math contact was ever injected".</p>
     */
    private static long mathContactLogAt;

    /** Quietest gap between two "radar append" summary lines, in ticks. */
    private static final int RADAR_APPEND_LOG_TICKS = 40;

    private static long radarAppendLogAt;

    /**
     * Adds fire control's mathematical missiles to the contact list.
     *
     * <p>Since 0.9.3 a fast guided round is a {@code VirtualMissile} record, not an entity, so an
     * entity sweep cannot see it: the radar showed nothing at all for an incoming round that the
     * pack was already flying. This used to read the record table out of {@code MissileManager}
     * through reflection, and that path answered "nothing there" for a whole session - the one
     * symptom that cannot be told apart from "no missiles in the air". The records now come from
     * {@link com.qkdream.firecontrolcompat.missile.MathMissileMirror}, which is filled from inside
     * the manager's own tick, and every sweep reports what the mirror held and what became of it:
     *
     * <pre>math radar: mirror=&lt;n&gt; added=&lt;m&gt; dedup=&lt;k&gt; reason=&lt;none|no-level|mirror-empty|dedup|out-of-range&gt;</pre>
     *
     * <p>The contact is flagged as ordnance, which is what the lock and interception code reads;
     * there is no entity behind it, so anything that resolves the UUID back to an entity simply
     * gets nothing and the contact stays a display/interception entry.</p>
     */
    public static void appendMathRadarContacts(
            Level level,
            List<StabilizerControllerBlockEntity.RadarContact> contacts,
            Set<UUID> existing,
            Vec3 origin,
            double range
    ) {
        int mirror = 0;
        int added = 0;
        int dedup = 0;
        int outOfRange = 0;
        String reason = "none";
        try {
            // A controller riding a Sable sublevel still flies its rounds on the root level's
            // manager, so the mirror has to be read for the root level.
            Level root = rootLevel(level);
            if (!(root instanceof ServerLevel serverLevel)) {
                reason = "no-level";
                reportMathRadar(null, 0, 0, 0, 0, reason, contacts.size());
                return;
            }
            double rangeSqr = range * range;
            for (com.qkdream.firecontrolcompat.missile.MathMissileMirror.Entry entry
                    : com.qkdream.firecontrolcompat.missile.MathMissileMirror.entries(serverLevel)) {
                mirror++;
                if (entry == null || entry.id() == null) {
                    continue;
                }
                Vec3 at = entry.position();
                if (at == null) {
                    continue;
                }
                if (existing.contains(entry.id())) {
                    dedup++;
                    continue;
                }
                if (origin != null && at.distanceToSqr(origin) > rangeSqr) {
                    outOfRange++;
                    continue;
                }
                AABB bounds = new AABB(at, at).inflate(1.5D);
                Vec3 velocity = entry.velocity() == null ? Vec3.ZERO : entry.velocity();
                contacts.add(new StabilizerControllerBlockEntity.RadarContact(
                        entry.id(),
                        at,
                        velocity,
                        bounds,
                        Set.of(),
                        true
                ));
                existing.add(entry.id());
                added++;
                // The radar list is where "selectable" begins for the radar side: from this point
                // the sweep offers the round, so the lock cycle can pick it. Reported once per
                // round per second by MathLocks, in the shape the acceptance criteria ask for.
                try {
                    com.qkdream.firecontrolcompat.missile.MathLocks.reportSelectable(
                            com.qkdream.firecontrolcompat.missile.MathLocks.resolve(serverLevel, entry.id()),
                            "radar");
                } catch (Throwable ignored) {
                    // never let evidence take the sweep down
                }
            }
            if (mirror == 0) {
                reason = "mirror-empty";
            } else if (added == 0) {
                reason = dedup > 0 ? "dedup" : outOfRange > 0 ? "out-of-range" : "none";
            }
            reportMathRadar(serverLevel, mirror, added, dedup, outOfRange, reason, contacts.size());
            if (added > 0) {
                StabilizerControllerBlockEntity.RadarContact last = contacts.get(contacts.size() - 1);
                long now = serverLevel.getGameTime();
                if (mathContactLogAt == 0L || now - mathContactLogAt >= MATH_CONTACT_LOG_TICKS) {
                    mathContactLogAt = now;
                    FireControlCompat.LOGGER.info(
                            "math contact injected: id={} pos={} speed={} count={} total={}",
                            last.subLevelId(),
                            last.center(),
                            String.format(java.util.Locale.ROOT, "%.3f",
                                    last.velocity() == null ? 0.0D : last.velocity().length()),
                            added, contacts.size());
                }
            }
        } catch (Throwable failure) {
            FireControlCompat.LOGGER.info("math contact injection skipped: {}", failure.toString());
        }
    }

    /**
     * One "math radar" line per {@link #RADAR_APPEND_LOG_TICKS} ticks, and one straight away
     * whenever a record actually reached the contact list.
     */
    private static void reportMathRadar(
            ServerLevel level, int mirror, int added, int dedup, int outOfRange, String reason, int total) {
        long now = level == null ? 0L : level.getGameTime();
        if (added == 0 && radarAppendLogAt != 0L && now - radarAppendLogAt < RADAR_APPEND_LOG_TICKS) {
            return;
        }
        radarAppendLogAt = now;
        FireControlCompat.LOGGER.info(
                "math radar: mirror={} added={} dedup={} out-of-range={} reason={} contacts={}",
                mirror, added, dedup, outOfRange, reason, total);
    }

    /**
     * How much of a box stands in liquid, as a share of the box's height samples.
     *
     * <p>A structure is not either dry or sunk: it settles until the water is part way up the
     * hull. Sampling the corners and the middle of the box and asking what share of those points
     * are in fluid gives the "awash past the deck" reading a radar should honour, without walking
     * every block of a large hull.</p>
     */
    private static double submergedPart(Level level, net.minecraft.world.phys.AABB box) {
        int wet = 0;
        int total = 0;
        for (int i = 0; i <= 2; i++) {
            double x = box.minX + (box.maxX - box.minX) * i / 2.0D;
            for (int j = 0; j <= 2; j++) {
                double y = box.minY + (box.maxY - box.minY) * j / 2.0D;
                for (int k = 0; k <= 2; k++) {
                    double z = box.minZ + (box.maxZ - box.minZ) * k / 2.0D;
                    total++;
                    if (!level.getFluidState(net.minecraft.core.BlockPos.containing(x, y, z)).isEmpty()) {
                        wet++;
                    }
                }
            }
        }
        return total == 0 ? 0.0D : (double) wet / (double) total;
    }

    /**
     * Drops every contact a radar could not actually see: one under liquid, or one with the
     * ground between it and the aerial.
     *
     * <p>A radar paints what its beam reaches. A hull that has slipped under the surface, or a
     * structure tucked behind a hill, is not reflecting anything back, and painting it anyway
     * handed the crew and the automatic defense targets they had no line to - the interception
     * then chased a contact through a mountain. Two tests are applied here, both in world space:
     * the contact's own block being fluid, and a block clip from the aerial to the contact that
     * lands short of it. A contact whose centre does not fit the radar's own range gate is left
     * alone, so a position given in another frame can never be culled by mistake.</p>
     *
     * <h2>Why it stopped working, and what changed</h2>
     *
     * <p>Two independent holes, both of which made the whole screen look "turned off":</p>
     *
     * <ol>
     *   <li><b>The clip ran on the controller's own level.</b> A computer riding a Sable structure
     *       lives on that structure's sub-level, whose blocks are addressed in the plot's
     *       <em>stored</em> coordinates - millions of blocks away from where the structure is seen.
     *       The origin and every contact centre are <b>world</b> positions
     *       ({@code getRadarOrigin()} projects out of the sub-level), so the clip was cast through
     *       a level that has no blocks along it at all: it hit nothing, every contact read as
     *       visible, and the filter did nothing. Everything terrain-related is now done on the
     *       <b>root</b> level, which is the level whose blocks are in the same space as the
     *       coordinates being tested.</li>
     *   <li><b>It ran before the overhead append.</b> {@code appendOverheadContacts} adds exactly
     *       the ordnance the elevation window turned away - the top-attack rounds and the diving
     *       shots - and it was called after the strip had already finished, so those contacts were
     *       never tested at all. That is why the contacts a crew sees most of all were the ones the
     *       ground never hid. The overhead path now tests each candidate before adding it
     *       ({@link #overlooks}).</li>
     * </ol>
     *
     * <p>The display radar ({@code appendDisplayContacts}) never had the screen at all; it now runs
     * the same test through {@link #stripTerrainBlockedDisplay}.</p>
     *
     * @return what the sweep saw, so the caller can report it
     */
    public static TerrainReport stripTerrainBlocked(
            Level level,
            Vec3 origin,
            double range,
            List<StabilizerControllerBlockEntity.RadarContact> contacts
    ) {
        if (level == null || origin == null || contacts == null || contacts.isEmpty()) {
            TerrainReport disabled = new TerrainReport(
                    contacts == null ? 0 : contacts.size(), 0, 0, 0, 0, 0, 0, "disabled");
            reportOcclusion(level, disabled);
            return disabled;
        }
        if (!OcclusionSwitch.enabled()) {
            // Switched off by command or config: every contact is visible. Nothing is looked up
            // and nothing is remembered - a screen that is off must not leave state behind that
            // changes how it behaves the moment it is switched back on.
            TerrainReport off = new TerrainReport(
                    contacts.size(), contacts.size(), 0, 0, 0, 0, 0, "disabled");
            reportOcclusion(level, off);
            return off;
        }
        long now = firecontrolcompat$tick(level);
        String dimension = firecontrolcompat$dimension(level);
        // Closes the previous sweep's cost, which is what the status command quotes as "the last
        // tick": the rays of one sweep added up, in microseconds.
        OcclusionPerf.beginSweep();
        // Which level's blocks hold the world this radar stands in. Sable structures are stored in
        // a plot region twenty million blocks away, so a ray cast in the wrong space meets nothing
        // and every contact reads as visible - the failure this whole screen kept hiding behind.
        SubLevelPositions.BlockSpace terrainSpace = firecontrolcompat$space(level, origin, now);
        double loose = range * 1.5D;
        double looseSqr = loose * loose;
        // Beyond this the screen stops judging at all - see firecontrolcompat$sightLimit for why
        // it can never bite inside the radar's own range. A contact left untested stays *visible*,
        // which is the only direction this screen is allowed to be wrong in.
        double early = firecontrolcompat$sightLimit(range);
        double earlySqr = early * early;
        int total = contacts.size();
        // A lambda cannot write a local, so the three counters travel in one array. Index order is
        // fixed here and read back below: 0 fluid, 1 terrain, 2 submerged.
        int[] counters = new int[3];
        int[] raycastFailed = new int[1];
        // The one "is this filter even alive" question could not be answered from the old report:
        // a sweep whose every contact came back visible and a sweep whose clip never ran at all
        // printed the same line. The enter line below is unconditional (rate limited only), and it
        // carries the three facts that decide the rest: which level the clip runs on, whether that
        // level is the root, and whether the line of sight arithmetic has already gone non-finite.
        boolean reportThisSweep = firecontrolcompat$enter(level, terrainSpace, origin, range, total);
        // One terrain ray per contact, with what it hit. Kept per sweep so the line can say
        // "candidate N is behind this block at this distance" instead of only counting.
        boolean detailed = reportThisSweep;
        int[] detailBudget = new int[]{detailed ? PROBE_CONTACTS : 0};
        int[] marchBudget = new int[]{MARCH_SAMPLE_BUDGET};
        long[] marchSamples = new long[1];
        long[] marchSolid = new long[1];
        int[] contested = new int[1];
        contacts.removeIf(contact -> {
            Vec3 at = contact == null ? null : contact.center();
            if (at == null) {
                return false;
            }
            double distanceSqr = at.distanceToSqr(origin);
            if (distanceSqr > looseSqr) {
                // Not where this radar's own frame would put it; leave it to the pack.
                return false;
            }
            if (distanceSqr > earlySqr) {
                OcclusionPerf.skipRange();
                return false;
            }
            Sight sight = new Sight();
            boolean fromCache = firecontrolcompat$sightCached(dimension, now, terrainSpace,
                    origin, at, contact.bounds(), raycastFailed, sight,
                    marchBudget, marchSamples, marchSolid);
            if (fromCache) {
                return false;
            }
            if (detailBudget[0] > 0) {
                detailBudget[0]--;
                firecontrolcompat$reportRay(level, origin, at, distanceSqr, sight);
            }
            if (sight.reason == null) {
                return false;
            }
            if ("march".equals(sight.method)) {
                contested[0]++;
            }
            switch (sight.reason) {
                case "fluid" -> counters[0]++;
                case "submerged" -> counters[2]++;
                default -> counters[1]++;
            }
            return true;
        });
        int fluid = counters[0];
        int terrain = counters[1];
        int submerged = counters[2];
        int occluded = fluid + submerged + terrain;
        String reason = raycastFailed[0] > 0 ? "raycast-fail"
                : (terrainSpace != null && terrainSpace.isPlot() ? "plot" : "none");
        TerrainReport report = new TerrainReport(total, total - occluded, occluded,
                fluid, terrain, submerged, raycastFailed[0], reason);
        reportOcclusion(level, report);
        firecontrolcompat$reportPerf(now);
        if (reportThisSweep && (contested[0] > 0 || marchSolid[0] > 0 || total > 0)) {
            FireControlCompat.LOGGER.info(
                    "occlusion scope=radar: called=true total={} occluded={} visible={} march={}"
                            + " marched-samples={} marched-solid={} budget={} space={}",
                    total, occluded, total - occluded, contested[0], marchSamples[0], marchSolid[0],
                    marchBudget[0], terrainSpace == null ? "-" : terrainSpace.id());
        }
        return report;
    }

    /** How many contacts of a reported sweep also print their own ray. */
    private static final int PROBE_CONTACTS = 2;

    /** Samples one sweep may spend on the block-marching cross-check, in total. */
    private static final int MARCH_SAMPLE_BUDGET = 2400;

    /** Blocks between two samples of the cross-check walk. */
    private static final double MARCH_STEP = 1.5D;

    /**
     * The furthest a contact is judged at, in blocks.
     *
     * <p>Five hundred is the default floor: beyond it a ray is both the most expensive one the
     * sweep can cast and the least meaningful one - the far edge of the drawn map, where a single
     * unloaded chunk decides the answer. But the floor is raised to the radar's own range, because
     * a contact the radar actually paints is a contact this screen has to judge: a mount watching
     * eight hundred blocks would otherwise lose the screen over half its own picture, which is a
     * worse fault than any amount of lag. So the invariant is</p>
     *
     * <pre>the screen never stops judging a contact that is inside the radar's own range</pre>
     *
     * <p>and what the limit really removes is the band the caller already tolerates - contacts
     * past {@code range} but inside {@code range * 1.5}, which exist only because a contact may be
     * reported in a frame this radar does not share. Those read as visible, which is the only
     * direction this screen is allowed to be wrong in.</p>
     */
    private static final double SIGHT_EARLY_LIMIT = 512.0D;

    /** The distance a radar's contacts stop being judged at; see {@link #SIGHT_EARLY_LIMIT}. */
    private static double firecontrolcompat$sightLimit(double range) {
        return Math.min(range * 1.5D, Math.max(SIGHT_EARLY_LIMIT, range));
    }

    /** Tick of the level passed in, or zero when the level has no clock yet. */
    private static long firecontrolcompat$tick(Level level) {
        try {
            return level == null ? 0L : level.getGameTime();
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    /** Writes the five-second cost line when its window has closed, and nothing otherwise. */
    private static void firecontrolcompat$reportPerf(long now) {
        try {
            String line = OcclusionPerf.reportIfDue(now);
            if (line != null) {
                FireControlCompat.LOGGER.info(line);
            }
        } catch (RuntimeException ignored) {
            // A report is never worth a throw on the server thread.
        }
    }

    // A radar asks "which level are my blocks in" once per contact and once per sweep, and the
    // answer is the same for both: the level it stands on does not change between two reads. The
    // two memos below are that answer, kept for a few ticks so a sweep with twenty contacts walks
    // the Sable container once instead of forty times.

    /** Level the root memo was taken for. */
    private static Level rootMemoIn;

    /** The root space of {@link #rootMemoIn}. */
    private static SubLevelPositions.BlockSpace rootMemoOut;

    /** Tick the root memo was taken at. */
    private static long rootMemoAt;

    /** How long the root memo is trusted, in ticks. */
    private static final int ROOT_MEMO_TICKS = 20;

    /** Level the dimension memo was taken for. */
    private static Level dimensionMemoIn;

    /** The dimension id of {@link #dimensionMemoIn}, pre-formatted. */
    private static String dimensionMemoOut;

    /** Tick the dimension memo was taken at. */
    private static long dimensionMemoAt;

    /** How long the dimension memo is trusted, in ticks. */
    private static final int DIMENSION_MEMO_TICKS = 40;

    /**
     * The main level behind whatever level a caller holds, remembered for {@link #ROOT_MEMO_TICKS}.
     *
     * <p>Resolving it means a walk of the Sable container; a radar on a structure asks for it once
     * per contact, so remembering it is the difference between one container walk a sweep and one
     * per ray. The memo is keyed on the level instance, not the dimension, because two levels of
     * one dimension are two different worlds as far as this is concerned.</p>
     */
    private static SubLevelPositions.BlockSpace firecontrolcompat$root(Level level, long now) {
        if (rootMemoOut != null && rootMemoIn == level && now - rootMemoAt < ROOT_MEMO_TICKS) {
            return rootMemoOut;
        }
        SubLevelPositions.BlockSpace root = SubLevelPositions.rootSpace(level);
        rootMemoIn = level;
        rootMemoOut = root;
        rootMemoAt = now;
        return root;
    }

    /**
     * The dimension id of a level, remembered for {@link #DIMENSION_MEMO_TICKS}.
     *
     * <p>It is the cache key's first field, so it would otherwise be built - a resource location
     * turned into a string - once per contact per tick, purely to be hashed.</p>
     */
    private static String firecontrolcompat$dimension(Level level) {
        long now = firecontrolcompat$tick(level);
        if (dimensionMemoOut != null && dimensionMemoIn == level
                && now - dimensionMemoAt < DIMENSION_MEMO_TICKS) {
            return dimensionMemoOut;
        }
        String id = "-";
        try {
            id = level == null ? "-" : level.dimension().location().toString();
        } catch (Throwable ignored) {
        }
        dimensionMemoIn = level;
        dimensionMemoOut = id;
        dimensionMemoAt = now;
        return id;
    }

    /**
     * One contact's sight line, answered from the cache when the same line was cleared recently.
     *
     * <p>The cache only ever holds "clear" answers, so a hit here can only ever keep a contact that
     * the last ray found visible. That is the whole safety argument: a stale entry costs a couple
     * of ticks of latency on a target that has just slipped behind cover, and can never hide one
     * the radar can actually see.</p>
     *
     * @return true when the answer came from the cache and no ray was cast
     */
    private static boolean firecontrolcompat$sightCached(
            String dimension,
            long now,
            SubLevelPositions.BlockSpace space,
            Vec3 origin,
            Vec3 at,
            AABB box,
            int[] raycastFailed,
            Sight out,
            int[] marchBudget,
            long[] marchSamples,
            long[] marchSolid) {
        if (origin == null || at == null) {
            return false;
        }
        if (OcclusionCache.isClear(dimension, origin.x, origin.y, origin.z, at.x, at.y, at.z, now)) {
            OcclusionPerf.skipCache();
            return true;
        }
        int failuresBefore = raycastFailed == null ? 0 : raycastFailed[0];
        firecontrolcompat$sight(space, origin, at, box, raycastFailed, out,
                marchBudget, marchSamples, marchSolid);
        // Only a ray that ran clean and found nothing is remembered: a ray that fell back on the
        // sampled walk, or that could not run at all, says nothing about what is between the two
        // points and must be asked again next tick.
        if (out != null && out.reason == null
                && (raycastFailed == null || raycastFailed[0] == failuresBefore)) {
            OcclusionCache.rememberClear(dimension, origin.x, origin.y, origin.z, at.x, at.y, at.z, now);
        }
        return false;
    }

    /** The unconditional "the caller really ran" line, printed once per {@link #OCCLUSION_LOG_TICKS}
     * ticks, and immediately when a sweep arrives with contacts to test.
     */
    private static boolean firecontrolcompat$enter(
            Level level, SubLevelPositions.BlockSpace terrainSpace, Vec3 origin, double range, int total) {
        long now = 0L;
        try {
            now = level.getGameTime();
        } catch (Throwable ignored) {
        }
        boolean due = enterLogAt == 0L || now - enterLogAt >= OCCLUSION_LOG_TICKS;
        if (!due) {
            // One line every forty ticks per sweep, never one per sweep: a radar that has contacts
            // sweeps every other tick, so "print whenever there is something" would write twenty
            // lines a second on the server thread - the report would then be the lag.
            return false;
        }
        enterLogAt = now;
        String dimension = firecontrolcompat$dimension(level);
        boolean chunk = false;
        try {
            chunk = terrainSpace != null && terrainSpace.level != null
                    && terrainSpace.level.hasChunkAt(net.minecraft.core.BlockPos.containing(origin));
        } catch (Throwable ignored) {
        }
        FireControlCompat.LOGGER.info(
                "occlusion enter: total={} reason={} origin={} range={} level={} clip={} root={}"
                        + " sublevel={} chunk={} mode={} space={}",
                total, total <= 0 ? "no-contacts" : "sweep", origin, range, dimension,
                terrainSpace == null ? "-" : terrainSpace.id(),
                terrainSpace != null && !terrainSpace.isPlot(),
                terrainSpace != null && terrainSpace.isPlot(), chunk,
                firecontrolcompat$modeName(), terrainSpace == null ? "-" : terrainSpace.id());
        return true;
    }

    // ---------------------------------------------------------------------------------------
    // Which space the ray is cast in
    // ---------------------------------------------------------------------------------------

    /** {@code 0} before the first probe, then {@link #SPACE_ROOT} or {@link #SPACE_PLOT}. */
    private static volatile int spaceMode;

    /** The plot space the probe settled on, kept so a sweep never resolves it twice. */
    private static volatile SubLevelPositions.BlockSpace spacePlot;

    /** Tick of the last space probe. */
    private static long spaceProbeAt;

    /** Quietest gap between two space probes, in ticks. */
    private static final int SPACE_PROBE_TICKS = 200;

    private static final int SPACE_ROOT = 1;
    private static final int SPACE_PLOT = 2;

    private static String firecontrolcompat$modeName() {
        return switch (spaceMode) {
            case SPACE_PLOT -> "plot";
            case SPACE_ROOT -> "root";
            default -> "unknown";
        };
    }

    /**
     * Resolves the space this radar's line of sight must be cast in, and re-checks it every
     * {@link #SPACE_PROBE_TICKS} ticks.
     *
     * <p>The choice is made by the known-answer probe in {@link #firecontrolcompat$probe}: a ray
     * dropped from ninety-six blocks above the aerial has to meet the ground. On a Sable structure
     * it does <b>not</b> meet anything in the main level - the structure's blocks, the deck under
     * the radar and the ground it rides over are all stored in the plot's region millions of blocks
     * away - so the main level answers "clear" down to bedrock, which is the {@code down=clear}
     * that made every contact look visible. When that happens the screen switches itself to the
     * plot's own space, and says so: {@code occlusion: mode=plot reason=root-blind}.</p>
     */
    private static SubLevelPositions.BlockSpace firecontrolcompat$space(Level level, Vec3 origin, long now) {
        boolean due = spaceMode == 0 || spaceProbeAt == 0L || now - spaceProbeAt >= SPACE_PROBE_TICKS;
        if (!due) {
            if (spaceMode == SPACE_PLOT) {
                SubLevelPositions.BlockSpace plot = spacePlot;
                if (plot != null) {
                    return plot;
                }
            }
            return firecontrolcompat$root(level, now);
        }
        spaceProbeAt = now;
        // Root and plot are resolved from one answer: both need to know which level the main world
        // is, and asking the Sable container twice per sweep for the same number was pure waste.
        SubLevelPositions.BlockSpace root = firecontrolcompat$root(level, now);
        SubLevelPositions.BlockSpace plot = origin == null ? null
                : SubLevelPositions.plotSpace(level, origin, root == null ? null : root.level);
        Probe rootProbe = firecontrolcompat$probe(root, origin, "root");
        Probe plotProbe = plot == null ? null : firecontrolcompat$probe(plot, origin, "plot");
        boolean rootOk = rootProbe != null && rootProbe.healthy();
        boolean plotOk = plotProbe != null && plotProbe.healthy();
        int previous = spaceMode;
        String reason;
        SubLevelPositions.BlockSpace chosen;
        if (rootOk) {
            chosen = root;
            spaceMode = SPACE_ROOT;
            reason = plot == null ? "root-only" : "root-healthy";
        } else if (plotOk) {
            chosen = plot;
            spaceMode = SPACE_PLOT;
            reason = "root-blind";
        } else if (plot != null) {
            // Neither space sees anything: a genuinely empty sky, or a world that is not there for
            // us at all. Keep the main level (the cheaper one) and say that it is blind.
            chosen = root;
            spaceMode = SPACE_ROOT;
            reason = "both-blind";
        } else {
            chosen = root;
            spaceMode = SPACE_ROOT;
            reason = "root-blind-no-sublevel";
        }
        spacePlot = spaceMode == SPACE_PLOT ? plot : null;
        terrainBlind = !(spaceMode == SPACE_PLOT ? plotOk : rootOk);
        if (previous != 0 && previous != spaceMode) {
            // Every remembered sight line was decided in the other space; none of them is a fact
            // about the space in use now, so they are all retired together.
            OcclusionCache.invalidate();
        }
        FireControlCompat.LOGGER.info(
                "occlusion: mode={} reason={} space={} root-down={} root-solid={} plot-down={}"
                        + " plot-solid={} changed={}",
                firecontrolcompat$modeName(), reason, chosen == null ? "-" : chosen.id(),
                rootProbe == null ? "-" : rootProbe.down(), rootProbe == null ? 0 : rootProbe.solid(),
                plotProbe == null ? "-" : plotProbe.down(), plotProbe == null ? 0 : plotProbe.solid(),
                previous != 0 && previous != spaceMode);
        return chosen;
    }

    /** What one known-answer probe saw. */
    private record Probe(String space, String down, int solid, String method, String block, double hit) {
        boolean healthy() {
            // The ground is there for this space when the dropped ray met it, by either test.
            return solid > 0 || !"clear".equals(down);
        }
    }

    /**
     * The hard-coded "through the mountain" test, run once per space.
     *
     * <p>A ray straight down from ninety-six blocks above the aerial has to meet the deck or the
     * ground. Meeting nothing is not "the sky is clear", it is "this level does not hold the world
     * this radar stands in", and it is printed as {@code down=clear blind=true} so the two can
     * never be confused again.</p>
     */
    private static Probe firecontrolcompat$probe(
            SubLevelPositions.BlockSpace space, Vec3 origin, String label) {
        if (space == null || space.level == null || origin == null) {
            return null;
        }
        try {
            Vec3 top = origin.add(0.0D, 96.0D, 0.0D);
            Vec3 bottom = origin.add(0.0D, -32.0D, 0.0D);
            Sight down = new Sight();
            int[] budget = new int[]{600};
            long[] samples = new long[1];
            long[] solid = new long[1];
            int[] failures = new int[1];
            firecontrolcompat$sight(space, top, bottom, null, failures, down, budget, samples, solid);
            Probe probe = new Probe(space.id(), down.reason == null ? "clear" : down.reason,
                    (int) solid[0], down.method, down.block, down.hit);
            FireControlCompat.LOGGER.info(
                    "occlusion selftest: space={} level={} from={} to={} span={} down={} solid={}"
                            + " method={} block={} at={} samples={} blind={}",
                    label, space.id(), top, bottom, (int) Math.round(top.distanceTo(bottom)),
                    probe.down(), probe.solid(), probe.method(), probe.block(),
                    probe.hit() < 0.0D ? "-" : String.format(java.util.Locale.ROOT, "%.1f", probe.hit()),
                    samples[0], !probe.healthy());
            return probe;
        } catch (Throwable failure) {
            FireControlCompat.LOGGER.info("occlusion selftest failed: space={} {}", label, failure.toString());
            return null;
        }
    }

    /** One candidate ray, as evidence: where it went, what it met, and what was decided. */
    private static void firecontrolcompat$reportRay(Level level, Vec3 origin, Vec3 at,
            double distanceSqr, Sight sight) {
        try {
            double span = Math.sqrt(Math.max(0.0D, distanceSqr));
            FireControlCompat.LOGGER.info(
                    "occlusion ray: from={} to={} span={} hit={} method={} block={} at={}"
                            + " verdict={} space={} span-ok={}",
                    origin, at, String.format(java.util.Locale.ROOT, "%.1f", span),
                    sight.hit < 0.0D ? "miss" : String.format(java.util.Locale.ROOT, "%.1f", sight.hit),
                    sight.method, sight.block,
                    sight.hit < 0.0D ? "-" : String.format(java.util.Locale.ROOT, "%.1f", sight.hit),
                    sight.reason == null ? "visible" : "occluded:" + sight.reason,
                    sight.space,
                    span < 1.0E6D);
        } catch (Throwable ignored) {
            // evidence is never worth a throw on the server thread
        }
    }

    /**
     * Whether the ground (or water) stops this radar from seeing one point.
     *
     * <p>Used by the overhead append, which adds its candidates one at a time after the list-wide
     * strip has already run; testing the whole list a second time for the sake of a handful of
     * contacts would double the cost of every sweep.</p>
     */
    public static boolean overlooks(Level level, Vec3 origin, double range, Vec3 at, AABB box) {
        if (level == null || origin == null || at == null || !OcclusionSwitch.enabled()) {
            return false;
        }
        double loose = range * 1.5D;
        double distanceSqr = at.distanceToSqr(origin);
        if (distanceSqr > loose * loose) {
            return false;
        }
        double early = firecontrolcompat$sightLimit(range);
        if (distanceSqr > early * early) {
            OcclusionPerf.skipRange();
            return false;
        }
        long now = firecontrolcompat$tick(level);
        Sight sight = new Sight();
        boolean fromCache = firecontrolcompat$sightCached(
                firecontrolcompat$dimension(level), now, firecontrolcompat$space(level, origin, now),
                origin, at, box, new int[1], sight,
                new int[]{MARCH_SAMPLE_BUDGET}, new long[1], new long[1]);
        firecontrolcompat$reportPerf(now);
        return !fromCache && sight.reason != null;
    }

    /**
     * What one line of sight came back with.
     *
     * <p>{@code reason} is {@code null} when the radar can see the contact. {@code method} says
     * which test produced the verdict, because the two can disagree: the block clip is exact but
     * silently answers "nothing" for a level or a coordinate space that does not hold the world,
     * while the sampled walk is coarse but reads the blocks directly. When the clip finds nothing
     * and the walk does, the walk wins - a coarse "there is rock in the way" is the right answer,
     * a confident "the way is clear" from a blind clip is not.</p>
     */
    private static final class Sight {
        String reason;
        String method = "-";
        double hit = -1.0D;
        String block = "-";
        String space = "-";
    }

    /**
     * The reason one contact is invisible, or {@code null} when it is visible.
     *
     * <p>Every test happens in {@code space}: the origin, the contact centre and the blocks between
     * them all have to be in one coordinate space, and that space is the one whose blocks hold the
     * world this radar stands in.</p>
     */
    private static String blockedReason(
            String dimension, long now, SubLevelPositions.BlockSpace space, Vec3 origin, Vec3 at,
            AABB box, int[] raycastFailed) {
        Sight sight = new Sight();
        firecontrolcompat$sightCached(dimension, now, space, origin, at, box, raycastFailed, sight,
                new int[]{MARCH_SAMPLE_BUDGET}, new long[1], new long[1]);
        return sight.reason;
    }

    /**
     * The line-of-sight test, timed.
     *
     * <p>One call is one ray - the clip, the fallback walk and the fluid tests of one contact -
     * so wrapping it here is what makes {@code calls=} and {@code avg-us=} in the perf line mean
     * something a report can quote. The wrapper cannot change the verdict: the body fills the same
     * {@link Sight} it always did, and the only thing this adds is the stamp around it.</p>
     */
    private static void firecontrolcompat$sight(
            SubLevelPositions.BlockSpace space,
            Vec3 origin,
            Vec3 at,
            AABB box,
            int[] raycastFailed,
            Sight out,
            int[] marchBudget,
            long[] marchSamples,
            long[] marchSolid) {
        long started = System.nanoTime();
        boolean blocked = false;
        try {
            firecontrolcompat$sightOnce(space, origin, at, box, raycastFailed, out,
                    marchBudget, marchSamples, marchSolid);
            blocked = out != null && out.reason != null;
        } finally {
            OcclusionPerf.record(System.nanoTime() - started, blocked);
        }
    }

    /**
     * The line-of-sight test: liquid at the contact, a hull awash past its deck, ground in between.
     *
     * <p>{@code origin} and {@code at} arrive in world coordinates and are re-expressed in
     * {@code space} first; the clip then runs on that level with {@code sable$doNotProject} set, so
     * the hit comes back in the same coordinates the ray was given in and the distance measured
     * against it means something. A hit closer than {@link #NEAR_SKIP} is the radar's own mount and
     * is stepped over, not counted as cover.</p>
     */
    private static void firecontrolcompat$sightOnce(
            SubLevelPositions.BlockSpace space,
            Vec3 origin,
            Vec3 at,
            AABB box,
            int[] raycastFailed,
            Sight out,
            int[] marchBudget,
            long[] marchSamples,
            long[] marchSolid) {
        if (space == null || space.level == null || origin == null || at == null || out == null) {
            return;
        }
        out.space = space.name;
        Vec3 from = space.toLocal(origin);
        Vec3 to = space.toLocal(at);
        if (from == null || to == null) {
            return;
        }
        double span = from.distanceTo(to);
        double worldSpan = origin.distanceTo(at);
        if (!(span > 1.0E-6D) || !Double.isFinite(span)) {
            return;
        }
        try {
            net.minecraft.core.BlockPos inside = net.minecraft.core.BlockPos.containing(to);
            if (!space.level.getFluidState(inside).isEmpty()) {
                out.reason = "fluid";
                out.method = "fluid";
                return;
            }
            // Half under the surface counts as under it: a hull awash to the deck is not a radar
            // target either, and testing only the centre missed exactly that case.
            if (box != null && submergedPart(space.level, firecontrolcompat$localBox(space, box)) > 0.10D) {
                out.reason = "submerged";
                out.method = "fluid";
                return;
            }
        } catch (RuntimeException | LinkageError failure) {
            raycastFailed[0]++;
        }
        boolean clipSawSomething = false;
        try {
            net.minecraft.world.phys.BlockHitResult hit = SubLevelPositions.clipIn(space.level, from, to);
            if (hit != null && hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
                // Same space as the ray: this is the distance the user asked for, not a coordinate
                // magnitude. A raw level.clip on a structure answers in the plot's stored space and
                // this number then reads twenty million, which is what made every contact visible.
                double distance = hit.getLocation().distanceTo(from);
                out.method = "clip";
                out.hit = span > 1.0E-9D ? distance * (worldSpan / span) : distance;
                try {
                    out.block = String.valueOf(net.minecraft.core.registries.BuiltInRegistries.BLOCK
                            .getKey(space.level.getBlockState(hit.getBlockPos()).getBlock()));
                } catch (RuntimeException ignored) {
                    out.block = "?";
                }
                if (distance >= NEAR_SKIP && distance < span - 2.0D) {
                    out.reason = "terrain";
                    return;
                }
                // A hit inside NEAR_SKIP is the aerial's own mount, and a hit at the far end is the
                // contact itself. Neither is cover, so the walk below still gets its say.
                clipSawSomething = distance >= NEAR_SKIP;
            }
        } catch (RuntimeException | LinkageError failure) {
            // A clip that cannot run must not silently read as "visible everywhere" without saying
            // so - that was half of the "the occlusion is dead" report. Counted, and named in the
            // report line as reason=raycast-fail.
            raycastFailed[0]++;
        }
        if (clipSawSomething) {
            return;
        }
        // The clip answered "nothing in the way". Before that is believed, walk the blocks: a clip
        // that never touches the world (wrong level, a stored-coordinate space, an unloaded region)
        // answers exactly the same thing, and that is the failure this filter kept hiding behind.
        try {
            double fraction = firecontrolcompat$march(space.level, from, to, marchBudget, marchSamples);
            if (fraction >= 0.0D && fraction * span < span - 2.0D) {
                if (marchSolid != null) {
                    marchSolid[0]++;
                }
                out.reason = "terrain";
                out.method = "march";
                out.hit = fraction * worldSpan;
                out.block = "march";
            }
        } catch (RuntimeException | LinkageError failure) {
            raycastFailed[0]++;
        }
    }

    /** A world box expressed in the space's own coordinates (translation only). */
    private static AABB firecontrolcompat$localBox(SubLevelPositions.BlockSpace space, AABB box) {
        if (space == null || !space.isPlot() || box == null) {
            return box;
        }
        try {
            Vec3 center = box.getCenter();
            Vec3 local = space.toLocal(center);
            if (local == null || !Double.isFinite(local.x)) {
                return box;
            }
            return box.move(local.x - center.x, local.y - center.y, local.z - center.z);
        } catch (RuntimeException failure) {
            return box;
        }
    }

    /** The aerial's own mount, mount bolts and mast: closer than this is not cover. */
    private static final double NEAR_SKIP = 2.5D;

    /**
     * Walks the line of sight in {@link #MARCH_STEP} block steps and answers where the first solid
     * block is as a fraction of the ray, or {@code -1} when the walk reaches the contact.
     *
     * <p>Sampling is deliberately coarse (a one-block wall can slip between two samples) and
     * deliberately capped: it exists to catch a clip that is blind, not to replace it. The budget
     * is shared by a whole sweep, so a radar with twenty contacts cannot turn one tick into
     * thousands of block reads.</p>
     */
    private static double firecontrolcompat$march(
            Level level, Vec3 from, Vec3 to, int[] budget, long[] samplesOut) {
        if (level == null || from == null || to == null || budget == null || budget[0] <= 0) {
            return -1.0D;
        }
        double span = from.distanceTo(to);
        if (!(span > 2.0D)) {
            return -1.0D;
        }
        int wanted = (int) Math.ceil(span / firecontrolcompat$marchStep(span));
        // The far cap is lower than the near one on purpose: the walk is a cross-check on a clip
        // that answered "nothing", and the longer the ray the less a single extra sample buys. A
        // coarser walk can only miss cover, never invent it, so the direction this trades in is the
        // one the screen is allowed to be wrong in.
        int cap = span <= 96.0D ? 1200 : 384;
        int samples = Math.min(Math.min(wanted, cap), budget[0]);
        if (samples <= 2) {
            return -1.0D;
        }
        budget[0] -= samples;
        net.minecraft.core.BlockPos.MutableBlockPos pos = new net.minecraft.core.BlockPos.MutableBlockPos();
        for (int i = 1; i < samples; i++) {
            double fraction = (double) i / (double) samples;
            double along = span * fraction;
            if (along > span - 2.0D) {
                return -1.0D;
            }
            Vec3 at = from.lerp(to, fraction);
            pos.set(at.x, at.y, at.z);
            if (samplesOut != null) {
                samplesOut[0]++;
            }
            net.minecraft.world.level.block.state.BlockState state;
            try {
                state = level.getBlockState(pos);
            } catch (RuntimeException failure) {
                return -1.0D;
            }
            if (state.isAir() || !state.getFluidState().isEmpty()) {
                continue;
            }
            try {
                if (state.getCollisionShape(level, pos).isEmpty()) {
                    continue;
                }
            } catch (RuntimeException failure) {
                continue;
            }
            if (along < NEAR_SKIP) {
                continue;
            }
            return fraction;
        }
        return -1.0D;
    }

    /**
     * Blocks between two samples of the cross-check walk, by distance.
     *
     * <p>The walk exists to catch a clip that is blind, so it has to read real blocks - but it does
     * not have to read them at one fixed density for a twelve-block ray and a five-hundred-block
     * one. Near the aerial a one-block step is what makes the walk worth running at all; at four
     * hundred blocks a six-block step still crosses every hill and costs a twelfth of the reads.
     * Coarser sampling can only ever miss a thin wall (a false "visible"), which is the direction
     * this screen is allowed to err in.</p>
     */
    private static double firecontrolcompat$marchStep(double span) {
        if (!(span > 0.0D) || !Double.isFinite(span)) {
            return MARCH_STEP;
        }
        if (span <= 24.0D) {
            return 1.0D;
        }
        if (span <= 96.0D) {
            return MARCH_STEP;
        }
        if (span <= 256.0D) {
            return 3.0D;
        }
        return 6.0D;
    }

    /** Display-side twin of {@link #stripTerrainBlocked}: same tests, the display's own contact type. */
    public static TerrainReport stripTerrainBlockedDisplay(
            Level level, Vec3 origin, double range, List<ConnectedDisplayBlockEntity.Contact> contacts) {
        if (level == null || origin == null || contacts == null || contacts.isEmpty()) {
            return new TerrainReport(contacts == null ? 0 : contacts.size(), 0, 0, 0, 0, 0, 0, "disabled");
        }
        if (!OcclusionSwitch.enabled()) {
            // Same contract as the radar-side strip: off means every contact is visible, and no
            // block is read, no entry is written.
            return new TerrainReport(contacts.size(), contacts.size(), 0, 0, 0, 0, 0, "disabled");
        }
        long now = firecontrolcompat$tick(level);
        String dimension = firecontrolcompat$dimension(level);
        OcclusionPerf.beginSweep();
        SubLevelPositions.BlockSpace terrainSpace = firecontrolcompat$space(level, origin, now);
        double looseSqr = range * 1.5D * range * 1.5D;
        double early = firecontrolcompat$sightLimit(range);
        double earlySqr = early * early;
        int total = contacts.size();
        int[] raycastFailed = new int[1];
        contacts.removeIf(contact -> {
            Vec3 at = contact == null ? null : contact.center();
            if (at == null) {
                return false;
            }
            double distanceSqr = at.distanceToSqr(origin);
            if (distanceSqr > looseSqr) {
                return false;
            }
            if (distanceSqr > earlySqr) {
                OcclusionPerf.skipRange();
                return false;
            }
            return blockedReason(dimension, now, terrainSpace, origin, at, contact.bounds(),
                    raycastFailed) != null;
        });
        int occluded = Math.max(0, total - contacts.size());
        TerrainReport report = new TerrainReport(total, total - occluded, occluded, 0, occluded, 0,
                raycastFailed[0], raycastFailed[0] > 0 ? "raycast-fail"
                        : (terrainSpace != null && terrainSpace.isPlot() ? "plot" : "none"));
        reportOcclusion(level, report);
        firecontrolcompat$reportPerf(now);
        return report;
    }

    /** What one terrain pass saw, and why it could not run when it could not. */
    public record TerrainReport(
            int total,
            int visible,
            int occluded,
            int fluid,
            int terrain,
            int submerged,
            int raycastFailures,
            String reason
    ) {
    }

    /** Quietest gap between two "occlusion" lines, in ticks. */
    private static final int OCCLUSION_LOG_TICKS = 40;

    /** Tick of the last "occlusion" line, or {@code 0} before the first one. */
    private static long occlusionLogAt;

    /** Quietest gap between two "occlusion enter" lines, in ticks. */
    private static long enterLogAt;

    /**
     * Whether the last self test could not see the world at all.
     *
     * <p>Kept so the report line can say so instead of reporting a clean {@code occluded=0} that
     * means nothing: a blind clip and a clear sky print the same counts.</p>
     */
    private static volatile boolean terrainBlind;

    /**
     * One line per forty ticks, and one straight away whenever the ground removed something.
     *
     * <pre>occlusion: total=&lt;n&gt; visible=&lt;n&gt; occluded=&lt;n&gt; fluid=&lt;n&gt; terrain=&lt;n&gt; submerged=&lt;n&gt; reason=&lt;none|plot|raycast-fail|disabled&gt; blind=&lt;bool&gt; mode=&lt;root|plot&gt;</pre>
     *
     * <p>The three counts are split because the three failures mean different things: a contact
     * under water is a target that dived, one behind cover is a target with no line, and a hull
     * awash past its deck is a target that is half sunk. {@code reason=plot} says the ray had to be
     * cast in a sub-level's own stored space, because the main level holds no blocks where the world
     * is drawn - the case that used to make the screen do nothing. {@code blind=true} means
     * neither space could see any block, so the counts above say nothing about the world.</p>
     */
    private static void reportOcclusion(Level level, TerrainReport report) {
        long now = 0L;
        try {
            now = level == null ? 0L : level.getGameTime();
        } catch (Throwable ignored) {
            // a level without a clock still reports its first line
        }
        boolean urgent = report.occluded() > 0 || "raycast-fail".equals(report.reason());
        if (!urgent && occlusionLogAt != 0L && now - occlusionLogAt < OCCLUSION_LOG_TICKS) {
            return;
        }
        occlusionLogAt = now;
        FireControlCompat.LOGGER.info(
                "occlusion: total={} visible={} occluded={} fluid={} terrain={} submerged={} raycastfail={} reason={} blind={} mode={}",
                report.total(), report.visible(), report.occluded(), report.fluid(),
                report.terrain(), report.submerged(), report.raycastFailures(), report.reason(),
                terrainBlind, firecontrolcompat$modeName());
    }


    /**
     * Ordnance the sweep would otherwise look straight past.
     *
     * <p>A ground radar's elevation ceiling keeps its sweep below the sky above
     * it, which is right for a post watching the horizon and wrong for one being
     * attacked from it: a top attack comes down on the mount from nearly
     * overhead, and through the whole terminal dive it sits above that ceiling
     * and is painted by nobody. The track is then dropped in the second the
     * round has to be shot at, and neither the crew nor the automatic defense
     * ever gets a shot at it.</p>
     *
     * <p>Ordnance is therefore painted outside the window as well, on either
     * side of it: above the ceiling, and below a depression floor that a post
     * on a ridge or a ship riding high would otherwise keep its sweep under.
     * Nothing else changes: structures still obey the window, the range gate
     * still applies, and the IFF and speed gates run over these contacts like
     * the rest.</p>
     */
    public static void appendOverheadContacts(
            StabilizerControllerBlockEntity controller,
            List<StabilizerControllerBlockEntity.RadarContact> contacts
    ) {
        Level level = controller.getLevel();
        if (level == null || controller.isAircraftRadarMode()) {
            return;
        }
        Vec3 origin = controller.getRadarOrigin();
        if (origin == null) {
            return;
        }
        double range = controller.getRadarRange();
        double ceiling = controller.getRadarElevation();
        Set<UUID> existing = new HashSet<>();
        for (StabilizerControllerBlockEntity.RadarContact contact : contacts) {
            existing.add(contact.subLevelId());
        }
        Set<UUID> visited = new HashSet<>();
        int overhead = 0;
        int hidden = 0;
        for (Entity entity : firecontrolcompat$candidates(level, controller, origin, range)) {
            if (!visited.add(entity.getUUID()) || existing.contains(entity.getUUID())) {
                continue;
            }
            if (isOwnFireControlOrdnance(entity) || !isOrdnance(entity)) {
                continue;
            }
            Vec3 center = MianbaoMissileCompat.getWorldPosition(entity);
            AABB bounds = entity.getBoundingBox();
            if (center == null || bounds == null || center.distanceTo(origin) > range) {
                continue;
            }
            if (outsideCruiseStealth(center, origin, entity)) {
                continue;
            }
            if (elevationWindowHolds(controller, origin, bounds)) {
                continue;
            }
            overhead++;
            // The list-wide screen has already run by the time this append happens, so each
            // candidate is tested here instead - otherwise every contact this method adds is
            // exempt from terrain, which is exactly the family a crew watches most: the ordnance
            // the elevation window turned away. A round below a ridge line is hidden by that ridge
            // whether or not the window also turned it away.
            if (overlooks(level, origin, range, center, bounds)) {
                hidden++;
                continue;
            }
            contacts.add(new StabilizerControllerBlockEntity.RadarContact(
                    entity.getUUID(),
                    center,
                    MianbaoMissileCompat.getWorldVelocity(entity),
                    bounds,
                    Set.of(),
                    // Ordnance, not a structure: the radar list and everything
                    // built on it - interception priority, the diving hand
                    // over, the automatic defense - is decided by this flag.
                    true
            ));
        }
        if (overhead > 0) {
            FireControlCompat.LOGGER.info(
                    "occlusion: scope=overhead total={} visible={} occluded={} reason={}",
                    overhead, overhead - hidden, hidden, "none");
        }
    }

    /** Every entity family of flying ordnance a radar should treat as a threat. */
    private static boolean isOrdnance(Entity entity) {
        if (entity == null) {
            return false;
        }
        try {
            return TargetClassifier.isCompatMissile(entity)
                    || MianbaoAirDefenseCompat.isMissile(entity)
                    || MianbaoMissileCompat.isGuidedMissile(entity)
                    || MianbaoCruiseMissileCompat.isRadarMissile(entity)
                    || MianbaoAircraftCompat.isAirToGroundMissile(entity);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * The elevation of the lowest point of a contact's box above the radar, in
     * degrees. This is the same measure the sweep's own window is built on, so a
     * contact whose reading is above the ceiling is one the window itself turned
     * away.
     */
    private static double lowestElevationDegrees(Vec3 origin, AABB bounds) {
        double closestX = Math.max(bounds.minX, Math.min(bounds.maxX, origin.x));
        double closestZ = Math.max(bounds.minZ, Math.min(bounds.maxZ, origin.z));
        double horizontalMin = Math.sqrt((closestX - origin.x) * (closestX - origin.x) + (closestZ - origin.z) * (closestZ - origin.z));
        double horizontalMax = 0.0;
        for (double x : new double[]{bounds.minX, bounds.maxX}) {
            for (double z : new double[]{bounds.minZ, bounds.maxZ}) {
                horizontalMax = Math.max(horizontalMax, Math.sqrt((x - origin.x) * (x - origin.x) + (z - origin.z) * (z - origin.z)));
            }
        }
        double lowY = bounds.minY - origin.y;
        return Math.toDegrees(Math.atan2(lowY, Math.max(1.0E-6, lowY <= 0.0 ? horizontalMin : horizontalMax)));
    }

    private static boolean isOwnFireControlOrdnance(Entity entity) {
        boolean flagged = entity.getPersistentData().getBoolean("CreateFireControlDisplayGuided")
                || entity.getPersistentData().getBoolean("CreateFireControlAaGuided")
                || entity.getPersistentData().getBoolean("CreateFireControlIntercepted");
        if (!flagged) {
            return false;
        }
        // The heavy air-defense missile must remain a visible missile threat
        // so the automatic missile defense can engage it.
        return !TargetClassifier.isHeavyAaMissile(entity);
    }

    /**
     * The ordnance this radar can see: a list of candidates kept for a few
     * sweeps and resolved back to live entities on every use, so positions stay
     * current while the expensive search is not repeated every sweep.
     */
    private static List<Entity> firecontrolcompat$candidates(
            Level level, StabilizerControllerBlockEntity controller, Vec3 origin, double range) {
        long now = level.getGameTime();
        CachedScan cached = SCAN_CACHE.get(controller);
        List<Entity> found = new ArrayList<>();
        if (cached != null && now - cached.tick() < SCAN_REFRESH_TICKS) {
            for (Entity entity : cached.candidates()) {
                if (entity.isAlive() && !entity.isRemoved()) {
                    found.add(entity);
                }
            }
            return found;
        }
        Set<UUID> seen = new HashSet<>();
        scanEntities(level, origin, range, entity -> {
            if (isOwnFireControlOrdnance(entity) || seen.contains(entity.getUUID())) {
                return;
            }
            if (!isOrdnance(entity)) {
                return;
            }
            seen.add(entity.getUUID());
            found.add(entity);
        });
        SCAN_CACHE.put(controller, new CachedScan(now, List.copyOf(found)));
        return found;
    }

    private interface EntityConsumer {
        void accept(Entity entity);
    }

    private static void scanEntities(Level level, Vec3 origin, double range, EntityConsumer consumer) {
        AABB query = new AABB(origin, origin).inflate(range);
        Level root = rootLevel(level);
        if (root != null && root != level) {
            for (Entity entity : root.getEntitiesOfClass(Entity.class, query, entity -> entity.isAlive() && !entity.isRemoved())) {
                consumer.accept(entity);
            }
        }
        for (Entity entity : level.getEntitiesOfClass(Entity.class, query, entity -> entity.isAlive() && !entity.isRemoved())) {
            consumer.accept(entity);
        }
    }

    private static Level rootLevel(Level level) {
        try {
            return SubLevelContainer.getContainer(level).getLevel();
        } catch (Throwable t) {
            return level;
        }
    }

    private static boolean inRadarVolume(
            StabilizerControllerBlockEntity controller,
            boolean aircraft,
            Vec3 origin,
            Vec3 center,
            AABB bounds,
            double range
    ) {
        if (center.distanceToSqr(origin) > range * range) {
            return false;
        }
        if (aircraft) {
            Vec3 forward = controller.getAircraftRadarForward();
            Vec3 up = controller.getAircraftRadarUp();
            Vec3 right = forward.cross(up).normalize();
            Vec3 planarForward = forward.subtract(up.scale(forward.dot(up))).normalize();
            Vec3 delta = center.subtract(origin);
            double longitudinal = delta.dot(planarForward);
            if (longitudinal <= 0.0) {
                return false;
            }
            double azimuth = Math.toDegrees(Math.atan2(delta.dot(right), longitudinal));
            double elevation = Math.toDegrees(Math.atan2(delta.dot(up), Math.sqrt(longitudinal * longitudinal + Math.pow(delta.dot(right), 2.0))));
            return Math.abs(azimuth) <= controller.getAircraftRadarHorizontalHalfAngle()
                    && Math.abs(elevation) <= controller.getAircraftRadarVerticalAngle() * 0.5;
        }

        double closestX = Math.max(bounds.minX, Math.min(bounds.maxX, origin.x));
        double closestZ = Math.max(bounds.minZ, Math.min(bounds.maxZ, origin.z));
        double horizontalMin = Math.sqrt((closestX - origin.x) * (closestX - origin.x) + (closestZ - origin.z) * (closestZ - origin.z));
        double horizontalMax = 0.0;
        for (double x : new double[]{bounds.minX, bounds.maxX}) {
            for (double z : new double[]{bounds.minZ, bounds.maxZ}) {
                horizontalMax = Math.max(horizontalMax, Math.sqrt((x - origin.x) * (x - origin.x) + (z - origin.z) * (z - origin.z)));
            }
        }
        return elevationWindowHolds(controller, origin, bounds, horizontalMin, horizontalMax);
    }

    /** The sweep's own elevation window, applied to a contact's bounding box. */
    private static boolean elevationWindowHolds(
            StabilizerControllerBlockEntity controller, Vec3 origin, AABB bounds) {
        double closestX = Math.max(bounds.minX, Math.min(bounds.maxX, origin.x));
        double closestZ = Math.max(bounds.minZ, Math.min(bounds.maxZ, origin.z));
        double horizontalMin = Math.sqrt((closestX - origin.x) * (closestX - origin.x) + (closestZ - origin.z) * (closestZ - origin.z));
        double horizontalMax = 0.0;
        for (double x : new double[]{bounds.minX, bounds.maxX}) {
            for (double z : new double[]{bounds.minZ, bounds.maxZ}) {
                horizontalMax = Math.max(horizontalMax, Math.sqrt((x - origin.x) * (x - origin.x) + (z - origin.z) * (z - origin.z)));
            }
        }
        return elevationWindowHolds(controller, origin, bounds, horizontalMin, horizontalMax);
    }

    private static boolean elevationWindowHolds(
            StabilizerControllerBlockEntity controller, Vec3 origin, AABB bounds,
            double horizontalMin, double horizontalMax) {
        double lowY = bounds.minY - origin.y;
        double highY = bounds.maxY - origin.y;
        double minElevation = Math.toDegrees(Math.atan2(lowY, Math.max(1.0E-6, lowY <= 0.0 ? horizontalMin : horizontalMax)));
        double maxElevation = Math.toDegrees(Math.atan2(highY, Math.max(1.0E-6, highY >= 0.0 ? horizontalMin : horizontalMax)));
        return maxElevation >= -controller.getRadarDepression() && minElevation <= controller.getRadarElevation();
    }

    private static void appendShaolibDisplayContacts(
            Level level,
            List<ConnectedDisplayBlockEntity.Contact> found,
            Vec3 origin,
            double radarRange,
            Vec3 radarVelocity,
            Set<UUID> existing
    ) {
        if (!ShaolibBridge.available()) {
            return;
        }
        Level root = rootLevel(level);
        if (!(root instanceof ServerLevel serverLevel)) {
            return;
        }
        String dimension = serverLevel.dimension().location().toString();
        for (Object instance : ShaolibBridge.activeInstances()) {
            if (instance == null || !ShaolibBridge.isTauOrHellfire(instance) || !ShaolibBridge.isAlive(instance)) {
                continue;
            }
            if (!dimension.equals(ShaolibBridge.dimensionId(instance))) {
                continue;
            }
            UUID id = ShaolibBridge.uuidFor(instance);
            if (existing.contains(id)) {
                continue;
            }
            Vec3 center = ShaolibBridge.position(instance);
            if (center == null) {
                continue;
            }
            double distance = center.distanceTo(origin);
            if (distance > radarRange || distance < 1.0) {
                continue;
            }
            Vec3 velocity = ShaolibBridge.velocity(instance);
            Vec3 los = center.subtract(origin).normalize();
            double closing = -velocity.subtract(radarVelocity).dot(los) * 20.0;
            found.add(new ConnectedDisplayBlockEntity.Contact(
                    new ConnectedDisplayBlockEntity.TargetKey(id, false),
                    Set.of(),
                    center,
                    velocity,
                    new AABB(center, center).inflate(0.75),
                    distance,
                    closing,
                    true
            ));
        }
    }

    private static void appendShaolibRadarContacts(
            Level level,
            List<StabilizerControllerBlockEntity.RadarContact> contacts,
            Set<UUID> existing,
            StabilizerControllerBlockEntity controller,
            boolean aircraft,
            Vec3 origin,
            double range
    ) {
        if (!ShaolibBridge.available()) {
            return;
        }
        Level root = rootLevel(level);
        if (!(root instanceof ServerLevel serverLevel)) {
            return;
        }
        String dimension = serverLevel.dimension().location().toString();
        for (Object instance : ShaolibBridge.activeInstances()) {
            if (instance == null || !ShaolibBridge.isTauOrHellfire(instance) || !ShaolibBridge.isAlive(instance)) {
                continue;
            }
            if (!dimension.equals(ShaolibBridge.dimensionId(instance))) {
                continue;
            }
            UUID id = ShaolibBridge.uuidFor(instance);
            if (existing.contains(id)) {
                continue;
            }
            Vec3 center = ShaolibBridge.position(instance);
            if (center == null) {
                continue;
            }
            AABB bounds = new AABB(center, center).inflate(0.75);
            if (!inRadarVolume(controller, aircraft, origin, center, bounds, range)) {
                continue;
            }
            contacts.add(new StabilizerControllerBlockEntity.RadarContact(
                    id,
                    center,
                    ShaolibBridge.velocity(instance),
                    bounds,
                    Set.of(),
                    // Ordnance, not a structure: the radar list and everything
                    // built on it - interception priority, the diving hand
                    // over, the automatic defense - is decided by this flag.
                    true
            ));
        }
    }

    /** Startup cost probe: closes the static initialisation window opened at the top. */
    static {
        StartupProbe.staticEnd("CompatScanner");
    }
}

package com.qkdream.firecontrolcompat.sonar;

import com.qkdream.firecontrolcompat.FireControlCompat;
import com.qkdream.firecontrolcompat.PhysicsGroups;
import com.qkdream.firecontrolcompat.SilencerShield;
import com.qkdream.firecontrolcompat.TargetClassifier;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Turns everything the water is carrying into the marks a sonar set paints.
 *
 * <p>A hearing set only listens: it reads the noises other hardware already
 * dropped in {@link SonarLog} - burners, running gear, gunfire, munitions
 * under way - plus the living things moving nearby, plus the hulls that have
 * shifted since the last sweep. A pinging set adds range finding on top of
 * that: ordnance, hulls and living things inside the beam come back as echoes
 * whether they are making any noise of their own or not.</p>
 *
 * <p>Everything is filed by the steel it belongs to, so a hull with six
 * engines, a dozen guns and a boiler makes one mark and not nineteen. A mark
 * takes the loudest weight of the noise found on it: a ship that is both
 * running its engines and firing its guns reads as a gun.</p>
 */
public final class SonarScanner {

    /** Noises older than this are background: half a second of history is plenty. */
    private static final int HEARD_WINDOW_TICKS = 40;
    /** Noises read back in one sweep. */
    private static final int MAX_HEARD = 128;
    /** Marks painted in one sweep. */
    private static final int MAX_CONTACTS = 64;
    /** A living thing that moved this far since the last sweep is on the move. */
    private static final double MOVING_MOB = 0.15D;
    /** A hull that moved this far since the last sweep is under way. */
    private static final double MOVING_BODY = 0.25D;
    /** Two noises inside the same cell with no hull around them count as one source. */
    private static final double CELL = 8.0D;
    /** Hulls of one welded group painted when a gun on that platform goes off. */
    private static final int MAX_GUNFIRE_GROUP = 16;

    private static final Map<Object, ScanMemory> MEMORY = new WeakHashMap<>();

    /** The line each set last reported, so a sweep only logs when it changes. */
    private static final Map<Object, String> LAST_REPORT = new WeakHashMap<>();

    /** When each set last wrote a line, so the log is never flooded by a busy sea. */
    private static final Map<Object, Long> LAST_REPORT_AT = new WeakHashMap<>();

    /** Shortest gap between two reported lines from one set. */
    private static final long REPORT_GAP_MS = 500L;

    private SonarScanner() {
    }

    /** What the last sweep saw, so movement can be measured since. */
    private static final class ScanMemory {

        private final Map<UUID, Vec3> mobs = new HashMap<>();
        private final Map<UUID, Vec3> bodies = new HashMap<>();
    }

    private static synchronized ScanMemory memoryOf(Object owner) {
        return MEMORY.computeIfAbsent(owner, key -> new ScanMemory());
    }

    /**
     * One sweep of a sonar set: what it hears right now, inside its window.
     *
     * @param owner         the set asking, used to remember the last sweep
     * @param ownBodies     bodies the set stands on, which never mark themselves
     * @param ownBoxes      the boxes of those bodies, so their crew stays quiet
     */
    public static List<SonarContact> scan(
            Level level,
            Object owner,
            Vec3 origin,
            Vec3 forward,
            double range,
            double halfAngle,
            double elevationUp,
            double elevationDown,
            boolean active,
            Set<UUID> ownBodies,
            List<AABB> ownBoxes) {
        Map<Object, SonarContact> heard = new LinkedHashMap<>();
        if (level == null || origin == null) {
            return List.of();
        }
        ScanMemory memory = memoryOf(owner);
        Map<Long, Object> bodyKeys = new HashMap<>();
        double window = Math.max(16.0D, range);

        // 1. Everything already making a noise: machinery, gunfire, munitions.
        for (SonarLog.Pulse pulse : SonarLog.recent(level, HEARD_WINDOW_TICKS, MAX_HEARD)) {
            if (pulse.owner() == owner) {
                continue;
            }
            if (pulse.position().distanceTo(origin) > range) {
                continue;
            }
            Object key = bodyKey(level, pulse.position(), bodyKeys);
            if (key instanceof UUID body && ownBodies.contains(body)) {
                continue;
            }
            offer(heard, key, new SonarContact(pulse.kind(), pulse.position()));
            if (pulse.kind() == SonarKind.GUNFIRE && key instanceof UUID body) {
                offerGunfireOnGroup(heard, level, body, ownBodies);
            }
        }

        // 2. Living things: a set that is only listening hears the ones on the move,
        //    because footfalls carry further than a hull suggests; a set that is pinging
        //    paints everything alive inside its beam, standing still or not.
        try {
            AABB search = new AABB(origin, origin).inflate(range);
            Map<UUID, Vec3> movedOn = new HashMap<>();
            for (LivingEntity mob : level.getEntitiesOfClass(LivingEntity.class, search)) {
                if (mob == null || mob.isRemoved() || !mob.isAlive()) {
                    continue;
                }
                Vec3 position = mob.position();
                movedOn.put(mob.getUUID(), position);
                Vec3 last = memory.mobs.get(mob.getUUID());
                boolean moving = last != null && position.distanceTo(last) >= MOVING_MOB;
                if (!moving && !active) {
                    continue;
                }
                if (insideAny(ownBoxes, position)) {
                    continue;
                }
                offer(heard, mob.getUUID(), new SonarContact(SonarKind.MOB, position));
            }
            memory.mobs.clear();
            memory.mobs.putAll(movedOn);
        } catch (Throwable ignored) {
        }

        // 3. Hulls and structures: a body under way pushes water around, and a
        //    pinging set marks every one of them whether she is under way or
        //    not. Each body is measured on its own, so one the world cannot
        //    answer for yet - a blueprint still being welded up, a body being
        //    moved between plots - costs only its own mark instead of the marks
        //    of every other ship in the water.
        int hullsSeen = 0;
        int hullsOffered = 0;
        try {
            SubLevelContainer container = SubLevelContainer.getContainer(level);
            Map<UUID, Vec3> bodiesNow = new HashMap<>();
            for (SubLevel sub : container == null ? List.<SubLevel>of() : container.getAllSubLevels()) {
                if (sub == null || sub.isRemoved()) {
                    continue;
                }
                hullsSeen++;
                try {
                    UUID id = sub.getUniqueId();
                    AABB box = sub.boundingBox().toMojang();
                    Vec3 centre = box.getCenter();
                    if (id == null) {
                        continue;
                    }
                    bodiesNow.put(id, centre);
                    if (ownBodies.contains(id)) {
                        continue;
                    }
                    if (centre.distanceTo(origin) > window) {
                        continue;
                    }
                    Vec3 last = memory.bodies.get(id);
                    boolean moving = last != null && last.distanceTo(centre) >= MOVING_BODY;
                    if (!moving && !active) {
                        continue;
                    }
                    offer(heard, id, new SonarContact(SonarKind.BODY, centre, echoOf(box)));
                    hullsOffered++;
                } catch (Throwable ignored) {
                }
            }
            memory.bodies.clear();
            memory.bodies.putAll(bodiesNow);
        } catch (Throwable ignored) {
        }

        // 4. Active set: everything inside the beam answers, moving or not.
        if (active) {
            try {
                AABB search = new AABB(origin, origin).inflate(range);
                for (Entity entity : level.getEntitiesOfClass(Entity.class, search)) {
                    if (entity == null || entity.isRemoved() || !TargetClassifier.isCompatMissile(entity)) {
                        continue;
                    }
                    if (insideAny(ownBoxes, entity.position())) {
                        continue;
                    }
                    offer(heard, entity.getUUID(), new SonarContact(SonarKind.AMMO, entity.position()));
                }
            } catch (Throwable ignored) {
            }
        }

        List<SonarContact> contacts = new ArrayList<>(heard.size());
        for (SonarContact contact : heard.values()) {
            // A noise made against a tile is swallowed whole, and so is one the
            // tiles stand between the set and - the machinery of a hull wrapped
            // in them reads as quiet water. A pinging set still marks a lined
            // hull, only against the shorter reach the tiles leave her.
            Vec3 at = contact.position();
            if (SilencerShield.muffled(level, at)) {
                continue;
            }
            double reach = active ? range * SilencerShield.echoReach(level, at, origin) : range;
            if (!inWindow(origin, forward, at, reach, halfAngle, elevationUp, elevationDown)) {
                continue;
            }
            if (!active && SilencerShield.crossings(level, at, origin) > 0) {
                continue;
            }
            contacts.add(contact);
        }
        contacts.sort(Comparator.comparingDouble(contact -> contact.position().distanceToSqr(origin)));
        if (contacts.size() > MAX_CONTACTS) {
            contacts = new ArrayList<>(contacts.subList(0, MAX_CONTACTS));
        }
        report(owner, contacts, active, hullsSeen, hullsOffered);
        return contacts;
    }

    /**
     * Writes one line whenever a sweep paints something other than what the last
     * one did, so what a set is making of the water can be followed from the log
     * without flooding it. A pinging sweep is written down as a ping and a
     * listening one as a listen, and both carry the structures the world offered
     * and the count that reached the picture, so a picture that has no hull on
     * it can be read for whether none was there or none got through.
     */
    private static synchronized void report(
            Object owner, List<SonarContact> contacts, boolean active, int hullsSeen, int hullsOffered) {
        int mobs = 0;
        int hulls = 0;
        int ordnance = 0;
        int light = 0;
        int medium = 0;
        int heavy = 0;
        for (SonarContact contact : contacts) {
            switch (contact.kind().weight()) {
                case LIGHT -> light++;
                case MEDIUM -> medium++;
                case HEAVY -> heavy++;
            }
            switch (contact.kind()) {
                case MOB -> mobs++;
                case BODY -> hulls++;
                case AMMO -> ordnance++;
                default -> {
                }
            }
        }
        // A listening sweep is written down by loudness alone: what a source is
        // is what a ping works out, and a set that is only listening never
        // works it out at all. The kinds behind those counts still drive the
        // scan, so the marks are filed and drawn the same way as before.
        int noise = contacts.size() - mobs - hulls - ordnance;
        String line = active
                ? "mode=active mobs=" + mobs + " hulls=" + hulls + " ordnance=" + ordnance + " noise=" + noise
                : "mode=passive light=" + light + " medium=" + medium + " heavy=" + heavy;
        line = line + " hulls_seen=" + hullsSeen + " hulls_marked=" + hullsOffered;
        if (line.equals(LAST_REPORT.put(owner, line))) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = LAST_REPORT_AT.get(owner);
        LAST_REPORT_AT.put(owner, now);
        if (last != null && now - last < REPORT_GAP_MS) {
            return;
        }
        FireControlCompat.LOGGER.info("[firecontrolcompat] sonar {} contacts={} {}",
                active ? "ping" : "listen", contacts.size(), line);
    }

    /** Keeps the loudest noise found on one body, so a hull makes a single mark. */
    private static void offer(Map<Object, SonarContact> heard, Object key, SonarContact contact) {
        SonarContact existing = heard.get(key);
        if (existing == null) {
            heard.put(key, contact);
            return;
        }
        int weight = contact.kind().weight().ordinal();
        int loudest = existing.kind().weight().ordinal();
        if (weight > loudest || (weight == loudest && contact.echo() > existing.echo())) {
            heard.put(key, contact);
        }
    }

    /**
     * How much of a ping comes back off a hull, read off the steel she is made
     * of: a launch or a gunboat answers faintly, a freighter answers plainly and
     * a capital ship answers so loudly that its mark on the picture is twice the
     * size of the small craft's. The steps are the size of the box the structure
     * stands in, which is the only measure of a hull a set has.
     */
    private static int echoOf(AABB box) {
        double span = Math.max(box.getXsize(), Math.max(box.getYsize(), box.getZsize()));
        if (span < 12.0D) {
            return 0;
        }
        if (span < 28.0D) {
            return 1;
        }
        if (span < 60.0D) {
            return 2;
        }
        return 3;
    }

    /**
     * A platform is one thing even when she is welded out of several bodies: a
     * gun fired anywhere on her is her noise, so every part of that group is
     * painted as a gun for this sweep. Without it the body the gun stands on
     * reads heavy while the rest of the ship keeps reading as a moving hull,
     * which reads on the picture as a platform that never fired.
     */
    private static void offerGunfireOnGroup(Map<Object, SonarContact> heard, Level level, UUID body, Set<UUID> ownBodies) {
        try {
            int painted = 0;
            for (UUID member : PhysicsGroups.group(level, body)) {
                if (member == null || ownBodies.contains(member)) {
                    continue;
                }
                SubLevel sub = PhysicsGroups.resolve(level, member);
                if (sub == null) {
                    continue;
                }
                offer(heard, member, new SonarContact(SonarKind.GUNFIRE, sub.boundingBox().toMojang().getCenter()));
                if (++painted >= MAX_GUNFIRE_GROUP) {
                    break;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * The steel a noise came from: the hull around it when there is one, and a
     * coarse cell of open water when there is not.
     */
    private static Object bodyKey(Level level, Vec3 position, Map<Long, Object> cache) {
        long cell = cellOf(position);
        Object cached = cache.get(cell);
        if (cached != null) {
            return cached;
        }
        Object key = null;
        try {
            for (UUID body : PhysicsGroups.bodiesContaining(level, position, 0.5D)) {
                key = body;
                break;
            }
        } catch (Throwable ignored) {
        }
        if (key == null) {
            key = "water:" + cell;
        }
        cache.put(cell, key);
        return key;
    }

    private static long cellOf(Vec3 position) {
        long x = (long) Math.floor(position.x / CELL) & 0x1FFFFFL;
        long y = (long) Math.floor(position.y / CELL) & 0x1FFFFFL;
        long z = (long) Math.floor(position.z / CELL) & 0x1FFFFFL;
        return x << 42 | y << 21 | z;
    }

    private static boolean insideAny(List<AABB> boxes, Vec3 position) {
        if (boxes == null || boxes.isEmpty()) {
            return false;
        }
        for (AABB box : boxes) {
            if (box != null && box.contains(position)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a mark falls inside the set's window: in front of the dish when
     * the horizontal window is narrower than a full circle, and inside the
     * elevation window, which reaches its own angle above and below the
     * horizon rather than one angle both ways.
     */
    public static boolean inWindow(
            Vec3 origin,
            Vec3 forward,
            Vec3 position,
            double range,
            double halfAngle,
            double elevationUp,
            double elevationDown) {
        Vec3 delta = position.subtract(origin);
        double distance = delta.length();
        if (distance < 0.5D || distance > range) {
            return false;
        }
        if (halfAngle >= 180.0D && elevationUp >= 90.0D && elevationDown >= 90.0D) {
            return true;
        }
        Vec3 direction = delta.scale(1.0D / distance);
        if (halfAngle < 180.0D) {
            Vec3 flat = forward == null ? new Vec3(0.0D, 0.0D, -1.0D) : new Vec3(forward.x, 0.0D, forward.z);
            if (flat.lengthSqr() < 1.0E-6D) {
                flat = new Vec3(0.0D, 0.0D, -1.0D);
            }
            flat = flat.normalize();
            Vec3 flatDirection = new Vec3(direction.x, 0.0D, direction.z);
            if (flatDirection.lengthSqr() > 1.0E-6D) {
                double cosine = flatDirection.normalize().dot(flat);
                if (cosine < Math.cos(Math.toRadians(halfAngle))) {
                    return false;
                }
            }
        }
        if (elevationUp < 90.0D || elevationDown < 90.0D) {
            double elevation = Math.toDegrees(Math.asin(Mth.clamp(direction.y, -1.0D, 1.0D)));
            if (elevation > elevationUp || elevation < -elevationDown) {
                return false;
            }
        }
        return true;
    }
}

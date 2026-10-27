package com.qkdream.firecontrolcompat;

import com.hooya.stabilizedturret.content.controller.ControllerTracker;
import com.hooya.stabilizedturret.content.controller.RwrThreat;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.hooya.stabilizedturret.content.display.ConnectedDisplayBlockEntity;
import com.qkdream.firecontrolcompat.network.RadarNameSyncPayload;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Per radar call signs, keyed by the platform that owns the fire control
 * computer (its Sable structure, or its block position for a computer standing
 * in the world). Names are set from the fire control screen and are what an
 * opposing RWR prints next to the threat mark.
 *
 * <p>One radar rig raises several marks on the opposing receiver: the fire
 * control computer itself, a connected display that sweeps with the same dish,
 * and the dish's own radar bearing. They are all matched here, because a mark
 * that cannot be traced back to the named computer falls back to its bare
 * threat type, which is exactly the stray AAA that used to sit next to a call
 * sign. A name is therefore filed under every identity its rig can appear
 * under:</p>
 *
 * <ul>
 * <li>the emitter id fire control derives from the computer (a threat id),</li>
 * <li>the emitter ids of the displays that sweep alongside it,</li>
 * <li>the structure of the computer and of every body of its platform,</li>
 * <li>the world position of its radar dish and of the computer itself, so a
 * display bound to the same dish - or a dish that was re-bound later - still
 * finds the name even when nothing else about the rig matches.</li>
 * </ul>
 *
 * <p>The table lives in the overworld's saved data so the names survive a
 * restart. Every lookup goes through the stored instance, so a name that was
 * written in an earlier session is still found without re-entering it.</p>
 */
public final class RadarNameStore extends SavedData {

    public static final String DATA_ID = "firecontrolcompat_radar_names";
    /** Upper bound asked for by design: four characters, like a radio call sign. */
    public static final int MAX_NAME_LENGTH = 4;

    /** Emitters this close to a named radar count as that radar's own hardware. */
    private static final double EMITTER_RADIUS = 32.0D;

    /** Displays this close to a named radar still belong to the same rig. */
    private static final double DISPLAY_RADIUS = 96.0D;

    /** Separator between the call sign and its owner inside an anchor entry. */
    private static final char SEPARATOR = '\u0001';

    /** Call sign that no route found, so callers can tell "unnamed" from "not looked up yet". */
    private static final Resolution NONE = new Resolution("", "none");

    private final Map<String, String> names = new HashMap<>();

    /** Emitter position -> "call sign<SEPARATOR>owner key", for the proximity fallback. */
    private final Map<String, String> anchors = new HashMap<>();

    public static RadarNameStore load(CompoundTag tag, HolderLookup.Provider registries) {
        RadarNameStore store = new RadarNameStore();
        CompoundTag stored = tag.getCompound("names");
        for (String key : stored.getAllKeys()) {
            String value = stored.getString(key);
            if (!value.isEmpty()) {
                store.names.put(key, value);
            }
        }
        CompoundTag storedAnchors = tag.getCompound("anchors");
        for (String key : storedAnchors.getAllKeys()) {
            String value = storedAnchors.getString(key);
            if (!value.isEmpty()) {
                store.anchors.put(key, value);
            }
        }
        return store;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag stored = new CompoundTag();
        this.names.forEach(stored::putString);
        tag.put("names", stored);
        CompoundTag storedAnchors = new CompoundTag();
        this.anchors.forEach(storedAnchors::putString);
        tag.put("anchors", storedAnchors);
        return tag;
    }

    /** Identity of the platform behind a computer: its structure, else its position. */
    public static String keyOf(Level level, StabilizerControllerBlockEntity controller) {
        UUID subLevel = ControllerTracker.controllerSubLevelId(controller);
        if (subLevel != null) {
            return "s:" + subLevel;
        }
        String dimension = level == null ? "unknown" : level.dimension().location().toString();
        return "p:" + dimension + ":" + controller.getBlockPos().asLong();
    }

    /** Name of a computer, empty when it has none. */
    public static String nameOf(Level level, StabilizerControllerBlockEntity controller) {
        if (controller == null) {
            return "";
        }
        try {
            return table(level).getOrDefault(keyOf(level, controller), "");
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** Name of the radar mounted on a structure, empty when it has none. */
    public static String nameOfStructure(Level level, UUID structureId) {
        if (structureId == null) {
            return "";
        }
        try {
            return table(level).getOrDefault("s:" + structureId, "");
        } catch (Throwable ignored) {
            return "";
        }
    }

    /**
     * Name of the platform a structure belongs to: the structure itself first,
     * then every body connected, jointed or welded to it. A computer and a
     * display on one hull therefore answer with the same call sign.
     */
    public static String nameOfPlatform(Level level, UUID structureId) {
        String direct = nameOfStructure(level, structureId);
        if (!direct.isEmpty()) {
            return direct;
        }
        try {
            for (UUID related : PhysicsGroups.group(level, structureId)) {
                String name = nameOfStructure(level, related);
                if (!name.isEmpty()) {
                    return name;
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    /** Call sign plus the route that produced it, empty when nothing matched. */
    public record Resolution(String name, String route) {

        public boolean found() {
            return !this.name.isEmpty();
        }
    }

    /**
     * Call sign of one radar threat of a receiver's session, in the order the
     * routes can identify the emitter: the exact threat id first, then the
     * computer or display sitting on the mark, then the platform it belongs to,
     * and finally a dish that was named earlier and is still in reach.
     */
    public static Resolution resolve(Level level, UUID threatId, Vec3 position) {
        if (level == null) {
            return NONE;
        }
        try {
            if (threatId != null) {
                String byId = table(level).getOrDefault("id:" + threatId, "");
                if (!byId.isEmpty()) {
                    return new Resolution(byId, "id");
                }
            }
            StabilizerControllerBlockEntity controller = RadarEmitters.at(level, position);
            if (controller != null) {
                String direct = nameOf(level, controller);
                if (!direct.isEmpty()) {
                    return new Resolution(direct, "computer");
                }
                String platform = nameOfPlatform(level, ControllerTracker.controllerSubLevelId(controller));
                if (!platform.isEmpty()) {
                    return new Resolution(platform, "group");
                }
            }
            ConnectedDisplayBlockEntity display = RadarEmitters.displayAt(level, position);
            if (display != null) {
                String viaDisplay = nameOfPlatform(
                        level, IffRadar.structureAt(level, Vec3.atCenterOf(display.getBlockPos())));
                if (!viaDisplay.isEmpty()) {
                    return new Resolution(viaDisplay, "display");
                }
            }
            String viaStructure = nameOfPlatform(level, IffRadar.structureAt(level, position));
            if (!viaStructure.isEmpty()) {
                return new Resolution(viaStructure, "structure");
            }
            String neighbour = neighbourName(level, position);
            if (!neighbour.isEmpty()) {
                return new Resolution(neighbour, "neighbour");
            }
            String viaAnchor = anchorName(level, position);
            return viaAnchor.isEmpty() ? NONE : new Resolution(viaAnchor, "anchor");
        } catch (Throwable ignored) {
            return NONE;
        }
    }

    /** Trims a raw input down to a call sign: no control characters, at most four. */
    public static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(MAX_NAME_LENGTH);
        for (int index = 0; index < raw.length() && builder.length() < MAX_NAME_LENGTH; index++) {
            char character = raw.charAt(index);
            if (character < ' ' || character == 127) {
                continue;
            }
            builder.append(character);
        }
        return builder.toString().trim();
    }

    /** Applies a name edited in the screen; returns false when the computer is gone. */
    public static boolean setName(ServerPlayer player, BlockPos pos, UUID subLevel, String raw) {
        if (player == null || pos == null) {
            return false;
        }
        StabilizerControllerBlockEntity controller =
                ControllerTracker.findLoadedForSettings(player, pos, subLevel);
        if (controller == null) {
            return false;
        }
        ServerLevel level = player.serverLevel();
        RadarNameStore store = store(level);
        String key = keyOf(level, controller);
        String name = sanitize(raw);
        if (name.isEmpty()) {
            store.names.remove(key);
            store.dropAnchors(key);
        } else {
            store.names.put(key, name);
            store.index(level, controller, key, name);
        }
        store.setDirty();
        return true;
    }

    /** Tells the client which name the computer it just opened already carries. */
    public static void sendStatus(ServerPlayer player, ServerLevel level, BlockPos pos, UUID subLevel) {
        if (player == null || level == null || pos == null) {
            return;
        }
        StabilizerControllerBlockEntity controller =
                ControllerTracker.findLoadedForSettings(player, pos, subLevel);
        PacketDistributor.sendToPlayer(
                player, new RadarNameSyncPayload(nameOf(level, controller)), new RadarNameSyncPayload[0]);
    }

    /**
     * Files one call sign under every identity the rig of this computer can
     * raise a threat under, so nothing on the opposing receiver is left as a
     * bare threat type.
     */
    private void index(
            ServerLevel level, StabilizerControllerBlockEntity controller, String owner, String name) {
        this.dropAnchors(owner);
        String dimension = level.dimension().location().toString();
        for (Vec3 point : emitters(controller)) {
            BlockPos anchor = BlockPos.containing(point);
            this.anchors.put("x:" + dimension + ":" + anchor.asLong(), name + SEPARATOR + owner);
        }
        String ownKey = controllerThreatKey(controller);
        if (!ownKey.isEmpty()) {
            this.names.put(ownKey, name);
        }
        for (String displayKey : displayThreatKeys(level, controller)) {
            this.names.put(displayKey, name);
        }
        for (UUID body : platformBodies(level, controller)) {
            this.names.put("s:" + body, name);
        }
    }

    /** Drops the anchors of one computer, so a rename never leaves the old one behind. */
    private void dropAnchors(String owner) {
        if (owner == null || owner.isEmpty()) {
            return;
        }
        this.anchors.entrySet().removeIf(entry -> owner.equals(ownerOf(entry.getValue())));
    }

    /** Threat id fire control derives from this computer when its radar paints somebody. */
    private static String controllerThreatKey(StabilizerControllerBlockEntity controller) {
        try {
            return "id:" + RwrThreat.sourceId(
                    "controller",
                    controller.getLevel(),
                    controller.getBlockPos(),
                    ControllerTracker.controllerSubLevelId(controller));
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** Threat ids of the displays that sweep alongside this computer. */
    private static List<String> displayThreatKeys(Level level, StabilizerControllerBlockEntity controller) {
        List<String> keys = new ArrayList<>(2);
        Vec3 origin;
        try {
            origin = controller.getRadarOrigin();
        } catch (Throwable ignored) {
            return keys;
        }
        double limit = DISPLAY_RADIUS * DISPLAY_RADIUS;
        for (ConnectedDisplayBlockEntity display : RadarEmitters.displays()) {
            try {
                Level displayLevel = display.getLevel();
                if (displayLevel == null
                        || !displayLevel.dimension().equals(level.dimension())
                        || display.getRadarWorldOrigin().distanceToSqr(origin) > limit) {
                    continue;
                }
                keys.add("id:" + RwrThreat.sourceId(
                        "connected-display", displayLevel, display.getBlockPos(), subLevelIdOf(display)));
            } catch (Throwable ignored) {
            }
        }
        return keys;
    }

    /** Where the hardware of a computer sits: its dish and its own block. */
    private static List<Vec3> emitters(StabilizerControllerBlockEntity controller) {
        List<Vec3> points = new ArrayList<>(2);
        try {
            points.add(controller.getRadarOrigin());
        } catch (Throwable ignored) {
        }
        try {
            points.add(projectOut(controller.getLevel(), controller.getBlockPos()));
        } catch (Throwable ignored) {
        }
        return points;
    }

    /** Every body of the platform the computer stands on. */
    private static Set<UUID> platformBodies(Level level, StabilizerControllerBlockEntity controller) {
        Set<UUID> bodies = new LinkedHashSet<>();
        try {
            UUID containing = ControllerTracker.controllerSubLevelId(controller);
            if (containing != null) {
                bodies.addAll(PhysicsGroups.group(level, containing));
            }
        } catch (Throwable ignored) {
        }
        try {
            bodies.addAll(PhysicsGroups.bodiesContaining(
                    level, projectOut(controller.getLevel(), controller.getBlockPos()), 1.5D));
        } catch (Throwable ignored) {
        }
        return bodies;
    }

    /**
     * Named computer sitting within reach of an unnamed mark. A rig paints with
     * its dish and with the displays wired to it, and the dish of a computer is
     * often several blocks away from the computer itself, so the mark of one
     * device is explained by the call sign of the other one on the same rig.
     */
    private static String neighbourName(Level level, Vec3 position) {
        if (position == null) {
            return "";
        }
        String best = "";
        double bestDistance = EMITTER_RADIUS * EMITTER_RADIUS;
        for (StabilizerControllerBlockEntity controller : RadarEmitters.controllers()) {
            try {
                Level controllerLevel = controller.getLevel();
                if (controllerLevel == null || !controllerLevel.dimension().equals(level.dimension())) {
                    continue;
                }
                double distance = controller.getRadarOrigin().distanceToSqr(position);
                if (distance >= bestDistance) {
                    continue;
                }
                String name = nameOf(level, controller);
                if (name.isEmpty()) {
                    continue;
                }
                bestDistance = distance;
                best = name;
            } catch (Throwable ignored) {
            }
        }
        return best;
    }

    /** A named dish this close to an unnamed mark answers for it. */
    private static String anchorName(Level level, Vec3 position) {
        RadarNameStore store = storeOf(level);
        if (store == null || position == null) {
            return "";
        }
        String dimension = level.dimension().location().toString();
        String best = "";
        double bestDistance = EMITTER_RADIUS * EMITTER_RADIUS;
        for (Map.Entry<String, String> entry : store.anchors.entrySet()) {
            BlockPos anchor = anchorPosition(entry.getKey(), dimension);
            if (anchor == null) {
                continue;
            }
            double distance = anchor.distToCenterSqr(position.x, position.y, position.z);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = nameOfEntry(entry.getValue());
            }
        }
        return best;
    }

    private static BlockPos anchorPosition(String key, String dimension) {
        if (key == null || !key.startsWith("x:" + dimension + ":")) {
            return null;
        }
        try {
            return BlockPos.of(Long.parseLong(key.substring(key.lastIndexOf(':') + 1)));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String nameOfEntry(String entry) {
        int split = entry == null ? -1 : entry.indexOf(SEPARATOR);
        return split < 0 ? (entry == null ? "" : entry) : entry.substring(0, split);
    }

    private static String ownerOf(String entry) {
        int split = entry == null ? -1 : entry.indexOf(SEPARATOR);
        return split < 0 ? "" : entry.substring(split + 1);
    }

    private static Vec3 projectOut(Level level, BlockPos pos) {
        try {
            return Sable.HELPER.projectOutOfSubLevel(level, Vec3.atCenterOf(pos));
        } catch (Throwable ignored) {
            return Vec3.atCenterOf(pos);
        }
    }

    private static UUID subLevelIdOf(BlockEntity blockEntity) {
        try {
            SubLevel containing = Sable.HELPER.getContaining(blockEntity);
            return containing == null ? null : containing.getUniqueId();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** The stored table of a level, empty on the client or without a server. */
    private static Map<String, String> table(Level level) {
        RadarNameStore store = storeOf(level);
        return store == null ? Map.of() : store.names;
    }

    private static RadarNameStore storeOf(Level level) {
        ServerLevel server = IffRadar.serverLevelOf(level);
        return server == null ? null : store(server);
    }

    private static RadarNameStore store(ServerLevel level) {
        MinecraftServer server = level.getServer();
        ServerLevel overworld = server == null ? level : server.overworld();
        return overworld.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(RadarNameStore::new, RadarNameStore::load, null), DATA_ID);
    }
}

package com.qkdream.firecontrolcompat.sonar;

import com.qkdream.firecontrolcompat.FireControlCompat;
import com.qkdream.firecontrolcompat.SilencerShield;
import com.qkdream.firecontrolcompat.SubLevelPositions;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.VanillaGameEvent;

/**
 * The world's own noises, for the sonar sets listening to the water.
 *
 * <p>The game already turns a block going down, a door swinging, a lever
 * thrown, a bell rung and a hundred other things into vibrations - the same
 * network the sculk sensors and shriekers read. Every one of them is dropped
 * into {@link SonarLog} as a light source, so a dish hears the harbour around
 * it and not just the guns. A bang - an explosion, a shrieker, a lightning
 * strike - is filed heavy instead, because that is the one thing in the list a
 * set should treat as a threat rather than a murmur.</p>
 *
 * <p>The one splash this class does not file is the mod's own: an active set
 * sends a real splash to make itself heard (see {@link SonarPulse}), and the
 * sweep already marks that ping itself, so the echo is skipped rather than
 * counted twice.</p>
 *
 * <p>Footsteps, swimming and flying are left out on purpose: a moving living
 * thing is already painted by the sweep itself, and a busy deck would otherwise
 * bury every other noise on the picture. This mod's own pulses are left out
 * too - they are filed by the machinery that makes them, with the weight they
 * deserve.</p>
 */
@EventBusSubscriber(modid = FireControlCompat.MOD_ID)
public final class VanillaVibrations {

    /** Namespaces whose events are filed by whoever fired them instead. */
    private static final String OWN_NAMESPACE = "firecontrolcompat";

    /** Events that would flood the picture: a moving body is painted anyway. */
    private static final Map<String, Boolean> IGNORED = Map.ofEntries(
            Map.entry("step", Boolean.TRUE),
            Map.entry("swim", Boolean.TRUE),
            Map.entry("flap", Boolean.TRUE),
            Map.entry("elytra_glide", Boolean.TRUE),
            Map.entry("sculk_sensor_tendrils_clicking", Boolean.TRUE),
            Map.entry("entity_mount", Boolean.TRUE),
            Map.entry("entity_dismount", Boolean.TRUE),
            Map.entry("equip", Boolean.TRUE),
            Map.entry("item_interact_start", Boolean.TRUE),
            Map.entry("item_interact_finish", Boolean.TRUE),
            Map.entry("entity_interact", Boolean.TRUE),
            Map.entry("entity_action", Boolean.TRUE),
            Map.entry("drink", Boolean.TRUE),
            Map.entry("eat", Boolean.TRUE),
            Map.entry("resonate_1", Boolean.TRUE),
            Map.entry("resonate_2", Boolean.TRUE),
            Map.entry("resonate_3", Boolean.TRUE),
            Map.entry("resonate_4", Boolean.TRUE),
            Map.entry("resonate_5", Boolean.TRUE),
            Map.entry("resonate_6", Boolean.TRUE),
            Map.entry("resonate_7", Boolean.TRUE),
            Map.entry("resonate_8", Boolean.TRUE),
            Map.entry("resonate_9", Boolean.TRUE),
            Map.entry("resonate_10", Boolean.TRUE),
            Map.entry("resonate_11", Boolean.TRUE),
            Map.entry("resonate_12", Boolean.TRUE),
            Map.entry("resonate_13", Boolean.TRUE),
            Map.entry("resonate_14", Boolean.TRUE),
            Map.entry("resonate_15", Boolean.TRUE));

    /** Events loud enough to be filed as a bang rather than a murmur. */
    private static final Map<String, Boolean> HEAVY = Map.ofEntries(
            Map.entry("explode", Boolean.TRUE),
            Map.entry("shriek", Boolean.TRUE),
            Map.entry("lightning_strike", Boolean.TRUE),
            Map.entry("prime_fuse", Boolean.TRUE));

    /** Ticks between two reports of the same noise from the same place. */
    private static final int PERIOD = 10;

    private static final int MAX_TRACKED = 4096;
    private static final Map<String, Long> LAST = new HashMap<>();

    private VanillaVibrations() {
    }

    @SubscribeEvent
    public static void onVanillaGameEvent(VanillaGameEvent event) {
        try {
            Level level = event.getLevel();
            if (level == null || level.isClientSide) {
                return;
            }
            Holder<GameEvent> holder = event.getVanillaEvent();
            if (holder == null) {
                return;
            }
            ResourceLocation id = holder.unwrapKey().map(key -> key.location()).orElse(null);
            if (id == null || OWN_NAMESPACE.equals(id.getNamespace())) {
                return;
            }
            String path = id.getPath();
            if (IGNORED.containsKey(path)) {
                return;
            }
            Vec3 position = event.getEventPosition();
            if (position == null || SonarPulse.isOwn(level, position)) {
                return;
            }
            if (SilencerShield.muffled(level, position)) {
                event.setCanceled(true);
                return;
            }
            long now = level.getGameTime();
            String key = id + "@" + Math.round(position.x / 2.0D) + "," + Math.round(position.y / 2.0D) + ","
                    + Math.round(position.z / 2.0D);
            Long last = LAST.get(key);
            if (last != null && now - last < PERIOD) {
                return;
            }
            if (LAST.size() >= MAX_TRACKED) {
                LAST.clear();
            }
            LAST.put(key, now);
            Vec3 world = SubLevelPositions.worldPosition(level, position);
            SonarLog.record(level, world, HEAVY.containsKey(path) ? SonarKind.GUNFIRE : SonarKind.WORLD, null);
        } catch (Throwable ignored) {
        }
    }
}

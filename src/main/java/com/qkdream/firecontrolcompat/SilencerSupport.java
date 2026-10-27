package com.qkdream.firecontrolcompat;

import java.util.List;
import java.util.function.Consumer;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;

/**
 * Finds the silencer tiles without naming them.
 *
 * <p>The tiles are cut from another mod's camouflage blocks, so their sources
 * are only part of the build while that mod sits in the pack: a pack without it
 * still compiles, and the game then starts with no tiles in it at all. This
 * class is always in the jar and reaches the support by name instead, so the
 * feature costs nothing but a log line when the other mod is switched off.</p>
 */
public final class SilencerSupport {

    /** Where the tiles live when they were compiled in. */
    private static final String IMPLEMENTATION = "com.qkdream.firecontrolcompat.compat.copycats.SilencerBlocks";

    /** Mod the tiles are built on. */
    private static final String COPYCATS = "copycats";

    private SilencerSupport() {
    }

    /**
     * Hands the three tiles to whoever is filling a creative tab, in the order
     * the tab shows them. Nothing at all is handed over when the camouflage mod
     * is not in the pack, so the tab is simply shorter there.
     */
    @SuppressWarnings("unchecked")
    public static void acceptTabItems(Consumer<Item> sink) {
        if (sink == null || !FireControlCompat.isModLoaded(COPYCATS)) {
            return;
        }
        try {
            Class<?> implementation = Class.forName(IMPLEMENTATION);
            List<Item> tiles = (List<Item>) implementation.getMethod("tabItems").invoke(null);
            for (Item tile : tiles) {
                sink.accept(tile);
            }
        } catch (Throwable error) {
            FireControlCompat.LOGGER.warn("[firecontrolcompat] silencer tiles unavailable: {}", error.toString());
        }
    }

    /** Wires the tiles up, or says so and moves on when they are not around. */
    public static void register(IEventBus bus) {
        if (!FireControlCompat.isModLoaded(COPYCATS)) {
            FireControlCompat.LOGGER.info("[firecontrolcompat] copycats is not installed, the silencer tiles are skipped");
            return;
        }
        try {
            Class.forName(IMPLEMENTATION).getMethod("register", IEventBus.class).invoke(null, bus);
            FireControlCompat.LOGGER.info("[firecontrolcompat] silencer tiles registered");
        } catch (Throwable error) {
            FireControlCompat.LOGGER.warn("[firecontrolcompat] silencer tiles unavailable: {}", error.toString());
        }
    }
}

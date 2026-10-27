package com.qkdream.firecontrolcompat.blueprint.photomancy;

import com.qkdream.firecontrolcompat.blueprint.BlueprintExporter;
import dev.rew1nd.sableschematicapi.tool.client.mode.BlueprintToolModes;

/**
 * Adds the export mode to the Sable blueprint tool.
 *
 * <p>Kept in its own class, and only touched from a client setup guarded by a
 * mod-loaded check, so that a pack without the blueprint tool still loads this mod
 * instead of dying on a {@code NoClassDefFoundError} for
 * {@link BlueprintToolModes}.
 */
public final class PhotomancyIntegration {

    private static boolean registered;

    private PhotomancyIntegration() {}

    /** Called once from client setup. Safe to call more than once. */
    public static void register() {
        if (registered) {
            return;
        }
        try {
            BlueprintToolModes.register(new BlueprintExportToolMode());
            registered = true;
            BlueprintExporter.LOGGER.info(
                "registered the blueprint tool export mode");
        } catch (Throwable t) {
            // A duplicate registration throws, and so does a tool version whose API
            // moved. Neither should take the mod down; the command still works.
            BlueprintExporter.LOGGER.warn("could not register the export mode: {}", t.toString());
        }
    }
}

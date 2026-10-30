package com.qkdream.firecontrolcompat.blueprint;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sable blueprint -&gt; OBJ exporter.
 *
 * <p>The point of this mod is that it does <em>not</em> reimplement any of
 * Minecraft's model pipeline. It reads the blueprint exactly the way the game
 * does, renders it through {@code ModelBlockRenderer} exactly the way Create's
 * built-in isometric preview does, and writes the resulting triangles out. Every
 * mod's models therefore resolve the same way they do in game - including the
 * Copycats+ blocks whose blockstates are {@code minecraft:block/air} stubs and
 * whose shapes only exist at runtime.
 *
 * <p>Two ways in: the {@code /sableexport} command, and a screen opened with a
 * keybind (default {@code O}), which is where a player naturally is when they want
 * to export a blueprint.
 */
public final class BlueprintExporter {

    /** Kept for log lines only: the mod id is this mod's now, the exporter is a package inside it. */
    public static final String MOD_ID = "sableblueprintexporter";
    public static final Logger LOGGER = LoggerFactory.getLogger("SableBlueprintExporter");

    /**
     * Things that went wrong without stopping the export.
     *
     * <p>Collected so the result reaches the player rather than only a log file. A
     * texture that could not be written used to be a warning nobody saw, and the
     * symptom - a mesh with no texture in Blender - gave no hint that the exporter had
     * failed to produce one.
     */
    public static final java.util.List<String> NOTES =
        java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    /**
     * TEMP-DISABLED(blueprint-export): what the command answers while the saved-blueprint path is off.
     *
     * <p>Kept as one constant so the command, the export screen and the blueprint tool's export page
     * cannot drift into three slightly different sentences. The colour codes are legacy
     * {@code §}-codes on purpose: they are what this mod's other chat lines use, and both
     * {@code CommandSourceStack} and {@code LocalPlayer#displayClientMessage} render them.
     */
    public static final String DISABLED_MESSAGE =
        "§c本地蓝图导出已停用 —— 请用 §e/sableexport select§c + "
            + "§e/sableexport region <名字>§c（或导出页的「选区导出」按钮）";

    /** The same sentence without colour codes, for a GUI label that draws its own colour. */
    public static final String DISABLED_TEXT =
        "本地蓝图导出已停用 —— 请用 /sableexport select + "
            + "/sableexport region <名字>（或导出页的「选区导出」按钮）";

    /**
     * How often {@code blueprint-export: disabled} may be written, in nanoseconds.
     *
     * <p>A player who tries the old button twice does not need two lines; the count of the folded
     * refusals is carried in the one line that is written. Ten seconds is short enough that a
     * second attempt later in the session still leaves its own trace.
     */
    private static final long DISABLED_LOG_INTERVAL_NANOS = 10_000_000_000L;

    /** When the last {@code blueprint-export: disabled} line was written; {@code 0} means never. */
    private static final java.util.concurrent.atomic.AtomicLong DISABLED_LOGGED_AT =
        new java.util.concurrent.atomic.AtomicLong(0L);

    /** Refusals folded into the next line, so throttling does not lose the count. */
    private static final java.util.concurrent.atomic.AtomicLong DISABLED_SUPPRESSED =
        new java.util.concurrent.atomic.AtomicLong(0L);

    private static KeyMapping openExporter;

    /** No instances, on purpose: the four entry points below are static and client-only. */
    private BlueprintExporter() {
    }

    /*
     * Registration note (1.70.6, dedicated-server isolation):
     *
     * There is deliberately NO constructor any more. This class has net.minecraft.client.* in its
     * constant pool (Minecraft, Screen via BlueprintExportScreen), so it must never be touched on
     * a dedicated server. Registration used to run from FireControlCompat's constructor
     * (`new BlueprintExporter(modEventBus)`), which is exactly why a dedicated server died with
     * "Attempted to load class net/minecraft/client/gui/screens/Screen for invalid dist
     * DEDICATED_SERVER".
     *
     * The four entry points below are now called from two @EventBusSubscriber(value = Dist.CLIENT)
     * classes (client.BlueprintClientSetup on the mod bus, client.BlueprintClientEvents on the game
     * bus). FML reads that annotation out of the scanned mod-file data and only instantiates the
     * class when the dist matches, so a server never resolves them - and the common entry point
     * does not name either class.
     */

    /**
     * Adds the export mode to the Sable blueprint tool.
     *
     * <p>Guarded by an explicit mod-loaded check and reached through a separate
     * class, so a pack without the blueprint tool - or without LowDragLib2, which it
     * needs - still loads this mod rather than failing on a missing class. The mod
     * therefore declares no hard dependency on either.
     */
    public static void onClientSetup(net.neoforged.fml.event.lifecycle.FMLClientSetupEvent event) {
        if (!net.neoforged.fml.ModList.get().isLoaded("sable_schematic_api")) {
            LOGGER.info("blueprint tool not present; skipping the export mode");
            return;
        }
        event.enqueueWork(com.qkdream.firecontrolcompat.blueprint.photomancy.PhotomancyIntegration::register);
    }

    public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        openExporter = new KeyMapping(
            "key.sableblueprintexporter.open",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_O,
            "key.categories.sableblueprintexporter");
        event.register(openExporter);
    }

    public static void onClientTick(ClientTickEvent.Post event) {
        // The capture window is one frame; this closes it even if the render loop never calls back.
        try {
            com.qkdream.firecontrolcompat.blueprint.capture.CaptureExport.tick();
        } catch (Throwable ignored) {
            // never let the capture state machine affect a tick
        }
        if (openExporter == null) {
            return;
        }
        // consumeClick() is the only correct way to read a keybind: it fires once
        // per press and clears the state, so holding the key does not reopen the
        // screen every tick.
        while (openExporter.consumeClick()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null && mc.screen == null) {
                mc.setScreen(new BlueprintExportScreen());
            }
        }
    }

    /**
     * Client-side command, because the bake needs baked models and they only exist
     * on the client. Registered on the game bus so a mod with no common-side code
     * still loads on a dedicated server without a class-loading failure.
     */
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("sableexport")
                .then(Commands.literal("where")
                    .executes(ctx -> {
                        BlueprintBake.reportDirs(ctx.getSource());
                        return 1;
                    }))
                .then(Commands.literal("capture")
                    .then(Commands.argument("name", StringArgumentType.string())
                        .executes(ctx -> capture(StringArgumentType.getString(ctx, "name"), 24.0D))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(8, 64))
                            .executes(ctx -> capture(StringArgumentType.getString(ctx, "name"),
                                IntegerArgumentType.getInteger(ctx, "radius")))))
                    .then(Commands.argument("radius", IntegerArgumentType.integer(8, 64))
                        .executes(ctx -> capture("capture",
                            IntegerArgumentType.getInteger(ctx, "radius"))))
                    .executes(ctx -> capture("capture", 24.0D)))
                .then(Commands.literal("gui")
                    .executes(ctx -> {
                        Minecraft.getInstance().setScreen(new BlueprintExportScreen());
                        return 1;
                    }))
                // The second way in: whatever the blueprint tool has selected right now, exported
                // from the live world rather than from a saved file. It reads the tool's own
                // selection (RegionSelection), so there is no pos1/pos2 to keep in step with it.
                .then(Commands.literal("select")
                    .executes(ctx -> select(ctx.getSource())))
                .then(Commands.literal("region")
                    .then(Commands.argument("name", StringArgumentType.string())
                        .executes(ctx -> region(ctx.getSource(),
                            StringArgumentType.getString(ctx, "name"))))
                    .executes(ctx -> region(ctx.getSource(), "region")))
                .then(Commands.literal("all")
                    .executes(ctx -> run(ctx.getSource(), null)))
                .then(Commands.argument("name", StringArgumentType.string())
                    .executes(ctx -> run(ctx.getSource(),
                        StringArgumentType.getString(ctx, "name"))))
                .executes(ctx -> run(ctx.getSource(), null))
        );
    }

    private static int run(net.minecraft.commands.CommandSourceStack source, String name) {
        // TEMP-DISABLED(blueprint-export): entry gate only. Nothing below is removed or rewritten -
        // BlueprintBake#bake/#bakeOne and the whole .nbt pipeline stay exactly as they were - so
        // `blueprintExportEnabled=true` in config/firecontrolcompat-pod.txt restores this command.
        if (!BlueprintBake.blueprintExportEnabled()) {
            logBlueprintExportDisabled("command");
            source.sendFailure(Component.literal(DISABLED_MESSAGE));
            return 0;
        }
        try {
            return BlueprintBake.run(source, name);
        } catch (Throwable t) {
            LOGGER.error("sableexport failed", t);
            source.sendFailure(Component.literal("sableexport failed: " + t));
            return 0;
        }
    }

    /**
     * TEMP-DISABLED(blueprint-export): the one throttled log line every refused entry point leaves.
     *
     * <p>{@code by} is {@code command} or {@code gui}, so the log says which way in was refused
     * without needing the player's story.
     *
     * <p>Throttling note: {@code now} is a {@code System.nanoTime()} reading and the stored value is
     * either another reading or the "never" sentinel {@code 0}, which is tested for explicitly. The
     * subtraction therefore only ever runs between two real readings and can not overflow - in
     * particular this never computes anything like {@code now - Long.MIN_VALUE}.
     */
    public static void logBlueprintExportDisabled(String by) {
        long now = System.nanoTime();
        long last = DISABLED_LOGGED_AT.get();
        if (last != 0L && now - last < DISABLED_LOG_INTERVAL_NANOS) {
            DISABLED_SUPPRESSED.incrementAndGet();
            return;
        }
        if (!DISABLED_LOGGED_AT.compareAndSet(last, now)) {
            // Another thread wrote the line for this window; count this refusal into the next one.
            DISABLED_SUPPRESSED.incrementAndGet();
            return;
        }
        LOGGER.info("blueprint-export: disabled reason=config by={} suppressed={}",
            by, DISABLED_SUPPRESSED.getAndSet(0L));
    }

    /**
     * A command source that answers into the player's chat, for the two export screens.
     *
     * <p>{@link RegionExport#run} is written against {@code CommandSourceStack} because a command is
     * where its messages normally go. A screen has no dispatcher, so this stands in and hands every
     * {@code sendSuccess}/{@code sendFailure} to {@code LocalPlayer#displayClientMessage} - the same
     * line in chat the command would have produced.
     *
     * <p>{@code shouldInformAdmins()} is {@code false} on purpose: {@code CommandSourceStack#sendSuccess}
     * then skips {@code broadcastToAdmins}, the only branch that dereferences the server, so the null
     * level/server this client-side source carries is never touched. Verified against the decompiled
     * 1.21.1 {@code CommandSourceStack} (constructor and {@code sendSuccess}/{@code sendFailure}).
     */
    public static net.minecraft.commands.CommandSourceStack chatSource(String textName) {
        Minecraft mc = Minecraft.getInstance();
        net.minecraft.client.player.LocalPlayer player = mc == null ? null : mc.player;
        net.minecraft.world.phys.Vec3 position =
            player == null ? net.minecraft.world.phys.Vec3.ZERO : player.position();
        String name = textName == null ? "sableexport" : textName;
        return new net.minecraft.commands.CommandSourceStack(
            CHAT_SOURCE, position, net.minecraft.world.phys.Vec2.ZERO,
            null, 4, name, Component.literal(name), null, player);
    }

    /** Routes a command result into the local player's chat; never touches the server. */
    private static final net.minecraft.commands.CommandSource CHAT_SOURCE =
        new net.minecraft.commands.CommandSource() {
            @Override
            public void sendSystemMessage(Component message) {
                Minecraft mc = Minecraft.getInstance();
                if (mc != null && mc.player != null) {
                    mc.player.displayClientMessage(message, false);
                }
            }

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return false;
            }
        };

    /**
     * Arms a frame capture.
     *
     * <p>Guarded like every other entry point into the exporter: a capture that cannot arm is one
     * warning in the log, never a command that throws into the chat screen.</p>
     *
     * @param name   file name stem of the capture
     * @param radius half size of the recorded region in blocks
     */
    private static int capture(String name, double radius) {
        try {
            com.qkdream.firecontrolcompat.blueprint.capture.CaptureExport.arm(name, radius);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("sableexport capture failed", t);
            return 0;
        }
    }

    /**
     * Toggles the two-left-click selection mode.
     *
     * <p>The blueprint tool's own library mode cannot be entered in the pack this ships with - a
     * left click digs the block - so this is the same gesture with the same meaning, recorded in
     * {@link RegionSelection} and read by {@code /sableexport region}.
     */
    private static int select(net.minecraft.commands.CommandSourceStack source) {
        try {
            return RegionSelectMode.toggle(source);
        } catch (Throwable t) {
            LOGGER.error("sableexport select failed", t);
            source.sendFailure(Component.literal("sableexport select failed: " + t));
            return 0;
        }
    }

    /**
     * Exports the blueprint tool's current selection from the live world.
     *
     * <p>Guarded for the same reason as every other entry point: a region export that cannot run
     * answers in chat, it does not throw into the command dispatcher.</p>
     */
    private static int region(net.minecraft.commands.CommandSourceStack source, String name) {
        try {
            return RegionExport.run(source, name);
        } catch (Throwable t) {
            LOGGER.error("sableexport region failed", t);
            source.sendFailure(Component.literal("sableexport region failed: " + t));
            return 0;
        }
    }
}

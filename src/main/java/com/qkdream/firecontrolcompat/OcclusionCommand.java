package com.qkdream.firecontrolcompat;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.util.Locale;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /firecontrol occlusion}: reads and sets the terrain radar screen.
 *
 * <p>The screen hides from a radar whatever the ground, the water or a hull awash past its deck
 * stands in front of. It has been on by default since it was added, and a crew that either wants
 * every painted contact back or wants to check whether the screen is hiding something had no way
 * to ask. This adds that: a bare command reports the switch and what it has been doing, and
 * {@code on} / {@code off} set it.</p>
 *
 * <p>Reading is open to everyone - knowing whether the screen is on is part of diagnosing a radar
 * that looks wrong, and a non-operator can be asked to type it. Changing it needs permission level
 * two, the same level every other command in this mod that changes state asks for, because turning
 * the screen off hands a radar targets behind cover and turning it on takes them away again.</p>
 *
 * <p>The report line is deliberately the one a bug report can quote as-is:</p>
 *
 * <pre>occlusion: enabled=&lt;true|false&gt; source=&lt;config|command&gt; hits=&lt;n&gt; avg-us=&lt;n&gt;</pre>
 *
 * <p>followed by the rest of the last five-second window, so "is it on" and "is it doing anything"
 * are answered by the same line.</p>
 */
@EventBusSubscriber(modid = FireControlCompat.MOD_ID)
public final class OcclusionCommand {

    private OcclusionCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> occlusion = Commands.literal("occlusion")
                .executes(context -> {
                    report(context.getSource(), false);
                    return 1;
                })
                .then(Commands.literal("on")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> {
                            OcclusionSwitch.set(true);
                            report(context.getSource(), true);
                            return 1;
                        }))
                .then(Commands.literal("off")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> {
                            OcclusionSwitch.set(false);
                            report(context.getSource(), true);
                            return 1;
                        }));
        try {
            event.getDispatcher().register(Commands.literal("firecontrol").then(occlusion));
        } catch (RuntimeException failure) {
            // Another mod already owns /firecontrol: register the short form rather than losing
            // the command entirely, and say which one is in use.
            FireControlCompat.LOGGER.warn(
                    "occlusion: /firecontrol is taken ({}), registering /occlusion instead",
                    failure.toString());
            event.getDispatcher().register(Commands.literal("occlusion").then(
                    Commands.literal("on").requires(source -> source.hasPermission(2))
                            .executes(context -> {
                                OcclusionSwitch.set(true);
                                report(context.getSource(), true);
                                return 1;
                            })).then(
                    Commands.literal("off").requires(source -> source.hasPermission(2))
                            .executes(context -> {
                                OcclusionSwitch.set(false);
                                report(context.getSource(), true);
                                return 1;
                            })).executes(context -> {
                                report(context.getSource(), false);
                                return 1;
                            }));
        }
    }

    /**
     * Sends the status line to the caller and, once per change, to the log.
     *
     * <p>A command that only answers in chat cannot be checked from a bug report, and one that only
     * writes the log makes the crew dig through files. It does both: chat for the player standing
     * there, log for whoever reads the session afterwards. Plain readings are not logged, so
     * asking twice does not double the lines.</p>
     */
    private static void report(CommandSourceStack source, boolean log) {
        String line = statusLine();
        try {
            source.sendSuccess(() -> Component.literal(line), false);
        } catch (RuntimeException ignored) {
            // A source without a chat channel still gets the log line below.
        }
        if (log) {
            FireControlCompat.LOGGER.info(line);
        }
    }

    /** The one-line status a player reads and a bug report quotes. */
    public static String statusLine() {
        double avg = OcclusionPerf.lastWindowCalls() > 0L
                ? OcclusionPerf.lastAvgMicros()
                : OcclusionPerf.lifetimeAvgMicros();
        return String.format(Locale.ROOT,
                "occlusion: enabled=%s source=%s hits=%d avg-us=%.1f last-sweep-us=%.1f"
                        + " max-us=%d skipped-cache=%d skipped-range=%d calls=%d",
                OcclusionSwitch.enabled(), OcclusionSwitch.source(), OcclusionPerf.totalHits(),
                avg, OcclusionPerf.lastSweepMicros(), OcclusionPerf.lastMaxMicros(),
                OcclusionPerf.totalSkippedCache(), OcclusionPerf.totalSkippedRange(),
                OcclusionPerf.totalCalls());
    }
}

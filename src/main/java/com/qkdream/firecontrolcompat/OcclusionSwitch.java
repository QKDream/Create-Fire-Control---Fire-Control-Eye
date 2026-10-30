package com.qkdream.firecontrolcompat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The on/off switch of the terrain radar screen, and the place that keeps it written down.
 *
 * <p>The screen has been on since it was added, which was fine while the only complaint about it
 * was that it did nothing. Once it started doing something the complaint became the opposite one:
 * a crew that wants to see everything a radar paints - or that suspects the screen of hiding a
 * target it should have - had no way to turn it off without editing the jar. It is now a command
 * (<code>/firecontrol occlusion off</code>) and a config line, and both live here.</p>
 *
 * <p>The precedence is deliberately the simple one: the file is read once at class initialisation
 * and the command overrides it for the rest of the session, writing the new value back to the file
 * so the next start comes up the same way. A switch whose state cannot be written down is worse
 * than none, because the crew re-types it every session and cannot tell whether the game respects
 * it.</p>
 *
 * <p>Default is on, so an install with no config line at all behaves exactly like every build
 * before this one.  Written key:</p>
 *
 * <pre>occlusionEnabled=on|off</pre>
 */
public final class OcclusionSwitch {

    /** The same per-mod config file every other switch in this mod reads. */
    private static final String FILE = "config/firecontrolcompat-pod.txt";

    /** The key this switch owns. */
    private static final String KEY = "occlusionEnabled";

    /** {@link #KEY} lower-cased and space-free, as every reader in this mod matches lines. */
    private static final String LOWER_KEY = "occlusionenabled";

    /** Whether the screen may run; on unless the config or the command says off. */
    private static volatile boolean enabled = readConfig();

    /** Whether the command (rather than the file) decided the current value. */
    private static volatile boolean fromCommand;

    /** Set while the "disabled" line has already been written, so it is written once. */
    private static volatile boolean offLogged;

    private OcclusionSwitch() {
    }

    /** Whether a sweep may cast rays at all. When false every contact reads as visible. */
    public static boolean enabled() {
        return enabled;
    }

    /** {@code config} or {@code command}: what decided the value in use. */
    public static String source() {
        return fromCommand ? "command" : "config";
    }

    /**
     * Turns the screen on or off, writes the value to the config, and says so once.
     *
     * @return the value now in use
     */
    public static boolean set(boolean want) {
        boolean changed = enabled != want;
        enabled = want;
        fromCommand = true;
        // A different answer means every remembered verdict was decided under the old one.
        OcclusionCache.invalidate();
        try {
            persist(want);
        } catch (RuntimeException ignored) {
            // persist() already logs its own failure; the session state still stands.
        }
        if (!want) {
            if (!offLogged) {
                offLogged = true;
                FireControlCompat.LOGGER.info("occlusion: disabled by command");
            }
        } else {
            offLogged = false;
            FireControlCompat.LOGGER.info(
                    "occlusion: enabled by command (changed={})", changed);
        }
        return enabled;
    }

    /**
     * Reads {@code occlusionEnabled} from the config; on when the line is absent or unreadable.
     *
     * <p>Announces its answer once, because a screen that silently turns itself off is
     * indistinguishable from a screen that is broken - the same lesson the seat-view-port gate
     * taught.</p>
     */
    private static boolean readConfig() {
        boolean value = true;
        try {
            Path path = Path.of(FILE);
            if (Files.isRegularFile(path)) {
                for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                    String text = line.trim().toLowerCase().replace(" ", "");
                    if (!text.startsWith(LOWER_KEY)) {
                        continue;
                    }
                    int equals = text.indexOf('=');
                    String raw = equals < 0 ? "on" : text.substring(equals + 1).trim();
                    boolean off = raw.startsWith("off") || raw.startsWith("false") || raw.startsWith("0");
                    value = !off;
                    break;
                }
            }
        } catch (IOException | RuntimeException failure) {
            FireControlCompat.LOGGER.info(
                    "occlusion: config unreadable, leaving the screen on: {}", failure.toString());
            value = true;
        }
        if (!value) {
            offLogged = true;
            FireControlCompat.LOGGER.info(
                    "occlusion: disabled by config ({} {} off)", FILE, KEY);
        } else {
            FireControlCompat.LOGGER.info("occlusion: enabled=<true> source=config");
        }
        return value;
    }

    /**
     * Writes {@code occlusionEnabled=on|off} into the config, leaving every other line alone.
     *
     * <p>The file is shared with the pod and blueprint switches, so it is rewritten line by line
     * rather than replaced: an existing line of this key is updated in place, and the key is
     * appended only when it is not there yet.</p>
     */
    private static void persist(boolean want) {
        String value = KEY + "=" + (want ? "on" : "off");
        try {
            Path path = Path.of(FILE);
            List<String> lines = Files.isRegularFile(path)
                    ? new ArrayList<>(Files.readAllLines(path, StandardCharsets.UTF_8))
                    : new ArrayList<>();
            boolean replaced = false;
            for (int i = 0; i < lines.size(); i++) {
                String text = lines.get(i).trim().toLowerCase().replace(" ", "");
                if (!text.startsWith(LOWER_KEY)) {
                    continue;
                }
                lines.set(i, value);
                replaced = true;
            }
            if (!replaced) {
                lines.add(value);
            }
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(path, lines, StandardCharsets.UTF_8);
            FireControlCompat.LOGGER.info("occlusion: {} written to {}", value, FILE);
        } catch (IOException | RuntimeException failure) {
            FireControlCompat.LOGGER.warn(
                    "occlusion: could not write {} to {}: {}", value, FILE, failure.toString());
        }
    }
}

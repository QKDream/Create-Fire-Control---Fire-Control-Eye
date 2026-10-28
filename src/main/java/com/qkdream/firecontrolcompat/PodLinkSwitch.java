package com.qkdream.firecontrolcompat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The switch that turns this mod's whole pod / aim / optical-lock chain off.
 *
 * <p>It exists for one reason: a crew reported that the optical pod's model and
 * the sight disagree about which way the pod is pointing, and the pack's own pod
 * does not show it. Nothing in this mod turns the pod except the pieces below,
 * so the quickest way to place the fault is to be able to take all of them out
 * at once and look again.</p>
 *
 * <p>Write {@code off} into {@code config/firecontrolcompat-pod.txt} and restart
 * to disable: the lock camera follow, the optical lock's extension to missiles,
 * the pod laser spot, and the relief-post seat priority.</p>
 */
public final class PodLinkSwitch {

    private static final String FILE = "config/firecontrolcompat-pod.txt";

    private static final boolean ENABLED = read();

    /**
     * Whether the seat view port integration may run: it computes aim origins,
     * aim points and lock frames across the structure/world boundary, which is
     * where a wrong frame would show up as a pod camera in the wrong place and
     * at the wrong angle. It has its own line so that one restart can tell
     * whether that path is the one at fault.
     */
    private static final boolean SEAT_VIEW_PORT = readSeatViewPort();

    /**
     * Whether the lock may take the camera over. Off unless asked for: a crew
     * found the view stuck at one angle after leaving the pod, and this is the
     * only piece of this mod that holds the camera after the pod view ends.
     */
    private static final boolean LOCK_CAMERA = readFlag("lockcamera", false);

    /**
     * Whether the half turn of the pod view is applied to the camera as it is
     * drawn, or to the direction the pack keeps.
     *
     * <p>Turning the pack's stored direction fixed the angle but left a crew
     * seeing the previous visit's view on every other entry: the pack caches that
     * direction and puts it back when the pod is entered again, so a value turned
     * on the way out was read back unturned on the way in. Turning the camera
     * instead touches no state at all, which is the default. {@code
     * cameraFlipMode=state} restores the old way.</p>
     */
    private static final boolean FLIP_ON_STATE = readFlipOnState();

    private PodLinkSwitch() {
    }

    /** Whether this mod may touch the pod, its aim, its lock or its laser. */
    public static boolean enabled() {
        return ENABLED;
    }

    /** Whether the half turn is applied to the pack's stored direction instead of the camera. */
    public static boolean flipOnState() {
        return FLIP_ON_STATE;
    }

    private static boolean readFlipOnState() {
        try {
            Path path = Path.of(FILE);
            if (!Files.isRegularFile(path)) {
                return false;
            }
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String text = line.trim().toLowerCase().replace(" ", "");
                if (!text.startsWith("cameraflipmode")) {
                    continue;
                }
                boolean state = text.endsWith("state");
                FireControlCompat.LOGGER.info("[firecontrolcompat] camera flip mode: {}",
                        state ? "state" : "camera");
                return state;
            }
            return false;
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    /** Whether the lock may take the camera over; off unless the config says on. */
    public static boolean lockCameraEnabled() {
        return LOCK_CAMERA;
    }

    /** Reads {@code key=on|off} from the config, answering the fallback otherwise. */
    private static boolean readFlag(String key, boolean fallback) {
        try {
            Path path = Path.of(FILE);
            if (!Files.isRegularFile(path)) {
                return fallback;
            }
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String text = line.trim().toLowerCase().replace(" ", "");
                if (!text.startsWith(key)) {
                    continue;
                }
                int equals = text.indexOf('=');
                String value = equals < 0 ? "on" : text.substring(equals + 1).trim();
                boolean on = value.startsWith("on") || value.startsWith("true") || value.startsWith("1");
                FireControlCompat.LOGGER.info("[firecontrolcompat] {} set to {}", key, on ? "on" : "off");
                return on;
            }
            return fallback;
        } catch (IOException | RuntimeException failure) {
            return fallback;
        }
    }

    /** Whether the seat view port integration may run; separate from the rest. */
    public static boolean seatViewPortEnabled() {
        return SEAT_VIEW_PORT;
    }

    /**
     * Says once, at startup, whether the Synaxis seat view port is on and what decided it.
     *
     * <p>It was off in the pack and nothing said so: no port was registered, Synaxis never asked for
     * an angle, and the symptom was identical to a broken API. A gate that silently removes a feature
     * is worse than the feature being broken, because there is nothing to search for.</p>
     */
    public static void reportSeatViewPort() {
        if (SEAT_VIEW_PORT) {
            FireControlCompat.LOGGER.info(
                    "synaxis: seat-view-port enabled - the plant port will be registered");
        } else {
            FireControlCompat.LOGGER.warn(
                    "synaxis: seat-view-port DISABLED by config - no plant port is registered, so"
                            + " Synaxis will never ask for the view angles. Re-enable with"
                            + " `seatViewPort=on` in config/firecontrolcompat-pod.txt");
        }
    }

    /** Reads {@code seatViewPort=off} from the same file; on unless switched off. */
    private static boolean readSeatViewPort() {
        try {
            Path path = Path.of(FILE);
            if (!Files.isRegularFile(path)) {
                return true;
            }
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String text = line.trim().toLowerCase().replace(" ", "");
                if (!text.startsWith("seatviewport")) {
                    continue;
                }
                boolean off = text.endsWith("off") || text.endsWith("false") || text.endsWith("=0");
                FireControlCompat.LOGGER.info(
                        "[firecontrolcompat] seat view port switch: {}", off ? "OFF" : "on");
                return !off;
            }
            return true;
        } catch (IOException | RuntimeException failure) {
            FireControlCompat.LOGGER.info("[firecontrolcompat] seat view port switch unreadable, leaving it on: {}",
                    failure.toString());
            return true;
        }
    }

    private static boolean read() {
        try {
            Path path = Path.of(FILE);
            if (!Files.isRegularFile(path)) {
                return true;
            }
            String text = Files.readString(path, StandardCharsets.UTF_8).trim().toLowerCase();
            boolean off = text.startsWith("off") || text.startsWith("false") || text.startsWith("0");
            FireControlCompat.LOGGER.info("[firecontrolcompat] pod chain switch: {}", off ? "OFF" : "on");
            return !off;
        } catch (IOException | RuntimeException failure) {
            FireControlCompat.LOGGER.info("[firecontrolcompat] pod chain switch unreadable, leaving it on: {}",
                    failure.toString());
            return true;
        }
    }
}

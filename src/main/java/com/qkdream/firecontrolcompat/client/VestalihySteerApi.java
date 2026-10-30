package com.qkdream.firecontrolcompat.client;

import com.qkdream.firecontrolcompat.FireControlCompat;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Reflection-only door onto vestalihy's steering API.
 *
 * <p>{@code VestalihyMalytkaSteerClient} used to name {@code com.vestalihy.client.Keybinds} and
 * {@code com.vestalihy.network.MalyutkaSteerPacket} directly. An import is compile-time only, but
 * the bytecode is not: the two references sit in that class's constant pool, and the verifier has
 * to resolve {@code MalyutkaSteerPacket} to check that it is a {@code CustomPacketPayload} before
 * the first tick ever runs - so a pack without vestalihy (or with a vestalihy whose API moved) got
 * {@code NoClassDefFoundError} at class link time, i.e. before any "is the mod loaded" branch
 * could help.</p>
 *
 * <p>Both classes are therefore reached through {@link Class#forName} here, in a class whose own
 * constant pool contains nothing of vestalihy: the strings only become classes when the probe
 * actually runs, and the probe is only reached once {@link #available()} has confirmed the mod is
 * installed. The answer - usable, or the one reason it is not - is cached in a static, so the
 * reflection happens once per game session and a missing API costs a {@code return} per tick.
 * Nothing throws out of here.</p>
 */
public final class VestalihySteerApi {

    /** Mod id of the soft dependency whose 9M14 keys are reused. */
    public static final String VESTALIHY = "vestalihy";

    private static final String KEYBINDS_CLASS = "com.vestalihy.client.Keybinds";
    private static final String PACKET_CLASS = "com.vestalihy.network.MalyutkaSteerPacket";

    /** The four look-up fields, in the order the client sends them: left, right, up, down. */
    private static final String[] KEY_FIELDS = {"CONTROL_LEFT", "CONTROL_RIGHT", "CONTROL_UP", "CONTROL_DOWN"};

    /** Result of the one probe: {@code 0} not yet run, {@code 1} usable, {@code 2} skipped. */
    private static int state;

    /** Why it is skipped, in the shape the log line wants: {@code mod-absent} / {@code api-missing:..}. */
    private static String reason = "not-probed";

    /** The four mappings, read once. Only meaningful while {@link #state} is {@code 1}. */
    private static final KeyMapping[] KEYS = new KeyMapping[KEY_FIELDS.length];

    /** The record's canonical constructor, or null while skipped. */
    private static Constructor<?> packetConstructor;

    /** One line about a failed send, never a line per tick. */
    private static final AtomicBoolean SEND_FAILURE_LOGGED = new AtomicBoolean(false);

    private VestalihySteerApi() {
    }

    /** True while vestalihy is installed <b>and</b> its steering API has everything used here. */
    public static boolean available() {
        return probe() == 1;
    }

    /** The reason cached by the probe; {@code ok} when it is usable. */
    public static String reason() {
        return probe() == 1 ? "ok" : reason;
    }

    /** The steering key for {@code index}: 0 left, 1 right, 2 up, 3 down. */
    public static KeyMapping key(int index) {
        return KEYS[index];
    }

    /**
     * Sends one steering state to the server.
     *
     * @return false when the API is unavailable or the send failed - never throws
     */
    public static boolean send(boolean left, boolean right, boolean up, boolean down) {
        Constructor<?> constructor = packetConstructor;
        if (constructor == null) {
            return false;
        }
        try {
            Object packet = constructor.newInstance(left, right, up, down);
            PacketDistributor.sendToServer((CustomPacketPayload) packet);
            return true;
        } catch (Throwable failure) {
            if (SEND_FAILURE_LOGGED.compareAndSet(false, true)) {
                FireControlCompat.LOGGER.info("vestalihy: steer send failed error={}", describe(failure));
            }
            return false;
        }
    }

    private static synchronized int probe() {
        if (state != 0) {
            return state;
        }
        state = 2;
        if (!FireControlCompat.isModLoaded(VESTALIHY)) {
            reason = "mod-absent";
            // Nothing of vestalihy is referenced below this point, so the probe stays silent.
            FireControlCompat.LOGGER.info("vestalihy: steer skipped reason={}", reason);
            return state;
        }
        try {
            Class<?> keybinds = Class.forName(KEYBINDS_CLASS);
            for (int i = 0; i < KEY_FIELDS.length; i++) {
                Field field = keybinds.getField(KEY_FIELDS[i]);
                if (!KeyMapping.class.isAssignableFrom(field.getType())) {
                    throw new NoSuchFieldException(KEY_FIELDS[i] + " is " + field.getType().getName());
                }
                Object value = field.get(null);
                if (!(value instanceof KeyMapping mapping)) {
                    throw new NoSuchFieldException(KEY_FIELDS[i] + " is null");
                }
                KEYS[i] = mapping;
            }
            Class<?> packet = Class.forName(PACKET_CLASS);
            if (!CustomPacketPayload.class.isAssignableFrom(packet)) {
                throw new NoSuchMethodException(PACKET_CLASS + " is not a CustomPacketPayload");
            }
            // The payload is only deliverable while the mod itself registered it: TYPE and
            // STREAM_CODEC are what the network layer reads. A packet class without them would
            // construct fine and then be dropped (or throw) on send.
            packet.getField("TYPE");
            packet.getField("STREAM_CODEC");
            packetConstructor = packet.getDeclaredConstructor(
                    boolean.class, boolean.class, boolean.class, boolean.class);
            reason = "ok";
            state = 1;
        } catch (Throwable failure) {
            reason = "api-missing:" + describe(failure);
            packetConstructor = null;
            state = 2;
            FireControlCompat.LOGGER.info("vestalihy: steer skipped reason={}", reason);
        }
        return state;
    }

    /** Short, log-safe description: the failure kind and its message, never a stack trace. */
    private static String describe(Throwable failure) {
        String message = failure.getMessage();
        String kind = failure.getClass().getSimpleName();
        if (message == null || message.isBlank()) {
            return kind;
        }
        // A missing class reports its binary name; keep the line one line and bounded.
        String trimmed = message.length() > 120 ? message.substring(0, 120) : message;
        return kind + ":" + trimmed.replace('\n', ' ');
    }
}

package com.qkdream.firecontrolcompat.blueprint.capture;

import java.lang.reflect.Method;

/**
 * Whether the frame capture's hooks are actually <b>in</b> the classes they aim at.
 *
 * <h2>Why this has to be asked at runtime</h2>
 *
 * <p>Every hook this mod adds is declared with {@code require = 0}, because a hook that stops
 * matching a later game version must not take the whole mod down at load. The price is that a hook
 * which never applied is <b>indistinguishable from one that applied and was never called</b>: the
 * capture window then ends with {@code verts=0 faces=0} and no way to say which of the two happened.
 * That is exactly the report this class exists for ("capture 帧捕获一帧都没录到").</p>
 *
 * <p>Mixin renames every callback it injects to
 * {@code handler$<reference>$<the handler method's name>}, so the handler's own name survives in the
 * target class. Looking for it with reflection answers the real question - "is the injected call
 * inside {@code getBuffer} right now" - instead of the weaker "did the config ask for it".</p>
 *
 * <p>Nothing here initialises a class ({@code Class.forName(name, false, loader)}) and every step is
 * inside a try/catch: this is a diagnostic, and a diagnostic never gets to break a frame.</p>
 */
public final class CaptureHooks {

    /** The one door every renderer asks for a consumer (blocks, block entities, entities alike). */
    public static final String BUFFER_SOURCE =
            "net.minecraft.client.renderer.MultiBufferSource$BufferSource";

    /** Hands every entity's {@code MultiBufferSource} to the entity renderer. */
    public static final String ENTITY_DISPATCHER =
            "net.minecraft.client.renderer.entity.EntityRenderDispatcher";

    /** The same for block entities - which is where tracks, tyres and contraption parts draw. */
    public static final String BLOCK_ENTITY_DISPATCHER =
            "net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher";

    /** Present in every handler name this mod injects into a renderer. */
    private static final String MARKER = "firecontrolcompat";

    private static Boolean bufferSource;

    private static Boolean entityDispatcher;

    private static Boolean blockEntityDispatcher;

    private CaptureHooks() {
    }

    /** Whether {@code MultiBufferSource$BufferSource#getBuffer} carries this mod's callback. */
    public static boolean bufferSourceHooked() {
        if (bufferSource == null) {
            bufferSource = carriesHook(BUFFER_SOURCE);
        }
        return bufferSource;
    }

    /** Whether the entity dispatcher replaces the buffer source for the duration of a draw. */
    public static boolean entityDispatcherHooked() {
        if (entityDispatcher == null) {
            entityDispatcher = carriesHook(ENTITY_DISPATCHER);
        }
        return entityDispatcher;
    }

    /** Whether the block-entity dispatcher replaces the buffer source for the duration of a draw. */
    public static boolean blockEntityDispatcherHooked() {
        if (blockEntityDispatcher == null) {
            blockEntityDispatcher = carriesHook(BLOCK_ENTITY_DISPATCHER);
        }
        return blockEntityDispatcher;
    }

    /**
     * Which door the recording is expected to arrive through.
     *
     * <p>{@code buffer-source} is the complete one (every render type in the frame); {@code
     * dispatcher} is the narrower one that still catches block entities and entities. Reported, not
     * chosen: both doors are always installed, so the file is filled by whichever is reached.
     */
    public static String expectedMode() {
        if (bufferSourceHooked()) {
            return "buffer-source";
        }
        if (entityDispatcherHooked() || blockEntityDispatcherHooked()) {
            return "dispatcher";
        }
        return "none";
    }

    /** One line naming every hook, for the {@code capture-hook:} log. */
    public static String describe() {
        return "getBuffer=" + flag(bufferSourceHooked())
                + " entity=" + flag(entityDispatcherHooked())
                + " block-entity=" + flag(blockEntityDispatcherHooked())
                + " mode=" + expectedMode();
    }

    /** How many methods the target class declares, so "class not found" is not mistaken for "no hook". */
    public static int methodCount(String className) {
        try {
            Class<?> type = load(className);
            return type == null ? -1 : type.getDeclaredMethods().length;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static boolean carriesHook(String className) {
        try {
            Class<?> type = load(className);
            if (type == null) {
                return false;
            }
            for (Method method : type.getDeclaredMethods()) {
                String name = method.getName();
                if (name != null && name.contains(MARKER) && name.contains("capture")) {
                    return true;
                }
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Class<?> load(String className) {
        try {
            // Initialise = false: asking a question must not run anybody's static initialiser.
            return Class.forName(className, false, CaptureHooks.class.getClassLoader());
        } catch (Throwable missing) {
            return null;
        }
    }

    private static String flag(boolean value) {
        return value ? "true" : "false";
    }
}

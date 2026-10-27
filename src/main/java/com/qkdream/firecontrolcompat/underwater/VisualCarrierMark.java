package com.qkdream.firecontrolcompat.underwater;

import java.util.UUID;
import net.minecraft.world.entity.LivingEntity;

/**
 * Who was looking down the tube when it went off.
 *
 * <p>面包学's visually guided round swims at whatever its owner's eyes are on,
 * so the round wants a gunner and not a layer: the man who loaded the tube may
 * be standing behind it with his sights nowhere near the water. The shot itself
 * carries the answer - the tube's contraption knows its controlling passenger -
 * so the mark is taken at the head of the shot, handed to the round that shot
 * builds, and dropped again when the shot is over.</p>
 */
public final class VisualCarrierMark {

    private static UUID shooter;

    private VisualCarrierMark() {
    }

    /** Remembers who is sitting in the mount, if anybody is. */
    public static void arm(LivingEntity controller) {
        shooter = controller == null ? null : controller.getUUID();
    }

    /** Who was looking down the tube, or null when it was fired by redstone. */
    public static UUID shooter() {
        return shooter;
    }

    public static void clear() {
        shooter = null;
    }
}

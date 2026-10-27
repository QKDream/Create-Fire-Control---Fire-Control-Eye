package com.qkdream.firecontrolcompat.sonar;

import com.qkdream.firecontrolcompat.FireControlCompat;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * The two halves of the redstone-link frequency a fire control computer answers to, stored the way
 * 火控学 stores its own.
 *
 * <h2>What "Create's own mechanism" means on 0.9.4 (read off javap/vineflower)</h2>
 *
 * <p>Create's wireless redstone is a network of {@code IRedstoneLinkable}s keyed by a
 * {@code Couple<RedstoneLinkNetworkHandler.Frequency>}, where a {@code Frequency} is just an
 * {@code ItemStack} (a {@code create:redstone_link} item, or anything else the crew wants to use as
 * a token). 火控学 does not invent anything on top of that: {@code WirelessPulseReceiver} - one per
 * input action - holds the pair and implements that interface, and the pair is stored on the
 * controller's own block entity as one compound with a {@code "First"} and a {@code "Second"} saved
 * item stack:</p>
 *
 * <pre>
 *   tag.put("First",  first.getStack().saveOptional(registries));
 *   tag.put("Second", second.getStack().saveOptional(registries));
 *   first  = Frequency.of(ItemStack.parseOptional(registries, tag.getCompound("First")));
 * </pre>
 *
 * <p>This class is that shape, on the controller, under a key of ours: no {@code SavedData}, no
 * item-handedness rule of our own, and nothing that has to be re-derived from a position. The
 * receiver in {@link SonarSelectWireless} then joins Create's network
 * ({@code Create.REDSTONE_LINK_NETWORK_HANDLER.addToNetwork}) with exactly this pair, which is the
 * same call the pack's own receivers are added with ({@code StabilizerControllerBlockEntity#setFrequency}).</p>
 */
public final class SonarRedstonePair {

    /** Our compound on the controller's persistent data, in the pack's own two-part shape. */
    public static final String KEY = "firecontrolcompat:sonar_redstone";

    private static final String FIRST = "First";
    private static final String SECOND = "Second";

    private SonarRedstonePair() {
    }

    /**
     * The pair, read off the controller.
     *
     * @return a two-element array, {@code {first, second}}; either may be {@link ItemStack#EMPTY}
     */
    public static ItemStack[] of(BlockEntity controller) {
        return new ItemStack[] {firecontrolcompat$read(controller, FIRST),
                firecontrolcompat$read(controller, SECOND)};
    }

    /** Writes both halves at once; an empty stack clears that half. */
    public static boolean set(BlockEntity controller, ItemStack first, ItemStack second) {
        if (controller == null) {
            return false;
        }
        try {
            net.minecraft.core.HolderLookup.Provider registries = firecontrolcompat$registries(controller);
            if (registries == null) {
                return false;
            }
            CompoundTag data = controller.getPersistentData();
            CompoundTag pair = new CompoundTag();
            pair.put(FIRST, firecontrolcompat$normalize(first).saveOptional(registries));
            pair.put(SECOND, firecontrolcompat$normalize(second).saveOptional(registries));
            data.put(KEY, pair);
            controller.setChanged();
            return true;
        } catch (Throwable failure) {
            FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] sonar redstone: could not file the pair on the computer: {}",
                    failure.toString());
            return false;
        }
    }

    /** Drops both halves. */
    public static void clear(BlockEntity controller) {
        if (controller == null) {
            return;
        }
        try {
            controller.getPersistentData().remove(KEY);
            controller.setChanged();
        } catch (Throwable ignored) {
        }
    }

    /** Whether either half is bound, which is also "the receiver is on Create's network". */
    public static boolean bound(BlockEntity controller) {
        ItemStack[] pair = of(controller);
        return !pair[0].isEmpty() || !pair[1].isEmpty();
    }

    /**
     * The pair as one log-ready token: the two item ids joined by {@code '|'}.
     *
     * <p>This is the {@code freq=} of the {@code sonar redstone: source=create …} line, and it is
     * deliberately the <em>item</em> ids: a Create frequency <b>is</b> an item stack, so two crews
     * agreeing on a pair of tokens is what makes their links talk.</p>
     */
    public static String describe(ItemStack first, ItemStack second) {
        return firecontrolcompat$id(first) + "|" + firecontrolcompat$id(second);
    }

    /** Same, read straight off the controller. */
    public static String describe(BlockEntity controller) {
        ItemStack[] pair = of(controller);
        return describe(pair[0], pair[1]);
    }

    private static String firecontrolcompat$id(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "-";
        }
        try {
            return String.valueOf(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()));
        } catch (Throwable ignored) {
            return String.valueOf(stack.getItem());
        }
    }

    /** The pack's own normalisation: a frequency is one item, never a stack. */
    private static ItemStack firecontrolcompat$normalize(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack one = stack.copy();
        one.setCount(1);
        return one;
    }

    private static net.minecraft.core.HolderLookup.Provider firecontrolcompat$registries(BlockEntity controller) {
        if (controller == null || controller.getLevel() == null) {
            return null;
        }
        try {
            return controller.getLevel().registryAccess();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static ItemStack firecontrolcompat$read(BlockEntity controller, String key) {
        if (controller == null) {
            return ItemStack.EMPTY;
        }
        try {
            CompoundTag data = controller.getPersistentData();
            if (!data.contains(KEY)) {
                return ItemStack.EMPTY;
            }
            CompoundTag pair = data.getCompound(KEY);
            if (!pair.contains(key)) {
                return ItemStack.EMPTY;
            }
            net.minecraft.core.HolderLookup.Provider registries = firecontrolcompat$registries(controller);
            if (registries == null) {
                return ItemStack.EMPTY;
            }
            return ItemStack.parseOptional(registries, pair.getCompound(key));
        } catch (Throwable ignored) {
            return ItemStack.EMPTY;
        }
    }
}

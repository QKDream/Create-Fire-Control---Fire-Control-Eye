package com.qkdream.firecontrolcompat.sonar;

import com.hooya.stabilizedturret.content.controller.ControllerTracker;
import com.hooya.stabilizedturret.content.controller.DriveByWireCompat;
import com.hooya.stabilizedturret.content.controller.RemoteControllerCompat;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.qkdream.firecontrolcompat.FireControlCompat;
import com.qkdream.firecontrolcompat.IffRadar;
import com.qkdream.firecontrolcompat.RadarNameStore;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * What a fire control computer answers to for the sonar pick.
 *
 * <p>The pick is one of the computer's inputs, so it is driven the way the
 * computer's own actions are: a pair of wireless frequencies, one key on the
 * ordinary remote controller and one on the advanced one.</p>
 *
 * <p>The bindings belong to the computer rather than to the dish, so a boat
 * with several sets still has one pick, and they are filed under the
 * computer's own identity (see {@link RadarNameStore#keyOf}) exactly as the
 * torpedo swim is, so two boats can answer to different frequencies.</p>
 *
 * <p>A computer with nothing on file answers to nothing but its key.</p>
 */
public final class SonarSelectInputStore extends SavedData {

    public static final String DATA_ID = "firecontrolcompat_sonar_select_inputs";

    /**
     * One computer's bindings for the <em>one</em> sonar action: the wireless redstone pair and one
     * key on each of the three exchange channels 火控学 itself offers
     * ({@code RemoteControllerCompat.Type}: the ordinary drive-by-wire controller, the advanced one,
     * and the full-keyboard wireless controller, which is the channel that also carries the mouse
     * wheel binds since 0.9.3).
     */
    public record Input(ItemStack first, ItemStack second, String normalKey, String advancedKey,
            String wirelessKey) {

        /** The key a channel of that kind answers to, empty when it carries none. */
        public String key(RemoteControllerCompat.Type type) {
            if (type == RemoteControllerCompat.Type.WIRELESS) {
                return this.wirelessKey;
            }
            return type == RemoteControllerCompat.Type.ADVANCED ? this.advancedKey : this.normalKey;
        }

        /** Whether a wireless pair is bound, either half of it being enough. */
        public boolean hasWireless() {
            return !this.first.isEmpty() || !this.second.isEmpty();
        }

        /** Whether anything at all is bound, so a computer with nothing is left alone. */
        public boolean configured() {
            return this.hasWireless() || !this.normalKey.isEmpty() || !this.advancedKey.isEmpty()
                    || !this.wirelessKey.isEmpty();
        }
    }

    /** The bindings a computer with none on file answers to. */
    public static Input plain() {
        return new Input(ItemStack.EMPTY, ItemStack.EMPTY, "", "", "");
    }

    /** Owner key -> the bindings. A computer with none on file carries nothing. */
    private final Map<String, Input> entries = new HashMap<>();

    /**
     * The same bindings, filed on the computer's own block entity.
     *
     * <p>A {@link SavedData} is indexed by the computer's identity, and that identity is its
     * sub-level id or its coordinates - both of which a blueprint copy changes, so the table is the
     * one place a copy cannot follow. The computer's block entity, on the other hand, is copied
     * whole. The table stays the index the page reads and writes; this is the copy a copy carries,
     * and {@link #of} rebuilds the index from it when the computer comes back under a new identity.
     * Same two-part shape as {@link SonarRedstonePair}, for the same reason.</p>
     */
    public static final String MIRROR_KEY = "firecontrolcompat:sonar_select";

    public static SonarSelectInputStore load(CompoundTag tag, HolderLookup.Provider registries) {
        SonarSelectInputStore store = new SonarSelectInputStore();
        CompoundTag stored = tag.getCompound("inputs");
        for (String key : stored.getAllKeys()) {
            CompoundTag entry = stored.getCompound(key);
            store.entries.put(key, new Input(
                    ItemStack.parseOptional(registries, entry.getCompound("first")),
                    ItemStack.parseOptional(registries, entry.getCompound("second")),
                    entry.getString("normal"),
                    entry.getString("advanced"),
                    entry.getString("wireless")));
        }
        return store;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag stored = new CompoundTag();
        this.entries.forEach((key, input) -> {
            CompoundTag one = new CompoundTag();
            one.put("first", input.first().saveOptional(registries));
            one.put("second", input.second().saveOptional(registries));
            one.putString("normal", input.normalKey());
            one.putString("advanced", input.advancedKey());
            one.putString("wireless", input.wirelessKey());
            stored.put(key, one);
        });
        tag.put("inputs", stored);
        return tag;
    }

    /** The bindings of a computer, or the plain ones when it carries none. */
    public static Input of(Level level, StabilizerControllerBlockEntity controller) {
        if (controller == null) {
            return plain();
        }
        try {
            String key = RadarNameStore.keyOf(level, controller);
            Input filed = table(level).get(key);
            if (filed != null) {
                // A world bound before the copy existed: file it on the computer now, so the
                // bindings travel from here on. Once per computer, and never again.
                if (!hasMirror(controller) && mirror(controller, filed)) {
                    FireControlCompat.LOGGER.info(
                            "sonar-binding: migrate saveddata->nbt be={} entries=1",
                            controller.getBlockPos().toShortString());
                }
                return filed;
            }
            return rebuild(level, controller, key);
        } catch (Throwable ignored) {
            return plain();
        }
    }

    /**
     * Rebuilds the table entry of a computer that has just been copied.
     *
     * <p>The copy kept the bindings on its block entity but arrives under a new identity, so the
     * table has nothing under its new key. The bindings are read back off the computer, filed under
     * the new key and reported once, which is what makes a printed ship answer to the same sonar
     * channels without being set up again.</p>
     */
    private static Input rebuild(Level level, StabilizerControllerBlockEntity controller, String key) {
        Input mirrored = mirrored(controller);
        if (mirrored == null || !mirrored.configured()) {
            return plain();
        }
        try {
            SonarSelectInputStore store = storeOf(level);
            if (store != null) {
                store.entries.put(key, mirrored);
                store.setDirty();
            }
            FireControlCompat.LOGGER.info(
                    "sonar-binding: migrate nbt->index be={} entries=1",
                    controller.getBlockPos().toShortString());
        } catch (Throwable ignored) {
        }
        return mirrored;
    }

    /** Files the bindings on the computer itself; {@code false} when it could not be written. */
    public static boolean mirror(BlockEntity controller, Input input) {
        if (controller == null || input == null) {
            return false;
        }
        try {
            net.minecraft.core.HolderLookup.Provider registries = registries(controller);
            if (registries == null) {
                return false;
            }
            CompoundTag stored = new CompoundTag();
            stored.put("first", normalize(input.first()).saveOptional(registries));
            stored.put("second", normalize(input.second()).saveOptional(registries));
            stored.putString("normal", input.normalKey() == null ? "" : input.normalKey());
            stored.putString("advanced", input.advancedKey() == null ? "" : input.advancedKey());
            stored.putString("wireless", input.wirelessKey() == null ? "" : input.wirelessKey());
            controller.getPersistentData().put(MIRROR_KEY, stored);
            controller.setChanged();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** The bindings filed on the computer itself, or {@code null} when it carries none. */
    public static Input mirrored(BlockEntity controller) {
        if (controller == null) {
            return null;
        }
        try {
            CompoundTag data = controller.getPersistentData();
            if (!data.contains(MIRROR_KEY)) {
                return null;
            }
            CompoundTag stored = data.getCompound(MIRROR_KEY);
            net.minecraft.core.HolderLookup.Provider registries = registries(controller);
            if (registries == null) {
                return null;
            }
            return new Input(
                    ItemStack.parseOptional(registries, stored.getCompound("first")),
                    ItemStack.parseOptional(registries, stored.getCompound("second")),
                    stored.getString("normal"),
                    stored.getString("advanced"),
                    stored.getString("wireless"));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean hasMirror(BlockEntity controller) {
        try {
            return controller != null && controller.getPersistentData().contains(MIRROR_KEY);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static ItemStack normalize(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack one = stack.copy();
        one.setCount(1);
        return one;
    }

    private static net.minecraft.core.HolderLookup.Provider registries(BlockEntity controller) {
        try {
            Level level = controller == null ? null : controller.getLevel();
            return level == null ? null : level.registryAccess();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Files a wireless frequency, one half at a time; an empty stack clears it.
     *
     * <p>Kept for the "second half only" path; the page binds both halves at once through
     * {@link #setWirelessPair}. Either way the pair is mirrored onto the controller itself (see
     * {@link SonarRedstonePair}), which is where Create's own {@code IRedstoneLinkable} reads it
     * from.</p>
     */
    public static boolean setWireless(
            ServerPlayer player, BlockPos pos, UUID subLevel, boolean first, ItemStack stack) {
        StabilizerControllerBlockEntity controller = controllerOf(player, pos, subLevel);
        if (controller == null) {
            return false;
        }
        ItemStack one = stack == null ? ItemStack.EMPTY : stack.copy();
        one.setCount(one.isEmpty() ? 0 : 1);
        ItemStack[] current = SonarRedstonePair.of(controller);
        SonarRedstonePair.set(controller, first ? one : current[0], first ? current[1] : one);
        FireControlCompat.LOGGER.info(
                "[firecontrolcompat] sonar redstone: source=create half={} freq={} pos={}",
                first ? "first" : "second", SonarRedstonePair.describe(controller), controller.getBlockPos());
        return edit(player, controller, input -> new Input(
                first ? one : input.first(),
                first ? input.second() : one,
                input.normalKey(),
                input.advancedKey(),
                input.wirelessKey()));
    }

    /**
     * Files <b>both</b> halves of the wireless redstone pair in one go, the way a Create link pair
     * is made: the item in the main hand is one frequency, the item in the off hand is the other.
     *
     * <p>The page used to send only {@code SLOT_WIRELESS_FIRST}, which takes the main-hand item -
     * so the second half of the pair could never be bound from this page at all, and the row read
     * {@code stone / -}. A pair is the unit Create's network keys on, so it is bound as a pair, and
     * it is stored the way 火控学 stores its own receivers ({@link SonarRedstonePair}).</p>
     *
     * <p>Empty hands clear the pair: that is the "unbind" gesture, and it is what makes the rail
     * switch answer to nothing again.</p>
     */
    public static boolean setWirelessPair(ServerPlayer player, BlockPos pos, UUID subLevel) {
        StabilizerControllerBlockEntity controller = controllerOf(player, pos, subLevel);
        if (controller == null) {
            return false;
        }
        ItemStack main = player.getMainHandItem();
        ItemStack off = player.getOffhandItem();
        ItemStack first = main == null ? ItemStack.EMPTY : main.copy();
        ItemStack second = off == null ? ItemStack.EMPTY : off.copy();
        first.setCount(first.isEmpty() ? 0 : 1);
        second.setCount(second.isEmpty() ? 0 : 1);
        if (first.isEmpty() && second.isEmpty()) {
            SonarRedstonePair.clear(controller);
        } else {
            SonarRedstonePair.set(controller, first, second);
        }
        FireControlCompat.LOGGER.info(
                "[firecontrolcompat] sonar redstone: source=create bound={} freq={} pos={} controller={}",
                !first.isEmpty() || !second.isEmpty(), SonarRedstonePair.describe(first, second),
                pos, controller.getBlockPos().toShortString());
        return edit(player, controller, input -> new Input(
                first, second, input.normalKey(), input.advancedKey(), input.wirelessKey()));
    }

    /**
     * Files the key one of the remotes answers to; an empty key clears it, and a
     * signal neither remote type can send is turned down.
     */
    public static boolean setRemoteKey(
            ServerPlayer player, BlockPos pos, UUID subLevel, RemoteControllerCompat.Type type, String key) {
        StabilizerControllerBlockEntity controller = controllerOf(player, pos, subLevel);
        if (controller == null) {
            return false;
        }
        String signal = key == null ? "" : key;
        if (!signal.isEmpty() && !DriveByWireCompat.accepts(type, signal)) {
            return false;
        }
        // All three of 火控学's channels file onto the same action: the ordinary drive-by-wire
        // controller, the advanced one, and the full-keyboard wireless controller.
        boolean advanced = type == RemoteControllerCompat.Type.ADVANCED;
        boolean wireless = type == RemoteControllerCompat.Type.WIRELESS;
        return edit(player, controller, current -> new Input(
                current.first(),
                current.second(),
                advanced || wireless ? current.normalKey() : signal,
                advanced ? signal : current.advancedKey(),
                wireless ? signal : current.wirelessKey()));
    }

    private static boolean edit(
            ServerPlayer player,
            StabilizerControllerBlockEntity controller,
            java.util.function.UnaryOperator<Input> change) {
        if (player == null) {
            return false;
        }
        try {
            ServerLevel level = player.serverLevel();
            SonarSelectInputStore store = store(level);
            String key = RadarNameStore.keyOf(level, controller);
            Input updated = change.apply(of(level, controller));
            store.entries.put(key, updated);
            // Written on the computer as well, so the bindings ride along with a blueprint copy.
            mirror(controller, updated);
            store.setDirty();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static StabilizerControllerBlockEntity controllerOf(ServerPlayer player, BlockPos pos, UUID subLevel) {
        if (player == null || pos == null) {
            return null;
        }
        try {
            return ControllerTracker.findLoadedForSettings(player, pos, subLevel);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Map<String, Input> table(Level level) {
        SonarSelectInputStore store = storeOf(level);
        return store == null ? Map.of() : store.entries;
    }

    private static SonarSelectInputStore storeOf(Level level) {
        ServerLevel server = IffRadar.serverLevelOf(level);
        return server == null ? null : store(server);
    }

    private static SonarSelectInputStore store(ServerLevel level) {
        MinecraftServer server = level.getServer();
        ServerLevel overworld = server == null ? level : server.overworld();
        return overworld.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(SonarSelectInputStore::new, SonarSelectInputStore::load, null), DATA_ID);
    }
}

package com.qkdream.firecontrolcompat.underwater;

import com.qkdream.firecontrolcompat.sonar.SonarLog;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;

/**
 * 面包学's anti-infantry grenade launcher, throwing the decoy.
 *
 * <p>That launcher is a drum of up to six rounds and nothing else: it counts
 * what is in it and never writes down what kind it is, so once a round of ours
 * is in there nothing about the block can tell it apart from one of the
 * launcher's own grenades. The drum is therefore read from the outside - every
 * decoy loaded is noted against the launcher's position and every throw takes
 * one off again - and only a throw with one of ours left in it is answered
 * here. Everything else falls straight through to the launcher, which loads and
 * fires its own grenades exactly as it always did.</p>
 *
 * <p>What is left in a drum is kept in the overworld's saved data, so a
 * launcher loaded before a restart still knows what is in it. All six block
 * states of both families are recognised by their registry name rather than by
 * a table of references, so a launcher added by a later 面包学 is picked up as
 * soon as it follows the same naming.</p>
 */
public final class TorpedoDecoyLauncher {

    /** The mod that owns the launcher, and the two families it comes in. */
    private static final String NAMESPACE = "mianbaos_modernwarfare";
    private static final String EMPTY = "antimangrenade";
    private static final String GREEN = "antimangrenadelvse";
    /** How many rounds a drum holds, which is also the number on its last block. */
    private static final int DRUM = 6;

    /** How far out of the launcher's face the round leaves, in blocks. */
    private static final double MUZZLE_DISTANCE = 0.6D;
    /** The launcher's own throw: this much speed, and this much spray. */
    private static final float LAUNCH_SPEED = 1.2F;
    private static final float LAUNCH_SPREAD = 2.0F;
    /** The bang a round makes leaving the drum, and the click one makes going in. */
    private static final String LAUNCH_SOUND = "mianbaos_modernwarfare:antimangrenadelaunher";
    private static final String LOAD_SOUND = "block.candle.place";

    private TorpedoDecoyLauncher() {
    }

    /**
     * Notes a decoy going into the drum, and takes the round off the player who
     * put it there. False when the hand holds something else, or the drum has no
     * room: the launcher's own logic then answers the click untouched.
     */
    public static boolean tryLoad(LevelAccessor world, double x, double y, double z, Entity entity) {
        if (!(world instanceof Level level) || entity == null) {
            return false;
        }
        ItemStack held = held(entity);
        if (held.getItem() != TorpedoDecoy.TORPEDO_DECOY_ITEM.get()) {
            return false;
        }
        BlockPos pos = BlockPos.containing(x, y, z);
        BlockState state = level.getBlockState(pos);
        Launcher launcher = read(state.getBlock());
        if (launcher == null || launcher.rounds() >= DRUM) {
            return false;
        }
        if (level.isClientSide()) {
            return true;
        }
        play(level, pos, LOAD_SOUND);
        level.setBlock(pos, filled(state, launcher.rounds() + 1), 3);
        take(entity);
        Magazine.add(level, pos, 1);
        return true;
    }

    /**
     * Throws the decoy when the launcher goes off with one of ours on top of
     * the drum. A drum holding nothing but the launcher's own grenades is left
     * to the launcher, and so is the whole client side: the client runs the
     * launcher's own throw, which shows the same bang and takes the same round
     * off the drum, and the server corrects the block either way.
     */
    public static boolean tryFire(LevelAccessor world, double x, double y, double z) {
        if (!(world instanceof Level level) || level.isClientSide()) {
            return false;
        }
        BlockPos pos = BlockPos.containing(x, y, z);
        BlockState state = level.getBlockState(pos);
        Launcher launcher = read(state.getBlock());
        if (launcher == null || launcher.rounds() <= 0 || Magazine.count(level, pos) <= 0) {
            return false;
        }
        play(level, pos, LAUNCH_SOUND);
        lob(level, pos, state);
        level.setBlock(pos, filled(state, launcher.rounds() - 1), 3);
        Magazine.add(level, pos, -1);
        return true;
    }

    /** The throw itself: the launcher's muzzle, the launcher's lob, our round. */
    private static void lob(Level level, BlockPos pos, BlockState state) {
        Direction facing = facing(state);
        Vec3 muzzle = Vec3.atCenterOf(pos).add(facing.getStepX() * MUZZLE_DISTANCE,
                facing.getStepY() * MUZZLE_DISTANCE, facing.getStepZ() * MUZZLE_DISTANCE);
        TorpedoDecoyEntity decoy = new TorpedoDecoyEntity(TorpedoDecoy.TORPEDO_DECOY.get(), level);
        decoy.setPos(muzzle.x, muzzle.y, muzzle.z);
        decoy.shoot(facing.getStepX(), facing.getStepY() + 1.0D, facing.getStepZ(), LAUNCH_SPEED, LAUNCH_SPREAD);
        level.addFreshEntity(decoy);
    }

    /** Which way the launcher faces; it is a directional block like any other. */
    private static Direction facing(BlockState state) {
        Property<?> property = state.getBlock().getStateDefinition().getProperty("facing");
        if (property instanceof DirectionProperty direction && state.hasProperty(direction)) {
            return state.getValue(direction);
        }
        return Direction.NORTH;
    }

    /** The same launcher with a different number of rounds in it. */
    private static BlockState filled(BlockState from, int rounds) {
        Launcher launcher = read(from.getBlock());
        if (launcher == null || rounds < 0 || rounds > DRUM) {
            return from;
        }
        Block block = blockOf(launcher.family(), rounds);
        return block == null ? from : copyShared(from, block.defaultBlockState());
    }

    /** Every property the two launcher blocks have in common, carried across. */
    private static BlockState copyShared(BlockState from, BlockState to) {
        BlockState result = to;
        for (Map.Entry<Property<?>, Comparable<?>> entry : from.getValues().entrySet()) {
            Property<?> property = result.getBlock().getStateDefinition().getProperty(entry.getKey().getName());
            if (property != null && entry.getValue() != null && property.getPossibleValues().contains(entry.getValue())) {
                result = copyProperty(result, property, entry.getValue());
            }
        }
        return result;
    }

    /** The value is known to belong to the property: it came off the same block family. */
    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> BlockState copyProperty(BlockState to, Property<T> property, Comparable<?> value) {
        return to.setValue(property, (T) value);
    }

    /** A launcher block read back as the family it belongs to and the rounds in it. */
    private static Launcher read(Block block) {
        ResourceLocation key = block == null ? null : BuiltInRegistries.BLOCK.getKey(block);
        if (key == null || !NAMESPACE.equals(key.getNamespace())) {
            return null;
        }
        String path = key.getPath();
        int split = path.lastIndexOf('_');
        if (split < 0) {
            return EMPTY.equals(path) || GREEN.equals(path) ? new Launcher(path, 0) : null;
        }
        String family = path.substring(0, split);
        if (!EMPTY.equals(family) && !GREEN.equals(family)) {
            return null;
        }
        try {
            int rounds = Integer.parseInt(path.substring(split + 1));
            return rounds > 0 && rounds <= DRUM ? new Launcher(family, rounds) : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /** The block of a family holding this many rounds. */
    private static Block blockOf(String family, int rounds) {
        String path = rounds == 0 ? family : family + "_" + rounds;
        Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath(NAMESPACE, path));
        return block == Blocks.AIR ? null : block;
    }

    private static ItemStack held(Entity entity) {
        return entity instanceof LivingEntity living ? living.getMainHandItem() : ItemStack.EMPTY;
    }

    /** The round the player loaded is spent; a creative hand keeps it, as the launcher does. */
    private static void take(Entity entity) {
        if (!(entity instanceof Player player) || player.getAbilities().instabuild) {
            return;
        }
        ItemStack spent = new ItemStack(TorpedoDecoy.TORPEDO_DECOY_ITEM.get());
        player.getInventory().clearOrCountMatchingItems(
                stack -> spent.getItem() == stack.getItem(), 1, player.inventoryMenu.getCraftSlots());
    }

    private static void play(Level level, BlockPos pos, String sound) {
        SoundEvent event = BuiltInRegistries.SOUND_EVENT.get(ResourceLocation.parse(sound));
        if (event != null) {
            level.playSound(null, pos, event, SoundSource.BLOCKS, 1.0F, 1.0F);
        }
    }

    /** A launcher block: which family it belongs to, and how many rounds are in it. */
    private record Launcher(String family, int rounds) {
    }

    /** Decoy rounds waiting in each drum, keyed by where that drum stands. */
    private static final class Magazine extends SavedData {

        private static final String DATA_ID = "firecontrolcompat_decoy_magazines";

        private final Map<String, Integer> rounds = new HashMap<>();

        static Magazine load(CompoundTag tag, HolderLookup.Provider registries) {
            Magazine magazine = new Magazine();
            CompoundTag stored = tag.getCompound("rounds");
            for (String key : stored.getAllKeys()) {
                int count = stored.getInt(key);
                if (count > 0) {
                    magazine.rounds.put(key, count);
                }
            }
            return magazine;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            CompoundTag stored = new CompoundTag();
            this.rounds.forEach(stored::putInt);
            tag.put("rounds", stored);
            return tag;
        }

        static int count(Level level, BlockPos pos) {
            Magazine magazine = of(level);
            return magazine == null ? 0 : magazine.rounds.getOrDefault(key(level, pos), 0);
        }

        /** Files one more, or one fewer, decoy against a launcher. */
        static void add(Level level, BlockPos pos, int delta) {
            Magazine magazine = of(level);
            if (magazine == null) {
                return;
            }
            String key = key(level, pos);
            int count = magazine.rounds.getOrDefault(key, 0) + delta;
            if (count <= 0) {
                magazine.rounds.remove(key);
            } else {
                magazine.rounds.put(key, count);
            }
            magazine.setDirty();
        }

        /** A launcher on a Sable structure is filed against the level the ship is in. */
        private static String key(Level level, BlockPos pos) {
            Level root = SonarLog.rootLevel(level);
            Level dimension = root == null ? level : root;
            return dimension.dimension().location() + "@" + pos.asLong();
        }

        private static Magazine of(Level level) {
            MinecraftServer server = level == null ? null : level.getServer();
            if (server == null) {
                return null;
            }
            return server.overworld().getDataStorage().computeIfAbsent(
                    new SavedData.Factory<>(Magazine::new, Magazine::load, null), DATA_ID);
        }
    }
}

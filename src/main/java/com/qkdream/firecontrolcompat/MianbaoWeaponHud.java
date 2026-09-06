package com.qkdream.firecontrolcompat;

import com.verr1.taov.core.weaponhud.WeaponHudBinding;
import com.verr1.taov.core.weaponhud.WeaponHudBindingAccess;
import com.verr1.taov.core.weaponhud.WeaponHudSeatRef;
import com.verr1.taov.core.weaponhud.WeaponHudSourceDescriptor;
import com.verr1.taov.core.weaponhud.WeaponHudSources;
import com.verr1.taov.core.weaponhud.server.WeaponHudService;
import com.verr1.taov.weapons.registry.TaovWeaponItems;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import net.mcreator.myfirstmod.init.MianbaosModernwarfareModBlocks;
import net.mcreator.myfirstmod.init.MianbaosModernwarfareModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;

/**
 * Registers Mianbao (Mianbao's Modern Warfare) missile launchers and aircraft
 * weapon racks as TAOV weapon-HUD sources, so the TAOV HUD-linker can bind
 * them to a seat and show their payload/ammo card on the seated player's HUD.
 *
 * Mianbao block entities are MCreator generated, so the seat bindings are
 * kept in a side table and persisted through a mixin on
 * RandomizableContainerBlockEntity instead of adding fields to each class.
 */
public final class MianbaoWeaponHud {

    static final String BINDING_KEY = "firecontrolcompat:weapon_hud_binding";

    private static final Map<BlockEntity, WeaponHudBinding> BINDINGS =
            Collections.synchronizedMap(new IdentityHashMap<>());
    private static final Set<BlockEntityType<?>> RACK_TYPES =
            Collections.newSetFromMap(new IdentityHashMap<>());

    private static boolean sourcesRegistered;

    private MianbaoWeaponHud() {
    }

    public static void registerSources() {
        if (sourcesRegistered || !ModList.get().isLoaded("mianbaos_modernwarfare")) {
            return;
        }
        sourcesRegistered = true;

        // Missile launchers.
        register(MianbaosModernwarfareModBlockEntities.ANTIAIRMISSILELAUNCHERBLOCK, false);
        register(MianbaosModernwarfareModBlockEntities.ANTIAIRMISSILELAUNCHERLEFT, false);
        register(MianbaosModernwarfareModBlockEntities.ANTIAIRMISSILELAUNCHERRIGHT, false);
        register(MianbaosModernwarfareModBlockEntities.ANTITANKEMISSILELAUNCHER, false);
        register(MianbaosModernwarfareModBlockEntities.ANTITANKEMISSILELAUNCHERH_2, false);
        register(MianbaosModernwarfareModBlockEntities.ANTITANKMISSILELAUNCHERERXING_LEFT, false);
        register(MianbaosModernwarfareModBlockEntities.ANTITANKMISSILELAUNCHERERXING_RIGHT, false);
        register(MianbaosModernwarfareModBlockEntities.ANTITANKMISSILELAUNCHERSANXING, false);
        register(MianbaosModernwarfareModBlockEntities.ANTITANKMISSILELAUNCHERSIXING, false);
        register(MianbaosModernwarfareModBlockEntities.PORTABLEANTIAIRSYSTEM, false);
        register(MianbaosModernwarfareModBlockEntities.GROUNDMISSILELAUNCHERHEAD, false);
        register(MianbaosModernwarfareModBlockEntities.GROUNDMISSILELAUNCHERTAIL, false);

        // Aircraft weapon racks.
        register(MianbaosModernwarfareModBlockEntities.RACK, true);
        register(MianbaosModernwarfareModBlockEntities.AGM_MISSILE_1RACK, true);
        register(MianbaosModernwarfareModBlockEntities.AGM_MISSILE_2RACK, true);
        register(MianbaosModernwarfareModBlockEntities.AGM_MISSILE_3RACK, true);
        register(MianbaosModernwarfareModBlockEntities.AGM_MISSILE_4RACK, true);
        register(MianbaosModernwarfareModBlockEntities.AG_MMISSILEMIXED_RACK, true);
        register(MianbaosModernwarfareModBlockEntities.CLOSE_MISSILE_1RACK, true);
        register(MianbaosModernwarfareModBlockEntities.CLOSE_MISSILE_2RACK, true);
        register(MianbaosModernwarfareModBlockEntities.FAR_MISSILE_1RACK, true);
        register(MianbaosModernwarfareModBlockEntities.FAR_MISSILE_2RACK, true);
        register(MianbaosModernwarfareModBlockEntities.MEDIUM_MISSILE_RACK, true);
        register(MianbaosModernwarfareModBlockEntities.MEDIUM_AI_MMISSILE_2RACK, true);
        register(MianbaosModernwarfareModBlockEntities.LASER_GUIDED_MISSILE_RACK, true);
        register(MianbaosModernwarfareModBlockEntities.LASERGUIDEDMISSILESECONDRACK, true);
        register(MianbaosModernwarfareModBlockEntities.OPTICALGUIDEDMISSILERACK, true);
        register(MianbaosModernwarfareModBlockEntities.ANTIRADIATIONMISSILERACK, true);
        register(MianbaosModernwarfareModBlockEntities.CRUISEMISSILERACK, true);
        register(MianbaosModernwarfareModBlockEntities.NUCLEARCRUISEMISSILERACK, true);
        register(MianbaosModernwarfareModBlockEntities.JDAM_1RACK, true);
        register(MianbaosModernwarfareModBlockEntities.JDAM_2RACK, true);
        register(MianbaosModernwarfareModBlockEntities.BOMBRACK, true);
        register(MianbaosModernwarfareModBlockEntities.MEDIUM_BOMBRACK, true);
        register(MianbaosModernwarfareModBlockEntities.CLUSTER_BOMBRACK, true);
        register(MianbaosModernwarfareModBlockEntities.EARTHPENETRATORBOMBRACK, true);
        register(MianbaosModernwarfareModBlockEntities.FIRE_BOMBRACK, true);
        register(MianbaosModernwarfareModBlockEntities.HIGHRESISTANCEBOMBRACK, true);
        register(MianbaosModernwarfareModBlockEntities.HIGHEXPLOSIVEGUIDEDTORPEDORACK, true);
        register(MianbaosModernwarfareModBlockEntities.HIGHEXPLOSIVEVISUALGUIDEDTORPEDORACK, true);
    }

    private static void register(Supplier<? extends BlockEntityType<?>> holder, boolean rack) {
        BlockEntityType<?> type = holder.get();
        if (type == null) {
            return;
        }
        if (rack) {
            RACK_TYPES.add(type);
        }
        WeaponHudSources.register(new WeaponHudSourceDescriptor<>(
                (BlockEntityType<RandomizableContainerBlockEntity>) type,
                RandomizableContainerBlockEntity.class,
                ResourceLocation.fromNamespaceAndPath(FireControlCompat.MOD_ID, "weapon_hud/mianbao"),
                90,
                new WeaponHudBindingAccess<>() {
                    @Override
                    public List<WeaponHudSeatRef> read(RandomizableContainerBlockEntity source) {
                        return readSeats(source);
                    }

                    @Override
                    public void write(RandomizableContainerBlockEntity source, List<WeaponHudSeatRef> seats) {
                        writeSeats(source, seats);
                    }
                }));
    }

    static List<WeaponHudSeatRef> readSeats(BlockEntity source) {
        WeaponHudBinding binding = BINDINGS.get(source);
        return binding == null ? List.of() : binding.seats();
    }

    static void writeSeats(BlockEntity source, List<WeaponHudSeatRef> seats) {
        WeaponHudBinding binding = WeaponHudBinding.ofSeats(seats == null ? List.of() : seats);
        if (binding.seats().isEmpty()) {
            BINDINGS.remove(source);
        } else {
            BINDINGS.put(source, binding);
        }
    }

    public static void saveBinding(BlockEntity source, CompoundTag tag) {
        WeaponHudBinding binding = BINDINGS.get(source);
        if (binding == null || binding.seats().isEmpty()) {
            return;
        }
        ListTag list = new ListTag();
        for (WeaponHudSeatRef seat : binding.seats()) {
            CompoundTag entry = new CompoundTag();
            entry.putString("dimension", seat.dimension().location().toString());
            entry.putInt("x", seat.pos().getX());
            entry.putInt("y", seat.pos().getY());
            entry.putInt("z", seat.pos().getZ());
            list.add(entry);
        }
        tag.put(BINDING_KEY, list);
    }

    public static void loadBinding(BlockEntity source, CompoundTag tag) {
        if (!tag.contains(BINDING_KEY, Tag.TAG_LIST)) {
            return;
        }
        ListTag list = tag.getList(BINDING_KEY, Tag.TAG_COMPOUND);
        ArrayList<WeaponHudSeatRef> seats = new ArrayList<>();
        for (int index = 0; index < list.size() && index < WeaponHudBinding.MAX_SEATS; index++) {
            CompoundTag entry = list.getCompound(index);
            ResourceLocation dimension = ResourceLocation.tryParse(entry.getString("dimension"));
            if (dimension != null) {
                seats.add(new WeaponHudSeatRef(
                        ResourceKey.create(Registries.DIMENSION, dimension),
                        new BlockPos(entry.getInt("x"), entry.getInt("y"), entry.getInt("z"))));
            }
        }
        if (seats.isEmpty()) {
            BINDINGS.remove(source);
        } else {
            BINDINGS.put(source, WeaponHudBinding.ofSeats(seats));
        }
    }

    public static void heartbeatAll() {
        if (BINDINGS.isEmpty()) {
            return;
        }
        synchronized (BINDINGS) {
            ArrayList<BlockEntity> stale = new ArrayList<>();
            for (BlockEntity source : BINDINGS.keySet()) {
                if (source.isRemoved() || !(source.getLevel() instanceof ServerLevel)) {
                    stale.add(source);
                } else {
                    WeaponHudService.heartbeat(source);
                }
            }
            for (BlockEntity source : stale) {
                BINDINGS.remove(source);
            }
        }
    }

    /**
     * HUD ammunition display for a bound Mianbao source. Launchers report the
     * real loaded count from the persistent-data ammo keys (plus anything the
     * player put in the container GUI). Racks report 1 while loaded and 0 when
     * empty: their loaded state is the block itself, and firing swaps the block
     * back to the bare RACK pylon.
     */
    public static int ammoCount(BlockEntity source) {
        int inventory = 0;
        if (source instanceof RandomizableContainerBlockEntity container) {
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                inventory += container.getItem(slot).getCount();
            }
        }
        if (isRack(source)) {
            boolean loaded = inventory > 0
                    || persistentAmmo(source) > 0.0
                    || source.getPersistentData().getBoolean(BeamMissileCompat.IR_RACK_KEY)
                    || source.getBlockState().getBlock() != MianbaosModernwarfareModBlocks.RACK.get();
            return loaded ? 1 : 0;
        }
        return inventory + (int) persistentAmmo(source);
    }

    public static boolean isRack(BlockEntity source) {
        return RACK_TYPES.contains(source.getType());
    }

    /**
     * True when the held stack is the TAOV weapon HUD-linker. Used by the
     * launcher interaction mixin so a binding click is not swallowed by the
     * launcher's reload handling.
     */
    public static boolean isWeaponHudLinker(ItemStack stack) {
        if (stack.isEmpty() || !ModList.get().isLoaded("taov_weapons")) {
            return false;
        }
        try {
            return stack.is(TaovWeaponItems.WEAPON_HUD_LINKER);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static double persistentAmmo(BlockEntity source) {
        CompoundTag data = source.getPersistentData();
        return data.getDouble(BeamMissileCompat.AA_AMMO_KEY)
                + data.getDouble(BeamMissileCompat.ATGM_AMMO_KEY_1)
                + data.getDouble(BeamMissileCompat.ATGM_AMMO_KEY_2)
                + data.getDouble(BeamMissileCompat.MIXED_AA_KEY)
                + data.getDouble(BeamMissileCompat.MIXED_AT_KEY_1)
                + data.getDouble(BeamMissileCompat.MIXED_AT_KEY_2);
    }
}

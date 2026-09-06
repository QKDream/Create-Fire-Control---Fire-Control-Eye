package com.qkdream.firecontrolcompat;

import com.verr1.taov.core.weaponhud.WeaponHudBinding;
import com.verr1.taov.core.weaponhud.WeaponHudBindingAccess;
import com.verr1.taov.core.weaponhud.WeaponHudSeatRef;
import com.verr1.taov.core.weaponhud.WeaponHudSourceDescriptor;
import com.verr1.taov.core.weaponhud.WeaponHudSources;
import com.verr1.taov.core.weaponhud.server.WeaponHudService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
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

    private static boolean sourcesRegistered;

    private MianbaoWeaponHud() {
    }

    public static void registerSources() {
        if (sourcesRegistered || !ModList.get().isLoaded("mianbaos_modernwarfare")) {
            return;
        }
        sourcesRegistered = true;

        // Missile launchers.
        register(MianbaosModernwarfareModBlockEntities.ANTIAIRMISSILELAUNCHERBLOCK);
        register(MianbaosModernwarfareModBlockEntities.ANTIAIRMISSILELAUNCHERLEFT);
        register(MianbaosModernwarfareModBlockEntities.ANTIAIRMISSILELAUNCHERRIGHT);
        register(MianbaosModernwarfareModBlockEntities.ANTITANKEMISSILELAUNCHER);
        register(MianbaosModernwarfareModBlockEntities.ANTITANKEMISSILELAUNCHERH_2);
        register(MianbaosModernwarfareModBlockEntities.ANTITANKMISSILELAUNCHERERXING_LEFT);
        register(MianbaosModernwarfareModBlockEntities.ANTITANKMISSILELAUNCHERERXING_RIGHT);
        register(MianbaosModernwarfareModBlockEntities.ANTITANKMISSILELAUNCHERSANXING);
        register(MianbaosModernwarfareModBlockEntities.ANTITANKMISSILELAUNCHERSIXING);
        register(MianbaosModernwarfareModBlockEntities.PORTABLEANTIAIRSYSTEM);
        register(MianbaosModernwarfareModBlockEntities.GROUNDMISSILELAUNCHERHEAD);
        register(MianbaosModernwarfareModBlockEntities.GROUNDMISSILELAUNCHERTAIL);

        // Aircraft weapon racks.
        register(MianbaosModernwarfareModBlockEntities.RACK);
        register(MianbaosModernwarfareModBlockEntities.AGM_MISSILE_1RACK);
        register(MianbaosModernwarfareModBlockEntities.AGM_MISSILE_2RACK);
        register(MianbaosModernwarfareModBlockEntities.AGM_MISSILE_3RACK);
        register(MianbaosModernwarfareModBlockEntities.AGM_MISSILE_4RACK);
        register(MianbaosModernwarfareModBlockEntities.AG_MMISSILEMIXED_RACK);
        register(MianbaosModernwarfareModBlockEntities.CLOSE_MISSILE_1RACK);
        register(MianbaosModernwarfareModBlockEntities.CLOSE_MISSILE_2RACK);
        register(MianbaosModernwarfareModBlockEntities.FAR_MISSILE_1RACK);
        register(MianbaosModernwarfareModBlockEntities.FAR_MISSILE_2RACK);
        register(MianbaosModernwarfareModBlockEntities.MEDIUM_MISSILE_RACK);
        register(MianbaosModernwarfareModBlockEntities.MEDIUM_AI_MMISSILE_2RACK);
        register(MianbaosModernwarfareModBlockEntities.LASER_GUIDED_MISSILE_RACK);
        register(MianbaosModernwarfareModBlockEntities.LASERGUIDEDMISSILESECONDRACK);
        register(MianbaosModernwarfareModBlockEntities.OPTICALGUIDEDMISSILERACK);
        register(MianbaosModernwarfareModBlockEntities.ANTIRADIATIONMISSILERACK);
        register(MianbaosModernwarfareModBlockEntities.CRUISEMISSILERACK);
        register(MianbaosModernwarfareModBlockEntities.NUCLEARCRUISEMISSILERACK);
        register(MianbaosModernwarfareModBlockEntities.JDAM_1RACK);
        register(MianbaosModernwarfareModBlockEntities.JDAM_2RACK);
        register(MianbaosModernwarfareModBlockEntities.BOMBRACK);
        register(MianbaosModernwarfareModBlockEntities.MEDIUM_BOMBRACK);
        register(MianbaosModernwarfareModBlockEntities.CLUSTER_BOMBRACK);
        register(MianbaosModernwarfareModBlockEntities.EARTHPENETRATORBOMBRACK);
        register(MianbaosModernwarfareModBlockEntities.FIRE_BOMBRACK);
        register(MianbaosModernwarfareModBlockEntities.HIGHRESISTANCEBOMBRACK);
        register(MianbaosModernwarfareModBlockEntities.HIGHEXPLOSIVEGUIDEDTORPEDORACK);
        register(MianbaosModernwarfareModBlockEntities.HIGHEXPLOSIVEVISUALGUIDEDTORPEDORACK);
    }

    private static void register(Supplier<? extends BlockEntityType<?>> holder) {
        BlockEntityType<?> type = holder.get();
        if (type == null) {
            return;
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
}

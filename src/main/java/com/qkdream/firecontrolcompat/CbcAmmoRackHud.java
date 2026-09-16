package com.qkdream.firecontrolcompat;

import com.cainiao1053.cbcmoreshells.blocks.ammo_rack.AmmoRackBlockEntity;
import com.cainiao1053.cbcmoreshells.index.CBCMSBlockEntities;
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
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.fml.ModList;

/**
 * Registers the CBCMS ammo racks (both the regular water-jacket rack and the
 * steel one, which share {@code cbcmoreshells:ammo_rack} block entities) as
 * TAOV weapon-HUD sources so the HUD-linker can bind them to a seat. CBCMS
 * block entities cannot implement TAOV's {@code WeaponHudBindable}, so seat
 * bindings are kept in a side table and persisted through the same
 * {@code saveWithFullMetadata}/{@code loadWithComponents} hooks used for the
 * Mianbao sources.
 */
public final class CbcAmmoRackHud {

    static final String BINDING_KEY = "firecontrolcompat:weapon_hud_binding_cbc";

    private static final Map<BlockEntity, WeaponHudBinding> BINDINGS =
            Collections.synchronizedMap(new IdentityHashMap<>());

    private static boolean sourcesRegistered;

    private CbcAmmoRackHud() {
    }

    public static void registerSources() {
        if (sourcesRegistered
                || !ModList.get().isLoaded("cbcmoreshells")
                || !ModList.get().isLoaded("taov_core")) {
            return;
        }
        sourcesRegistered = true;
        BlockEntityType<AmmoRackBlockEntity> type = CBCMSBlockEntities.AMMO_RACK.get();
        if (type == null) {
            return;
        }
        WeaponHudSources.register(new WeaponHudSourceDescriptor<>(
                type,
                AmmoRackBlockEntity.class,
                ResourceLocation.fromNamespaceAndPath(FireControlCompat.MOD_ID, "weapon_hud/cbc_ammo_rack"),
                90,
                new WeaponHudBindingAccess<>() {
                    @Override
                    public List<WeaponHudSeatRef> read(AmmoRackBlockEntity source) {
                        return readSeats(source);
                    }

                    @Override
                    public void write(AmmoRackBlockEntity source, List<WeaponHudSeatRef> seats) {
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
        if (!isAmmoRack(source)) {
            return;
        }
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
        if (!isAmmoRack(source) || !tag.contains(BINDING_KEY, Tag.TAG_LIST)) {
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

    private static boolean isAmmoRack(BlockEntity source) {
        return ModList.get().isLoaded("cbcmoreshells") && source instanceof AmmoRackBlockEntity;
    }
}

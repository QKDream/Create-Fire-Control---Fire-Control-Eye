package com.qkdream.firecontrolcompat.sonar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The man-portable set's sweep and the marks it hands to the client.
 *
 * <p>The sweep is the same listening pass the dish sets run, only shorter and
 * narrower: fifty blocks out, a ninety degree gate across and up, taken once
 * every three seconds. Everything the water is carrying inside that gate is
 * kept - a burner running, a gun going off, a hull pushing water, a creature
 * on the move, a torpedo swimming - and the list is handed to the client so
 * the marks can be drawn in the world.</p>
 */
public class ManportableSonarBlockEntity extends BlockEntity {

    /** How far the set looks, in blocks. */
    public static final double RANGE = 50.0D;
    /** Half of the gate it looks through: ninety degrees across and up. */
    public static final double HALF_ANGLE = 45.0D;
    /** Three seconds between two sweeps. */
    private static final int SWEEP_PERIOD = 60;
    /** Marks carried to the client, the nearest first. */
    private static final int MAX_MARKS = 24;

    /** Sets standing in the client's world, so the marks can be drawn. */
    private static final Set<ManportableSonarBlockEntity> CLIENT_SETS =
            Collections.newSetFromMap(new WeakHashMap<>());

    private List<SonarContact> contacts = List.of();

    public ManportableSonarBlockEntity(BlockPos pos, BlockState state) {
        super(SonarBlocks.MANPORTABLE_SONAR_BE.get(), pos, state);
    }

    /** Sets the client may draw marks for. */
    public static List<ManportableSonarBlockEntity> clientSets() {
        return new ArrayList<>(CLIENT_SETS);
    }

    @Override
    public void setLevel(Level level) {
        super.setLevel(level);
        if (level != null && level.isClientSide) {
            CLIENT_SETS.add(this);
        }
    }

    @Override
    public void setRemoved() {
        CLIENT_SETS.remove(this);
        super.setRemoved();
    }

    public static <T extends BlockEntity> void serverTick(Level level, BlockPos pos, BlockState state, T blockEntity) {
        if (!(blockEntity instanceof ManportableSonarBlockEntity set)) {
            return;
        }
        long phase = Math.floorMod(pos.asLong(), (long) SWEEP_PERIOD);
        if ((level.getGameTime() + phase) % SWEEP_PERIOD != 0L) {
            return;
        }
        Direction facing = state.getValue(HorizontalDirectionalBlock.FACING);
        Vec3 forward = new Vec3(facing.getStepX(), 0.0D, facing.getStepZ());
        Vec3 origin = Vec3.atCenterOf(pos);
        List<SonarContact> found = SonarScanner.scan(level, set, origin, forward, RANGE, HALF_ANGLE, HALF_ANGLE,
                HALF_ANGLE, false, Set.of(), List.of());
        if (found.size() > MAX_MARKS) {
            found = new ArrayList<>(found.subList(0, MAX_MARKS));
        }
        set.contacts = List.copyOf(found);
        set.setChanged();
        level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS);
    }

    public List<SonarContact> contacts() {
        return this.contacts;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ListTag marks = new ListTag();
        for (SonarContact contact : this.contacts) {
            CompoundTag entry = new CompoundTag();
            entry.putInt("Kind", contact.kind().ordinal());
            entry.putDouble("X", contact.position().x);
            entry.putDouble("Y", contact.position().y);
            entry.putDouble("Z", contact.position().z);
            entry.putInt("Echo", contact.echo());
            marks.add(entry);
        }
        tag.put("Marks", marks);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        List<SonarContact> marks = new ArrayList<>();
        ListTag list = tag.getList("Marks", Tag.TAG_COMPOUND);
        for (int index = 0; index < list.size(); index++) {
            CompoundTag entry = list.getCompound(index);
            marks.add(new SonarContact(SonarKind.byOrdinal(entry.getInt("Kind")),
                    new Vec3(entry.getDouble("X"), entry.getDouble("Y"), entry.getDouble("Z")),
                    entry.getInt("Echo")));
        }
        this.contacts = List.copyOf(marks);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return this.saveWithoutMetadata(registries);
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}

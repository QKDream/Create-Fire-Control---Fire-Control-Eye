package com.qkdream.firecontrolcompat.compat.yuushya;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import rbasamoyai.createbigcannons.cannons.big_cannons.BigCannonBehavior;
import rbasamoyai.createbigcannons.cannons.big_cannons.BigCannonBlock;
import rbasamoyai.createbigcannons.cannons.big_cannons.BigCannonBlockEntity;
import rbasamoyai.createbigcannons.cannons.big_cannons.IBigCannonBlockEntity;

/**
 * Cannon behaviour of a modelling block that is playing a big cannon barrel.
 *
 * <p>Create Big Cannons expects the behaviour to live on the block entity of
 * every cannon block. A modelling block entity is not a cannon block entity, so
 * this host is built next to it: it borrows the position, state and type of the
 * modelling block entity, owns the behaviour and is never part of a level
 * itself. Every query keeps the behaviour's connected faces in step with the
 * cannon blocks around the modelling block, which is what makes the barrel
 * click onto the rest of the cannon.</p>
 */
public final class YuushyaBigCannonBehaviorHost extends BigCannonBlockEntity {

    private final BlockEntity owner;

    public YuushyaBigCannonBehaviorHost(BlockEntity owner) {
        super((BlockEntityType) owner.getType(), owner.getBlockPos(), owner.getBlockState());
        this.owner = owner;
        if (owner.getLevel() != null) {
            this.setLevel(owner.getLevel());
        }
        this.initialize();
    }

    /** The behaviour, with the connected faces refreshed from the world first. */
    public BigCannonBehavior behavior() {
        if (this.owner.getLevel() != null && this.getLevel() != this.owner.getLevel()) {
            this.setLevel(this.owner.getLevel());
        }
        this.setBlockState(this.owner.getBlockState());
        this.synchronizeConnections();
        return this.cannonBehavior();
    }

    private void synchronizeConnections() {
        if (!(this.owner.getLevel() instanceof ServerLevel level)
                || !(this.owner.getBlockState().getBlock() instanceof BigCannonBlock ownBlock)
                || level.getBlockEntity(this.owner.getBlockPos()) != this.owner) {
            return;
        }
        BlockState ownState = this.owner.getBlockState();
        Axis axis = ownBlock.getFacing(ownState).getAxis();
        BigCannonBehavior behavior = this.cannonBehavior();
        if (behavior == null) {
            return;
        }
        for (Direction direction : Direction.values()) {
            boolean connected = false;
            if (direction.getAxis() == axis) {
                BlockPos neighborPos = this.owner.getBlockPos().relative(direction);
                BlockState neighborState = level.getBlockState(neighborPos);
                if (neighborState.getBlock() instanceof BigCannonBlock neighborBlock
                        && level.getBlockEntity(neighborPos) instanceof IBigCannonBlockEntity) {
                    connected = ownBlock.canConnectToSide(ownState, direction)
                            && neighborBlock.canConnectToSide(neighborState, direction.getOpposite())
                            && ownBlock.getCannonMaterialInLevel(level, ownState, this.owner.getBlockPos())
                                    == neighborBlock.getCannonMaterialInLevel(level, neighborState, neighborPos);
                }
            }
            behavior.setConnectedFace(direction, connected);
            if (!connected) {
                behavior.setWelded(direction, false);
            }
        }
    }

    @Override
    public void setChanged() {
        super.setChanged();
        this.owner.setChanged();
    }
}
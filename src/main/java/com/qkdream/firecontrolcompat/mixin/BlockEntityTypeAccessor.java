package com.qkdream.firecontrolcompat.mixin;

import com.qkdream.firecontrolcompat.BlockEntityTypeAccess;

import java.util.Set;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Lets a block of another mod join the block entity type of the modelling
 * blocks, so a block entity can be built for it. The set is only changed while
 * the game starts up, before any block entity exists.
 */
@Mixin(BlockEntityType.class)
public interface BlockEntityTypeAccessor extends BlockEntityTypeAccess {

    @Mutable
    @Accessor("validBlocks")
    void firecontrolcompat$setValidBlocks(Set<Block> blocks);
}

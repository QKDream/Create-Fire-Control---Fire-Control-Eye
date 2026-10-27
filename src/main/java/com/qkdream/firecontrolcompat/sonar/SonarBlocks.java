package com.qkdream.firecontrolcompat.sonar;

import com.qkdream.firecontrolcompat.FireControlCompat;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The sonar set and its block entity. The dish borrows the airborne phased
 * array radar's model, which is already in every pack this mod runs in, so the
 * hardware looks like the radar it is wired next to.
 */
public final class SonarBlocks {

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(FireControlCompat.MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(FireControlCompat.MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, FireControlCompat.MOD_ID);

    public static final DeferredBlock<SonarBlock> SONAR = BLOCKS.register("sonar",
            () -> new SonarBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .strength(2.5F)
                    .sound(SoundType.METAL)
                    .noOcclusion()));

    public static final DeferredItem<BlockItem> SONAR_ITEM = ITEMS.registerSimpleBlockItem("sonar", SONAR);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<SonarBlockEntity>> SONAR_BE =
            BLOCK_ENTITIES.register("sonar", () -> BlockEntityType.Builder
                    .of(SonarBlockEntity::new, SONAR.get())
                    .build(null));

    /** The tripod a squad carries: a short, narrow sweep and marks in the world. */
    public static final DeferredBlock<ManportableSonarBlock> MANPORTABLE_SONAR = BLOCKS.register("manportable_sonar",
            () -> new ManportableSonarBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .strength(1.5F)
                    .sound(SoundType.METAL)
                    .noOcclusion()));

    public static final DeferredItem<BlockItem> MANPORTABLE_SONAR_ITEM =
            ITEMS.registerSimpleBlockItem("manportable_sonar", MANPORTABLE_SONAR);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ManportableSonarBlockEntity>>
            MANPORTABLE_SONAR_BE = BLOCK_ENTITIES.register("manportable_sonar", () -> BlockEntityType.Builder
                    .of(ManportableSonarBlockEntity::new, MANPORTABLE_SONAR.get())
                    .build(null));

    private SonarBlocks() {
    }

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
        BLOCK_ENTITIES.register(bus);
    }
}

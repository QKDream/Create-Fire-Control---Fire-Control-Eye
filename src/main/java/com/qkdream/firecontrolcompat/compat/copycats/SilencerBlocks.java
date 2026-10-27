package com.qkdream.firecontrolcompat.compat.copycats;

import com.copycatsplus.copycats.CCCustomModels;
import com.copycatsplus.copycats.content.copycat.board.CopycatMultiBoardModelCore;
import com.copycatsplus.copycats.content.copycat.half_layer.CopycatMultiHalfLayerModelCore;
import com.copycatsplus.copycats.content.copycat.layer.CopycatLayerModelCore;
import com.copycatsplus.copycats.foundation.copycat.CCCopycatBlockEntity;
import com.copycatsplus.copycats.foundation.copycat.multistate.MultiStateCopycatBlockEntity;
import com.qkdream.firecontrolcompat.BeamMissileRegistry;
import com.qkdream.firecontrolcompat.FireControlCompat;
import com.qkdream.firecontrolcompat.SilencerShield;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The silencer tiles: camouflage blocks that swallow the noise made against
 * them.
 *
 * <p>Each of the three is one of the camouflage mod's own shapes - the board,
 * the layer and the half layer - built on that shape's block so the material,
 * the wrenching and the drawing are all the very same code, with only the
 * block entity type swapped for one of this mod's own. {@link SilencerShield}
 * then treats them as sound insulation: a noise made against a tile is never
 * handed to the sculk and never filed with the sonar sets, and an echo coming
 * back through one is read at a shorter reach.</p>
 *
 * <p>Everything here names the camouflage mod, so the whole package is only
 * part of the build while that mod is in the pack; the mod reaches it through
 * {@code SilencerSupport} so a pack without it still starts.</p>
 */
public final class SilencerBlocks {

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(FireControlCompat.MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(FireControlCompat.MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, FireControlCompat.MOD_ID);

    /** The three shapes the tiles are cut to, matching the camouflage mod's own. */
    public static final DeferredBlock<SilencerBoardBlock> SILENCER_BOARD =
            BLOCKS.register("silencer_board", () -> new SilencerBoardBlock(properties()));
    public static final DeferredBlock<SilencerLayerBlock> SILENCER_LAYER =
            BLOCKS.register("silencer_layer", () -> new SilencerLayerBlock(properties()));
    public static final DeferredBlock<SilencerHalfLayerBlock> SILENCER_HALF_LAYER =
            BLOCKS.register("silencer_half_layer", () -> new SilencerHalfLayerBlock(properties()));

    /**
     * Two block entities, exactly as the camouflage mod splits them: a board and
     * a half layer are read in parts and carry a material per part, while a
     * layer is one piece with one material on the plain copycat entity. Both
     * are the other mod's own entities, only tied to this mod's types, because
     * that is where the material a tile is dressed in lives.
     */
    private static final DeferredHolder<BlockEntityType<?>, BlockEntityType<SilencerBlockEntity>> SILENCER_BE =
            BLOCK_ENTITIES.register("silencer", () -> BlockEntityType.Builder
                    .of(SilencerBlockEntity::new, SILENCER_BOARD.get(), SILENCER_HALF_LAYER.get())
                    .build(null));

    private static final DeferredHolder<BlockEntityType<?>, BlockEntityType<SilencerLayerBlockEntity>> SILENCER_LAYER_BE =
            BLOCK_ENTITIES.register("silencer_layer", () -> BlockEntityType.Builder
                    .of(SilencerLayerBlockEntity::new, SILENCER_LAYER.get())
                    .build(null));

    public static final DeferredItem<BlockItem> SILENCER_BOARD_ITEM = tile("silencer_board", SILENCER_BOARD);
    public static final DeferredItem<BlockItem> SILENCER_LAYER_ITEM = tile("silencer_layer", SILENCER_LAYER);
    public static final DeferredItem<BlockItem> SILENCER_HALF_LAYER_ITEM =
            tile("silencer_half_layer", SILENCER_HALF_LAYER);

    private static final List<DeferredItem<BlockItem>> TILES =
            List.of(SILENCER_BOARD_ITEM, SILENCER_LAYER_ITEM, SILENCER_HALF_LAYER_ITEM);

    private SilencerBlocks() {
    }

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
        BLOCK_ENTITIES.register(bus);
        bus.addListener(SilencerBlocks::onCommonSetup);
        SilencerShield.install(state -> state.getBlock() instanceof SilencerBoardBlock
                || state.getBlock() instanceof SilencerLayerBlock
                || state.getBlock() instanceof SilencerHalfLayerBlock);
    }

    /** The part-read entity the board and the half layer share. */
    static BlockEntityType<? extends MultiStateCopycatBlockEntity> blockEntity() {
        return SILENCER_BE.get();
    }

    /** The single-piece entity the layer uses. */
    static BlockEntityType<? extends CCCopycatBlockEntity> layerBlockEntity() {
        return SILENCER_LAYER_BE.get();
    }

    /**
     * The camouflage mod draws its blocks from a model core of its own, and
     * that core has to be handed to the client for these shapes as well: it
     * reads the material out of the block entity and builds the quads, so
     * without it the tiles would simply not be drawn.
     */
    private static void onCommonSetup(FMLCommonSetupEvent event) {
        CCCustomModels.createBlockModel(CopycatMultiBoardModelCore::new).accept(SILENCER_BOARD.get());
        CCCustomModels.createBlockModel(CopycatLayerModelCore::new).accept(SILENCER_LAYER.get());
        CCCustomModels.createBlockModel(CopycatMultiHalfLayerModelCore::new).accept(SILENCER_HALF_LAYER.get());
    }

    /**
     * The tiles in the order the creative tab lists them: they are part of the
     * underwater set now, so the tab asks for them itself.
     */
    public static List<Item> tabItems() {
        List<Item> items = new ArrayList<>(TILES.size());
        for (DeferredItem<BlockItem> tile : TILES) {
            items.add(tile.get());
        }
        return items;
    }

    /** The same properties the camouflage mod gives its own blocks. */
    private static BlockBehaviour.Properties properties() {
        return BlockBehaviour.Properties.ofFullCopy(Blocks.GOLD_BLOCK)
                .mapColor(MapColor.METAL)
                .noOcclusion()
                .forceSolidOn();
    }

    private static DeferredItem<BlockItem> tile(String id, DeferredBlock<? extends Block> block) {
        return ITEMS.register(id, () -> new BlockItem(block.get(), new Item.Properties()) {
            @Override
            public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
                tooltip.add(Component.translatable("block.firecontrolcompat.silencer.desc").withStyle(ChatFormatting.GRAY));
                tooltip.add(Component.translatable("block.firecontrolcompat.silencer.hint").withStyle(ChatFormatting.DARK_GRAY));
            }
        });
    }
}

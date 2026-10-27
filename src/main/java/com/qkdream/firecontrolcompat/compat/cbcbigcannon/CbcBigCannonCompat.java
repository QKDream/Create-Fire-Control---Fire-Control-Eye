package com.qkdream.firecontrolcompat.compat.cbcbigcannon;

import com.qkdream.firecontrolcompat.FireControlCompat;
import com.qkdream.firecontrolcompat.BlockEntityTypeAccess;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import rbasamoyai.createbigcannons.index.CBCBlockEntities;
import rbasamoyai.createbigcannons.index.CBCMunitionPropertiesHandlers;
import rbasamoyai.createbigcannons.munitions.big_cannon.BigCannonProjectileRenderer;
import rbasamoyai.createbigcannons.munitions.big_cannon.FuzedBlockEntity;
import rbasamoyai.createbigcannons.munitions.config.MunitionPropertiesHandler;

/**
 * The CBC big cannon round this mod adds: the EMP shell.
 *
 * <p>It is a plain CBC shell in every mechanical way - big cannon only, fuzed,
 * tracer-able, loaded and flown by CBC's own code - so the only pieces needed
 * here are the block, its item, the projectile entity and the data pack entry
 * that describes the ballistics, plus the one call that lets the shell join
 * CBC's own block entity type, which is what a fuze is fitted through.</p>
 */
public final class CbcBigCannonCompat {

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(FireControlCompat.MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(FireControlCompat.MOD_ID);
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, FireControlCompat.MOD_ID);

    public static final DeferredBlock<EmpShellBlock> EMP_SHELL = BLOCKS.register("emp_shell",
            () -> new EmpShellBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_LIGHT_BLUE)
                    .strength(2.0F, 3.0F)
                    .sound(SoundType.STONE)
                    .noOcclusion()));

    public static final DeferredItem<EmpShellItem> EMP_SHELL_ITEM = ITEMS.register("emp_shell",
            () -> new EmpShellItem(EMP_SHELL.get(), new Item.Properties()));

    public static final DeferredHolder<EntityType<?>, EntityType<EmpShellProjectile>> EMP_SHELL_ENTITY =
            ENTITY_TYPES.register("emp_shell",
                    () -> EntityType.Builder.<EmpShellProjectile>of(EmpShellProjectile::new, MobCategory.MISC)
                            .sized(0.8F, 0.8F)
                            .clientTrackingRange(16)
                            .updateInterval(1)
                            .fireImmune()
                            .setShouldReceiveVelocityUpdates(false)
                            .build("emp_shell"));

    private CbcBigCannonCompat() {
    }

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
        ENTITY_TYPES.register(bus);
        bus.addListener(CbcBigCannonCompat::registerRenderers);
    }

    private static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(EMP_SHELL_ENTITY.get(), BigCannonProjectileRenderer::new);
    }

    /**
     * Lets the shell join CBC's fuzed block entity type.
     *
     * <p>Every fuzed big cannon shell is the same block entity, and CBC's type
     * lists only its own shells as valid blocks: a block entity built for a
     * shell that is not on that list throws the moment the shell is placed, and
     * the fuze could not be fitted to a shell lying on the ground either. Joining
     * the list buys the shell CBC's whole setup - the fuze slot, the ticker that
     * fires it on a redstone signal, the renderer and the Flywheel visual
     * that draw the fuze - instead of a second block entity type that would have
     * to be wired up for every one of them.</p>
     */
    public static void initialize() {
        BlockEntityType<FuzedBlockEntity> type = CBCBlockEntities.FUZED_BLOCK.get();
        Set<Block> validBlocks = new HashSet<>(type.getValidBlocks());
        if (!validBlocks.add(EMP_SHELL.get())) {
            return;
        }
        if (type instanceof BlockEntityTypeAccess access) {
            access.firecontrolcompat$setValidBlocks(validBlocks);
        } else {
            // The type is only an accessor when this mod\'s mixin reached it. With
            // the mixin switched off, or applied late, there is nothing to widen
            // and the setup must carry on rather than die on a cast.
            FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] block entity type left as it is (accessor mixin not applied)");
        }
    }

    /** Ties the shell to the data pack entry in {@code munition_properties/projectiles}. */
    public static void registerProjectileHandlers() {
        MunitionPropertiesHandler.registerProjectileHandler(EMP_SHELL_ENTITY.get(),
                CBCMunitionPropertiesHandlers.COMMON_SHELL_BIG_CANNON_PROJECTILE);
    }

    /** The shell is the last stack of the functional tab's shell section. */
    public static void addToCreativeTab(CreativeModeTab.Output output) {
        output.accept(EMP_SHELL_ITEM.get());
    }

    /** The same shell, for the tabs that are filled by event instead of by builder. */
    public static void addToCreativeTab(BuildCreativeModeTabContentsEvent event) {
        event.accept(EMP_SHELL_ITEM.get());
    }
}
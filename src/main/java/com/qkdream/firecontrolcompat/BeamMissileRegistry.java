package com.qkdream.firecontrolcompat;

import com.qkdream.firecontrolcompat.client.AircraftInfraredMissileRenderer;
import com.qkdream.firecontrolcompat.client.BeamMissileRenderer;
import com.qkdream.firecontrolcompat.entity.AircraftInfraredMissileEntity;
import com.qkdream.firecontrolcompat.entity.BeamRidingMissileEntity;
import com.qkdream.firecontrolcompat.entity.InfraredMissileEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Item + entity registrations for the beam-riding and infrared missiles. */
public final class BeamMissileRegistry {

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(FireControlCompat.MOD_ID);
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, FireControlCompat.MOD_ID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, FireControlCompat.MOD_ID);

    public static final DeferredHolder<Item, Item> BEAM_RIDING_MISSILE =
            ITEMS.register("beam_riding_missile",
                    () -> new FireControlMissileItem("item.firecontrolcompat.beam_riding_missile"));
    public static final DeferredHolder<Item, Item> INFRARED_MISSILE =
            ITEMS.register("infrared_missile",
                    () -> new FireControlMissileItem("item.firecontrolcompat.infrared_missile"));
    public static final DeferredHolder<Item, Item> AIRCRAFT_INFRARED_MISSILE =
            ITEMS.register("aircraft_infrared_missile",
                    () -> new FireControlMissileItem("item.firecontrolcompat.aircraft_infrared_missile"));

    public static final DeferredHolder<EntityType<?>, EntityType<BeamRidingMissileEntity>> BEAMRIDER_TANSHE =
            ENTITY_TYPES.register("beamrider_tanshe",
                    () -> EntityType.Builder.<BeamRidingMissileEntity>of(BeamRidingMissileEntity::new, MobCategory.MISC)
                            .sized(0.6F, 0.6F)
                            .clientTrackingRange(8)
                            .updateInterval(20)
                            .build("beamrider_tanshe"));

    public static final DeferredHolder<EntityType<?>, EntityType<InfraredMissileEntity>> INFRARED_TANSHE =
            ENTITY_TYPES.register("infrared_tanshe",
                    () -> EntityType.Builder.<InfraredMissileEntity>of(InfraredMissileEntity::new, MobCategory.MISC)
                            .sized(0.6F, 0.6F)
                            .clientTrackingRange(8)
                            .updateInterval(20)
                            .build("infrared_tanshe"));

    public static final DeferredHolder<EntityType<?>, EntityType<AircraftInfraredMissileEntity>> AIRCRAFT_INFRARED_TANSHE =
            ENTITY_TYPES.register("aircraft_infrared_tanshe",
                    () -> EntityType.Builder.<AircraftInfraredMissileEntity>of(AircraftInfraredMissileEntity::new, MobCategory.MISC)
                            .sized(0.6F, 0.6F)
                            .clientTrackingRange(8)
                            .updateInterval(20)
                            .build("aircraft_infrared_tanshe"));

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> FIRE_CONTROL_EYE_TAB =
            CREATIVE_TABS.register("fire_control_eye",
                    () -> CreativeModeTab.builder()
                            .title(Component.translatable("itemGroup.firecontrolcompat.fire_control_eye"))
                            .icon(() -> new ItemStack(BEAM_RIDING_MISSILE.get()))
                            .displayItems((parameters, output) -> {
                                output.accept(BEAM_RIDING_MISSILE.get());
                                output.accept(INFRARED_MISSILE.get());
                                output.accept(AIRCRAFT_INFRARED_MISSILE.get());
                            })
                            .build());

    private BeamMissileRegistry() {
    }

    public static void register(IEventBus bus) {
        ITEMS.register(bus);
        ENTITY_TYPES.register(bus);
        CREATIVE_TABS.register(bus);
        bus.addListener(BeamMissileRegistry::registerRenderers);
        bus.addListener(BeamMissileRegistry::addCreativeTabItems);
    }

    private static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(BEAMRIDER_TANSHE.get(), BeamMissileRenderer::new);
        event.registerEntityRenderer(INFRARED_TANSHE.get(), BeamMissileRenderer::new);
        event.registerEntityRenderer(AIRCRAFT_INFRARED_TANSHE.get(), AircraftInfraredMissileRenderer::new);
    }

    private static void addCreativeTabItems(BuildCreativeModeTabContentsEvent event) {
        ResourceLocation key = event.getTabKey().location();
        if (key.equals(ResourceLocation.withDefaultNamespace("combat"))
                || key.equals(ResourceLocation.fromNamespaceAndPath("mianbaos_modernwarfare", "mianbaos_modern_warfare"))) {
            event.accept(BEAM_RIDING_MISSILE.get());
            event.accept(INFRARED_MISSILE.get());
            event.accept(AIRCRAFT_INFRARED_MISSILE.get());
        }
    }
}

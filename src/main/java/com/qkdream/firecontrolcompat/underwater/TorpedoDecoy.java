package com.qkdream.firecontrolcompat.underwater;

import com.qkdream.firecontrolcompat.FireControlCompat;
import net.minecraft.client.renderer.entity.ThrownItemRenderer;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The soft-kill torpedo decoy: 面包学's anti-infantry grenade carrying a noise
 * maker where its charge used to be.
 *
 * <p>It is loaded into, and thrown by, the anti-infantry grenade launcher
 * itself - see {@link TorpedoDecoyLauncher} for how a drum that only counts its
 * rounds is made to remember what kind they are. What comes out is this: the
 * launcher's own lob, drawn with the launcher's own round, and nothing that
 * goes off.</p>
 *
 * <p>What it does instead is make the loudest noise the water knows, once a
 * second, in the water where it lands. A passive homing torpedo hears it three
 * hundred blocks off and turns onto it, and the sculk network hears it too, so
 * a warden comes over to see. On the sonar it paints like any other source of
 * its weight: a heavy one.</p>
 */
public final class TorpedoDecoy {

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(FireControlCompat.MOD_ID);
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, FireControlCompat.MOD_ID);

    public static final DeferredItem<Item> TORPEDO_DECOY_ITEM =
            ITEMS.register("torpedo_decoy", TorpedoDecoyItem::new);

    public static final DeferredHolder<EntityType<?>, EntityType<TorpedoDecoyEntity>> TORPEDO_DECOY =
            ENTITY_TYPES.register("torpedo_decoy",
                    () -> EntityType.Builder.<TorpedoDecoyEntity>of(TorpedoDecoyEntity::new, MobCategory.MISC)
                            .sized(0.25F, 0.25F)
                            .clientTrackingRange(8)
                            .updateInterval(1)
                            .build("torpedo_decoy"));

    private TorpedoDecoy() {
    }

    public static void register(IEventBus bus) {
        ITEMS.register(bus);
        ENTITY_TYPES.register(bus);
        bus.addListener(TorpedoDecoy::registerRenderers);
    }

    /** Drawn the way 面包学 draws its own grenades, with the round the launcher fires. */
    private static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(TORPEDO_DECOY.get(), ThrownItemRenderer::new);
    }
}

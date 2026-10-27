package com.qkdream.firecontrolcompat.deco;

import com.qkdream.firecontrolcompat.BeamMissileRegistry;
import com.qkdream.firecontrolcompat.FireControlCompat;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Section banners for the two Fire Control Eye creative tabs.
 *
 * <p>A tab that is laid out in sections reserves one blank row in front of each
 * one: {@code CreativeModeTabBannerGapMixin} inserts those rows into the built
 * contents and {@code DecoBannerOverlay} paints the 162x18 sprite plus the
 * section title on top of them - the same layout the Simulated mod uses. The
 * decorations tab is split into Blue Archive and Warhammer 40K, the functional
 * tab into the missile family and the shell family.</p>
 */
public final class DecoRegistry {

    /** Creative tab title ("Fire Control Eye: Decorations"). */
    public static final String DECO_TAB_KEY = "itemGroup.firecontrolcompat.fire_control_eye_deco";

    /** Columns of the creative item picker; sections are padded to whole rows with it. */
    public static final int GRID_COLUMNS = 9;

    /** Sections of the decoration tab, in display order; every section owns one banner row. */
    public static final Sections DECO_SECTIONS = new Sections(
            new String[] {
                    "itemGroup.firecontrolcompat.blue_archive_section",
                    "itemGroup.firecontrolcompat.warhammer_section",
            },
            new String[] {"bluearchive_banner", "warhammer_banner"});

    /**
     * Sections of the functional tab: the whole missile family under the missile
     * banner, the medium cannon shells this mod adds under the shell banner.
     * Their counts have to match the order the tab lists its items in.
     */
    public static final Sections WEAPON_SECTIONS = new Sections(
            new String[] {
                    "itemGroup.firecontrolcompat.missile_section",
                    "itemGroup.firecontrolcompat.shell_section",
                    "itemGroup.firecontrolcompat.underwater_section",
            },
            new String[] {"missile_banner", "shell_banner", "underwater_banner"});

    /**
     * Missiles shown under the missile banner: the five missile stacks and the airborne strike
     * terminal, which is aircraft equipment and belongs on this line.
     *
     * <p><b>Six, not seven (2026-10-26).</b> The count has to be the number of stacks the tab
     * actually lists, because {@code CreativeModeTabBannerGapMixin} slices the built contents with
     * it. Two rounds have been withdrawn since this figure was set - the semi-active air-to-air
     * round and the airborne infrared round - so the tab lists six and the figure said seven. One
     * item too many does not shift the last section: it shifts <b>every</b> section after it, which
     * is exactly what was reported - the semi-armour-piercing warhead slid up under the missile
     * banner and the sonar set slid into the shell section, leaving the underwater banner one row
     * short.</p>
     */
    private static final int MISSILE_ROWS = 6;
    /**
     * Shells shown under the shell banner: the four medium cannon rounds with their
     * cartridges plus the EMP shell, which fills the section's first row exactly.
     */
    private static final int SHELL_ROWS = 9;
    /**
     * Under the underwater banner: the sonar set, the three homing torpedoes -
     * one of them the air-dropped round - the decoy, the man-portable set and
     * the three silencer tiles.
     */
    private static final int UNDERWATER_ROWS = 9;

    /** Mod id of the optional modelling mod this tab is soft compatible with. */
    private static final String YUUSHYA = "yuushya_modelling";
    private static final ResourceLocation YUUSHYA_TAB = ResourceLocation.fromNamespaceAndPath("yuushya", "modelling");

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(FireControlCompat.MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(FireControlCompat.MOD_ID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, FireControlCompat.MOD_ID);

    private static final List<DeferredItem<BlockItem>> DECO_ITEMS = new ArrayList<>();

    static {
        WEAPON_SECTIONS.setCount(0, MISSILE_ROWS);
        WEAPON_SECTIONS.setCount(1, SHELL_ROWS);
        WEAPON_SECTIONS.setCount(2, UNDERWATER_ROWS);
    }

    /*
     * Blue Archive section. Declaration order is the order of the tab, and the
     * counters above have to exist before the first registerDeco call runs.
     */
    public static final DeferredBlock<DecoBlock> ABYDOS = registerDeco("abydos", 0);
    public static final DeferredBlock<DecoBlock> MILLENNIUM = registerDeco("millennium", 0);
    public static final DeferredBlock<DecoBlock> TRINITY = registerDeco("trinity", 0);
    public static final DeferredBlock<DecoBlock> SCHALE = registerDeco("schale", 0);
    public static final DeferredBlock<DecoBlock> SHANHAIJING = registerDeco("shanhaijing", 0);
    public static final DeferredBlock<DecoBlock> RED_WINTER = registerDeco("red_winter", 0);
    public static final DeferredBlock<DecoBlock> GEHENNA = registerDeco("gehenna", 0);
    public static final DeferredBlock<DecoBlock> HYAKKIYAKO = registerDeco("hyakkiyako", 0);
    public static final DeferredBlock<DecoBlock> ARIUS = registerDeco("arius", 0);
    public static final DeferredBlock<DecoBlock> ODYSSEY = registerDeco("odyssey", 0);
    public static final DeferredBlock<DecoBlock> HIGHLANDER = registerDeco("highlander", 0);
    public static final DeferredBlock<DecoBlock> WILD_HUNT = registerDeco("wild_hunt", 0);
    public static final DeferredBlock<DecoBlock> VALKYRIE = registerDeco("valkyrie", 0);

    /* Warhammer 40K section. */
    public static final DeferredBlock<DecoBlock> DARK_ANGELS = registerDeco("dark_angels", 1);
    public static final DeferredBlock<DecoBlock> ULTRAMARINES = registerDeco("ultramarines", 1);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> DECO_TAB =
            CREATIVE_TABS.register("fire_control_eye_deco", () -> CreativeModeTab.builder()
                    .title(Component.translatable(DECO_TAB_KEY))
                    .icon(() -> new ItemStack(ABYDOS.get()))
                    .displayItems((parameters, output) -> {
                        for (DeferredItem<BlockItem> item : DECO_ITEMS) {
                            output.accept(item.get());
                        }
                    })
                    .build());

    private DecoRegistry() {
    }

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
        CREATIVE_TABS.register(bus);
        bus.addListener(DecoRegistry::addToModellingTab);
    }

    /** The decoration tab, for the banner overlay and the banner row mixin. */
    public static CreativeModeTab decoTab() {
        return DECO_TAB.get();
    }

    /** Banner sections of the given tab, or null when it is not laid out in sections. */
    public static Sections sectionsFor(CreativeModeTab tab) {
        if (tab == null) {
            return null;
        }
        if (tab == decoTab()) {
            return DECO_SECTIONS;
        }
        try {
            return tab == BeamMissileRegistry.FIRE_CONTROL_EYE_TAB.get() ? WEAPON_SECTIONS : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Soft dependency on the modelling mod: when it is installed the same
     * blocks also show up in its creative tab. Nothing is loaded from that mod,
     * so the game keeps working when it is absent.
     */
    private static void addToModellingTab(BuildCreativeModeTabContentsEvent event) {
        if (!event.getTabKey().location().equals(YUUSHYA_TAB) || !FireControlCompat.isModLoaded(YUUSHYA)) {
            return;
        }
        for (DeferredItem<BlockItem> item : DECO_ITEMS) {
            event.accept(item.get());
        }
    }

    private static DeferredBlock<DecoBlock> registerDeco(String id, int section) {
        DeferredBlock<DecoBlock> block = BLOCKS.register(id, () -> new DecoBlock(BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_LIGHT_BLUE)
                .strength(0.5F)
                .sound(SoundType.WOOD)
                .noOcclusion()));
        DECO_ITEMS.add(ITEMS.registerSimpleBlockItem(id, block));
        DECO_SECTIONS.addCount(section);
        return block;
    }

    /**
     * The banner rows of one creative tab: the title and sprite of every
     * section, and how many stacks the tab lists under it. The counts have to be
     * filled in the same order the tab displays its items, because the banner
     * row mixin slices the built content list with them.
     */
    public static final class Sections {

        private final String[] titleKeys;
        private final String[] sprites;
        private final int[] itemCounts;

        private Sections(String[] titleKeys, String[] sprites) {
            this.titleKeys = titleKeys;
            this.sprites = sprites;
            this.itemCounts = new int[titleKeys.length];
        }

        /** Number of sections, one banner row each. */
        public int count() {
            return this.titleKeys.length;
        }

        /** Translation key of the title painted on a section banner. */
        public String titleKey(int section) {
            return this.titleKeys[section];
        }

        /** Sprite drawn behind the title of a section banner. */
        public ResourceLocation sprite(int section) {
            return ResourceLocation.fromNamespaceAndPath(FireControlCompat.MOD_ID, this.sprites[section]);
        }

        /** Number of items registered for one section, in registration order. */
        public int itemCount(int section) {
            return this.itemCounts[section];
        }

        /**
         * Grid row (counted from the top of the tab contents) the banner of one
         * section is painted on - it is the blank row that opens the section.
         */
        public int bannerRow(int section) {
            int row = 0;
            for (int previous = 0; previous < section; previous++) {
                row += 1 + (this.itemCounts[previous] + GRID_COLUMNS - 1) / GRID_COLUMNS;
            }
            return row;
        }

        private void addCount(int section) {
            this.itemCounts[section]++;
        }

        private void setCount(int section, int value) {
            this.itemCounts[section] = value;
        }
    }
}

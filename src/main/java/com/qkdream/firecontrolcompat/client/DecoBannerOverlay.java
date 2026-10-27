package com.qkdream.firecontrolcompat.client;

import com.qkdream.firecontrolcompat.FireControlCompat;
import com.qkdream.firecontrolcompat.deco.DecoRegistry;
import java.lang.reflect.Field;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * Paints the section banners of the decoration tab.
 *
 * <p>Vanilla never draws anything but item slots, so the overlay hooks the
 * public NeoForge screen render event (no vanilla mixin is involved) and draws
 * every 162x18 sprite over the blank row its section reserves at the top. The
 * two private vanilla fields it reads - the selected tab and the scroll offset -
 * are read reflectively and fall back to "no banner" when they cannot be
 * resolved, so a future Minecraft update degrades instead of crashing.</p>
 */
@EventBusSubscriber(modid = FireControlCompat.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class DecoBannerOverlay {

    private static final int BANNER_WIDTH = 162;
    private static final int BANNER_HEIGHT = 18;
    private static final int PANEL_WIDTH = 195;
    private static final int PANEL_HEIGHT = 136;
    private static final int GRID_COLUMNS = 9;
    private static final int VISIBLE_ROWS = 5;

    private static Field selectedTabField;
    private static Field scrollOffsField;

    private DecoBannerOverlay() {
    }

    @SubscribeEvent
    public static void onScreenRender(ScreenEvent.Render.Post event) {
        if (!(event.getScreen() instanceof CreativeModeInventoryScreen screen)) {
            return;
        }
        DecoRegistry.Sections sections = DecoRegistry.sectionsFor(selectedTab());
        if (sections == null) {
            return;
        }
        GuiGraphics graphics = event.getGuiGraphics();
        int left = (screen.width - PANEL_WIDTH) / 2;
        int top = (screen.height - PANEL_HEIGHT) / 2;
        int firstVisibleRow = firstVisibleRow(screen);
        for (int section = 0; section < sections.count(); section++) {
            int visibleRow = sections.bannerRow(section) - firstVisibleRow;
            if (visibleRow < 0 || visibleRow >= VISIBLE_ROWS) {
                continue;
            }
            int y = top + 17 + visibleRow * 18;
            graphics.blitSprite(sections.sprite(section), left + 8, y, BANNER_WIDTH, BANNER_HEIGHT);
            graphics.drawString(Minecraft.getInstance().font,
                    Component.translatable(sections.titleKey(section)), left + 13, y + 5, 0xFFFFFF, true);
        }
    }

    private static CreativeModeTab selectedTab() {
        try {
            if (selectedTabField == null) {
                selectedTabField = CreativeModeInventoryScreen.class.getDeclaredField("selectedTab");
                selectedTabField.setAccessible(true);
            }
            return selectedTabField.get(null) instanceof CreativeModeTab tab ? tab : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Mirrors {@code ItemPickerMenu#getRowIndexForScroll} for the visible grid. */
    private static int firstVisibleRow(CreativeModeInventoryScreen screen) {
        int itemCount = screen.getMenu() == null ? 0 : screen.getMenu().items.size();
        int rowCount = (itemCount + GRID_COLUMNS - 1) / GRID_COLUMNS - VISIBLE_ROWS;
        if (rowCount <= 0) {
            return 0;
        }
        try {
            if (scrollOffsField == null) {
                scrollOffsField = CreativeModeInventoryScreen.class.getDeclaredField("scrollOffs");
                scrollOffsField.setAccessible(true);
            }
            float scroll = scrollOffsField.getFloat(screen);
            return Math.max((int) (scroll * (float) rowCount + 0.5F), 0);
        } catch (Throwable ignored) {
            return 0;
        }
    }
}

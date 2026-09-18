package com.qkdream.firecontrolcompat.client;

import com.qkdream.firecontrolcompat.iff.IffTransponderMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** Frequency band screen: two item slots define the identity code. */
public class IffTransponderScreen extends AbstractContainerScreen<IffTransponderMenu> {

    private static final int PANEL_DARK = 0xFF2B2B2B;
    private static final int PANEL_BRASS = 0xFF4A3B22;
    private static final int PANEL_INNER = 0xFF6B5533;
    private static final int SLOT_FRAME = 0xFF2B2B2B;
    private static final int SLOT_FILL = 0xFF9E8B66;
    private static final int INVENTORY_FILL = 0xFF5C4A2B;
    private static final int TEXT_TITLE = 0xFFFFE7A8;
    private static final int TEXT_HINT = 0xFFC8B490;

    public IffTransponderScreen(IffTransponderMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = 176;
        this.imageHeight = 166;
        this.inventoryLabelY = this.imageHeight - 94;
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = this.leftPos;
        int y = this.topPos;
        graphics.fill(x, y, x + this.imageWidth, y + this.imageHeight, PANEL_DARK);
        graphics.fill(x + 1, y + 1, x + this.imageWidth - 1, y + this.imageHeight - 1, PANEL_BRASS);
        graphics.fill(x + 6, y + 18, x + this.imageWidth - 6, y + 70, PANEL_INNER);
        this.bandSlot(graphics, x + 44, y + 35);
        this.bandSlot(graphics, x + 80, y + 35);
        graphics.fill(x + 6, y + 80, x + this.imageWidth - 6, y + this.imageHeight - 6, INVENTORY_FILL);
    }

    private void bandSlot(GuiGraphics graphics, int x, int y) {
        graphics.fill(x - 1, y - 1, x + 17, y + 17, SLOT_FRAME);
        graphics.fill(x, y, x + 16, y + 16, SLOT_FILL);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(this.font, this.title, this.titleLabelX, this.titleLabelY, TEXT_TITLE, false);
        graphics.drawString(
                this.font,
                Component.translatable("gui.firecontrolcompat.iff_band"),
                44,
                22,
                TEXT_TITLE,
                false);
        graphics.drawString(
                this.font,
                Component.translatable("gui.firecontrolcompat.iff_band_hint"),
                8,
                58,
                TEXT_HINT,
                false);
        graphics.drawString(
                this.font, this.playerInventoryTitle, this.inventoryLabelX, this.inventoryLabelY, TEXT_HINT, false);
    }
}
package com.qkdream.firecontrolcompat.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * Brass-panel toggle button matching the visual style of the fire control
 * computer screens, used by the compat lead settings section.
 */
public class CompatToggleButton extends Button {

    public enum Tone {
        NORMAL,
        GREEN,
        RED
    }

    private Tone tone = Tone.NORMAL;

    public CompatToggleButton(int x, int y, int width, int height, Component message, OnPress onPress) {
        super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
    }

    public CompatToggleButton setTone(Tone tone) {
        this.tone = tone;
        return this;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int background = switch (this.tone) {
            case NORMAL -> this.isHovered() ? -8949660 : -10528176;
            case GREEN -> this.isHovered() ? -10190502 : -11506615;
            case RED -> this.isHovered() ? -7708072 : -9417145;
        };
        if (!this.active) {
            background = -11909054;
        }
        graphics.fill(this.getX(), this.getY(), this.getX() + this.width, this.getY() + this.height, -13620186);
        graphics.fill(this.getX() + 1, this.getY() + 1, this.getX() + this.width - 1, this.getY() + this.height - 1, -4681667);
        graphics.fill(this.getX() + 2, this.getY() + 2, this.getX() + this.width - 2, this.getY() + this.height - 2, background);
        graphics.fill(this.getX() + 3, this.getY() + 3, this.getX() + this.width - 3, this.getY() + 4, 905969663);
        graphics.enableScissor(this.getX() + 4, this.getY() + 2, this.getX() + this.width - 4, this.getY() + this.height - 2);
        Font font = Minecraft.getInstance().font;
        graphics.drawString(
                font,
                this.getMessage(),
                this.getX() + (this.width - font.width(this.getMessage())) / 2,
                this.getY() + (this.height - 8) / 2,
                this.active ? -726059 : -6646390,
                false
        );
        graphics.disableScissor();
    }
}
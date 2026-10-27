package com.qkdream.firecontrolcompat.client;

import com.qkdream.firecontrolcompat.FireControlLeadSettings;
import com.qkdream.firecontrolcompat.network.TorpedoSettingsPayload;
import com.qkdream.firecontrolcompat.sonar.TorpedoSettingsState;
import java.util.Locale;
import java.util.UUID;
import java.util.function.DoubleConsumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The acoustic torpedo page of the sonar settings, reached from the button on
 * the sonar page.
 *
 * <p>What is written here is filed on the fire control computer the page was
 * opened from, and applies to every round that computer's tubes fire: what an
 * idle round does while it has nothing to chase - come up and patrol on the
 * surface, or be held at a depth - and how far a round swims before its
 * hydrophones are switched on, which is what keeps a launch beside a working
 * deck from turning round and taking the ship that fired it.</p>
 *
 * <p>Every computer keeps its own answer, so one boat can hold her rounds deep
 * and deaf for a hundred blocks while the next lets hers run along the surface
 * with their ears on from the muzzle. A tube wired to no computer keeps the
 * plain values these boxes start at.</p>
 */
public class TorpedoSettingsScreen extends Screen {

    private static final int PANEL_WIDTH = 320;
    private static final int PANEL_HEIGHT = 212;
    private static final int CONTENT_LEFT = 20;
    private static final int CONTENT_WIDTH = 280;
    private static final int CRUISE_TOP = 50;
    private static final int DEPTH_TOP = 84;
    private static final int ACTIVATION_TOP = 114;
    private static final int HINT_TOP = 142;
    private static final int FOOTER_TOP = 176;
    private static final int BUTTON_WIDTH = 136;
    private static final int FIELD_WIDTH = 70;

    private static final int OUTLINE = -13357272;
    private static final int BRASS = -4681667;
    private static final int INNER_BORDER = -9017782;
    private static final int BACKGROUND = -858162;
    private static final int HEADER = -9739950;
    private static final int LABEL = -12765137;
    private static final int WHITE = 16777215;
    private static final int DIMMED = -8355712;

    private final Screen parent;
    private final BlockPos controllerPos;
    private final UUID controllerSubLevel;

    /** What the boxes hold until the page is saved. */
    private boolean holdDepth;
    private double depth;
    private double activation;

    private CompatToggleButton cruiseButton;
    private EditBox depthBox;
    private EditBox activationBox;

    private int panelLeft;
    private int panelTop;
    /** Panel size actually used, which is the design size only on a large window. */
    private int panelWidth = PANEL_WIDTH;
    private int panelHeight = PANEL_HEIGHT;
    /** How far the content is scrolled down, in pixels. */
    private int scroll;
    /** Scrollbar track and thumb, laid out while the panel is drawn. */
    private int barX;
    private int barTop;
    private int barHeight;
    private int thumbTop;
    private int thumbHeight = 1;
    private boolean draggingBar;

    public TorpedoSettingsScreen(Screen parent, BlockPos controllerPos, UUID controllerSubLevel) {
        super(Component.translatable("gui.firecontrolcompat.torpedo_page"));
        this.parent = parent;
        this.controllerPos = controllerPos;
        this.controllerSubLevel = controllerSubLevel;
        this.holdDepth = TorpedoSettingsState.holdDepth();
        this.depth = TorpedoSettingsState.depth();
        this.activation = TorpedoSettingsState.activation();
    }

    @Override
    protected void init() {
        // The panel is clamped to the window rather than centred at a fixed size:
        // on a small window or a large GUI scale a fixed panel runs off the screen
        // and the rows past its bottom cannot be reached at all.
        this.panelWidth = Math.min(PANEL_WIDTH, Math.max(160, this.width - 8));
        this.panelHeight = Math.min(PANEL_HEIGHT, Math.max(96, this.height - 8));
        this.panelLeft = Math.max(0, (this.width - this.panelWidth) / 2);
        this.panelTop = Math.max(2, (this.height - this.panelHeight) / 2);
        this.scroll = net.minecraft.util.Mth.clamp(this.scroll, 0, this.maxScroll());
        int left = this.panelLeft + CONTENT_LEFT;
        int boxLeft = left + CONTENT_WIDTH - FIELD_WIDTH;

        this.cruiseButton = new CompatToggleButton(
                left, this.cy(CRUISE_TOP), CONTENT_WIDTH, 20, this.cruiseText(), button -> {
                    this.holdDepth = !this.holdDepth;
                    button.setMessage(this.cruiseText());
                    ((CompatToggleButton) button)
                            .setTone(this.holdDepth ? CompatToggleButton.Tone.GREEN : CompatToggleButton.Tone.NORMAL);
                });
        this.cruiseButton.setTone(this.holdDepth ? CompatToggleButton.Tone.GREEN : CompatToggleButton.Tone.NORMAL);
        this.addRenderableWidget(this.cruiseButton);

        this.depthBox = this.numberBox(boxLeft, this.cy(DEPTH_TOP), this.depth, value -> {
            if (value >= FireControlLeadSettings.MIN_TORPEDO_DEPTH
                    && value <= FireControlLeadSettings.MAX_TORPEDO_DEPTH) {
                this.depth = value;
            }
        });
        this.activationBox = this.numberBox(boxLeft, this.cy(ACTIVATION_TOP), this.activation, value -> {
            if (value >= FireControlLeadSettings.MIN_TORPEDO_ACTIVATION
                    && value <= FireControlLeadSettings.MAX_TORPEDO_ACTIVATION) {
                this.activation = value;
            }
        });

        this.addRenderableWidget(new CompatToggleButton(
                left, this.cy(FOOTER_TOP), BUTTON_WIDTH, 20,
                Component.translatable("gui.done"), button -> this.saveAndClose())
                .setTone(CompatToggleButton.Tone.GREEN));
        this.addRenderableWidget(new CompatToggleButton(
                left + CONTENT_WIDTH - BUTTON_WIDTH, this.cy(FOOTER_TOP), BUTTON_WIDTH, 20,
                Component.translatable("gui.back"), button -> this.onClose()));
    }

    /** The word on the cruise button: what an idle round will be doing. */
    private Component cruiseText() {
        return Component.translatable(
                "gui.firecontrolcompat.torpedo_cruise",
                Component.translatable(this.holdDepth
                        ? "gui.firecontrolcompat.torpedo_cruise_depth"
                        : "gui.firecontrolcompat.torpedo_cruise_surface"));
    }

    private EditBox numberBox(int x, int y, double initial, DoubleConsumer sink) {
        String text = format(initial);
        EditBox box = new EditBox(this.font, x, y, FIELD_WIDTH, 18, Component.literal(text));
        box.setMaxLength(7);
        box.setValue(text);
        box.setResponder(value -> {
            try {
                sink.accept(Double.parseDouble(value.trim()));
            } catch (NumberFormatException ignored) {
            }
        });
        this.addRenderableWidget(box);
        return box;
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int right = this.panelLeft + this.panelWidth;
        int bottom = this.panelTop + this.panelHeight;
        graphics.fill(this.panelLeft + 4, this.panelTop + 5, right + 5, bottom + 6, -1879048192);
        graphics.fill(this.panelLeft, this.panelTop, right, bottom, OUTLINE);
        graphics.fill(this.panelLeft + 2, this.panelTop + 2, right - 2, bottom - 2, BRASS);
        graphics.fill(this.panelLeft + 5, this.panelTop + 5, right - 5, bottom - 5, INNER_BORDER);
        graphics.fill(this.panelLeft + 7, this.panelTop + 28, right - 7, bottom - 7, BACKGROUND);
        graphics.fill(this.panelLeft + 7, this.panelTop + 7, right - 7, this.panelTop + 27, HEADER);
        graphics.fill(this.panelLeft + 8, this.panelTop + 27, right - 8, this.panelTop + 29, -7048138);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, this.panelTop + 12, WHITE);
        // Everything below the header is content: clipped to the panel so a scrolled
        // row never draws over the frame or outside the window.
        graphics.enableScissor(this.panelLeft + 7, this.panelTop + 29, right - 7, bottom - 7);

        int left = this.panelLeft + CONTENT_LEFT;
        graphics.drawString(this.font, Component.translatable("gui.firecontrolcompat.torpedo_depth"),
                left, this.cy(DEPTH_TOP) + 5, LABEL, false);
        graphics.drawString(this.font, Component.translatable("gui.firecontrolcompat.torpedo_activation"),
                left, this.cy(ACTIVATION_TOP) + 5, LABEL, false);
        graphics.drawString(this.font, Component.translatable("gui.firecontrolcompat.torpedo_hint"),
                left, this.cy(HINT_TOP), DIMMED, false);
        graphics.drawString(this.font, Component.translatable("gui.firecontrolcompat.torpedo_note"),
                left, this.cy(HINT_TOP) + 11, DIMMED, false);

        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.disableScissor();
        this.renderScrollbar(graphics, right, bottom);
    }

    /** A content row's screen y: the panel top, the row's own offset, less the scroll. */
    private int cy(int top) {
        return this.panelTop + top - this.scroll;
    }

    /** How tall the content is, footer included. */
    private int contentHeight() {
        return FOOTER_TOP + 26;
    }

    /** How far the content may be scrolled before its bottom is on screen. */
    private int maxScroll() {
        return Math.max(0, this.contentHeight() - this.panelHeight);
    }

    /** Scrolls to a position, clamped, and moves the widgets with it. */
    private void setScroll(int value) {
        int clamped = net.minecraft.util.Mth.clamp(value, 0, this.maxScroll());
        if (clamped == this.scroll) {
            return;
        }
        this.scroll = clamped;
        this.rebuildWidgets();
    }

    /** Track and thumb on the panel's right edge, drawn only when there is more content. */
    private void renderScrollbar(GuiGraphics graphics, int right, int bottom) {
        int max = this.maxScroll();
        this.barX = right - 6;
        this.barTop = this.panelTop + 31;
        this.barHeight = Math.max(1, bottom - 7 - this.barTop);
        if (max <= 0) {
            this.thumbTop = this.barTop;
            this.thumbHeight = this.barHeight;
            return;
        }
        this.thumbHeight = Math.max(16, this.barHeight * this.panelHeight / Math.max(1, this.contentHeight()));
        this.thumbTop = this.barTop + (this.barHeight - this.thumbHeight) * this.scroll / max;
        graphics.fill(this.barX, this.barTop, this.barX + 3, this.barTop + this.barHeight, -1442840576);
        graphics.fill(this.barX, this.thumbTop, this.barX + 3, this.thumbTop + this.thumbHeight, -7048138);
    }

    /** Whether a point is over the panel, which is where the wheel scrolls. */
    private boolean insidePanel(double mouseX, double mouseY) {
        return mouseX >= this.panelLeft && mouseX < this.panelLeft + this.panelWidth
                && mouseY >= this.panelTop && mouseY < this.panelTop + this.panelHeight;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (this.maxScroll() > 0 && scrollY != 0.0D && this.insidePanel(mouseX, mouseY)) {
            this.setScroll(this.scroll - (int) Math.round(scrollY * 24.0D));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && this.maxScroll() > 0
                && mouseX >= this.barX - 2 && mouseX < this.barX + 5
                && mouseY >= this.barTop && mouseY < this.barTop + this.barHeight) {
            this.draggingBar = true;
            this.dragScrollbar(mouseY);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.draggingBar) {
            this.dragScrollbar(mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (this.draggingBar) {
            this.draggingBar = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    /** Puts the scroll where the thumb was dragged to. */
    private void dragScrollbar(double mouseY) {
        int span = Math.max(1, this.barHeight - this.thumbHeight);
        double fraction = (mouseY - this.barTop - this.thumbHeight / 2.0D) / span;
        this.setScroll((int) Math.round(net.minecraft.util.Mth.clamp(fraction, 0.0D, 1.0D) * this.maxScroll()));
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        }
    }

    /**
     * Files what the page holds on the computer it was opened from: this side
     * straight away, so the boxes come back the way they were left, and the
     * server through the computer, which is what the rounds read.
     */
    private void saveAndClose() {
        TorpedoSettingsState.accept(this.holdDepth, this.depth, this.activation);
        if (this.controllerPos != null) {
            PacketDistributor.sendToServer(new TorpedoSettingsPayload(
                    this.controllerPos, this.controllerSubLevel, this.holdDepth, this.depth, this.activation));
        }
        this.onClose();
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.0f", value);
    }
}

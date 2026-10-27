package com.qkdream.firecontrolcompat.client;

import com.hooya.stabilizedturret.client.ControllerKeyMappingCompat;
import com.hooya.stabilizedturret.content.controller.RemoteControllerCompat;
import com.qkdream.firecontrolcompat.network.SonarSelectBindPayload;
import com.qkdream.firecontrolcompat.sonar.SonarSelectInputState;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The target select page, reached from the acoustic torpedo page.
 *
 * <p>The pick is one of the fire control computer's inputs, so it is bound the
 * way the computer's own actions are: a pair of wireless frequencies and one
 * key on each of the two remote controllers. Nothing here is filed on this side - a binding goes to
 * the computer and the computer's file comes back - so what the page shows is
 * what the boat actually answers to.</p>
 *
 * <p>A frequency is bound from what is in hand, the way the computer's own
 * frequency buttons work, and a remote key from the next key the operator
 * presses: the same keys the remotes themselves send.</p>
 */
public class SonarSelectBindScreen extends Screen {

    private static final int PANEL_WIDTH = 330;
    private static final int PANEL_HEIGHT = 232;
    private static final int CONTENT_LEFT = 18;
    private static final int CONTENT_WIDTH = 294;
    private static final int LABEL_TOP = 36;
    private static final int WIRELESS_TOP = 50;
    private static final int REMOTE_TOP = 88;
    private static final int BUTTON_WIDTH = 143;
    private static final int BUTTON_GAP = 8;
    private static final int HINT_TOP = 118;
    private static final int FOOTER_TOP = 198;

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

    /** The remote whose next key press is being waited on, or null. */
    private RemoteControllerCompat.Type capturing;

    private CompatToggleButton firstButton;
    private CompatToggleButton secondButton;
    private CompatToggleButton normalButton;
    private CompatToggleButton advancedButton;

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

    public SonarSelectBindScreen(Screen parent, BlockPos controllerPos, UUID controllerSubLevel) {
        super(Component.translatable("gui.firecontrolcompat.select_page"));
        this.parent = parent;
        this.controllerPos = controllerPos;
        this.controllerSubLevel = controllerSubLevel;
    }

    @Override
    protected void init() {
        // Clamped to the window rather than centred at a fixed size: on a small
        // window or a large GUI scale a fixed panel runs off the screen and its
        // lower rows cannot be reached at all.
        this.panelWidth = Math.min(PANEL_WIDTH, Math.max(160, this.width - 8));
        this.panelHeight = Math.min(PANEL_HEIGHT, Math.max(96, this.height - 8));
        this.panelLeft = Math.max(0, (this.width - this.panelWidth) / 2);
        this.panelTop = Math.max(2, (this.height - this.panelHeight) / 2);
        this.scroll = net.minecraft.util.Mth.clamp(this.scroll, 0, this.maxScroll());
        int left = this.panelLeft + CONTENT_LEFT;
        int right = left + BUTTON_WIDTH + BUTTON_GAP;

        this.firstButton = this.addRenderableWidget(new CompatToggleButton(
                left, this.cy(WIRELESS_TOP), BUTTON_WIDTH, 20, Component.empty(),
                button -> this.bindFrequency(SonarSelectBindPayload.SLOT_WIRELESS_FIRST)));
        this.secondButton = this.addRenderableWidget(new CompatToggleButton(
                right, this.cy(WIRELESS_TOP), BUTTON_WIDTH, 20, Component.empty(),
                button -> this.bindFrequency(SonarSelectBindPayload.SLOT_WIRELESS_SECOND)));
        this.normalButton = this.addRenderableWidget(new CompatToggleButton(
                left, this.cy(REMOTE_TOP), BUTTON_WIDTH, 20, Component.empty(),
                button -> this.capture(RemoteControllerCompat.Type.NORMAL)));
        this.advancedButton = this.addRenderableWidget(new CompatToggleButton(
                right, this.cy(REMOTE_TOP), BUTTON_WIDTH, 20, Component.empty(),
                button -> this.capture(RemoteControllerCompat.Type.ADVANCED)));

        this.addRenderableWidget(new CompatToggleButton(
                this.panelLeft + (this.panelWidth - 120) / 2, this.cy(FOOTER_TOP), 120, 20,
                Component.translatable("gui.back"), button -> this.onClose()));

        this.refresh();
    }

    @Override
    public void tick() {
        super.tick();
        this.refresh();
    }

    /** Draws what the computer reported each binding to be. */
    private void refresh() {
        if (this.firstButton == null) {
            return;
        }
        this.firstButton.setMessage(Component.translatable(
                "gui.firecontrolcompat.select_first", literal(SonarSelectInputState.first())));
        this.secondButton.setMessage(Component.translatable(
                "gui.firecontrolcompat.select_second", literal(SonarSelectInputState.second())));
        this.normalButton.setMessage(Component.translatable(
                "gui.firecontrolcompat.select_normal",
                this.keyName(RemoteControllerCompat.Type.NORMAL, SonarSelectInputState.normalKey())));
        this.advancedButton.setMessage(Component.translatable(
                "gui.firecontrolcompat.select_advanced",
                this.keyName(RemoteControllerCompat.Type.ADVANCED, SonarSelectInputState.advancedKey())));
        this.normalButton.setTone(this.capturing == RemoteControllerCompat.Type.NORMAL
                ? CompatToggleButton.Tone.GREEN
                : CompatToggleButton.Tone.NORMAL);
        this.advancedButton.setTone(this.capturing == RemoteControllerCompat.Type.ADVANCED
                ? CompatToggleButton.Tone.GREEN
                : CompatToggleButton.Tone.NORMAL);
    }

    private Component literal(String name) {
        return Component.literal(name == null || name.isEmpty()
                ? Component.translatable("gui.firecontrolcompat.select_none").getString()
                : name);
    }

    private Component keyName(RemoteControllerCompat.Type type, String signal) {
        if (signal == null || signal.isEmpty()) {
            return literal("");
        }
        try {
            return ControllerKeyMappingCompat.displayKey(type, signal);
        } catch (Throwable ignored) {
            return Component.literal(signal);
        }
    }

    /** Binds one half of the wireless pair from the item in hand. */
    private void bindFrequency(int slot) {
        this.send(slot, "");
        this.message("message.firecontrolcompat.select_sent");
    }

    private void capture(RemoteControllerCompat.Type type) {
        this.capturing = this.capturing == type ? null : type;
        if (this.capturing != null) {
            this.message(type == RemoteControllerCompat.Type.ADVANCED
                    ? "message.firecontrolcompat.select_press_advanced"
                    : "message.firecontrolcompat.select_press_normal");
        }
        this.refresh();
    }

    private void send(int slot, String key) {
        if (this.controllerPos == null) {
            return;
        }
        PacketDistributor.sendToServer(new SonarSelectBindPayload(
                this.controllerPos, this.controllerSubLevel, slot, key));
    }

    private void message(String key) {
        if (this.minecraft != null && this.minecraft.player != null) {
            this.minecraft.player.displayClientMessage(Component.translatable(key), true);
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (this.capturing == null) {
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        RemoteControllerCompat.Type type = this.capturing;
        this.capturing = null;
        this.refresh();
        if (keyCode == 256 || keyCode == 259 || keyCode == 261) {
            // Escape, delete and backspace all hand the binding back.
            this.send(this.slotOf(type), "");
            this.message("message.firecontrolcompat.select_cleared");
            return true;
        }
        String signal = null;
        try {
            signal = ControllerKeyMappingCompat.signalForKeyboardKey(type, keyCode, scanCode);
        } catch (Throwable ignored) {
        }
        if (signal == null) {
            this.message("message.firecontrolcompat.select_key_unbound");
            return true;
        }
        this.send(this.slotOf(type), signal);
        this.message("message.firecontrolcompat.select_bound");
        return true;
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
        if (this.capturing != null) {
            RemoteControllerCompat.Type type = this.capturing;
            this.capturing = null;
            this.refresh();
            String signal = null;
            try {
                signal = ControllerKeyMappingCompat.signalForMouseButton(type, button);
            } catch (Throwable ignored) {
            }
            if (signal == null) {
                this.message("message.firecontrolcompat.select_key_unbound");
                return true;
            }
            this.send(this.slotOf(type), signal);
            this.message("message.firecontrolcompat.select_bound");
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (this.maxScroll() > 0 && scrollY != 0.0D && this.insidePanel(mouseX, mouseY)) {
            this.setScroll(this.scroll - (int) Math.round(scrollY * 24.0D));
            return true;
        }
        if (this.capturing != null) {
            RemoteControllerCompat.Type type = this.capturing;
            String signal = null;
            try {
                signal = ControllerKeyMappingCompat.signalForMouseScroll(type, scrollY);
            } catch (Throwable ignored) {
            }
            if (signal != null) {
                this.capturing = null;
                this.refresh();
                this.send(this.slotOf(type), signal);
                this.message("message.firecontrolcompat.select_bound");
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private int slotOf(RemoteControllerCompat.Type type) {
        return type == RemoteControllerCompat.Type.ADVANCED
                ? SonarSelectBindPayload.SLOT_REMOTE_ADVANCED
                : SonarSelectBindPayload.SLOT_REMOTE_NORMAL;
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
        // Content is clipped to the panel, so a scrolled row never draws over the
        // frame or outside the window.
        graphics.enableScissor(this.panelLeft + 7, this.panelTop + 29, right - 7, bottom - 7);

        int left = this.panelLeft + CONTENT_LEFT;
        // The two group headings are clamped to the panel's content top: the rows may
        // scroll when the window is short, and a heading that scrolls away leaves two
        // columns of unlabelled buttons with nothing saying what they are for.
        int contentTop = this.panelTop + 32;
        graphics.drawString(this.font, Component.translatable("gui.firecontrolcompat.select_wireless"),
                left, Math.max(contentTop, this.cy(LABEL_TOP)), LABEL, false);
        graphics.drawString(this.font, Component.translatable("gui.firecontrolcompat.select_remote"),
                left, Math.max(contentTop, this.cy(REMOTE_TOP) - 14), LABEL, false);
        graphics.drawString(this.font, Component.translatable("gui.firecontrolcompat.select_hint_frequency"),
                left, this.cy(HINT_TOP), DIMMED, false);
        graphics.drawString(this.font, Component.translatable("gui.firecontrolcompat.select_hint_key"),
                left, this.cy(HINT_TOP) + 12, DIMMED, false);
        graphics.drawString(this.font, Component.translatable("gui.firecontrolcompat.select_hint_pick"),
                left, this.cy(HINT_TOP) + 24, DIMMED, false);

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
}

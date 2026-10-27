package com.qkdream.firecontrolcompat.client;

import com.qkdream.firecontrolcompat.network.SonarSettingsPayload;
import com.qkdream.firecontrolcompat.sonar.SonarBlockEntity;
import com.qkdream.firecontrolcompat.sonar.SonarSettingsState;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The sonar page of the fire control computer, opened from the button that
 * sits beside the radar and aircraft pages.
 *
 * <p>A computer can carry several dishes, so the page lists every set wired to
 * it and each one keeps its own settings: how far out it reaches, how wide its
 * window is either side of the bow, how far above and below the horizon it
 * looks, whether it only listens or pings as well, and where its picture sits
 * on the screen. A set can also be named, so the list and the picture on the
 * screen say which dish is which. The list marks the set being edited, and the
 * small preview shows the window with every wired set's picture in it, the one
 * being edited picked out brighter; a picture can be dragged around inside the
 * preview, which is quicker than typing the two numbers.</p>
 *
 * <p>Everything is sent the moment it is changed for the set in hand, so
 * switching to another dish and closing the page cannot lose a setting.</p>
 */
public class SonarSettingsScreen extends Screen {

    private static final int PANEL_WIDTH = 340;
    private static final int PANEL_HEIGHT = 320;
    private static final int LEFT_COLUMN = 14;
    private static final int RIGHT_COLUMN = 176;
    private static final int COLUMN_WIDTH = 150;
    private static final int FIELD_WIDTH = 74;
    private static final int NAME_TOP = 52;
    private static final int RANGE_TOP = 76;
    private static final int HALF_TOP = 100;
    private static final int UP_TOP = 124;
    private static final int DOWN_TOP = 148;
    private static final int MODE_TOP = 172;
    private static final int HUD_TITLE_TOP = 196;
    private static final int HUD_X_TOP = 210;
    private static final int HUD_Y_TOP = 234;
    private static final int HUD_SCALE_TOP = 258;
    private static final int LIST_ROWS = 5;
    private static final int LIST_TOP = 52;
    private static final int LIST_STEP = 22;
    private static final int PREVIEW_TOP = 178;
    private static final int PREVIEW_HEIGHT = 68;
    private static final int FOOTER_TOP = 290;
    private static final int TORPEDO_TOP = 264;

    private static final int OUTLINE = -13357272;
    private static final int BRASS = -4681667;
    private static final int INNER_BORDER = -9017782;
    private static final int BACKGROUND = -858162;
    private static final int HEADER = -9739950;
    private static final int LABEL = -12765137;
    private static final int WHITE = 16777215;
    private static final int WARN = -40336;
    private static final int SCREEN_FILL = 0xFF16202E;
    private static final int SCREEN_BORDER = 0xFF6B6152;
    private static final int BOX_FILL = 0xCC2F6FB0;
    private static final int BOX_BORDER = 0xFF9FD8FF;
    private static final int BOX_FILL_IDLE = 0x552F6FB0;
    private static final int BOX_BORDER_IDLE = 0x886E8FB0;

    private final Screen parent;
    private final BlockPos controllerPos;
    private final UUID controllerSubLevel;

    private int selected;
    private final Set<Integer> dirty = new HashSet<>();
    private final List<CompatToggleButton> sonarButtons = new ArrayList<>();

    private EditBox nameBox;
    private EditBox rangeBox;
    private EditBox halfBox;
    private EditBox upBox;
    private EditBox downBox;
    private EditBox hudXBox;
    private EditBox hudYBox;
    private EditBox hudScaleBox;
    private CompatToggleButton modeButton;
    private CompatToggleButton torpedoButton;

    private int panelLeft;
    private int panelTop;
    /** Panel size actually used, which is the design size only on a large window. */
    private int panelWidth = PANEL_WIDTH;
    private int panelHeight = PANEL_HEIGHT;
    /** How far the content is scrolled down, in pixels. */
    private int scroll;
    /** The laser code box and the label row it sits on. */
    private net.minecraft.client.gui.components.EditBox laserCodeBox;
    private int laserCodeTop;
    /** Scrollbar track and thumb, laid out while the panel is drawn. */
    private int barX;
    private int barTop;
    private int barHeight;
    private int thumbTop;
    private int thumbHeight = 1;
    private boolean draggingBar;

    /** Where the preview sits this frame, and where the picture sits inside it. */
    private int previewLeft;
    private int previewTop;
    private int previewWidth;
    private int previewHeight;
    private int boxLeft;
    private int boxTop;
    private int boxWidth;
    private int boxHeight;

    /** Every picture the preview drew this frame: index, then left, top, width, height. */
    private final List<int[]> previewBoxes = new ArrayList<>();

    private boolean dragging;
    private double dragOffsetX;
    private double dragOffsetY;
    private int listSignature = Integer.MIN_VALUE;

    public SonarSettingsScreen(Screen parent, BlockPos controllerPos, UUID controllerSubLevel) {
        super(Component.translatable("gui.firecontrolcompat.sonar_settings"));
        this.parent = parent;
        this.controllerPos = controllerPos;
        this.controllerSubLevel = controllerSubLevel;
    }

    @Override
    protected void init() {
        // The panel is clamped to the window rather than centred at a fixed
        // size: on a small window or a large GUI scale a fixed panel runs off
        // the screen and the rows past its bottom cannot be reached at all.
        this.panelWidth = Math.min(PANEL_WIDTH, Math.max(160, this.width - 8));
        this.panelHeight = Math.min(PANEL_HEIGHT, Math.max(96, this.height - 8));
        this.panelLeft = Math.max(0, (this.width - this.panelWidth) / 2);
        this.panelTop = Math.max(2, (this.height - this.panelHeight) / 2);
        this.scroll = Mth.clamp(this.scroll, 0, this.maxScroll());
        int listX = this.panelLeft + LEFT_COLUMN;
        int rightX = this.panelLeft + RIGHT_COLUMN;
        int fieldX = rightX + COLUMN_WIDTH - FIELD_WIDTH;

        this.sonarButtons.clear();
        for (int row = 0; row < LIST_ROWS; row++) {
            int index = row;
            CompatToggleButton button = new CompatToggleButton(
                    listX, this.cy(LIST_TOP) + row * LIST_STEP, COLUMN_WIDTH, 20,
                    Component.empty(), pressed -> this.select(index));
            this.sonarButtons.add(button);
            this.addRenderableWidget(button);
        }

        this.nameBox = this.stringBox(fieldX, this.cy(NAME_TOP), value -> {
            SonarSettingsState.Entry entry = this.entry();
            if (entry != null) {
                entry.name(value);
                this.touch();
            }
        });
        this.rangeBox = this.numberBox(fieldX, this.cy(RANGE_TOP), 0.0D, value -> {
            SonarSettingsState.Entry entry = this.entry();
            if (entry != null) {
                entry.range(value);
                this.touch();
            }
        });
        this.halfBox = this.numberBox(fieldX, this.cy(HALF_TOP), 0.0D, value -> {
            SonarSettingsState.Entry entry = this.entry();
            if (entry != null) {
                entry.halfAngle(value);
                this.touch();
            }
        });
        this.upBox = this.numberBox(fieldX, this.cy(UP_TOP), 0.0D, value -> {
            SonarSettingsState.Entry entry = this.entry();
            if (entry != null) {
                entry.elevationUp(value);
                this.touch();
            }
        });
        this.downBox = this.numberBox(fieldX, this.cy(DOWN_TOP), 0.0D, value -> {
            SonarSettingsState.Entry entry = this.entry();
            if (entry != null) {
                entry.elevationDown(value);
                this.touch();
            }
        });
        this.modeButton = new CompatToggleButton(
                rightX, this.cy(MODE_TOP), COLUMN_WIDTH, 20, this.modeText(), button -> {
                    SonarSettingsState.Entry entry = this.entry();
                    if (entry == null) {
                        return;
                    }
                    entry.activeMode(!entry.activeMode());
                    this.touch();
                    this.sendDirty();
                    this.refreshMode();
                });
        this.addRenderableWidget(this.modeButton);
        this.hudXBox = this.numberBox(fieldX, this.cy(HUD_X_TOP), 0.0D, value -> {
            SonarSettingsState.Entry entry = this.entry();
            if (entry != null) {
                entry.hudX(value);
                this.touch();
            }
        });
        this.hudYBox = this.numberBox(fieldX, this.cy(HUD_Y_TOP), 0.0D, value -> {
            SonarSettingsState.Entry entry = this.entry();
            if (entry != null) {
                entry.hudY(value);
                this.touch();
            }
        });
        this.hudScaleBox = this.numberBox(fieldX, this.cy(HUD_SCALE_TOP), 0.0D, value -> {
            SonarSettingsState.Entry entry = this.entry();
            if (entry != null) {
                entry.hudScale(value);
                this.touch();
            }
        });

        this.addRenderableWidget(new CompatToggleButton(
                listX, this.cy(FOOTER_TOP), COLUMN_WIDTH, 20,
                Component.translatable("gui.done"), button -> this.saveAndClose())
                .setTone(CompatToggleButton.Tone.GREEN));
        this.addRenderableWidget(new CompatToggleButton(
                rightX, this.cy(FOOTER_TOP), COLUMN_WIDTH, 20,
                Component.translatable("gui.back"), button -> this.onClose()));
        // The swim a tube's rounds take is filed on the computer as a whole, so
        // it sits on its own page behind this button rather than in a set.
        this.torpedoButton = this.addRenderableWidget(new CompatToggleButton(
                listX, this.cy(TORPEDO_TOP), COLUMN_WIDTH, 20,
                Component.translatable("gui.firecontrolcompat.torpedo_page"),
                button -> this.openTorpedoPage()));

        this.selected = Mth.clamp(this.selected, 0, Math.max(0, SonarSettingsState.size() - 1));
        this.refreshButtons();
        this.refreshFields();
        this.refreshMode();
    }

    /** A content row's screen y: the panel top, the row's own offset, less the scroll. */
    private int cy(int top) {
        return this.panelTop + top - this.scroll;
    }

    /** How tall the content is, footer included. */
    private int contentHeight() {
        return Math.max(FOOTER_TOP, TORPEDO_TOP) + 26;
    }

    /** How far the content may be scrolled before its bottom is on screen. */
    private int maxScroll() {
        return Math.max(0, this.contentHeight() - this.panelHeight);
    }

    /** Scrolls to a position, clamped, and moves the widgets with it. */
    private void setScroll(int value) {
        int clamped = Mth.clamp(value, 0, this.maxScroll());
        if (clamped == this.scroll) {
            return;
        }
        this.scroll = clamped;
        // Re-running init() rebuilds every widget at its row's new position,
        // which beats moving a dozen boxes and sliders by hand.
        this.rebuildWidgets();
    }

    /** Opens the acoustic torpedo page of the computer this screen belongs to. */
    private void openTorpedoPage() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(new TorpedoSettingsScreen(this, this.controllerPos, this.controllerSubLevel));
        }
    }

    /** The set being edited, or null when nothing is wired to the computer. */
    private SonarSettingsState.Entry entry() {
        return SonarSettingsState.entry(this.selected);
    }

    private void select(int index) {
        if (index >= SonarSettingsState.size()) {
            return;
        }
        this.selected = index;
        this.refreshButtons();
        this.refreshFields();
        this.refreshMode();
    }

    private void touch() {
        this.dirty.add(this.selected);
    }

    @Override
    public void tick() {
        super.tick();
        if (this.listSignature != this.signature()) {
            this.refreshButtons();
            this.refreshMode();
        }
        for (EditBox box : List.of(this.rangeBox, this.halfBox, this.upBox, this.downBox,
                this.hudXBox, this.hudYBox, this.hudScaleBox)) {
            if (box != null && !box.isFocused() && !this.dirty.contains(this.selected)) {
                box.setValue(format(this.valueFor(box)));
            }
        }
    }

    private int signature() {
        int result = SonarSettingsState.size();
        for (SonarSettingsState.Entry entry : SonarSettingsState.entries()) {
            result = result * 31 + (entry.activeMode() ? 1 : 0);
            result = result * 31 + entry.name().hashCode();
        }
        return result;
    }

    private void refreshButtons() {
        List<SonarSettingsState.Entry> entries = SonarSettingsState.entries();
        for (int row = 0; row < this.sonarButtons.size(); row++) {
            CompatToggleButton button = this.sonarButtons.get(row);
            boolean present = row < entries.size();
            button.visible = present;
            button.active = present;
            if (!present) {
                button.setMessage(Component.empty());
                continue;
            }
            SonarSettingsState.Entry entry = entries.get(row);
            Component head = entry.name().isEmpty()
                    ? Component.translatable("gui.firecontrolcompat.sonar_entry", row + 1)
                    : Component.literal(entry.name());
            Component label = head.copy()
                    .append(Component.literal(" · "))
                    .append(Component.translatable(entry.activeMode()
                            ? "gui.firecontrolcompat.sonar_active_short"
                            : "gui.firecontrolcompat.sonar_passive_short"));
            button.setMessage(label);
            button.setTone(row == this.selected
                    ? CompatToggleButton.Tone.GREEN
                    : CompatToggleButton.Tone.NORMAL);
        }
        this.listSignature = this.signature();
    }

    private void refreshMode() {
        SonarSettingsState.Entry entry = this.entry();
        boolean active = entry != null && entry.activeMode();
        if (this.modeButton != null) {
            this.modeButton.setMessage(this.modeText());
            this.modeButton.setTone(active ? CompatToggleButton.Tone.GREEN : CompatToggleButton.Tone.NORMAL);
            this.modeButton.active = entry != null;
        }
    }

    private void refreshFields() {
        SonarSettingsState.Entry entry = this.entry();
        if (entry == null) {
            return;
        }
        this.nameBox.setValue(entry.name());
        this.rangeBox.setValue(format(entry.range()));
        this.halfBox.setValue(format(entry.halfAngle()));
        this.upBox.setValue(format(entry.elevationUp()));
        this.downBox.setValue(format(entry.elevationDown()));
        this.hudXBox.setValue(format(entry.hudX()));
        this.hudYBox.setValue(format(entry.hudY()));
        this.hudScaleBox.setValue(format(entry.hudScale()));
    }

    private double valueFor(EditBox box) {
        SonarSettingsState.Entry entry = this.entry();
        if (entry == null) {
            return 0.0D;
        }
        if (box == this.rangeBox) {
            return entry.range();
        }
        if (box == this.halfBox) {
            return entry.halfAngle();
        }
        if (box == this.upBox) {
            return entry.elevationUp();
        }
        if (box == this.downBox) {
            return entry.elevationDown();
        }
        if (box == this.hudXBox) {
            return entry.hudX();
        }
        if (box == this.hudYBox) {
            return entry.hudY();
        }
        return entry.hudScale();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (this.maxScroll() > 0 && scrollY != 0.0D && this.insidePanel(mouseX, mouseY)) {
            this.setScroll(this.scroll - (int) Math.round(scrollY * 24.0D));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /** Whether a point is over the panel, which is where the wheel scrolls. */
    private boolean insidePanel(double mouseX, double mouseY) {
        return mouseX >= this.panelLeft && mouseX < this.panelLeft + this.panelWidth
                && mouseY >= this.panelTop && mouseY < this.panelTop + this.panelHeight;
    }

    /** Puts the scroll where the thumb was dragged to. */
    private void dragScrollbar(double mouseY) {
        int span = Math.max(1, this.barHeight - this.thumbHeight);
        double fraction = (mouseY - this.barTop - this.thumbHeight / 2.0D) / span;
        this.setScroll((int) Math.round(Mth.clamp(fraction, 0.0D, 1.0D) * this.maxScroll()));
    }

    /** Left empty on purpose: the fire control pages show the world undimmed. */
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
        // Everything below the header is content: it is clipped to the panel so a
        // scrolled row never draws over the frame or outside the window.
        graphics.enableScissor(this.panelLeft + 7, this.panelTop + 29, right - 7, bottom - 7);

        int listX = this.panelLeft + LEFT_COLUMN;
        int rightX = this.panelLeft + RIGHT_COLUMN;
        graphics.drawString(this.font, Component.translatable("gui.firecontrolcompat.sonar_list"),
                listX, this.cy(38), LABEL, false);
        graphics.drawString(this.font, Component.translatable("gui.firecontrolcompat.sonar_name"),
                rightX, this.cy(NAME_TOP) + 5, LABEL, false);
        graphics.drawString(this.font, Component.translatable("gui.firecontrolcompat.sonar_range"),
                rightX, this.cy(RANGE_TOP) + 5, LABEL, false);
        graphics.drawString(this.font, Component.translatable("gui.firecontrolcompat.sonar_angle"),
                rightX, this.cy(HALF_TOP) + 5, LABEL, false);
        graphics.drawString(this.font, Component.translatable("gui.firecontrolcompat.sonar_pitch_up"),
                rightX, this.cy(UP_TOP) + 5, LABEL, false);
        graphics.drawString(this.font, Component.translatable("gui.firecontrolcompat.sonar_pitch_down"),
                rightX, this.cy(DOWN_TOP) + 5, LABEL, false);
        graphics.drawString(this.font, Component.translatable("gui.firecontrolcompat.sonar_hud_place"),
                rightX, this.cy(HUD_TITLE_TOP), LABEL, false);
        graphics.drawString(this.font, Component.translatable("gui.firecontrolcompat.sonar_hud_x"),
                rightX, this.cy(HUD_X_TOP) + 5, LABEL, false);
        graphics.drawString(this.font, Component.translatable("gui.firecontrolcompat.sonar_hud_y"),
                rightX, this.cy(HUD_Y_TOP) + 5, LABEL, false);
        graphics.drawString(this.font, Component.translatable("gui.firecontrolcompat.sonar_hud_scale"),
                rightX, this.cy(HUD_SCALE_TOP) + 5, LABEL, false);
        graphics.drawString(this.font, Component.translatable("gui.firecontrolcompat.sonar_hud_preview"),
                listX, this.cy(168), LABEL, false);

        this.renderPreview(graphics);
        this.renderStatus(graphics, listX);


        int hidden = SonarSettingsState.size() - LIST_ROWS;
        if (hidden > 0) {
            graphics.drawString(this.font, Component.translatable(
                    "gui.firecontrolcompat.sonar_list_more", hidden), listX, this.cy(150), WARN, false);
        }

        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.disableScissor();
        this.renderScrollbar(graphics, right, bottom);
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

    private void renderStatus(GuiGraphics graphics, int left) {
        Component status = Component.translatable(SonarSettingsState.hasSonar()
                ? "message.firecontrolcompat.sonar.status_bound"
                : "message.firecontrolcompat.sonar.status_unbound");
        graphics.drawString(this.font, status, left, this.cy(252),
                SonarSettingsState.hasSonar() ? LABEL : WARN, false);
    }

    /**
     * The window, with every wired set's picture drawn where its own settings
     * put it: the layout of the whole screen is what the player is arranging,
     * so all of them are shown and the one being edited is picked out.
     */
    private void renderPreview(GuiGraphics graphics) {
        this.previewLeft = this.panelLeft + LEFT_COLUMN;
        this.previewTop = this.cy(PREVIEW_TOP);
        this.previewWidth = COLUMN_WIDTH;
        this.previewHeight = PREVIEW_HEIGHT;
        int innerLeft = this.previewLeft + 2;
        int innerTop = this.previewTop + 2;
        int innerWidth = this.previewWidth - 4;
        int innerHeight = this.previewHeight - 4;
        graphics.fill(this.previewLeft, this.previewTop, this.previewLeft + this.previewWidth,
                this.previewTop + this.previewHeight, SCREEN_BORDER);
        graphics.fill(innerLeft, innerTop, innerLeft + innerWidth, innerTop + innerHeight, SCREEN_FILL);

        this.boxWidth = 0;
        this.boxHeight = 0;
        this.boxLeft = 0;
        this.boxTop = 0;
        this.previewBoxes.clear();
        int count = SonarSettingsState.size();
        for (int index = 0; index < count; index++) {
            int[] box = this.previewBox(index);
            if (box == null) {
                continue;
            }
            this.previewBoxes.add(new int[] {index, box[0], box[1], box[2], box[3]});
            if (index == this.selected) {
                continue;
            }
            this.drawBox(graphics, box, BOX_BORDER_IDLE, BOX_FILL_IDLE);
        }
        int[] chosen = this.previewBox(this.selected);
        if (chosen == null) {
            return;
        }
        this.boxLeft = chosen[0];
        this.boxTop = chosen[1];
        this.boxWidth = chosen[2];
        this.boxHeight = chosen[3];
        this.drawBox(graphics, chosen, BOX_BORDER, BOX_FILL);
    }

    /** One picture in the preview: outline first, then the body over it. */
    private void drawBox(GuiGraphics graphics, int[] box, int border, int fill) {
        graphics.fill(box[0] - 1, box[1] - 1, box[0] + box[2] + 1, box[1] + box[3] + 1, border);
        graphics.fill(box[0], box[1], box[0] + box[2], box[1] + box[3], fill);
    }

    /**
     * Where the picture of the set at that index sits inside the preview, or
     * null when there is no such set. The size is the real picture's footprint
     * on the screen, scaled down to the preview, so what is dragged here is
     * what the player will see in game.
     */
    private int[] previewBox(int index) {
        SonarSettingsState.Entry entry = SonarSettingsState.entry(index);
        if (entry == null || this.previewWidth <= 0) {
            return null;
        }
        int innerWidth = this.previewWidth - 4;
        int innerHeight = this.previewHeight - 4;
        int realWidth = SonarHudRenderer.panelWidth(this.width, entry.hudScale());
        int realHeight = SonarHudRenderer.panelHeight(realWidth);
        double factor = Math.min(
                innerWidth / (double) Math.max(1, this.width),
                innerHeight / (double) Math.max(1, this.height));
        int width = Math.max(3, (int) Math.round(realWidth * factor));
        int height = Math.max(2, (int) Math.round(realHeight * factor));
        int spanX = Math.max(0, innerWidth - width);
        int spanY = Math.max(0, innerHeight - height);
        int left = this.previewLeft + 2 + (int) Math.round(spanX * entry.hudX() / 100.0D);
        int top = this.previewTop + 2 + (int) Math.round(spanY * entry.hudY() / 100.0D);
        return new int[] {left, top, width, height};
    }

    /** The picture under the cursor, or null when the cursor is on bare preview. */
    private int[] boxAt(double mouseX, double mouseY) {
        for (int index = this.previewBoxes.size() - 1; index >= 0; index--) {
            int[] box = this.previewBoxes.get(index);
            if (mouseX >= box[1] && mouseX < box[1] + box[3] && mouseY >= box[2] && mouseY < box[2] + box[4]) {
                return box;
            }
        }
        return null;
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
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (button != 0 || this.entry() == null || !this.inPreview(mouseX, mouseY)) {
            return false;
        }
        // Grabbing a picture picks that set, so a layout can be arranged by
        // dragging the boxes about without touching the list first.
        int[] grabbed = this.boxAt(mouseX, mouseY);
        if (grabbed != null && grabbed[0] != this.selected) {
            this.select(grabbed[0]);
        }
        int[] box = grabbed != null ? grabbed : new int[] {
                this.selected, this.boxLeft, this.boxTop, this.boxWidth, this.boxHeight};
        boolean inside = mouseX >= box[1] && mouseX < box[1] + box[3]
                && mouseY >= box[2] && mouseY < box[2] + box[4];
        this.dragging = true;
        this.dragOffsetX = inside ? mouseX - box[1] : box[3] / 2.0D;
        this.dragOffsetY = inside ? mouseY - box[2] : box[4] / 2.0D;
        this.applyDrag(mouseX, mouseY);
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.draggingBar) {
            this.dragScrollbar(mouseY);
            return true;
        }
        if (this.dragging) {
            this.applyDrag(mouseX, mouseY);
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
        this.dragging = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private boolean inPreview(double mouseX, double mouseY) {
        return mouseX >= this.previewLeft && mouseX < this.previewLeft + this.previewWidth
                && mouseY >= this.previewTop && mouseY < this.previewTop + this.previewHeight;
    }

    /** Moves the picture to follow the mouse, inside the preview and inside the screen. */
    private void applyDrag(double mouseX, double mouseY) {
        SonarSettingsState.Entry entry = this.entry();
        if (entry == null) {
            return;
        }
        int[] box = this.previewBox(this.selected);
        if (box == null) {
            return;
        }
        int innerLeft = this.previewLeft + 2;
        int innerTop = this.previewTop + 2;
        int spanX = Math.max(1, this.previewWidth - 4 - box[2]);
        int spanY = Math.max(1, this.previewHeight - 4 - box[3]);
        double fractionX = (mouseX - this.dragOffsetX - innerLeft) / spanX * 100.0D;
        double fractionY = (mouseY - this.dragOffsetY - innerTop) / spanY * 100.0D;
        entry.hudX(fractionX);
        entry.hudY(fractionY);
        this.touch();
        if (!this.hudXBox.isFocused()) {
            this.hudXBox.setValue(format(entry.hudX()));
        }
        if (!this.hudYBox.isFocused()) {
            this.hudYBox.setValue(format(entry.hudY()));
        }
    }

    /** Clears the picture when the page closes, so no stale marks are left behind. */
    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        }
    }

    private void saveAndClose() {
        this.sendDirty();
        this.onClose();
    }


    /** Lands what has been changed on every set it was changed for. */
    private void sendDirty() {
        if (this.controllerPos == null) {
            this.dirty.clear();
            return;
        }
        for (int index : this.dirty) {
            SonarSettingsState.Entry entry = SonarSettingsState.entry(index);
            if (entry == null) {
                continue;
            }
            PacketDistributor.sendToServer(new SonarSettingsPayload(
                    this.controllerPos, this.controllerSubLevel, entry.pos(), entry.subLevel(),
                    entry.range(), entry.halfAngle(), entry.elevationUp(), entry.elevationDown(),
                    entry.activeMode(), entry.hudX(), entry.hudY(), entry.hudScale(), entry.name()));
        }
        this.dirty.clear();
    }

    private Component modeText() {
        return Component.translatable(
                this.entry() != null && this.entry().activeMode()
                        ? "gui.firecontrolcompat.sonar_active"
                        : "gui.firecontrolcompat.sonar_passive");
    }

    /** A plain text field, used for the name written on a set. */
    private EditBox stringBox(int x, int y, java.util.function.Consumer<String> sink) {
        EditBox box = new EditBox(this.font, x, y, FIELD_WIDTH, 18, Component.empty());
        box.setMaxLength(SonarBlockEntity.MAX_NAME);
        box.setValue("");
        box.setResponder(sink);
        this.addRenderableWidget(box);
        return box;
    }

    private EditBox numberBox(int x, int y, double initial, java.util.function.DoubleConsumer sink) {
        EditBox box = new EditBox(this.font, x, y, FIELD_WIDTH, 18, Component.literal(format(initial)));
        box.setMaxLength(7);
        box.setValue(format(initial));
        box.setResponder(value -> {
            try {
                sink.accept(Double.parseDouble(value.trim()));
            } catch (NumberFormatException ignored) {
            }
        });
        this.addRenderableWidget(box);
        return box;
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.0f", value);
    }
}

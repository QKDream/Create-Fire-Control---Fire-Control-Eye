package com.qkdream.firecontrolcompat.mixin;

import org.spongepowered.asm.mixin.Final;
import com.hooya.stabilizedturret.client.ControllerKeyMappingCompat;
import com.hooya.stabilizedturret.client.StabilizerControllerScreen;
import com.hooya.stabilizedturret.content.controller.DriveByWireCompat;
import com.hooya.stabilizedturret.content.controller.RemoteControllerCompat;
import com.hooya.stabilizedturret.network.OpenControllerPayload;
import com.qkdream.firecontrolcompat.client.CompatToggleButton;
import com.qkdream.firecontrolcompat.sonar.SonarSelectInputState;
import com.qkdream.firecontrolcompat.network.SonarSelectBindPayload;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.network.PacketDistributor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Puts the sonar pick's own bindings on the input page, under the computer's
 * fifteen actions.
 *
 * <p>The pick is one of the computer's inputs, so it belongs where the rest of
 * them are set: the row carries a pair of wireless frequencies and a key on
 * each of the two remote controllers, set exactly the way the rows above it
 * are. A frequency is taken from the hand; a key is taken by pressing it, and
 * a key the controller cannot send is turned down.</p>
 *
 * <p>The row is appended below the actions, which means the panel has to be
 * that much taller and the back button one row lower, or the two would sit on
 * top of each other. Every hook is optional, so a later fire control release
 * that reshapes this page leaves the rest of the screen working instead of
 * failing to load.</p>
 */
@Mixin(StabilizerControllerScreen.class)
public abstract class ControllerSelectRowMixin extends Screen {

    /** The row our bindings take, after the computer's own fifteen. */
    @Unique
    private static final int COMPAT_SELECT_ROW = 15;
    /** What that row costs the panel, and therefore the back button. */
    @Unique
    /**
     * How much room this mod's block needs: a heading line, two rows of buttons and a
     * little air before the pack's own back button, which is moved down by this much.
     */
    private static final int COMPAT_SELECT_ROOM = 96;

    /** Two rows of buttons, the spacing the pack's own sub-rows use. */
    private static final int COMPAT_SUB_ROW = 22;
    /** The input page's measured size, as fire control asks for it. */
    @Unique
    private static final int COMPAT_INPUTS_PANEL = 960;
    @Unique
    private static final int COMPAT_INPUTS_WIDTH = 328;
    @Unique
    private static final int COMPAT_INPUTS_TOP = 48;
    /** How far apart the input page's rows are laid out. */
    @Unique
    private static final int COMPAT_ROW_STEP = 58;

    @Shadow
    private double scrollOffset;

    /** The pack's own scrolling rows; its scroll range is measured from this list. */
    @Shadow
    @Final
    private java.util.List<net.minecraft.client.gui.components.AbstractWidget> contentWidgets;

    /** Controls the pack pins to the frame; they are not part of the list above. */
    @Shadow
    @Final
    private java.util.List<net.minecraft.client.gui.components.AbstractWidget> fixedWidgets;

    /**
     * The heading's line: where this mod's block starts, remembered while the page is
     * built so the label pass can draw at the same place - less the live scroll.
     */
    @Unique
    private int compat$selectRowY;

    /** Throttle for the row probe. */
    @Unique
    private long compat$lastRowProbe;

    /** Whether the page being built is the one our row belongs on. */
    @Unique
    private boolean compat$selectPage;
    @Unique
    private BlockPos compat$selectPos;
    @Unique
    private UUID compat$selectSubLevel;
    /** Which remote's next key is being waited on, or null. */
    @Unique
    private RemoteControllerCompat.Type compat$selectCapture;

    /** The left edge and right edge of the pack's own rows, measured while the page is built. */
    @Unique
    private int compat$packRowLeft = -1;

    @Unique
    private int compat$packRowRight = -1;
    @Unique
    private net.minecraft.client.gui.components.Button compat$selectFirstButton;
    @Unique
    private net.minecraft.client.gui.components.Button compat$selectSecondButton;
    @Unique
    private net.minecraft.client.gui.components.Button compat$selectNormalButton;
    @Unique
    private net.minecraft.client.gui.components.Button compat$selectAdvancedButton;
    /** What the four buttons were last written with, so they are only redrawn on change. */
    @Unique
    private String compat$selectShown;

    protected ControllerSelectRowMixin(Component title) {
        super(title);
    }

    @Inject(method = "<init>", at = @At("TAIL"), require = 0)
    private void compat$initSelectRow(OpenControllerPayload initial, CallbackInfo ci) {
        this.compat$selectPos = initial.pos();
        this.compat$selectSubLevel = initial.controllerSubLevelId();
    }

    /** Drops the flag for every page, so only the input page carries our row. */
    @Inject(method = "buildPage", at = @At("HEAD"), require = 0)
    private void compat$clearSelectRow(CallbackInfo ci) {
        this.compat$selectPage = false;
        this.compat$selectCapture = null;
        this.compat$selectShown = "";
        this.compat$selectFirstButton = null;
        this.compat$selectSecondButton = null;
        this.compat$selectNormalButton = null;
        this.compat$selectAdvancedButton = null;
    }

    /** Claims the flag before the input page is measured, so the panel grows with our row. */
    @Inject(method = "buildInputsPage", at = @At("HEAD"), require = 0)
    private void compat$claimSelectRow(CallbackInfo ci) {
        this.compat$selectPage = true;
    }

    /**
     * Adds the sound-picking block under the pack's own bindings, measured from the widgets the
     * pack has just placed instead of from numbers copied out of one version of the screen.
     *
     * <p>Fire Control 0.9.1 rebuilt the input page: the rows are eighteen pixels high, the
     * actions are grouped, and the width comes from the panel, so the old anchors - a fixed row
     * height and a fixed block bottom - pointed into the middle of the pack's rows. What does not
     * change is that the page always ends with a back button and that every row is a widget, so
     * the block is placed from those: the lowest row the pack added decides where ours starts,
     * and its left edge and width decide how wide the two columns are.</p>
     */

    /**
     * Our four rows, drawn with the pack's own brass button when it can be reached.
     *
     * <p>0.9.1 builds every row with a private {@code styledButton(Component, OnPress, int, int,
     * int, int)} returning its {@code BrassButton}. That is what makes the rows look like they
     * belong to the page - the border, the tone, the text weight. The method is private, so it is
     * reached by name; if it cannot be reached (a newer pack, a renamed method) the mod's own toggle
     * button is used, so the page still works rather than failing.</p>
     */
    @Unique
    private net.minecraft.client.gui.components.Button compat$buttonOrOwn(
            net.minecraft.network.chat.Component label,
            net.minecraft.client.gui.components.Button.OnPress press,
            int x, int y, int width, int height) {
        try {
            for (java.lang.reflect.Method candidate : this.getClass().getDeclaredMethods()) {
                if (!"styledButton".equals(candidate.getName()) || candidate.getParameterCount() != 6) {
                    continue;
                }
                candidate.setAccessible(true);
                Object made = candidate.invoke(this, label, press, x, y, width, height);
                if (made instanceof net.minecraft.client.gui.components.Button button) {
                    return button;
                }
            }
        } catch (Throwable ignored) {
            // Fall through to this mod's own button.
        }
        return new CompatToggleButton(x, y, width, height, label, press);
    }

    @Inject(method = "buildInputsPage", at = @At("TAIL"), require = 0)
    private void compat$addSelectRow(CallbackInfo ci) {
        // Kept as the fallback: if the sonar group could not be added to the pack's own list, these
        // four controls are the only way to bind the sonar, so they are drawn unless the pack has
        // taken the job over.
        // The old standalone block is switched off for good: it was drawn over the pack's
        // scrolling content instead of inside it, so it stayed put while the rest of the page
        // scrolled. The sonar rows live in the pack's own input groups now.
        if (true) {
            return;
        }
        int backY = this.compat$backButtonY();
        int lastBlock = this.compat$packRowBottom(backY);
        int[] layout = this.compat$selectLayout();
        int left = layout[0];
        int width = layout[1];
        int gap = layout[2];
        if (this.compat$packRowLeft >= 0 && this.compat$packRowRight > this.compat$packRowLeft) {
            left = this.compat$packRowLeft;
            width = Math.max(52, (this.compat$packRowRight - this.compat$packRowLeft - 4) / 2);
            gap = 4;
        }
        // Anchored on the page's own back button, not on the rows alone: 0.9.1 hides the wireless
        // column while no terminal is bound, so the rows themselves move up and down with the
        // binding state. The back button is always placed just under the last row, which makes it
        // the one stable anchor: the block sits in the gap right above it, whatever the column
        // count is, and the button is pushed down afterwards to make the room.
        Integer anchored = null;
        if (backY > 0) {
            anchored = Math.max(lastBlock > 0 ? lastBlock + 8 : 0, backY - 104);
        }
        int base = anchored != null ? anchored
                : (lastBlock > 0 ? lastBlock + 10 : layout[3]);
        this.compat$selectRowY = base;
        int right = left + width + gap;

        // Laid out the way 0.9.1 lays out its own rows: two small frequency squares on the right,
        // then the wide key button. Our block used to be four equal wide buttons, which is why it
        // never quite matched the page beside it.
        // The squares sit in the pack's own frequency column: its row right edge, less the same two
        // steps it uses (a 44-pixel terminal gap and a 22-pixel offset between the two squares).
        int rightEdge = this.compat$packRowRight > 0 ? this.compat$packRowRight : left + width * 2 + gap;
        int square = 20;
        int sq1 = rightEdge - 44 - 22;
        int sq2 = rightEdge - 44;
        int wide = Math.max(60, width);
        this.compat$selectFirstButton = this.addRenderableWidget(this.compat$buttonOrOwn(
                Component.empty(), button -> this.compat$bindFrequency(true), sq1, base + 18, square, 18));
        this.compat$selectSecondButton = this.addRenderableWidget(this.compat$buttonOrOwn(
                Component.empty(), button -> this.compat$bindFrequency(false), sq2, base + 18, square, 18));
        this.compat$selectNormalButton = this.addRenderableWidget(this.compat$buttonOrOwn(
                Component.empty(), button -> this.compat$capture(RemoteControllerCompat.Type.NORMAL),
                left, base + 18, wide, 18));
        this.compat$selectAdvancedButton = this.addRenderableWidget(this.compat$buttonOrOwn(
                Component.empty(), button -> this.compat$capture(RemoteControllerCompat.Type.ADVANCED),
                left, base + 18 + COMPAT_SUB_ROW, wide, 18));

        int ourBottom = base + 18 + COMPAT_SUB_ROW + 20;
        com.qkdream.firecontrolcompat.FireControlCompat.LOGGER.info(
                "[firecontrolcompat] inputs rows: backY={} packBottom={} base={} left={} width={}",
                backY, lastBlock, base, left, width);
        this.compat$selectExtraRoom = backY > 0 ? Math.max(0, ourBottom + 30 - backY) : 0;
        this.compat$shiftBackButton(backY > 0 ? Math.max(backY, ourBottom + 8) : -1);
    }

    /**
     * The lowest bottom edge of the widgets the pack itself added to this page.
     *
     * <p>Measured rather than computed: the page's row height and grouping changed in 0.9.1, and
     * they can change again; the widgets themselves always carry their own positions.</p>
     */
    @Unique
    private int compat$packRowBottom(int backY) {
        int bottom = 0;
        for (net.minecraft.client.gui.components.Renderable renderable : this.renderables) {
            if (!(renderable instanceof net.minecraft.client.gui.components.AbstractWidget widget)) {
                continue;
            }
            if (this.compat$selectFirstButton == widget || this.compat$selectSecondButton == widget
                    || this.compat$selectNormalButton == widget || this.compat$selectAdvancedButton == widget) {
                continue;
            }
            int widgetBottom = widget.getY() + widget.getHeight();
            // The pack's back button is the one control placed below the rows; it is moved by
            // this mod afterwards, so it must not decide where the block starts.
            if (backY > 0 && Math.abs(widget.getY() - backY) <= 2) {
                continue;
            }
            if (widget.getY() > 0 && widgetBottom > bottom) {
                bottom = widgetBottom;
            }
            // Only the wide row controls describe the row's span: the two frequency buttons at the
            // right edge are twenty pixels wide, and taking the last widget of the page as the row
            // put our block out at that edge, half of it off the panel.
            if (widget.getWidth() >= 60) {
                int widgetLeft = widget.getX();
                int widgetRight = widget.getX() + widget.getWidth();
                if (this.compat$packRowLeft < 0 || widgetLeft < this.compat$packRowLeft) {
                    this.compat$packRowLeft = widgetLeft;
                }
                if (widgetRight > this.compat$packRowRight) {
                    this.compat$packRowRight = widgetRight;
                }
            }
        }
        return bottom;
    }


    /**
     * Makes sure the page can actually be scrolled far enough to show this mod's block.
     *
     * <p>The panel growth above leans on a constant in the pack's own code; if a release
     * moves or renames it the growth quietly stops happening - {@code require = 0} keeps
     * the game running instead of failing - and the block ends up below the reachable
     * area, which is exactly what "cannot scroll to the bottom" was. The room is added to
     * the scroll range directly here, so reaching the block never depends on that guess.</p>
     */
    @Inject(method = "maximumScrollOffset", at = @At("RETURN"), cancellable = true, require = 0)
    private void compat$selectScrollRoom(CallbackInfoReturnable<Double> cir) {
        if (this.compat$selectPage && this.compat$selectExtraRoom > 0) {
            cir.setReturnValue(cir.getReturnValueD() + this.compat$selectExtraRoom);
        }
    }

    /** How much further the page has to scroll so this mod's block is reachable. */
    @Unique
    private int compat$selectExtraRoom;

    /** The bottom of the pack's lowest row, ignoring its back button. */
    @Unique
    private int compat$lastBlockBottom(int backY) {
        int lowest = 0;
        for (GuiEventListener child : this.children()) {
            if (child instanceof AbstractWidget widget
                    && !this.fixedWidgets.contains(widget)
                    && widget.getY() != backY) {
                lowest = Math.max(lowest, widget.getY() + widget.getHeight());
            }
        }
        return backY > 0 ? Math.min(lowest, backY - 2) : lowest;
    }

    @Inject(method = "renderLabels", at = @At("TAIL"), require = 0)
    private void compat$renderSelectRowLabel(GuiGraphics graphics, CallbackInfo ci) {
        if (!this.compat$selectPage || this.compat$selectFirstButton == null) {
            return;
        }
        this.compat$refreshSelectButtons(false);
        int[] layout = this.compat$selectLayout();
        long now = System.currentTimeMillis();
        if (now - this.compat$lastRowProbe > 2000L) {
            this.compat$lastRowProbe = now;
            com.qkdream.firecontrolcompat.FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] inputs rows live: rowY={} scroll={} buttonY={} panelTop?={} extraRoom={}",
                    layout[3], this.scrollOffset,
                    this.compat$selectFirstButton == null ? -1 : this.compat$selectFirstButton.getY(),
                    this.compat$selectLayout()[3], this.compat$selectExtraRoom);
        }
        // Labels are drawn, not widgets: the pack moves its controls when the page is
        // scrolled and never moves a label, so the live scroll has to come off here or
        // the heading stays behind while its buttons slide away.
        graphics.drawString(
                this.font,
                Component.translatable("gui.firecontrolcompat.select_row_label"),
                layout[0],
                layout[3] + 2 - (int) Math.round(this.scrollOffset),
                -12765137,
                false);
    }

    /** The key the next press will be taken as the binding of. */
    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true, require = 0)
    private void compat$selectKeyPressed(int keyCode, int scanCode, int modifiers, CallbackInfoReturnable<Boolean> cir) {
        RemoteControllerCompat.Type type = this.compat$selectCapture;
        if (type == null) {
            return;
        }
        cir.setReturnValue(true);
        this.compat$selectCapture = null;
        this.compat$takeKey(type, ControllerKeyMappingCompat.signalForKeyboardKey(type, keyCode, scanCode));
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true, require = 0)
    private void compat$selectMouseClicked(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        RemoteControllerCompat.Type type = this.compat$selectCapture;
        if (type == null) {
            return;
        }
        cir.setReturnValue(true);
        this.compat$selectCapture = null;
        this.compat$takeKey(type, ControllerKeyMappingCompat.signalForMouseButton(type, button));
    }

    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true, require = 0)
    private void compat$selectMouseScrolled(
            double mouseX, double mouseY, double scrollX, double scrollY, CallbackInfoReturnable<Boolean> cir) {
        RemoteControllerCompat.Type type = this.compat$selectCapture;
        if (type == null) {
            return;
        }
        String signal = ControllerKeyMappingCompat.signalForMouseScroll(type, scrollY);
        if (signal == null) {
            return;
        }
        cir.setReturnValue(true);
        this.compat$selectCapture = null;
        this.compat$takeKey(type, signal);
    }

    /** Files the key that was pressed, or says why it cannot be used. */
    @Unique
    private void compat$takeKey(RemoteControllerCompat.Type type, String signal) {
        if (signal == null) {
            this.compat$message("message.firecontrolcompat.select_key_unbound");
        } else {
            this.compat$send(this.compat$slotOf(type), signal);
            this.compat$message("message.firecontrolcompat.select_bound");
        }
        this.compat$refreshSelectButtons(true);
    }

    /** Takes a frequency half from the hand, the way the rows above do. */
    @Unique
    private void compat$bindFrequency(boolean first) {
        this.compat$send(
                first ? SonarSelectBindPayload.SLOT_WIRELESS_FIRST : SonarSelectBindPayload.SLOT_WIRELESS_SECOND, "");
        this.compat$message("message.firecontrolcompat.select_sent");
    }

    @Unique
    private void compat$capture(RemoteControllerCompat.Type type) {
        this.compat$selectCapture = this.compat$selectCapture == type ? null : type;
        if (this.compat$selectCapture != null) {
            this.compat$message(type == RemoteControllerCompat.Type.ADVANCED
                    ? "message.firecontrolcompat.select_press_advanced"
                    : "message.firecontrolcompat.select_press_normal");
        }
        this.compat$refreshSelectButtons(true);
    }

    @Unique
    private void compat$send(int slot, String key) {
        if (this.compat$selectPos == null) {
            return;
        }
        PacketDistributor.sendToServer(
                new SonarSelectBindPayload(this.compat$selectPos, this.compat$selectSubLevel, slot, key));
    }

    @Unique
    private void compat$message(String key) {
        if (this.minecraft != null && this.minecraft.player != null) {
            this.minecraft.player.displayClientMessage(Component.translatable(key), true);
        }
    }

    @Unique
    private int compat$slotOf(RemoteControllerCompat.Type type) {
        return type == RemoteControllerCompat.Type.ADVANCED
                ? SonarSelectBindPayload.SLOT_REMOTE_ADVANCED
                : SonarSelectBindPayload.SLOT_REMOTE_NORMAL;
    }

    /**
     * Writes the four buttons from the bindings the computer last reported. The
     * answer to a press arrives a moment after it is made, so the labels are
     * written again whenever what they say has changed.
     */
    @Unique
    private void compat$refreshSelectButtons(boolean force) {
        if (this.compat$selectFirstButton == null) {
            return;
        }
        String first = SonarSelectInputState.first();
        String second = SonarSelectInputState.second();
        String normal = SonarSelectInputState.normalKey();
        String advanced = SonarSelectInputState.advancedKey();
        String signature = first + "\u0000" + second + "\u0000" + normal + "\u0000" + advanced + "\u0000"
                + (this.compat$selectCapture == null ? "" : this.compat$selectCapture.name());
        if (!force && signature.equals(this.compat$selectShown)) {
            return;
        }
        this.compat$selectShown = signature;
        // The two frequency squares stay blank, exactly like the pack's own frequency buttons: their
        // content is a colour/label drawn by the pack, and printing our text inside turned them into
        // two clipped Chinese labels.
        this.compat$selectNormalButton.setMessage(
                this.compat$remoteText(RemoteControllerCompat.Type.NORMAL, normal));
        this.compat$selectAdvancedButton.setMessage(
                this.compat$remoteText(RemoteControllerCompat.Type.ADVANCED, advanced));
        // Only this mod's own button carries a tone; the pack's brass button is coloured by the pack.
        if (this.compat$selectNormalButton instanceof CompatToggleButton normalOwn) {
            normalOwn.setTone(this.compat$selectCapture == RemoteControllerCompat.Type.NORMAL
                    ? CompatToggleButton.Tone.GREEN
                    : CompatToggleButton.Tone.NORMAL);
        }
        if (this.compat$selectAdvancedButton instanceof CompatToggleButton advancedOwn) {
            advancedOwn.setTone(this.compat$selectCapture == RemoteControllerCompat.Type.ADVANCED
                    ? CompatToggleButton.Tone.GREEN
                    : CompatToggleButton.Tone.NORMAL);
        }
    }

    @Unique
    private Component compat$frequencyText(String key, String name) {
        return Component.translatable(
                key,
                name == null || name.isEmpty()
                        ? Component.translatable("gui.firecontrolcompat.select_none")
                        : Component.literal(name));
    }

    @Unique
    private Component compat$remoteText(RemoteControllerCompat.Type type, String signal) {
        Component prefix = Component.literal(type == RemoteControllerCompat.Type.ADVANCED ? "A: " : "N: ");
        if (this.compat$selectCapture == type) {
            return prefix.copy().append(Component.translatable("screen.create_fire_control.press_key"));
        }
        if (signal == null || signal.isEmpty()) {
            return prefix.copy().append(Component.translatable("gui.firecontrolcompat.select_none"));
        }
        Component shown;
        try {
            shown = ControllerKeyMappingCompat.displayKey(type, signal);
        } catch (Throwable ignored) {
            shown = Component.literal(signal);
        }
        String name = "";
        try {
            name = DriveByWireCompat.displayTranslationKey(type, signal);
        } catch (Throwable ignored) {
        }
        return name.isEmpty()
                ? prefix.copy().append(shown)
                : prefix.copy().append(shown).append(" -> ").append(Component.translatable(name));
    }

    /**
     * Where our row sits, worked out the way the page works out its own rows:
     * the panel is clamped to the window, scrolled by the slider, and the rows
     * step down from its top.
     */
    @Unique
    private int[] compat$selectLayout() {
        int horizontalMargin = Mth.clamp(this.width / 24, 12, 28);
        int verticalMargin = Mth.clamp(this.height / 28, 10, 24);
        int panelWidth = Math.min(COMPAT_INPUTS_WIDTH, Math.max(8, this.width - horizontalMargin * 2));
        int panelHeight = Math.min(
                COMPAT_INPUTS_PANEL + COMPAT_SELECT_ROOM, Math.max(8, this.height - verticalMargin * 2));
        int panelLeft = (this.width - panelWidth) / 2;
        int panelTop = (this.height - panelHeight) / 2 - (int) Math.round(this.scrollOffset);
        int left = panelLeft + 14;
        int right = panelLeft + panelWidth - 24;
        int gap = 6;
        int width = Math.max(72, (right - left - gap) / 2);
        int rowY = this.compat$selectRowY > 0
                ? this.compat$selectRowY
                : panelTop + COMPAT_INPUTS_TOP + COMPAT_SELECT_ROW * COMPAT_ROW_STEP;
        return new int[] {left, width, gap, rowY};
    }

    /** Moves the back button below our row, so the two do not sit on each other. */
    @Unique
    private void compat$shiftBackButton(int targetY) {
        if (targetY <= 0) {
            return;
        }
        String back = Component.translatable("gui.back").getString();
        for (GuiEventListener child : this.children()) {
            if (child instanceof AbstractWidget widget
                    && widget.getMessage() != null
                    && back.equals(widget.getMessage().getString())) {
                widget.setY(targetY);
            }
        }
    }

    /** Where the pack's own back button sits, or -1 when this page has none. */
    @Unique
    private int compat$backButtonY() {
        String back = Component.translatable("gui.back").getString();
        int found = -1;
        for (GuiEventListener child : this.children()) {
            if (child instanceof AbstractWidget widget
                    && widget.getMessage() != null
                    && back.equals(widget.getMessage().getString())
                    && !this.fixedWidgets.contains(widget)) {
                found = Math.max(found, widget.getY());
            }
        }
        return found;
    }
}

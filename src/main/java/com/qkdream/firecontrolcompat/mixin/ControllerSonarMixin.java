package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.client.StabilizerControllerScreen;
import com.hooya.stabilizedturret.network.OpenControllerPayload;
import com.qkdream.firecontrolcompat.client.CompatToggleButton;
import com.qkdream.firecontrolcompat.client.SonarSettingsScreen;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
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
 * Puts the sonar page on the home screen of the fire control computer, beside
 * the radar and aircraft pages.
 *
 * <p>The row that opens the page takes the place of the done and cancel
 * buttons, which are moved down one row to make room for it. The home panel is
 * grown by that same row, so the footer keeps the margin the other pages give
 * it, and the page is handed one row of scroll room: that is what makes the
 * slider the taller pages use appear here as well, so the home rows can be
 * paged up and down.</p>
 *
 * <p>Every hook is optional: should a later fire control release reshape the
 * home screen, the game starts without the row instead of failing to load.</p>
 */
@Mixin(StabilizerControllerScreen.class)
public abstract class ControllerSonarMixin extends Screen {

    @Shadow
    private double scrollOffset;

    @Unique
    private BlockPos compat$controllerPos;

    @Unique
    private UUID compat$controllerSubLevel;

    /** Whether the page being built is the one that carries our row. */
    @Unique
    private boolean compat$sonarRow;

    protected ControllerSonarMixin(Component title) {
        super(title);
    }

    @Inject(method = "<init>", at = @At("TAIL"), require = 0)
    private void compat$initSonarPage(OpenControllerPayload initial, CallbackInfo ci) {
        this.compat$controllerPos = initial.pos();
        this.compat$controllerSubLevel = initial.controllerSubLevelId();
    }

    /** Drops the flag for every page, so only the home page changes shape. */
    @Inject(method = "buildPage", at = @At("HEAD"), require = 0)
    private void compat$clearSonarRow(CallbackInfo ci) {
        this.compat$sonarRow = false;
    }

    /** Claims the flag before the home page is measured, so it grows with our row. */
    @Inject(method = "buildHomePage", at = @At("HEAD"), require = 0)
    private void compat$claimSonarRow(CallbackInfo ci) {
        this.compat$sonarRow = true;
    }

    @Inject(method = "buildHomePage", at = @At("TAIL"), require = 0)
    private void compat$addSonarButton(CallbackInfo ci) {
        int row = this.compat$shiftFooter();
        int left = this.width / 2 - 125;
        var sonar = this.addRenderableWidget(new CompatToggleButton(
                left, row, 120, 20,
                Component.translatable("gui.firecontrolcompat.sonar_settings"),
                button -> this.compat$openSonarPage()));
        // This mod's own settings sit beside the sonar page, the same way: one entry
        // on the home menu, and a page laid out entirely by us.
        var eye = this.addRenderableWidget(new CompatToggleButton(
                left + 130, row, 120, 20,
                Component.translatable("gui.firecontrolcompat.eye_settings"),
                button -> this.compat$openEyePage()));
        // The two entries are reported once per page build: without this line there is
        // no way to tell "the player never opened the settings page" from "the menu row
        // that opens it was never placed", and both look like the page is missing.
        com.qkdream.firecontrolcompat.FireControlCompat.LOGGER.info(
                "[firecontrolcompat] home entries: sonar=({},{} {}x{}) eye=({},{} {}x{}) window={}x{} homeTop={}",
                sonar.getX(), sonar.getY(), sonar.getWidth(), sonar.getHeight(),
                eye.getX(), eye.getY(), eye.getWidth(), eye.getHeight(),
                this.width, this.height, this.compat$homeTop());
        this.compat$followScroll(sonar, eye);
    }

    /**
     * Puts the two home entries on the page's own scrolling list.
     *
     * <p>The home page is given a row of scroll room, and the pack scrolls by
     * moving the widgets in {@code contentWidgets}. A widget that is only
     * registered for rendering stays where it was, so as soon as the page was
     * scrolled even a little the sonar and settings entries drifted off their row
     * - they were drawn over another row, or below the panel where they could not
     * be clicked at all. Adding them to that list keeps them on their row.</p>
     */
    @Unique
    private void compat$followScroll(Object... widgets) {
        try {
            for (java.lang.reflect.Field field : this.getClass().getDeclaredFields()) {
                if (!"contentWidgets".equals(field.getName())
                        || !java.util.List.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                field.setAccessible(true);
                Object value = field.get(this);
                if (!(value instanceof java.util.List<?> list)) {
                    return;
                }
                @SuppressWarnings("unchecked")
                java.util.List<Object> target = (java.util.List<Object>) list;
                for (Object widget : widgets) {
                    if (!target.contains(widget)) {
                        target.add(widget);
                    }
                }
                return;
            }
        } catch (Throwable ignored) {
            // Rendered but not scrolled: the entries are still usable while the page
            // is not scrolled, so a miss here is not worth failing the screen over.
        }
    }

    /** Moves the done and cancel buttons down a row and reports the freed row. */
    @Unique
    private int compat$shiftFooter() {
        String done = Component.translatable("gui.done").getString();
        String cancel = Component.translatable("gui.cancel").getString();
        int row = this.compat$homeTop() + 90;
        boolean found = false;
        for (GuiEventListener child : this.children()) {
            if (!(child instanceof AbstractWidget widget) || widget.getMessage() == null) {
                continue;
            }
            String text = widget.getMessage().getString();
            if (done.equals(text) || cancel.equals(text)) {
                if (!found) {
                    row = widget.getY();
                    found = true;
                }
                widget.setY(widget.getY() + 30);
            }
        }
        return row;
    }

    /**
     * Grows the home panel by the row our button took. The home page is the one
     * panel measuring 214, so only it grows.
     */
    @ModifyConstant(method = "desiredPanelSize", constant = @Constant(intValue = 214), require = 0)
    private int compat$growHomePanel(int height) {
        return this.compat$sonarRow ? height + 30 : height;
    }

    /**
     * Hands the home page a row of scroll room. The taller pages scroll because
     * their content is longer than the window; the home page has to be given
     * the room outright for the same slider to appear on it.
     */
    @Inject(method = "maximumScrollOffset", at = @At("RETURN"), cancellable = true, require = 0)
    private void compat$homeScrollRoom(CallbackInfoReturnable<Double> cir) {
        if (this.compat$sonarRow && cir.getReturnValueD() <= 0.0D) {
            cir.setReturnValue(30.0D);
        }
    }

    @Unique
    private void compat$openEyePage() {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.setScreen(new com.qkdream.firecontrolcompat.client.FireControlEyeSettingsScreen(
                (Screen) (Object) this, this.compat$controllerPos, this.compat$controllerSubLevel));
    }

    @Unique
    private void compat$openSonarPage() {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.setScreen(new SonarSettingsScreen(
                (Screen) (Object) this, this.compat$controllerPos, this.compat$controllerSubLevel));
    }

    /** Mirrors {@code centeredPanel}'s clamping so injected rows line up with the vanilla ones. */
    @Unique
    private int compat$homeTop() {
        int verticalMargin = Mth.clamp(this.height / 28, 10, 24);
        int panelHeight = Math.min(244, Math.max(8, this.height - verticalMargin * 2));
        return (this.height - panelHeight) / 2 + 42 - (int) Math.round(this.scrollOffset);
    }
}

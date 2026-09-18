package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.client.StabilizerControllerScreen;
import com.hooya.stabilizedturret.network.OpenControllerPayload;
import com.qkdream.firecontrolcompat.FireControlLeadSettings;
import com.qkdream.firecontrolcompat.client.CompatToggleButton;
import com.qkdream.firecontrolcompat.network.IffOpenPayload;
import com.qkdream.firecontrolcompat.network.IffStatusPayload;
import com.qkdream.firecontrolcompat.network.LeadSettingsPayload;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.network.PacketDistributor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Adds the consolidated "lead settings" (提前量设置) section to the fire
 * control computer's fire-control page:
 *
 * <ul>
 * <li>cannon lead indicator toggle,</li>
 * <li>beam missile vertical / horizontal launch mode,</li>
 * <li>beam missile proximity fuse range.</li>
 * </ul>
 *
 * <p>The fire-control page panel is grown by 150 pixels so the section fits
 * below the vanilla rows, and the values are streamed to the server on save
 * through {@link LeadSettingsPayload}.</p>
 */
@Mixin(StabilizerControllerScreen.class)
public abstract class ControllerLeadSettingsMixin extends Screen {

    /** Vanilla fire-control page height (226) plus room for the lead section. */
    @Unique
    private static final int COMPAT_PANEL_HEIGHT = 376;

    /** Total content height of the fire-control page from the viewport top. */
    @Unique
    private static final int COMPAT_CONTENT_HEIGHT = 322;

    @Shadow
    private double scrollOffset;

    /** True while the fire control page is being built / displayed. */
    @Unique
    private boolean compat$leadPage;

    /** Computer this screen belongs to, used by the identity section. */
    @Unique
    private BlockPos compat$controllerPos;

    @Unique
    private UUID compat$controllerSubLevel;

    @Unique
    private boolean compat$gunLead;

    @Unique
    private boolean compat$verticalLaunch;

    @Unique
    private double compat$proximityRange;

    @Unique
    private EditBox compat$proximityBox;

    protected ControllerLeadSettingsMixin(Component title) {
        super(title);
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void compat$initFromSettings(OpenControllerPayload initial, CallbackInfo ci) {
        this.compat$controllerPos = initial.pos();
        this.compat$controllerSubLevel = initial.controllerSubLevelId();
        this.compat$gunLead = FireControlLeadSettings.gunLeadEnabled();
        this.compat$verticalLaunch = FireControlLeadSettings.beamVerticalLaunch();
        this.compat$proximityRange = FireControlLeadSettings.beamProximityRange();
    }

    @Inject(method = "buildPage", at = @At("HEAD"))
    private void compat$resetLeadPage(CallbackInfo ci) {
        this.compat$leadPage = false;
        this.compat$proximityBox = null;
    }

    @Inject(method = "openPage", at = @At("HEAD"))
    private void compat$resetLeadPageOnOpen(CallbackInfo ci) {
        this.compat$leadPage = false;
        this.compat$proximityBox = null;
    }

    @Inject(method = "rebuildPage", at = @At("HEAD"))
    private void compat$resetLeadPageOnRebuild(CallbackInfo ci) {
        this.compat$leadPage = false;
        this.compat$proximityBox = null;
    }

    @Inject(method = "buildFireControlPage", at = @At("HEAD"))
    private void compat$markLeadPage(CallbackInfo ci) {
        this.compat$leadPage = true;
    }

    @Inject(method = "buildFireControlPage", at = @At("TAIL"))
    private void compat$appendLeadSettingsSection(CallbackInfo ci) {
        int left = this.width / 2 - 125;
        int top = this.compat$fireControlTop();

        this.addRenderableWidget(
                new CompatToggleButton(
                                left,
                                top + 180,
                                250,
                                20,
                                this.compat$toggleText("gui.firecontrolcompat.gun_lead", this.compat$gunLead),
                                button -> {
                                    this.compat$gunLead = !this.compat$gunLead;
                                    button.setMessage(
                                            this.compat$toggleText("gui.firecontrolcompat.gun_lead", this.compat$gunLead));
                                    ((CompatToggleButton) button)
                                            .setTone(this.compat$gunLead
                                                    ? CompatToggleButton.Tone.GREEN
                                                    : CompatToggleButton.Tone.NORMAL);
                                })
                        .setTone(this.compat$gunLead
                                ? CompatToggleButton.Tone.GREEN
                                : CompatToggleButton.Tone.NORMAL));

        this.addRenderableWidget(
                new CompatToggleButton(
                                left,
                                top + 210,
                                250,
                                20,
                                this.compat$launchModeText(),
                                button -> {
                                    this.compat$verticalLaunch = !this.compat$verticalLaunch;
                                    button.setMessage(this.compat$launchModeText());
                                    ((CompatToggleButton) button)
                                            .setTone(this.compat$verticalLaunch
                                                    ? CompatToggleButton.Tone.GREEN
                                                    : CompatToggleButton.Tone.NORMAL);
                                })
                        .setTone(this.compat$verticalLaunch
                                ? CompatToggleButton.Tone.GREEN
                                : CompatToggleButton.Tone.NORMAL));

        this.compat$proximityBox = new EditBox(
                this.font,
                left + 150,
                top + 240,
                60,
                18,
                Component.literal(String.format(Locale.ROOT, "%.1f", this.compat$proximityRange)));
        this.compat$proximityBox.setMaxLength(5);
        this.compat$proximityBox.setResponder(value -> {
            try {
                double parsed = Double.parseDouble(value.trim());
                if (parsed >= FireControlLeadSettings.MIN_PROXIMITY_RANGE
                        && parsed <= FireControlLeadSettings.MAX_PROXIMITY_RANGE) {
                    this.compat$proximityRange = parsed;
                }
            } catch (NumberFormatException ignored) {
            }
        });
        this.addRenderableWidget(this.compat$proximityBox);

        if (IffStatusPayload.isLinked()) {
            this.addRenderableWidget(new CompatToggleButton(
                    left,
                    top + 276,
                    250,
                    20,
                    Component.translatable(IffStatusPayload.isBandSet()
                            ? "gui.firecontrolcompat.iff_link_set"
                            : "gui.firecontrolcompat.iff_link_empty"),
                    button -> PacketDistributor.sendToServer(
                            new IffOpenPayload(this.compat$controllerPos, this.compat$controllerSubLevel))));
        }
    }

    /**
     * Grows the fire-control page panel to fit the lead settings section.
     * {@code centeredPanel} is called by {@code viewportPanelBounds} with the
     * page's fixed desired size; the second argument (height) is replaced.
     */
    @ModifyArg(
            method = "viewportPanelBounds",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/hooya/stabilizedturret/client/StabilizerControllerScreen;centeredPanel(II)Lcom/hooya/stabilizedturret/client/StabilizerControllerScreen$PanelBounds;"
            ),
            index = 1
    )
    private int compat$enlargeFireControlPanel(int height) {
        return this.compat$leadPage ? COMPAT_PANEL_HEIGHT : height;
    }

    /**
     * Lets the lead section scroll when the window is too small to show the
     * grown panel. Vanilla computes the scroll range from the page's fixed
     * desired size (226), which would never expose the injected rows, so the
     * range is extended to the real content height while this page is open.
     */
    @Inject(method = "maximumScrollOffset", at = @At("RETURN"), cancellable = true)
    private void compat$leadPageScrollRange(CallbackInfoReturnable<Double> cir) {
        if (!this.compat$leadPage) {
            return;
        }
        int margin = Mth.clamp(this.height / 28, 10, 24);
        double viewportHeight = Math.min(COMPAT_PANEL_HEIGHT, Math.max(8, this.height - margin * 2));
        double extended = COMPAT_CONTENT_HEIGHT - viewportHeight + 22.0D;
        if (extended > cir.getReturnValue()) {
            cir.setReturnValue(extended);
        }
    }

    @Inject(method = "renderLabels", at = @At("TAIL"))
    private void compat$renderLeadSectionLabels(GuiGraphics graphics, CallbackInfo ci) {
        if (!this.compat$leadPage) {
            return;
        }
        int left = this.width / 2 - 125;
        int top = this.compat$fireControlTop();
        int color = -12765137;
        graphics.drawString(
                this.font, Component.translatable("gui.firecontrolcompat.lead_settings"), left, top + 168, color, false);
        graphics.drawString(
                this.font,
                Component.translatable("gui.firecontrolcompat.beam_proximity_range"),
                left,
                top + 245,
                color,
                false);
        if (IffStatusPayload.isLinked()) {
            graphics.drawString(
                    this.font, Component.translatable("gui.firecontrolcompat.iff_section"), left, top + 262, color, false);
        }
    }

    @Inject(method = "saveAndClose", at = @At("HEAD"))
    private void compat$saveLeadSettings(CallbackInfo ci) {
        double range = Double.isFinite(this.compat$proximityRange)
                ? this.compat$proximityRange
                : FireControlLeadSettings.beamProximityRange();
        FireControlLeadSettings.apply(
                this.compat$gunLead, this.compat$verticalLaunch, range);
        PacketDistributor.sendToServer(
                new LeadSettingsPayload(
                        this.compat$gunLead, this.compat$verticalLaunch, range));
    }

    /** Mirrors {@code centeredPanel}'s clamping so injected rows align with the vanilla rows. */
    @Unique
    private int compat$fireControlTop() {
        int verticalMargin = Mth.clamp(this.height / 28, 10, 24);
        int panelHeight = Math.min(COMPAT_PANEL_HEIGHT, Math.max(8, this.height - verticalMargin * 2));
        return (this.height - panelHeight) / 2 + 42 - (int) Math.round(this.scrollOffset);
    }

    @Unique
    private Component compat$toggleText(String key, boolean enabled) {
        return Component.translatable(key, Component.translatable(enabled ? "options.on" : "options.off"));
    }

    @Unique
    private Component compat$launchModeText() {
        return Component.translatable(
                "gui.firecontrolcompat.beam_launch_mode",
                Component.translatable(
                        this.compat$verticalLaunch
                                ? "gui.firecontrolcompat.vertical_launch"
                                : "gui.firecontrolcompat.horizontal_launch"));
    }
}

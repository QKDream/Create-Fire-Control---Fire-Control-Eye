package com.qkdream.firecontrolcompat.mixin;

import java.util.List;

import com.qkdream.firecontrolcompat.compat.yuushya.YuushyaBigCannonCompat;
import com.qkdream.firecontrolcompat.compat.yuushya.YuushyaCannonType;
import com.qkdream.firecontrolcompat.network.YuushyaCannonTypePayload;
import com.yuushya.modelling.blockentity.showblock.ShowBlockEntity;
import com.yuushya.modelling.gui.showblock.ShowBlockScreen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * One cannon type button for the modelling entity block, merged with the one
 * the fire control mod puts in this screen.
 *
 * <p>The fire control mod adds its own switch for its nethersteel medium cannon
 * barrel. That switch is taken over here and turned into a single button that
 * cycles through every barrel this block can be: none, the medium cannon barrel
 * of the fire control mod and the big cannon barrel of this mod. It sits in the
 * same spot and works the same way, the choice is sent to the server which
 * converts the block in place.</p>
 *
 * <p>Which mixin injects its button first is not fixed, so the switch of the
 * fire control mod is dropped on every frame instead of only once: by the time
 * the screen draws, both injections have long run and only one button is
 * left.</p>
 */
@Mixin(ShowBlockScreen.class)
public abstract class YuushyaShowBlockScreenMixin extends Screen {

    /** Name of the button of the fire control mod, used to recognise it. */
    @Unique
    private static final String firecontrolcompat$FOREIGN_NONE = "gui.create_fire_control.yuushya_cannon_type.none";

    @Unique
    private static final String firecontrolcompat$FOREIGN_MEDIUM = "gui.create_fire_control.yuushya_cannon_type.medium_barrel";

    /** The merged button of this screen, null while it is not built. */
    @Unique
    private CycleButton<YuushyaCannonType> firecontrolcompat$cannonSelector;

    @Shadow
    @Final
    private ShowBlockEntity blockEntity;

    protected YuushyaShowBlockScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void firecontrolcompat$addCannonTypeSelector(CallbackInfo ci) {
        if (!YuushyaBigCannonCompat.isAvailable()) {
            return;
        }
        List<YuushyaCannonType> types = YuushyaBigCannonCompat.selectableTypes();
        if (types.size() < 2) {
            return;
        }
        YuushyaCannonType current = YuushyaBigCannonCompat.currentType(this.blockEntity.getBlockState());
        if (!types.contains(current)) {
            current = types.get(0);
        }
        int buttonWidth = this.firecontrolcompat$buttonWidth(current);
        CycleButton<YuushyaCannonType> selector = CycleButton.builder(this::firecontrolcompat$cannonTypeText)
                .displayOnlyValue()
                .withValues(types)
                .withInitialValue(current)
                .withTooltip(value -> Tooltip.create(
                        Component.translatable("gui.firecontrolcompat.yuushya_cannon_type.tooltip")))
                .create(this.firecontrolcompat$buttonX(buttonWidth), 10, buttonWidth, 20, Component.empty(),
                        (button, chosen) -> {
                            int updatedWidth = this.firecontrolcompat$buttonWidth(chosen);
                            button.setWidth(updatedWidth);
                            button.setX(this.firecontrolcompat$buttonX(updatedWidth));
                            PacketDistributor.sendToServer(new YuushyaCannonTypePayload(
                                    this.blockEntity.getBlockPos(), chosen.ordinal()));
                        });
        this.firecontrolcompat$cannonSelector = selector;
        this.addRenderableWidget(selector);
        this.firecontrolcompat$dropForeignSelector();
    }

    /** Drops the switch of the fire control mod, whatever order it was added in. */
    @Inject(method = "render", at = @At("HEAD"))
    private void firecontrolcompat$mergeCannonTypeSelector(GuiGraphics graphics, int mouseX, int mouseY,
            float partialTick, CallbackInfo ci) {
        if (this.firecontrolcompat$cannonSelector != null) {
            this.firecontrolcompat$dropForeignSelector();
        }
    }

    @Unique
    private void firecontrolcompat$dropForeignSelector() {
        for (boolean found = true; found; ) {
            found = false;
            for (GuiEventListener listener : this.children()) {
                if (listener == this.firecontrolcompat$cannonSelector
                        || !(listener instanceof CycleButton<?> button)
                        || !firecontrolcompat$isForeignSelector(button)) {
                    continue;
                }
                this.removeWidget(listener);
                found = true;
                break;
            }
        }
    }

    /**
     * The switch of the fire control mod is a boolean cycle button that shows
     * the cannon type it sets, and no other button of this screen names a
     * cannon type, so its own two names identify it exactly.
     */
    @Unique
    private static boolean firecontrolcompat$isForeignSelector(CycleButton<?> button) {
        String text = button.getMessage().getString();
        return text.equals(Component.translatable(firecontrolcompat$FOREIGN_NONE).getString())
                || text.equals(Component.translatable(firecontrolcompat$FOREIGN_MEDIUM).getString());
    }

    @Unique
    private Component firecontrolcompat$cannonTypeText(YuushyaCannonType type) {
        // Single cannon families show the name of the barrel block they stand for, so the button
        // says which part the player is about to build.
        Component barrelName = YuushyaBigCannonCompat.barrelDisplayName(type);
        if (barrelName != null) {
            return barrelName;
        }
        if (type == YuushyaCannonType.MEDIUM) {
            return Component.translatable("gui.firecontrolcompat.yuushya_cannon_type.medium_barrel");
        }
        if (type == YuushyaCannonType.BIG) {
            return Component.translatable("gui.firecontrolcompat.yuushya_cannon_type.big_cannon_barrel");
        }
        return Component.translatable("gui.firecontrolcompat.yuushya_cannon_type.none");
    }

    @Unique
    private int firecontrolcompat$buttonWidth(YuushyaCannonType type) {
        return this.font.width(this.firecontrolcompat$cannonTypeText(type)) + 12;
    }

    /** The spot the fire control mod used for its switch, so nothing moves. */
    @Unique
    private int firecontrolcompat$buttonX(int buttonWidth) {
        return Math.max(252, (this.width - buttonWidth) / 2);
    }
}
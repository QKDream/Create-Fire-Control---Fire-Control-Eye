package com.qkdream.firecontrolcompat.blueprint.photomancy;

import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.texture.Icons;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import dev.rew1nd.sableschematicapi.tool.client.mode.BlueprintToolMode;
import dev.rew1nd.sableschematicapi.tool.client.mode.BlueprintToolModes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;

/**
 * A fourth mode on the Sable blueprint tool, beside the library, delete and
 * sub-level modes.
 *
 * <p>This is the integration point the rest of the mod exists for: rather than the
 * export living in its own screen, it sits where the player already is when they
 * want it - holding the blueprint tool, in the same page strip as placement,
 * sub-level management, welding and deletion.
 *
 * <p>Registered through {@link BlueprintToolModes#register}, which is a public API
 * in the blueprint tool, so no mixin is needed for any of this.
 */
public final class BlueprintExportToolMode implements BlueprintToolMode {

    public static final String MODE_ID = "export";

    @Override
    public ResourceLocation id() {
        return BlueprintToolModes.id(MODE_ID);
    }

    @Override
    public Component label() {
        return Component.translatable("sableblueprintexporter.mode.export");
    }

    @Override
    public IGuiTexture icon() {
        return Icons.EXPORT;
    }

    @Override
    public UIElement createPage(Player player) {
        return BlueprintExportPage.create(player);
    }
}

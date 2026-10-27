package com.qkdream.firecontrolcompat.mixin;

import com.qkdream.firecontrolcompat.deco.DecoRegistry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lays the Fire Control Eye tabs out in sections with a reserved banner row in
 * front of each one.
 *
 * <p>{@link CreativeModeTab.ItemDisplayBuilder} rejects empty stacks and stores
 * the contents in a set, so a tab cannot reserve a blank row through the public
 * builder API. This injection only touches our own tabs and rewrites their
 * content list afterwards, leaving every other tab (and the search tab list)
 * alone. {@code require = 0} keeps the game starting even if the vanilla method
 * ever changes shape - the tab then simply has no banner rows.</p>
 */
@Mixin(CreativeModeTab.class)
public abstract class CreativeModeTabBannerGapMixin {

    @Shadow
    private Collection<ItemStack> displayItems;

    @Inject(method = "buildContents", at = @At("TAIL"), require = 0)
    private void firecontrolcompat$reserveBannerRows(CreativeModeTab.ItemDisplayParameters parameters,
                                                     CallbackInfo callback) {
        DecoRegistry.Sections sections = DecoRegistry.sectionsFor((CreativeModeTab) (Object) this);
        if (sections == null) {
            return;
        }
        List<ItemStack> source = new ArrayList<>(this.displayItems);
        List<ItemStack> items = new ArrayList<>(source.size() + sections.count() * DecoRegistry.GRID_COLUMNS);
        int cursor = 0;
        for (int section = 0; section < sections.count(); section++) {
            for (int column = 0; column < DecoRegistry.GRID_COLUMNS; column++) {
                items.add(ItemStack.EMPTY);
            }
            int taken = 0;
            int wanted = Math.min(sections.itemCount(section), source.size() - cursor);
            for (; taken < wanted; taken++) {
                items.add(source.get(cursor++));
            }
            while (taken % DecoRegistry.GRID_COLUMNS != 0) {
                items.add(ItemStack.EMPTY);
                taken++;
            }
        }
        while (cursor < source.size()) {
            items.add(source.get(cursor++));
        }
        this.displayItems = items;
    }
}

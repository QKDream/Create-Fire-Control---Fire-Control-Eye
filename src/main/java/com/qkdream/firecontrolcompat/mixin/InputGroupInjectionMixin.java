package com.qkdream.firecontrolcompat.mixin;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Puts this mod's two sonar actions into the fire control screen's own input groups.
 *
 * <p>Fire Control 0.9.1 draws its binding page from {@code INPUT_GROUPS}, and a row's columns depend
 * on which terminals are bound. A separate block of our own can never line up with that, so the two
 * actions are added to the groups and the pack lays them out itself.</p>
 *
 * <p>Every injection carries {@code require = 0} and every step is inside a try/catch: a renamed
 * method or a refused reflective write must leave the page exactly as the pack built it. The two
 * per-action collections are grown from {@code buildInputsPage} rather than from the constructor,
 * because a constructor injection has to match the constructor's parameters exactly and that is the
 * sort of thing that took the game down at bootstrap.</p>
 */
@Mixin(targets = "com.hooya.stabilizedturret.client.StabilizerControllerScreen")
public abstract class InputGroupInjectionMixin {

    /**
     * The two actions this mod adds, and how far the per-action collections have to grow.
     *
     * <p>0.9.4 raised the pack's own action count to 44 ({@code INPUT_ACTION_COUNT}), and its list
     * of input groups already uses 16 - in the "air" section - for one of its own rows. Ours used to
     * be 16 and 17, which meant action 16 was the pack's row wearing our label ("sonar target A"
     * turned up under the airborne heading) and 17 belonged to no section at all. They are now the
     * first two ids past the pack's own range, so they collide with nothing.</p>
     */
    private static final int COMPAT_ACTION_A = -1;
    private static final int COMPAT_ACTION_B = -1;
    /**
     * How far the per-action collections are grown: to the pack's own action count, no further.
     * This mod adds no action of its own to this page any more (see below), so the growth is only
     * a guard against a null stack in the pack's own arrays.
     */
    private static final int COMPAT_ACTIONS = 44;

    /**
     * How much of the bottom of the screen is the footer band. Buttons sitting
     * in it are the page's own back / done keys and must never scroll.
     */
    private static final int FOOTER_BAND = 40;

    @Shadow
    @Mutable
    private List<String> wirelessRemoteKeys;

    @Shadow
    @Mutable
    private ItemStack[][] inputFrequencies;

    /**
     * The key name of every action for the normal and advanced terminals.
     *
     * <p>These two are what {@code remoteKeyButton} reads, and the pack pads them to its own
     * sixteen actions only in the constructor - the page itself never grows them. Our two actions
     * therefore read past the end of the list on every page build, and the exception takes the
     * whole page down with it: {@code buildPage} has already cleared the scroll list by then, so
     * the page is left with no widgets at all. That is why the left column of text kept scrolling
     * while the right column of squares stood still.</p>
     */
    @Shadow
    private List<String> normalRemoteKeys;

    @Shadow
    private List<String> advancedRemoteKeys;

    /** The page's scrolling content: only what is in here moves when the page is scrolled. */
    @Shadow
    private java.util.List<net.minecraft.client.gui.components.AbstractWidget> contentWidgets;

    /** How far down the page is scrolled; the target's own field, as the sonar page also reads it. */
    @Shadow
    private double scrollOffset;

    /** Widget positions taken before the pack's own scroll handler ran. */
    @org.spongepowered.asm.mixin.Unique
    private java.util.IdentityHashMap<net.minecraft.client.gui.components.AbstractWidget, Integer> firecontrolcompat$scrollBefore;

    /** The scroll offset taken at the same moment as the snapshot above. */
    @org.spongepowered.asm.mixin.Unique
    private double firecontrolcompat$scrollOffsetBefore;

    /** How many scroll lines have been logged for this page, so a wheel cannot flood the log. */
    @org.spongepowered.asm.mixin.Unique
    private int firecontrolcompat$scrollLogs;


    /**
     * Grows the per-action collections before the page reads them.
     *
     * <p>The pack sizes all of them to sixteen. Our actions index them at sixteen and seventeen, so
     * without this the page either runs off the end of a key list or hands a null stack to the item
     * renderer. The key name lists of the normal and advanced terminals are grown too: those are
     * read by {@code remoteKeyButton} and are the ones that were throwing, which emptied the whole
     * page rather than one row.</p>
     */
    @Inject(method = "buildInputsPage", at = @At("HEAD"), require = 0)
    private void firecontrolcompat$growInputCollections(CallbackInfo ci) {
        try {
            while (this.wirelessRemoteKeys.size() < COMPAT_ACTIONS) {
                this.wirelessRemoteKeys.add("");
            }
            while (this.normalRemoteKeys != null && this.normalRemoteKeys.size() < COMPAT_ACTIONS) {
                this.normalRemoteKeys.add("");
            }
            while (this.advancedRemoteKeys != null && this.advancedRemoteKeys.size() < COMPAT_ACTIONS) {
                this.advancedRemoteKeys.add("");
            }
            ItemStack[][] old = this.inputFrequencies;
            int rows = old == null ? 0 : old.length;
            if (rows < COMPAT_ACTIONS) {
                ItemStack[][] grown = new ItemStack[COMPAT_ACTIONS][2];
                for (int action = 0; action < rows; action++) {
                    if (old[action] != null) {
                        System.arraycopy(old[action], 0, grown[action], 0, 2);
                    }
                }
                for (int action = 0; action < COMPAT_ACTIONS; action++) {
                    for (int slot = 0; slot < 2; slot++) {
                        if (grown[action][slot] == null) {
                            grown[action][slot] = ItemStack.EMPTY;
                        }
                    }
                }
                this.inputFrequencies = grown;
            }
            com.qkdream.firecontrolcompat.FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] input collections grown: keys={} frequencies={} normal={} advanced={}",
                    this.wirelessRemoteKeys.size(), this.inputFrequencies.length,
                    this.normalRemoteKeys == null ? -1 : this.normalRemoteKeys.size(),
                    this.advancedRemoteKeys == null ? -1 : this.advancedRemoteKeys.size());
        } catch (Throwable failure) {
            com.qkdream.firecontrolcompat.FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] input collections not grown: {}", failure.toString());
        }
    }

    /**
     * Reports that the tail of the binding page build is reached, and nothing else.
     *
     * <p>This used to fill the scroll list here, which put every widget into it a second time:
     * the page collects its own children right after this method returns, so the list held each
     * widget twice and the page moved them twice per wheel notch. The field log named it plainly -
     * 109 entries, 54 duplicates dropped - so the filling is left to the one place that can see
     * whether a widget is missing, the scroll handler itself.</p>
     */
    @Inject(method = "buildInputsPage", at = @At("TAIL"), require = 0)
    private void firecontrolcompat$syncScrolledWidgets(CallbackInfo ci) {
        try {
            List<net.minecraft.client.gui.components.AbstractWidget> content = this.contentWidgets;
            List<?> children = firecontrolcompat$childrenOf(this);
            com.qkdream.firecontrolcompat.FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] inputs page tail reached: children={} registered={} sampleY={}",
                    children == null ? -1 : children.size(), content == null ? -1 : content.size(),
                    content == null || content.isEmpty() ? -1 : content.get(0).getY());
        } catch (Throwable failure) {
            com.qkdream.firecontrolcompat.FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] inputs page tail skipped: {}", failure.toString());
        }
    }

    /**
     * Drops every widget that ended up in the scroll list more than once, keeping the first entry.
     *
     * <p>The pack collects the screen's children into the scroll list on its own right after it has
     * built the page, so the widgets added by the pass above are in that list twice by then. The
     * scroll handler moves each entry it finds, so a duplicated widget would travel at twice the
     * speed of the labels drawn beside it. This runs at the tail of the method that installs the
     * scroll bar - the one called once per build, after the collection - and leaves the list in the
     * order the pack produced.</p>
     */
    @Inject(method = "installScrollSlider", at = @At("TAIL"), require = 0)
    private void firecontrolcompat$dedupeScrolledWidgets(CallbackInfo ci) {
        try {
            List<net.minecraft.client.gui.components.AbstractWidget> content = this.contentWidgets;
            int removed = 0;
            if (content != null && content.size() >= 2) {
                java.util.IdentityHashMap<Object, Boolean> seen = new java.util.IdentityHashMap<>();
                java.util.Iterator<net.minecraft.client.gui.components.AbstractWidget> iterator = content.iterator();
                while (iterator.hasNext()) {
                    Object widget = iterator.next();
                    if (seen.put(widget, Boolean.TRUE) != null) {
                        iterator.remove();
                        removed++;
                    }
                }
            }
            com.qkdream.firecontrolcompat.FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] scroll slider tail reached: widgets={} dropped={}",
                    content == null ? -1 : content.size(), removed);
        } catch (Throwable failure) {
            com.qkdream.firecontrolcompat.FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] scroll slider tail skipped: {}", failure.toString());
        }
    }

    /**
     * Fills the scroll list if the page has not, then records where every widget sits.
     *
     * <p>The page's own collection of its children into the scroll list is not something this mod
     * can rely on: in the field that list arrived at the scroll handler empty, so the widgets never
     * moved while the labels drawn beside them slid away. Filling it here - on the way into the
     * scroll handler, which is certainly reached - is what makes the two halves stay together; the
     * snapshot then turns "did the pack move them" into a measurement the tail can act on.</p>
     */
    @Inject(method = "setScrollOffset", at = @At("HEAD"), require = 0)
    private void firecontrolcompat$snapshotScroll(double requested, boolean updateSlider, CallbackInfo ci) {
        try {
            int filled = this.firecontrolcompat$fillScrolledWidgets();
            this.firecontrolcompat$scrollOffsetBefore = this.scrollOffset;
            List<net.minecraft.client.gui.components.AbstractWidget> content = this.contentWidgets;
            if (content == null) {
                this.firecontrolcompat$scrollBefore = null;
                return;
            }
            java.util.IdentityHashMap<net.minecraft.client.gui.components.AbstractWidget, Integer> snapshot =
                    new java.util.IdentityHashMap<>();
            StringBuilder slots = new StringBuilder();
            int index = 0;
            for (net.minecraft.client.gui.components.AbstractWidget widget : content) {
                if (widget != null) {
                    snapshot.put(widget, widget.getY());
                    if (index < 6) {
                        slots.append(' ').append(widget.getX()).append(',').append(widget.getY());
                    }
                    index++;
                }
            }
            this.firecontrolcompat$scrollBefore = snapshot;
            if (this.firecontrolcompat$scrollLogs < 12) {
                this.firecontrolcompat$scrollLogs++;
                com.qkdream.firecontrolcompat.FireControlCompat.LOGGER.info(
                        "[firecontrolcompat] scroll-before page={} filled={} widgets={} offset={} first6(x,y):{}",
                        firecontrolcompat$pageOf(this), filled, content.size(), this.scrollOffset, slots);
            }
        } catch (Throwable failure) {
            this.firecontrolcompat$scrollBefore = null;
        }
    }

    /**
     * Puts the page's widgets into the scroll list when the page itself has not.
     *
     * <p>Every widget the screen owns is a child. Two kinds are left out: the scroll bar itself,
     * and the page's own back / done / cancel keys, which belong to the window rather than to the
     * scrolling content. Those keys are recognised by their text, not by their position: on a page
     * taller than the window the rows below the fold are off-screen at rest, and a rule that
     * dropped everything low on the screen would drop them too. Answers how many widgets had to be
     * added, which is zero on a page that already collected them.</p>
     */
    private int firecontrolcompat$fillScrolledWidgets() {
        List<net.minecraft.client.gui.components.AbstractWidget> content = this.contentWidgets;
        List<?> children = firecontrolcompat$childrenOf(this);
        if (content == null || children == null || children.isEmpty()) {
            return 0;
        }
        java.util.IdentityHashMap<Object, Boolean> known = new java.util.IdentityHashMap<>();
        // Drop duplicates first. A widget listed twice is moved twice by the page's own scroll
        // handler, which is exactly how a previous attempt at this broke every page's scrolling.
        java.util.Iterator<net.minecraft.client.gui.components.AbstractWidget> iterator = content.iterator();
        while (iterator.hasNext()) {
            Object widget = iterator.next();
            if (known.put(widget, Boolean.TRUE) != null) {
                iterator.remove();
            }
        }
        int added = 0;
        for (Object child : children) {
            if (!(child instanceof net.minecraft.client.gui.components.AbstractWidget widget)) {
                continue;
            }
            if (widget instanceof net.minecraft.client.gui.components.AbstractSliderButton) {
                continue;
            }
            if (firecontrolcompat$isFooterKey(widget)) {
                continue;
            }
            if (known.put(widget, Boolean.TRUE) != null) {
                continue;
            }
            content.add(widget);
            added++;
        }
        return added;
    }

    /** True for the page's back / done / cancel keys, whatever row they were put on. */
    private static boolean firecontrolcompat$isFooterKey(net.minecraft.client.gui.components.AbstractWidget widget) {
        try {
            net.minecraft.network.chat.Component message = widget.getMessage();
            if (message == null) {
                return false;
            }
            String text = message.getString();
            return text.equals(net.minecraft.network.chat.Component.translatable("gui.back").getString())
                    || text.equals(net.minecraft.network.chat.Component.translatable("gui.done").getString())
                    || text.equals(net.minecraft.network.chat.Component.translatable("gui.cancel").getString());
        } catch (Throwable failure) {
            return false;
        }
    }

    /** Name of the page the screen is showing, for the logs; "?" when it cannot be read. */
    private static String firecontrolcompat$pageOf(Object screen) {
        try {
            java.lang.reflect.Field field = null;
            for (Class<?> type = screen.getClass(); type != null; type = type.getSuperclass()) {
                try {
                    field = type.getDeclaredField("page");
                    break;
                } catch (NoSuchFieldException ignored) {
                    // Declared by a superclass, keep walking.
                }
            }
            if (field == null) {
                return "?";
            }
            field.setAccessible(true);
            return String.valueOf(field.get(screen));
        } catch (Throwable failure) {
            return "?";
        }
    }

    /**
     * Moves whatever the pack left behind, and nothing else.
     *
     * <p>The expected shift is the change of the rounded scroll offset, because
     * that is the amount the labels beside the widgets are drawn with. For each
     * widget the shift it actually got is subtracted, and only the remainder is
     * applied - so a page whose own scroll handler already moved the widgets is
     * left exactly as it was, a page that moves none of them gets them all moved,
     * and a page that moves some of them gets only the missing ones. Nothing here
     * can double a movement, which is what made the earlier attempt at this
     * unsafe.</p>
     *
     * <p>A handful of lines are logged per page: the requested shift, how many
     * widgets moved on their own, how many had not, and a sample before/after
     * pair. That is what says whether the right column is a widget question or a
     * drawing question.</p>
     */
    @Inject(method = "setScrollOffset", at = @At("TAIL"), require = 0)
    private void firecontrolcompat$applyScroll(double requested, boolean updateSlider, CallbackInfo ci) {
        try {
            List<net.minecraft.client.gui.components.AbstractWidget> content = this.contentWidgets;
            java.util.IdentityHashMap<net.minecraft.client.gui.components.AbstractWidget, Integer> snapshot =
                    this.firecontrolcompat$scrollBefore;
            this.firecontrolcompat$scrollBefore = null;
            if (content == null || snapshot == null) {
                return;
            }
            int expected = (int) Math.round(this.firecontrolcompat$scrollOffsetBefore)
                    - (int) Math.round(this.scrollOffset);
            int moved = 0;
            int frozen = 0;
            int corrected = 0;
            int sampleBefore = 0;
            int sampleAfter = 0;
            boolean sampled = false;
            for (net.minecraft.client.gui.components.AbstractWidget widget : content) {
                Integer before = snapshot.get(widget);
                if (before == null) {
                    continue;
                }
                if (!sampled) {
                    sampleBefore = before;
                    sampled = true;
                }
                // The shift this widget actually got, in the same sense as the scroll: a page
                // scrolled down by 168 has moved its widgets UP by 168, so the shift is
                // (newY - oldY) and is negative. Getting this sign wrong on the first attempt
                // doubled the movement of every widget on every page, which is what the field
                // log showed: requested=-168, moved=55, corrected=55, sample 64 -> -440.
                int actual = widget.getY() - before;
                if (actual != 0) {
                    moved++;
                } else {
                    frozen++;
                }
                int missing = expected - actual;
                if (missing != 0) {
                    widget.setY(widget.getY() + missing);
                    corrected++;
                }
                if (sampleBefore == before) {
                    sampleAfter = widget.getY();
                }
            }
            if (this.firecontrolcompat$scrollLogs < 12) {
                this.firecontrolcompat$scrollLogs++;
                com.qkdream.firecontrolcompat.FireControlCompat.LOGGER.info(
                        "[firecontrolcompat] scroll-sync requested={} offset={}->{} widgets={} moved={} frozen={} corrected={} sampleY={}->{}",
                        expected, this.firecontrolcompat$scrollOffsetBefore, this.scrollOffset,
                        content.size(), moved, frozen, corrected, sampleBefore, sampleAfter);
            }
        } catch (Throwable failure) {
            com.qkdream.firecontrolcompat.FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] scroll-sync skipped: {}", failure.toString());
        }
    }

    /**
     * The screen's child widgets, read reflectively through {@code children()}.
     *
     * <p>{@code children()} lives on {@code Screen}, not on the target class, so it is looked up on
     * the superclass chain rather than with {@code getDeclaredMethod} on the target alone.</p>
     */
    private static List<?> firecontrolcompat$childrenOf(Object screen) {
        try {
            java.lang.reflect.Method method = null;
            for (Class<?> type = screen.getClass(); type != null; type = type.getSuperclass()) {
                try {
                    method = type.getDeclaredMethod("children");
                    break;
                } catch (NoSuchMethodException ignored) {
                    // Walk on up: the method is declared by one of the superclasses.
                }
            }
            if (method == null) {
                return null;
            }
            method.setAccessible(true);
            Object children = method.invoke(screen);
            return children instanceof List<?> list ? list : null;
        } catch (Throwable failure) {
            return null;
        }
    }

    /*
     * The sonar rows are no longer added to this page at all.
     *
     * <p>They used to be appended as a group of our own, and then merged into the pack's "ground"
     * group. Both were wrong for the same reason: 0.9.4 owns actions 0..43 and keeps its own tables
     * of which action belongs to which section, so an action of ours either landed under a heading
     * that was not ours or, once its id happened to collide with one of the pack's (16 is a real
     * airborne row in 0.9.4), renamed one of the pack's own rows. The two bindings now belong on
     * this mod's own settings page instead, and this page is left exactly as the pack built it.
     */
}

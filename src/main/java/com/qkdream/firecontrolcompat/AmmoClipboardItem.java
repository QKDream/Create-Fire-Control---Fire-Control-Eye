package com.qkdream.firecontrolcompat;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;

/**
 * The ammunition loading table: a clipboard that binds a maid to one crate and
 * one launcher.
 *
 * <p>Right click the maid, then the crate, then the launcher. Every right click
 * writes what it saw onto the clipboard and, as soon as the maid is known, the
 * whole order is copied onto that maid, so she keeps working after the clip
 * board is put away. Sneaking clears the clipboard.</p>
 */
public class AmmoClipboardItem extends Item {

    /** Entity id of the maid this clipboard understands. */
    public static final ResourceLocation MAID_ID = ResourceLocation.fromNamespaceAndPath("touhou_little_maid", "maid");

    public static final String KEY_MAID = "fcc_load_maid";
    public static final String KEY_CRATE = "fcc_load_crate";
    public static final String KEY_CRATE_DIM = "fcc_load_crate_dim";
    public static final String KEY_LAUNCHER = "fcc_load_launcher";
    public static final String KEY_LAUNCHER_DIM = "fcc_load_launcher_dim";
    /** Every launcher of the order, as packed block positions. */
    public static final String KEY_LAUNCHERS = "fcc_load_launchers";
    /** Launchers one order feeds; a maid fills them one after the other. */
    public static final int MAX_LAUNCHERS = 8;
    /** Dimension the maid's own order was written in. */
    public static final String KEY_ORDER_DIM = "fcc_load_order_dim";

    public AmmoClipboardItem(Properties properties) {
        super(properties);
    }

    /** True when the stack is this clipboard, checked without touching the maid API. */
    public static boolean isClipboard(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getItem() instanceof AmmoClipboardItem;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }
        ItemStack stack = context.getItemInHand();
        if (player.isCrouching()) {
            clear(stack, player);
            return InteractionResult.sidedSuccess(context.getLevel().isClientSide());
        }
        BlockPos pos = context.getClickedPos();
        boolean crate = com.qkdream.firecontrolcompat.SubLevelPositions.blockEntity(context.getLevel(), pos)
                instanceof com.qkdream.firecontrolcompat.crate.AmmoCrateBlockEntity;
        String dimension = context.getLevel().dimension().location().toString();
        int recorded = 0;
        if (crate) {
            write(stack, KEY_CRATE, pos);
            writeString(stack, KEY_CRATE_DIM, dimension);
        } else {
            recorded = recordLauncher(stack, pos, dimension);
        }
        if (context.getLevel().isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        String message = crate ? "已记录弹药箱" : switch (recorded) {
            case 1 -> "这具发射器已经在装弹指令里了";
            case 2 -> "装弹指令最多记录 " + MAX_LAUNCHERS + " 具发射器";
            default -> "已记录发射器（共 " + launcherArray(launcherTag(stack)).length + " 具）";
        };
        player.displayClientMessage(Component.literal(message), true);
        applyToMaid(stack, player);
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target,
            InteractionHand hand) {
        if (target == null || !MAID_ID.equals(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()))) {
            return InteractionResult.PASS;
        }
        if (player.isCrouching()) {
            clear(stack, player);
            return InteractionResult.sidedSuccess(player.level().isClientSide());
        }
        if (player.level().isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        CompoundTag data = target.getPersistentData();
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            tag.putUUID(KEY_MAID, target.getUUID());
            tag.putString(KEY_ORDER_DIM, player.level().dimension().location().toString());
            copyInto(data, tag, KEY_CRATE, KEY_CRATE_DIM);
            copyLaunchers(data, tag);
        });
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            copyBack(target.getPersistentData(), tag, KEY_CRATE, KEY_CRATE_DIM);
            copyLaunchers(target.getPersistentData(), tag);
        });
        player.displayClientMessage(Component.literal("已选中这名女仆：先点弹药箱，再点发射器"), true);
        return InteractionResult.SUCCESS;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        tooltip.add(Component.literal("右键女仆 → 弹药箱 → 发射器，可连续记录多具（上限 " + MAX_LAUNCHERS + "）").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("女仆：" + (tag.hasUUID(KEY_MAID)
                ? tag.getUUID(KEY_MAID).toString().substring(0, 8) : "未选择")).withStyle(ChatFormatting.DARK_AQUA));
        tooltip.add(Component.literal("弹药箱：" + describe(tag, KEY_CRATE)).withStyle(ChatFormatting.DARK_AQUA));
        tooltip.add(Component.literal("发射器（" + describeLaunchers(tag) + "）").withStyle(ChatFormatting.DARK_AQUA));
        tooltip.add(Component.literal("潜行右键清空").withStyle(ChatFormatting.DARK_GRAY));
        super.appendHoverText(stack, context, tooltip, flag);
    }

    /** Order stored on a maid, or null when she has none. */
    public static BlockPos boundCrate(CompoundTag maidData, net.minecraft.world.level.Level level) {
        return readPos(maidData, KEY_CRATE, KEY_CRATE_DIM, level);
    }

    public static BlockPos boundLauncher(CompoundTag maidData, net.minecraft.world.level.Level level) {
        List<BlockPos> launchers = boundLaunchers(maidData, level);
        return launchers.isEmpty() ? null : launchers.get(0);
    }

    /**
     * Every launcher the order feeds. A maid works through the list one launcher
     * at a time, so several identical launchers are filled from the same store.
     */
    public static List<BlockPos> boundLaunchers(CompoundTag maidData, net.minecraft.world.level.Level level) {
        if (maidData == null || level == null || !dimensionMatches(maidData, KEY_LAUNCHER_DIM, level)) {
            return List.of();
        }
        long[] packed = launcherArray(maidData);
        if (packed.length == 0) {
            return List.of();
        }
        List<BlockPos> launchers = new java.util.ArrayList<>(packed.length);
        for (long entry : packed) {
            launchers.add(BlockPos.of(entry));
        }
        return launchers;
    }

    /** Whether a position written in {@code dimKey} belongs to the level asking for it. */
    private static boolean dimensionMatches(CompoundTag data, String dimKey, net.minecraft.world.level.Level level) {
        if (data == null || !data.contains(dimKey)) {
            return true;
        }
        String dimension = data.getString(dimKey);
        return dimension.isEmpty() || dimension.equals(level.dimension().location().toString());
    }

    private static BlockPos readPos(CompoundTag data, String posKey, String dimKey, net.minecraft.world.level.Level level) {
        if (data == null || !data.contains(posKey) || level == null) {
            return null;
        }
        String dimension = data.contains(dimKey) ? data.getString(dimKey) : "";
        if (!dimension.isEmpty() && !dimension.equals(level.dimension().location().toString())) {
            return null;
        }
        return BlockPos.of(data.getLong(posKey));
    }

    private static void applyToMaid(ItemStack stack, Player player) {
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        if (!tag.hasUUID(KEY_MAID) || !(player.level() instanceof ServerLevel level)) {
            player.displayClientMessage(Component.literal("还没有选女仆：先右键女仆"), true);
            return;
        }
        Entity maid = level.getEntity(tag.getUUID(KEY_MAID));
        if (maid == null) {
            player.displayClientMessage(Component.literal("那名女仆不在这里"), true);
            return;
        }
        CompoundTag data = maid.getPersistentData();
        copyBack(tag, data, KEY_CRATE, KEY_CRATE_DIM);
        copyLaunchers(tag, data);
        data.putString(KEY_ORDER_DIM, level.dimension().location().toString());
        player.displayClientMessage(Component.literal("装弹指令已下达给女仆"), true);
    }

    private static void clear(ItemStack stack, Player player) {
        stack.remove(DataComponents.CUSTOM_DATA);
        if (!player.level().isClientSide()) {
            player.displayClientMessage(Component.literal("装弹指令已清空"), true);
        }
    }

    /** Adds one launcher to the order; 0 = added, 1 = already listed, 2 = order full. */
    private static int recordLauncher(ItemStack stack, BlockPos pos, String dimension) {
        int[] outcome = {0};
        long packed = pos.asLong();
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            long[] existing = launcherArray(tag);
            for (long entry : existing) {
                if (entry == packed) {
                    outcome[0] = 1;
                    return;
                }
            }
            if (existing.length >= MAX_LAUNCHERS) {
                outcome[0] = 2;
                return;
            }
            long[] grown = java.util.Arrays.copyOf(existing, existing.length + 1);
            grown[existing.length] = packed;
            tag.putLongArray(KEY_LAUNCHERS, grown);
            tag.putLong(KEY_LAUNCHER, grown[0]);
            tag.putString(KEY_LAUNCHER_DIM, dimension);
        });
        return outcome[0];
    }

    /** The recorded launchers, reading orders written before the list existed. */
    private static long[] launcherArray(CompoundTag tag) {
        if (tag == null) {
            return new long[0];
        }
        long[] packed = tag.getLongArray(KEY_LAUNCHERS);
        if (packed.length > 0) {
            return packed;
        }
        return tag.contains(KEY_LAUNCHER) ? new long[]{tag.getLong(KEY_LAUNCHER)} : new long[0];
    }

    private static CompoundTag launcherTag(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
    }

    private static String describeLaunchers(CompoundTag tag) {
        long[] packed = launcherArray(tag);
        if (packed.length == 0) {
            return "未选择";
        }
        String first = BlockPos.of(packed[0]).toShortString();
        return packed.length == 1 ? first : packed.length + " 具：" + first + " 起";
    }

    private static void copyLaunchers(CompoundTag from, CompoundTag to) {
        if (from == null || to == null) {
            return;
        }
        if (from.contains(KEY_LAUNCHERS)) {
            to.putLongArray(KEY_LAUNCHERS, from.getLongArray(KEY_LAUNCHERS));
        }
        if (from.contains(KEY_LAUNCHER)) {
            to.putLong(KEY_LAUNCHER, from.getLong(KEY_LAUNCHER));
        }
        if (from.contains(KEY_LAUNCHER_DIM)) {
            to.putString(KEY_LAUNCHER_DIM, from.getString(KEY_LAUNCHER_DIM));
        }
    }

    private static void write(ItemStack stack, String key, BlockPos pos) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putLong(key, pos.asLong()));
    }

    private static void writeString(ItemStack stack, String key, String value) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putString(key, value));
    }

    private static void copyBack(CompoundTag from, CompoundTag to, String posKey, String dimKey) {
        if (from == null || to == null || !from.contains(posKey)) {
            return;
        }
        to.putLong(posKey, from.getLong(posKey));
        if (from.contains(dimKey)) {
            to.putString(dimKey, from.getString(dimKey));
        }
    }

    private static void copyInto(CompoundTag from, CompoundTag to, String posKey, String dimKey) {
        if (from == null || !from.contains(posKey)) {
            return;
        }
        to.putLong(posKey, from.getLong(posKey));
        if (from.contains(dimKey)) {
            to.putString(dimKey, from.getString(dimKey));
        }
    }

    private static String describe(CompoundTag tag, String key) {
        return tag.contains(key) ? BlockPos.of(tag.getLong(key)).toShortString() : "未选择";
    }
}

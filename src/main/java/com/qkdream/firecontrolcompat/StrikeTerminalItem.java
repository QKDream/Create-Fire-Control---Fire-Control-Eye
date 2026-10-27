package com.qkdream.firecontrolcompat;

import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.fml.loading.FMLEnvironment;

/**
 * The airborne strike terminal: an aircrew's plotting board.
 *
 * <p>Sneak-free right click on a fire control computer binds the board to it.
 * Right click in the air then opens the board's map, drawn from the ground the
 * player can see around the computer; a left click puts the strike marker on a
 * square of it and the computer is told to strike that place, a right click
 * takes the marker off again, and the release button lets whatever the computer
 * holds go at the marker - the rounds that used to need a lock on the pod fly
 * to the marker instead.</p>
 *
 * <p>All it is, in the end, is a place to write a ground point down: the point
 * is kept on the computer, the racks read it when they are released, and the
 * air-to-ground guidance of the computer's own missiles uses it as the target it
 * would otherwise take from the pod.</p>
 */
public class StrikeTerminalItem extends Item {

    /** Where the board remembers the computer it was bound to. */
    private static final String CONTROLLER = "firecontrolcompat:StrikeController";
    /** Where the board remembers the point it last marked. */
    private static final String POINT = "firecontrolcompat:StrikePoint";

    public StrikeTerminalItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockEntity blockEntity = SubLevelPositions.blockEntity(level, context.getClickedPos());
        if (!(blockEntity instanceof StabilizerControllerBlockEntity)) {
            return InteractionResult.PASS;
        }
        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide) {
            BindingRef ref = BindingRef.capture(blockEntity.getLevel(), blockEntity.getBlockPos());
            ItemStack stack = context.getItemInHand();
            CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.put(CONTROLLER, ref.save()));
            player.displayClientMessage(
                    Component.translatable("message.firecontrolcompat.strike_bound").withStyle(ChatFormatting.AQUA),
                    true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide && FMLEnvironment.dist.isClient()) {
            if (controller(stack) == null) {
                player.displayClientMessage(
                        Component.translatable("message.firecontrolcompat.strike_unbound").withStyle(ChatFormatting.RED),
                        true);
                return InteractionResultHolder.fail(stack);
            }
            com.qkdream.firecontrolcompat.client.StrikeTerminalClient.open(stack);
            return InteractionResultHolder.success(stack);
        }
        if (!level.isClientSide && controller(stack) == null) {
            player.displayClientMessage(
                    Component.translatable("message.firecontrolcompat.strike_unbound").withStyle(ChatFormatting.RED), true);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    /** Writes the point last marked on the board, or takes it off when given none. */
    public static void rememberPoint(ItemStack stack, BlockPos point) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            if (point == null) {
                tag.remove(POINT);
            } else {
                tag.putLong(POINT, point.asLong());
            }
        });
    }

    /** The point the board last marked, or null while it has marked none. */
    public static BlockPos rememberedPoint(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return null;
        }
        CompoundTag tag = data.copyTag();
        return tag.contains(POINT) ? BlockPos.of(tag.getLong(POINT)) : null;
    }

    /** The computer the board is bound to, or null while it is bound to none. */
    public static BindingRef controller(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return null;
        }
        CompoundTag tag = data.copyTag();
        if (!tag.contains(CONTROLLER)) {
            return null;
        }
        try {
            return BindingRef.loadTag(tag.getCompound(CONTROLLER));
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}

package com.qkdream.firecontrolcompat;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.qkdream.firecontrolcompat.laser.LaserSpots;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Sets the laser code written on the computer a player is standing at.
 *
 * <p>Every computer already has a code of its own, derived from where it stands,
 * and rounds take the code of the computer that fired them: a pod only lights a
 * spot for rounds of its own code, which is what keeps two rigs from painting for
 * each other without anyone configuring anything. Writing a code by hand is for
 * the case where one crew should paint for another - the same number on both
 * computers, and the spot of the one serves the rounds of the other.</p>
 */
@EventBusSubscriber(modid = FireControlCompat.MOD_ID)
public final class LaserCodeCommand {

    /** How near a computer a player has to stand to write a code on it. */
    private static final double REACH = 32.0D;

    private LaserCodeCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("lasercode")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("code", IntegerArgumentType.integer(0, 9999))
                        .executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            int code = IntegerArgumentType.getInteger(context, "code");
                            StabilizerControllerBlockEntity controller =
                                    LaserSpots.nearestController(player.level(), player.position(), REACH);
                            if (controller == null) {
                                context.getSource().sendFailure(
                                        Component.translatable("message.firecontrolcompat.laser_code_no_computer"));
                                return 0;
                            }
                            if (code <= 0) {
                                controller.getPersistentData().remove(LaserSpots.CODE_OVERRIDE_KEY);
                            } else {
                                controller.getPersistentData().putInt(LaserSpots.CODE_OVERRIDE_KEY, code);
                            }
                            controller.setChanged();
                            int now = LaserSpots.codeFor(controller);
                            context.getSource().sendSuccess(() -> Component.translatable(
                                    "message.firecontrolcompat.laser_code_set", now), true);
                            FireControlCompat.LOGGER.info("[firecontrolcompat] laser code of computer at {} set to {}",
                                    controller.getBlockPos(), now);
                            return 1;
                        })));
    }
}

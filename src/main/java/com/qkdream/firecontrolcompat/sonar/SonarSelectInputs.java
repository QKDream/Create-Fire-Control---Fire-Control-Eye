package com.qkdream.firecontrolcompat.sonar;

import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.content.controller.ControllerTracker;
import com.hooya.stabilizedturret.content.controller.DriveByWireCompat;
import com.hooya.stabilizedturret.content.controller.RemoteControlTracker;
import com.hooya.stabilizedturret.content.controller.RemoteControllerCompat;
import com.hooya.stabilizedturret.content.controller.RemoteInputTracker;
import com.hooya.stabilizedturret.content.controller.RemoteTerminalDetector;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.qkdream.firecontrolcompat.FireControlCompat;
import com.simibubi.create.Create;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Drives the sonar pick from the inputs the computer's own actions answer to.
 *
 * <p>What the operator binds on the target select page is kept working here: a
 * wireless pair is held on the network for every computer that has one bound,
 * and the two remote keys are watched for the moment they go down, which is
 * when the pick is taken. The pick itself is the one the key bind makes, off
 * the operator's own sight line.</p>
 *
 * <p>The keys themselves are read exactly as the computer reads its own: the
 * terminal the remote of that kind is bound to has to be standing, and what it
 * reports counts alongside what the client driving over it sends.</p>
 *
 * <p>Nothing is kept for a computer with nothing bound, and a computer in that
 * state is looked over once and then left alone, so a world full of computers
 * that do not use the pick costs almost nothing.</p>
 */
@EventBusSubscriber(modid = FireControlCompat.MOD_ID)
public final class SonarSelectInputs {

    /**
     * Ticks between two looks. One: the computer reads its own remote keys every
     * tick, and a tap worked on the controller can be as short as one, so a look
     * that skipped ticks would drop presses. Nothing but the bound computers is
     * looked at either way.
     */
    private static final int PERIOD = 1;

    /** The source a pick came from, as it is written to the log. */
    private static final String SOURCE_WIRELESS = "wireless";
    private static final String SOURCE_REMOTE = "remote";
    private static final String SOURCE_REMOTE_ADVANCED = "remote_advanced";
    /** The full-keyboard wireless controller, which carries the whole keyboard plus the wheel. */
    private static final String SOURCE_WIRELESS_CONTROLLER = "wireless_controller";

    private static final Map<StabilizerControllerBlockEntity, Session> SESSIONS = new IdentityHashMap<>();

    /** What one computer answers to: its receiver, and which keys were down last look. */
    private static final class Session {

        private final SonarSelectWireless wireless;
        private ItemStack first = ItemStack.EMPTY;
        private ItemStack second = ItemStack.EMPTY;
        private boolean normalPressed;
        private boolean advancedPressed;
        /** The full-keyboard wireless controller's key (火控学's third channel). */
        private boolean wirelessPressed;

        private Session(SonarSelectWireless wireless) {
            this.wireless = wireless;
        }
    }

    private SonarSelectInputs() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server == null || server.getTickCount() % PERIOD != 0) {
            return;
        }
        try {
            tick();
        } catch (Throwable ignored) {
        }
    }

    private static void tick() {
        for (StabilizerControllerBlockEntity controller : ControllerTracker.loadedControllers()) {
            try {
                tick(controller);
            } catch (Throwable ignored) {
            }
        }
        for (Iterator<Map.Entry<StabilizerControllerBlockEntity, Session>> iterator =
                SESSIONS.entrySet().iterator(); iterator.hasNext(); ) {
            Map.Entry<StabilizerControllerBlockEntity, Session> entry = iterator.next();
            if (!alive(entry.getKey())) {
                unregister(entry.getKey(), entry.getValue());
                iterator.remove();
            }
        }
    }

    private static void tick(StabilizerControllerBlockEntity controller) {
        if (controller == null || controller.isRemoved() || controller.getLevel() == null) {
            return;
        }
        Level level = controller.getLevel();
        if (level.isClientSide) {
            return;
        }
        SonarSelectInputStore.Input input = SonarSelectInputStore.of(level, controller);
        Session session = SESSIONS.get(controller);
        if (session == null) {
            if (!input.configured()) {
                return;
            }
            session = new Session(new SonarSelectWireless(
                    controller, () -> wireless(controller)));
            SESSIONS.put(controller, session);
        }
        holdFrequency(controller, session, input);
        watch(controller, session, input);
    }

    /**
     * Keeps the receiver on the pair the operator bound, rebinding it when it changes.
     *
     * <p>The pair is read off the <b>computer</b> first ({@link SonarRedstonePair} - Create's own
     * {@code "First"}/{@code "Second"} item-stack shape, on the block entity), and the older
     * per-computer table is only a fallback for a world that was bound before this moved. Both
     * halves are now filed together, so a pair cannot be half-old and half-new.</p>
     */
    private static void holdFrequency(
            StabilizerControllerBlockEntity controller, Session session, SonarSelectInputStore.Input input) {
        ItemStack[] onController = SonarRedstonePair.of(controller);
        ItemStack first = onController[0].isEmpty() && onController[1].isEmpty()
                ? input.first() : onController[0];
        ItemStack second = onController[0].isEmpty() && onController[1].isEmpty()
                ? input.second() : onController[1];
        if (ItemStack.matches(first, session.first) && ItemStack.matches(second, session.second)) {
            return;
        }
        Level level = controller.getLevel();
        unregister(level, session);
        session.wireless.setFrequency(true, first);
        session.wireless.setFrequency(false, second);
        session.first = first.copy();
        session.second = second.copy();
        register(level, session);
        FireControlCompat.LOGGER.info(
                "[firecontrolcompat] sonar redstone: source=create freq={} listening={} controller={}",
                SonarRedstonePair.describe(first, second), session.wireless.hasFrequency(),
                controller.getBlockPos().toShortString());
    }

    /** Takes a pick the moment one of the bound remote keys goes down. */
    private static void watch(
            StabilizerControllerBlockEntity controller, Session session, SonarSelectInputStore.Input input) {
        Level level = controller.getLevel();
        session.normalPressed = watch(
                controller,
                level,
                RemoteControllerCompat.Type.NORMAL,
                input.normalKey(),
                session.normalPressed,
                SOURCE_REMOTE);
        session.advancedPressed = watch(
                controller,
                level,
                RemoteControllerCompat.Type.ADVANCED,
                input.advancedKey(),
                session.advancedPressed,
                SOURCE_REMOTE_ADVANCED);
        // The full-keyboard wireless controller is a third exchange channel of its own, not a
        // second key on one of the two wired controllers: it is read exactly the same way, so all
        // three of 火控学's channels reach the one sonar action.
        session.wirelessPressed = watch(
                controller,
                level,
                RemoteControllerCompat.Type.WIRELESS,
                input.wirelessKey(),
                session.wirelessPressed,
                SOURCE_WIRELESS_CONTROLLER);
    }

    /**
     * Whether that key is being worked right now, read the way the computer's own
     * actions read theirs: the terminal a remote of that kind answers to has to be
     * bound and standing, and the key then counts either when that terminal reports
     * it held or when the client driving over it says the key is down.
     *
     * <p>Reading only the second of the two was why a press could go unheard: it is
     * the terminal that carries what a remote controller is working, and it is read
     * straight off the block rather than waiting for the client to say so.</p>
     */
    private static boolean watch(
            StabilizerControllerBlockEntity controller,
            Level level,
            RemoteControllerCompat.Type type,
            String key,
            boolean wasPressed,
            String source) {
        boolean pressed = false;
        if (level != null && key != null && !key.isEmpty() && DriveByWireCompat.isAvailable()) {
            try {
                BindingRef hub = controller.getBinding(RemoteTerminalDetector.slotFor(type));
                if (hub != null && RemoteTerminalDetector.hasTerminal(controller, level, type)) {
                    pressed = DriveByWireCompat.signal(DriveByWireCompat.signals(level, hub.pos()), key) > 0
                            || RemoteInputTracker.isPressed(controller, type, key);
                }
            } catch (Throwable ignored) {
                pressed = false;
            }
        }
        if (pressed && !wasPressed) {
            FireControlCompat.LOGGER.info("[firecontrolcompat] sonar pick ({}) key={}", source, key);
            pick(controller, type, source);
        }
        return pressed;
    }

    /** A wireless pulse came in on one of the bound frequencies. */
    private static void wireless(StabilizerControllerBlockEntity controller) {
        FireControlCompat.LOGGER.info("[firecontrolcompat] sonar pick ({})", SOURCE_WIRELESS);
        pick(controller, null, SOURCE_WIRELESS);
    }

    /**
     * The operator hears the input. The one driving the computer with the remote
     * is taken first - they are the ones who pressed - and failing that whoever
     * is sitting at its station, whose sight line is what the pick reads.
     */
    private static void pick(
            StabilizerControllerBlockEntity controller, RemoteControllerCompat.Type type, String source) {
        try {
            ServerPlayer operator = null;
            if (type != null) {
                operator = RemoteControlTracker.findActivePlayer(controller, type);
            }
            if (operator == null) {
                operator = SonarLock.operatorOf(controller);
            }
            if (operator != null) {
                SonarLock.select(operator, controller, source);
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean alive(StabilizerControllerBlockEntity controller) {
        if (controller == null || controller.isRemoved() || controller.getLevel() == null) {
            return false;
        }
        return SonarSelectInputStore.of(controller.getLevel(), controller).configured();
    }

    private static void register(Level level, Session session) {
        if (level == null || level.isClientSide || !session.wireless.hasFrequency()) {
            return;
        }
        try {
            Create.REDSTONE_LINK_NETWORK_HANDLER.addToNetwork(level, session.wireless);
        } catch (Throwable ignored) {
        }
    }

    private static void unregister(Level level, Session session) {
        if (level == null || level.isClientSide || session == null) {
            return;
        }
        try {
            Create.REDSTONE_LINK_NETWORK_HANDLER.removeFromNetwork(level, session.wireless);
        } catch (Throwable ignored) {
        }
    }

    private static void unregister(StabilizerControllerBlockEntity controller, Session session) {
        unregister(controller == null ? null : controller.getLevel(), session);
    }
}

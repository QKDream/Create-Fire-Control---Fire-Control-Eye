package com.qkdream.firecontrolcompat.sonar;

import com.hooya.stabilizedturret.content.controller.ControllerTracker;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.qkdream.firecontrolcompat.FireControlCompat;
import com.qkdream.firecontrolcompat.RadarNameStore;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Taking a mark off the sonar picture rather than with the aim of a shot.
 *
 * <p>The operator looks at the picture and works the input the pick answers to
 * - the wireless pair or one of the two remote controllers: the mark nearest
 * their own sight line - a source on a listening picture, an echo on a pinging
 * one, whichever the set is painting - is taken, and the picture draws the bars
 * that say so. The input is a toggle: worked with nothing in hand it takes the
 * mark on the sight line, and worked again on the mark in hand - or at open
 * water, with nothing under the sight line at all - it hands the lock back. A
 * mark other than the one in hand is taken straight away, so the operator walks
 * from one contact to the next with one press each and never drops the lock on
 * the way.</p>
 *
 * <p>One press counts once however it was heard: a press that reaches the pick
 * over two of its inputs at once is folded together - one press is to take or
 * hand back one lock, not two - while two presses of the same input are two
 * presses however fast they follow.</p>
 *
 * <p>The pick is filed on the fire control computer the operator is sitting at,
 * which is what makes it the mark that computer's torpedo tubes are laid on:
 * see {@link com.qkdream.firecontrolcompat.underwater.TorpedoDesignation}.</p>
 */
public final class SonarLock {

    /** How close a press has to land to count as the mark already held. */
    private static final double SAME_MARK = 1.5D;
    /**
     * Ticks the two hearings of one press may be apart. A press can reach the
     * pick over two of its inputs at once, and one press is to take or hand back
     * one lock, not two. It is deliberately longer than the couple of ticks a
     * press takes to arrive over both, and it only ever folds hearings from
     * different inputs: the same input is always two presses when it is pressed
     * twice, so a hand that works the toggle quickly keeps every press.
     */
    private static final long DOUBLE_PRESS_GUARD = 6L;
    /** Picks remembered for the guard, above which the stale ones are dropped. */
    private static final int GUARD_ENTRIES = 64;
    /** The last press counted for each player at each computer. */
    private static final Map<String, Press> LAST_PRESS = new HashMap<>();

    /** When a press was counted, and which input it came in over. */
    private record Press(long at, String source) {
    }

    private SonarLock() {
    }

    /**
     * The same pick, off the picture of the computer that was handed in.
     *
     * <p>The wireless pulse and the two remote keys know which computer they
     * answer to - they are filed on it - so they hand it straight in.</p>
     *
     * @param controller the computer whose picture is being read
     * @param source     which input asked, so the log says where a pick came from
     */
    public static void select(ServerPlayer player, StabilizerControllerBlockEntity controller, String source) {
        if (player == null) {
            return;
        }
        try {
            Level level = player.level();
            if (controller == null) {
                FireControlCompat.LOGGER.info(
                        "[firecontrolcompat] sonar select ({}): nobody is at a station", source);
                return;
            }
            List<SonarBlockEntity> sets = SonarSettings.all(
                    level, controller.getBlockPos(), ControllerTracker.controllerSubLevelId(controller));
            if (sets.isEmpty()) {
                FireControlCompat.LOGGER.info(
                        "[firecontrolcompat] sonar select ({}): no set is wired to that computer", source);
                return;
            }
            if (!countPress(player, level, controller, source)) {
                return;
            }
            SonarContact picked = nearest(sets, player);
            SonarLockStore.Lock held = SonarLockStore.of(level, controller);
            if (held != null) {
                // The one in hand is the press that hands the lock back, and so
                // is a press at open water; any other mark is taken straight
                // away, so walking from one contact to the next stays one press.
                if (picked == null || held.position().distanceToSqr(picked.position()) <= SAME_MARK * SAME_MARK) {
                    release(player, level, controller, picked == null
                            ? ", nothing on the sight line"
                            : ", the mark in hand");
                    return;
                }
                take(player, level, controller, picked, " (switched)");
                return;
            }
            if (picked == null) {
                FireControlCompat.LOGGER.info(
                        "[firecontrolcompat] sonar select ({}): nothing is on the sight line", source);
                tell(player, Component.translatable("message.firecontrolcompat.sonar.lock_none"));
                return;
            }
            take(player, level, controller, picked, "");
        } catch (Throwable ignored) {
        }
    }

    /**
     * Whether this is a new press rather than the other half of one already
     * heard. A press can reach the pick over two of its inputs at once, and one
     * press is to take or hand back one lock, not two.
     *
     * @return true when the press is the one to act on
     */
    private static boolean countPress(
            ServerPlayer player, Level level, StabilizerControllerBlockEntity controller, String source) {
        try {
            long now = level.getGameTime();
            if (LAST_PRESS.size() > GUARD_ENTRIES) {
                LAST_PRESS.entrySet().removeIf(entry -> now - entry.getValue().at() > DOUBLE_PRESS_GUARD * 8L);
            }
            String key = player.getUUID() + "|" + RadarNameStore.keyOf(level, controller);
            Press last = LAST_PRESS.get(key);
            if (last != null && !last.source().equals(source) && now - last.at() < DOUBLE_PRESS_GUARD) {
                FireControlCompat.LOGGER.info(
                        "[firecontrolcompat] sonar pick ({}) ignored: the same press has already been heard over {}",
                        source,
                        last.source());
                return false;
            }
            LAST_PRESS.put(key, new Press(now, source));
            return true;
        } catch (Throwable ignored) {
            return true;
        }
    }

    /** Files the mark under the sight line, and says so. */
    private static void take(
            ServerPlayer player,
            Level level,
            StabilizerControllerBlockEntity controller,
            SonarContact picked,
            String note) {
        SonarLockStore.set(level, controller, new SonarLockStore.Lock(picked.position(), picked.kind()));
        FireControlCompat.LOGGER.info(
                "[firecontrolcompat] sonar locked a {} {} blocks out{}",
                picked.kind(),
                Math.round(Math.sqrt(picked.position().distanceToSqr(player.getEyePosition(1.0F)))),
                note);
        tell(player, Component.translatable("message.firecontrolcompat.sonar.lock_taken", kindName(picked.kind())));
        SonarServerSync.refresh(player);
    }

    /** Hands the lock back, and says so. */
    private static void release(
            ServerPlayer player, Level level, StabilizerControllerBlockEntity controller, String note) {
        SonarLockStore.set(level, controller, null);
        FireControlCompat.LOGGER.info("[firecontrolcompat] sonar lock taken back{}", note);
        tell(player, Component.translatable("message.firecontrolcompat.sonar.lock_released"));
        SonarServerSync.refresh(player);
    }

    /**
     * The player sitting at that computer's station, found the way its picture
     * is: whoever is riding something the computer answers to. A computer with
     * nobody in its seat has nobody to pick for, and the screen is not being
     * looked at either.
     */
    public static ServerPlayer operatorOf(StabilizerControllerBlockEntity controller) {
        if (controller == null || controller.getLevel() == null) {
            return null;
        }
        Level level = controller.getLevel();
        try {
            for (Player player : level.players()) {
                if (player instanceof ServerPlayer serverPlayer
                        && ControllerTracker.findForVehicle(level, serverPlayer.getVehicle()) == controller) {
                    return serverPlayer;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** What a picked mark is called on the screen: the three things a ping tells apart. */
    private static Component kindName(SonarKind kind) {
        String suffix = switch (kind) {
            case MOB -> "mob";
            case AMMO, MISSILE, TORPEDO -> "ammo";
            default -> "body";
        };
        return Component.translatable("gui.firecontrolcompat.sonar_mark." + suffix);
    }

    /** Says what the pick did on the action bar, so a silent input is not a mystery. */
    private static void tell(ServerPlayer player, Component message) {
        try {
            player.displayClientMessage(message, true);
        } catch (Throwable ignored) {
        }
    }

    /**
     * The mark of everything the station's sets are painting that lies closest
     * to the sight line. Only what is in front of the operator counts, so a
     * press never picks something behind the boat; of what is left, the one
     * nearest the line wins, however far away it is.
     */
    private static SonarContact nearest(List<SonarBlockEntity> sets, ServerPlayer player) {
        Vec3 eye = player.getEyePosition(1.0F);
        Vec3 look = player.getViewVector(1.0F);
        SonarContact best = null;
        double bestCosine = -1.0D;
        for (SonarBlockEntity set : sets) {
            for (SonarContact contact : set.contacts()) {
                Vec3 delta = contact.position().subtract(eye);
                double length = delta.length();
                if (length < 0.5D) {
                    continue;
                }
                double cosine = delta.scale(1.0D / length).dot(look);
                if (cosine <= 0.0D || cosine <= bestCosine) {
                    continue;
                }
                bestCosine = cosine;
                best = contact;
            }
        }
        return best;
    }
}

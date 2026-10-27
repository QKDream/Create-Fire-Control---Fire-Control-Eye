package com.qkdream.firecontrolcompat.sonar;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;

/**
 * The noise an active set puts into the water.
 *
 * <p>A set that pings is not only loud to the other sets: the ping is a real
 * splash of sound in the world, so everything down there that listens for
 * vibrations hears it too. It is sent as the game's own splash - the splash the
 * sculk sensors and the deep dark's warden both keep an ear out for - so a set
 * left pinging trips the sensors around it and draws a warden in. The set
 * remembers what it sent, so its own sweep does not file that splash as a noise
 * from the world on top of the ping it already marks.</p>
 */
public final class SonarPulse {

    /** How many of our own pulses are held on to while they are still fresh. */
    private static final int MAX_OWN = 64;

    /** How long a remembered pulse still counts as ours, in ticks. */
    private static final long OWN_TICKS = 4L;

    /** How close a splash has to be to a remembered pulse to be that pulse. */
    private static final double OWN_RADIUS = 0.05D;

    private record Own(Level level, double x, double y, double z, long tick) {
    }

    private static final Deque<Own> OWN = new ArrayDeque<>();

    private SonarPulse() {
    }

    /** Sends one ping the whole world can hear, from where the dish stands. */
    public static void ping(Level level, Vec3 position, BlockState state) {
        if (level == null || position == null || level.isClientSide) {
            return;
        }
        try {
            remember(level, position);
            level.gameEvent(GameEvent.SPLASH, position, GameEvent.Context.of(state));
        } catch (Throwable ignored) {
        }
    }

    /** Whether a vibration is one of ours, using the note up when it is. */
    public static boolean isOwn(Level level, Vec3 position) {
        if (level == null || position == null) {
            return false;
        }
        long now = -1L;
        try {
            now = level.getGameTime();
        } catch (Throwable ignored) {
        }
        synchronized (OWN) {
            Iterator<Own> iterator = OWN.iterator();
            while (iterator.hasNext()) {
                Own own = iterator.next();
                if (own.level() != level) {
                    continue;
                }
                if (now >= 0L && own.tick() >= 0L && now - own.tick() > OWN_TICKS) {
                    continue;
                }
                double dx = own.x() - position.x;
                double dy = own.y() - position.y;
                double dz = own.z() - position.z;
                if (dx * dx + dy * dy + dz * dz > OWN_RADIUS * OWN_RADIUS) {
                    continue;
                }
                iterator.remove();
                return true;
            }
        }
        return false;
    }

    private static void remember(Level level, Vec3 position) {
        long tick = -1L;
        try {
            tick = level.getGameTime();
        } catch (Throwable ignored) {
        }
        synchronized (OWN) {
            OWN.addLast(new Own(level, position.x, position.y, position.z, tick));
            while (OWN.size() > MAX_OWN) {
                OWN.removeFirst();
            }
        }
    }
}

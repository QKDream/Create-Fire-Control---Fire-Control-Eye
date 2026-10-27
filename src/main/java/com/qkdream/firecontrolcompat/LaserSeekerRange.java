package com.qkdream.firecontrolcompat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * How far out 面包学's laser rounds look for a spot.
 *
 * <p>Her flight procedures search a box of 600 blocks around the round. That is
 * the real limit on a laser shot: inside it the round steers onto the spot,
 * outside it the round flies on regardless. The box is multiplied here so a crew
 * can lase from further out. Only the 600-block box is touched, so the boxes the
 * same procedures use for damage keep their size.</p>
 *
 * <p>{@code laserSeekerRange=3} in {@code config/firecontrolcompat-pod.txt} means
 * three times the original reach; the default is 1, which changes nothing.</p>
 */
public final class LaserSeekerRange {

    /** The size 面包学's seeker box has, and the only one this mod touches. */
    private static final double ORIGINAL = 600.0D;

    /** How many times that box is widened; 1 leaves everything as it was. */
    private static final double FACTOR = read();

    private LaserSeekerRange() {
    }

    /** The factor in use. */
    public static double factor() {
        return FACTOR;
    }

    /** The box a laser round searches, widened when it is the spot search box. */
    public static AABB widen(Vec3 centre, double sizeX, double sizeY, double sizeZ) {
        if (FACTOR > 1.0D
                && sizeX == ORIGINAL && sizeY == ORIGINAL && sizeZ == ORIGINAL) {
            return AABB.ofSize(centre, sizeX * FACTOR, sizeY * FACTOR, sizeZ * FACTOR);
        }
        return AABB.ofSize(centre, sizeX, sizeY, sizeZ);
    }

    private static double read() {
        try {
            Path path = Path.of("config/firecontrolcompat-pod.txt");
            if (!Files.isRegularFile(path)) {
                return 1.0D;
            }
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String text = line.trim().toLowerCase().replace(" ", "");
                if (!text.startsWith("laserseekerrange")) {
                    continue;
                }
                int equals = text.indexOf('=');
                if (equals < 0) {
                    continue;
                }
                double factor = Double.parseDouble(text.substring(equals + 1).trim());
                if (factor < 1.0D) {
                    factor = 1.0D;
                }
                FireControlCompat.LOGGER.info("[firecontrolcompat] laser seeker range widened {}x", factor);
                return factor;
            }
            return 1.0D;
        } catch (IOException | RuntimeException failure) {
            FireControlCompat.LOGGER.info("[firecontrolcompat] laserSeekerRange unreadable, leaving it as it was: {}",
                    failure.toString());
            return 1.0D;
        }
    }
}

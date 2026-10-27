package com.qkdream.firecontrolcompat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.Vec3;

/**
 * A shift applied to the pod's camera position, written down in the pod's own
 * frame and settable from a file.
 *
 * <p>A crew reported the view leaving the pod's model from the wrong face - the
 * camera standing on the side opposite the lens rather than at it. Nothing in
 * this mod computes that position, so rather than guess at a number, the shift
 * is made adjustable and the current offset is printed in the pod's own frame
 * (forward along its facing, up, and right) so the correction can be read off a
 * log line instead of tried blindly.</p>
 *
 * <p>Write into {@code config/firecontrolcompat-pod.txt}:</p>
 *
 * <pre>cameraOffset=right,up,forward</pre>
 *
 * <p>in blocks, for example {@code cameraOffset=0,-0.2,0.6} to move the camera
 * 0.6 blocks towards the lens and a little down. With no such line the camera is
 * left exactly as fire control computes it.</p>
 */
public final class PodCameraOffset {

    private static final String FILE = "config/firecontrolcompat-pod.txt";

    private static final Vec3 OFFSET = readOffset();

    /**
     * How far the camera's offset from the pod's centre is turned about the
     * vertical axis, in degrees. The crew measured the view leaving the pod on
     * the side opposite its lens, which is a half turn of that offset. It is
     * made opt-in rather than applied by default: a first attempt at applying it
     * automatically moved the camera out of the world on a structure whose block
     * position and camera position are in different frames, and a pod on an
     * aircraft then came out behind the aircraft. Nothing is moved unless the
     * config asks for it.
     */
    private static final double SPIN = readSpin();

    /**
     * The axis the pod's own view direction is turned about, or null for none.
     *
     * <p>A crew measured the view as being a half turn out. Turning the camera's
     * <i>position</i> about the pod made it stand at the tail of the aircraft
     * instead, which says the half turn belongs to the direction the view looks
     * along rather than to where it starts from. The two are separate switches so
     * they can be tried apart: this one turns the look direction, {@code
     * cameraSpin} turns the starting point.</p>
     */
    private static final String FLIP = readFlip();

    /** A yaw correction added to the pod view as it is drawn, in degrees. */
    private static final double YAW_OFFSET = readAngle("camerayawoffset");

    /** A pitch correction added to the pod view as it is drawn, in degrees. */
    private static final double PITCH_OFFSET = readAngle("camerapitchoffset");

    /** The yaw correction in use. */
    public static double yawOffset() {
        return YAW_OFFSET;
    }

    /** The pitch correction in use. */
    public static double pitchOffset() {
        return PITCH_OFFSET;
    }

    /** Whether either correction is set. */
    public static boolean offsets() {
        return YAW_OFFSET != 0.0D || PITCH_OFFSET != 0.0D;
    }

    /** Reads {@code key=<degrees>} from the config, zero when it says nothing. */
    private static double readAngle(String key) {
        try {
            Path path = Path.of(FILE);
            if (!Files.isRegularFile(path)) {
                return 0.0D;
            }
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String text = line.trim().toLowerCase().replace(" ", "");
                if (!text.startsWith(key)) {
                    continue;
                }
                int equals = text.indexOf('=');
                if (equals < 0) {
                    continue;
                }
                double degrees = Double.parseDouble(text.substring(equals + 1).trim());
                FireControlCompat.LOGGER.info("[firecontrolcompat] {} set to {} degrees", key, degrees);
                return degrees;
            }
            return 0.0D;
        } catch (IOException | RuntimeException ignored) {
            return 0.0D;
        }
    }

    private PodCameraOffset() {
    }

    /** Whether the camera is moved at all. */
    public static boolean active() {
        return OFFSET != Vec3.ZERO || Math.abs(SPIN % 360.0D) > 1.0E-3D;
    }

    /** The half turn applied to the camera's offset, in degrees. */
    public static double spin() {
        return SPIN;
    }

    /**
     * Turns the camera's offset from the pod's centre about the vertical axis, so
     * a camera standing on the far side of the pod's lens comes round to it. The
     * view direction is left alone: only the point the view starts from moves.
     */
    public static Vec3 spin(Vec3 camera, net.minecraft.world.level.Level level, BlockPos pos) {
        if (camera == null || pos == null || Math.abs(SPIN % 360.0D) < 1.0E-3D) {
            return camera;
        }
        Vec3 centre = centreNear(camera, level, pos);
        if (centre == null) {
            return camera;
        }
        double x = camera.x - centre.x;
        double z = camera.z - centre.z;
        double radians = Math.toRadians(SPIN);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        return new Vec3(
                centre.x + x * cos - z * sin,
                camera.y,
                centre.z + x * sin + z * cos);
    }

    /** The configured shift, in the pod's own frame: right, up, forward. */
    public static Vec3 offset() {
        return OFFSET;
    }

    /**
     * Moves a camera position by the configured shift, turned into world space by
     * the pod's facing.
     */
    public static Vec3 apply(Vec3 camera, BlockState state) {
        if (camera == null || OFFSET == Vec3.ZERO) {
            return camera;
        }
        Direction facing = facing(state);
        Vec3 forward = new Vec3(facing.getStepX(), 0.0D, facing.getStepZ());
        if (forward.lengthSqr() < 1.0E-6D) {
            forward = new Vec3(0.0D, 0.0D, 1.0D);
        }
        forward = forward.normalize();
        Vec3 up = new Vec3(0.0D, 1.0D, 0.0D);
        Vec3 right = forward.cross(up).normalize();
        Vec3 world = forward.scale(OFFSET.z).add(up.scale(OFFSET.y)).add(right.scale(OFFSET.x));
        return camera.add(world);
    }

    /** The pod's facing, from whichever property its block declares. */
    public static Direction facing(BlockState state) {
        if (state != null) {
            for (Property<?> property : state.getProperties()) {
                if (property instanceof DirectionProperty direction && property.getName().equals("facing")) {
                    return state.getValue(direction);
                }
            }
        }
        return Direction.NORTH;
    }

    /** The offset in the pod's own frame, as (forward, right, up), for the log. */
    public static Vec3 local(Vec3 worldOffset, BlockState state) {
        if (worldOffset == null) {
            return Vec3.ZERO;
        }
        Direction facing = facing(state);
        Vec3 forward = new Vec3(facing.getStepX(), 0.0D, facing.getStepZ());
        if (forward.lengthSqr() < 1.0E-6D) {
            forward = new Vec3(0.0D, 0.0D, 1.0D);
        }
        forward = forward.normalize();
        Vec3 up = new Vec3(0.0D, 1.0D, 0.0D);
        Vec3 right = forward.cross(up).normalize();
        return new Vec3(worldOffset.dot(forward), worldOffset.dot(right), worldOffset.dot(up));
    }

    /**
     * The pod's centre in whichever frame the camera is in.
     *
     * <p>A pod on a Sable structure has its block position in the structure's own
     * space while the camera it hands out is in world space, and a centre taken
     * from the wrong one is millions of blocks away: turning the camera's offset
     * about that gave a camera flung out of the world - "subspace" to the crew
     * that saw it. Both candidates are therefore offered and the nearest sane one
     * wins; when neither is close the camera is left exactly as it was.</p>
     */
    private static Vec3 centreNear(Vec3 camera, net.minecraft.world.level.Level level, BlockPos pos) {
        Vec3 best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Vec3 candidate : new Vec3[] {
                com.qkdream.firecontrolcompat.SubLevelPositions.worldCenter(level, pos),
                Vec3.atCenterOf(pos)}) {
            if (candidate == null) {
                continue;
            }
            double distance = candidate.distanceTo(camera);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        if (best == null || bestDistance > MAX_SANE_OFFSET) {
            if (!warned) {
                warned = true;
                FireControlCompat.LOGGER.info(
                        "[firecontrolcompat] pod camera frames do not line up (nearest centre {} blocks away), "
                                + "leaving the camera where fire control put it",
                        String.format(java.util.Locale.ROOT, "%.1f", bestDistance));
            }
            return null;
        }
        return best;
    }

    /** How far from the pod a camera may sit before the frames are judged mismatched. */
    private static final double MAX_SANE_OFFSET = 8.0D;

    private static boolean warned;

    /** Whether the view direction is turned at all. */
    public static boolean flips() {
        return FLIP != null;
    }

    /**
     * Whether the pitch is turned over as well as the yaw.
     *
     * <p>A crew measured the pod's view as a half turn out while the pod was
     * level, and a quarter turn out once it was pitched. Turning only the yaw does
     * exactly that: with no pitch the half turn is the whole correction, and with
     * pitch the pitch has to change sign with it - which is the same as turning
     * the direction about the world's vertical axis rather than its angles. {@code
     * cameraFlip=y,pitch} asks for that.</p>
     */
    public static boolean flipsPitch() {
        return FLIP != null && FLIP.contains("pitch");
    }

    /** The configured axis of the view turn, or null. */
    public static String flipAxis() {
        return FLIP;
    }

    /**
     * Turns the pod's look direction about the given axis by a half turn, so a
     * view looking out of the wrong face of the pod looks out of the right one.
     */
    public static Vec3 flip(Vec3 direction) {
        if (direction == null || FLIP == null) {
            return direction;
        }
        return switch (FLIP) {
            case "x" -> new Vec3(direction.x, -direction.y, -direction.z);
            case "z" -> new Vec3(-direction.x, -direction.y, direction.z);
            default -> new Vec3(-direction.x, direction.y, -direction.z);
        };
    }

    /** Reads {@code cameraFlip=y|x|z|true}, or none when the config says nothing. */
    private static String readFlip() {
        try {
            Path path = Path.of(FILE);
            if (!Files.isRegularFile(path)) {
                return null;
            }
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String text = line.trim().toLowerCase();
                if (!text.startsWith("cameraflip")) {
                    continue;
                }
                int equals = text.indexOf('=');
                String value = equals < 0 ? "y" : text.substring(equals + 1).trim();
                if (value.startsWith("x")) {
                    return value.contains("pitch") ? "x,pitch" : "x";
                }
                if (value.startsWith("z")) {
                    return value.contains("pitch") ? "z,pitch" : "z";
                }
                if (value.startsWith("off") || value.startsWith("false") || value.startsWith("0")) {
                    return null;
                }
                String axis = value.contains("pitch") ? "y,pitch" : "y";
                FireControlCompat.LOGGER.info("[firecontrolcompat] pod view direction turned: {}", axis);
                return axis;
            }
            return null;
        } catch (IOException | RuntimeException failure) {
            FireControlCompat.LOGGER.info("[firecontrolcompat] cameraFlip unreadable: {}", failure.toString());
            return null;
        }
    }

    /** The configured turn, or none at all when the config says nothing. */
    private static double readSpin() {
        try {
            Path path = Path.of(FILE);
            if (!Files.isRegularFile(path)) {
                return 0.0D;
            }
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String text = line.trim();
                if (!text.startsWith("cameraSpin")) {
                    continue;
                }
                int equals = text.indexOf('=');
                if (equals < 0) {
                    continue;
                }
                double degrees = Double.parseDouble(text.substring(equals + 1).trim());
                FireControlCompat.LOGGER.info("[firecontrolcompat] pod camera spin set to {} degrees", degrees);
                return degrees;
            }
            return 0.0D;
        } catch (IOException | RuntimeException failure) {
            FireControlCompat.LOGGER.info("[firecontrolcompat] pod camera spin unreadable, leaving it alone: {}",
                    failure.toString());
            return 0.0D;
        }
    }

    private static Vec3 readOffset() {
        try {
            Path path = Path.of(FILE);
            if (!Files.isRegularFile(path)) {
                return Vec3.ZERO;
            }
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String text = line.trim();
                if (!text.startsWith("cameraOffset")) {
                    continue;
                }
                int equals = text.indexOf('=');
                if (equals < 0) {
                    continue;
                }
                String[] parts = text.substring(equals + 1).split(",");
                if (parts.length < 3) {
                    continue;
                }
                Vec3 parsed = new Vec3(
                        Double.parseDouble(parts[0].trim()),
                        Double.parseDouble(parts[1].trim()),
                        Double.parseDouble(parts[2].trim()));
                FireControlCompat.LOGGER.info(
                        "[firecontrolcompat] pod camera offset set to right={} up={} forward={}",
                        parsed.x, parsed.y, parsed.z);
                return parsed;
            }
            return Vec3.ZERO;
        } catch (IOException | RuntimeException failure) {
            FireControlCompat.LOGGER.info("[firecontrolcompat] pod camera offset unreadable: {}",
                    failure.toString());
            return Vec3.ZERO;
        }
    }
}

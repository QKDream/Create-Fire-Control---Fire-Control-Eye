package com.qkdream.firecontrolcompat.blueprint;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Where each sub-level actually sits on the assembled vehicle.
 *
 * <p>A blueprint stores every sub-level's blocks in that sub-level's own grid, and
 * those grids are <em>not</em> a shared frame - placing them all at the origin leaves
 * the hull, the turret and the track skirts as separate slabs. Two mechanisms recorded
 * in {@code global_extra_data} put them together:
 *
 * <dl>
 *   <dt>welds ({@code synaxis:sable_blueprint/weld_constraints})</dt>
 *   <dd>A weld stores the same physical point in two bodies' frames
 *       ({@code anchor_a}/{@code anchor_b}), each frame's orientation
 *       ({@code frame_a}/{@code frame_b}) and the shared surface direction
 *       ({@code normal_a}/{@code normal_b}). The rotation from B's grid into A's is
 *       {@code R = frameA * frameB^T} and the translation pins the anchors:
 *       {@code R * anchor_b + shift == anchor_a}.</dd>
 *   <dt>swivel links ({@code sable_schematic_api:simulated/swivel_bearing})</dt>
 *   <dd>A link records where a bearing sits on the parent and where the mating plate
 *       sits on the attached body. Same physical point, so the child moves by
 *       {@code bearing_point - plate_point}, accumulated up the chain from the root.</dd>
 * </dl>
 *
 * <h2>Why the matrix maths is written out by hand</h2>
 *
 * <p>JOML's {@code Matrix3f.m00..m22}, its {@code mul} and its {@code transform} did
 * not agree with each other about row/column order: the same matrix printed as one
 * thing and transformed a vector as another, which made {@code R} and {@code R^T}
 * indistinguishable by inspection and sent this assembly round in circles. Nine
 * multiply-adds written out plainly are cheaper than that ambiguity, and the
 * {@link #mapsOnto} check below then has something trustworthy to test.
 *
 * <h2>How correctness is checked</h2>
 *
 * <p>Two independent properties, both from the file itself:
 * <ol>
 *   <li>{@code R * normal_b == normal_a} - the weld's own statement that the two
 *       normals are the same direction seen from two frames. This is what tells
 *       {@code R} apart from {@code R^T}: both are rotations and both pin the anchors,
 *       so only the normal distinguishes them.</li>
 *   <li>{@code R * anchor_b + shift == anchor_a} - the anchors coincide.</li>
 * </ol>
 * The global check - that the assembled union lands inside {@code canonical_bound} -
 * lives in the offline probe, since it needs every body placed, not just one.
 */
final class BlueprintAssembly {

    /** Verbose placement logging; off with {@code -Dsableexport.assemblyTrace=false}. */
    private static final boolean TRACE =
        !"false".equalsIgnoreCase(System.getProperty("sableexport.assemblyTrace", "true"));

    private BlueprintAssembly() {}

    /** One sub-level's placement in the assembled frame. */
    static final class Placement {
        float[][] rotation = identity3();
        Vector3f translation = new Vector3f();

        boolean isIdentity() {
            return Math.abs(translation.x) < 1e-6f && Math.abs(translation.y) < 1e-6f
                && Math.abs(translation.z) < 1e-6f
                && Math.abs(rotation[0][0] - 1) < 1e-6f
                && Math.abs(rotation[1][1] - 1) < 1e-6f
                && Math.abs(rotation[2][2] - 1) < 1e-6f;
        }
    }

    static Map<Integer, Placement> compute(CompoundTag root, int subCount) {
        Map<Integer, Placement> out = new LinkedHashMap<>();
        for (int i = 0; i < subCount; i++) {
            out.put(i, new Placement());
        }

        CompoundTag ged = root.getCompound("global_extra_data");
        ListTag subs = root.getList("sub_levels", Tag.TAG_COMPOUND);

        // --- the frame everything else is measured in --------------------------
        // The file states it: the basis body is the one whose relative_pose.position
        // is at the origin. Nothing else is needed to identify it.
        int basis = -1;
        for (int i = 0; i < subs.size(); i++) {
            CompoundTag sub = subs.getCompound(i);
            if (sub.getList("blocks", Tag.TAG_COMPOUND).isEmpty()) {
                continue;
            }
            CompoundTag pos = sub.getCompound("relative_pose").getCompound("position");
            if (Math.abs(pos.getFloat("x")) < 1e-6f
                && Math.abs(pos.getFloat("y")) < 1e-6f
                && Math.abs(pos.getFloat("z")) < 1e-6f) {
                basis = i;
                break;
            }
        }
        if (TRACE) {
            System.out.printf("[assembly] basis sub = %d%n", basis);
        }

        // --- swivel offsets, accumulated from the root ------------------------
        ListTag links = ged.getCompound("sable_schematic_api:simulated/swivel_bearing")
                           .getList("links", Tag.TAG_COMPOUND);
        Map<Integer, Integer> parent = new HashMap<>();
        Map<Integer, Vector3f> delta = new HashMap<>();
        for (int i = 0; i < links.size(); i++) {
            CompoundTag link = links.getCompound(i);
            CompoundTag bearing = link.getCompound("bearing_ref");
            CompoundTag plate = link.getCompound("plate_ref");
            if (bearing.isEmpty() || plate.isEmpty()) {
                continue;
            }
            int parentId = bearing.getInt("sub_level_id");
            int childId = plate.getInt("sub_level_id");
            if (parentId == childId) {
                continue;
            }
            Vector3f bearingPoint = refPoint(bearing);
            Vector3f platePoint = refPoint(plate);
            if (bearingPoint == null || platePoint == null) {
                continue;
            }
            parent.putIfAbsent(childId, parentId);
            delta.putIfAbsent(childId, new Vector3f(bearingPoint).sub(platePoint));
        }
        Map<Integer, Vector3f> swivel = new HashMap<>();
        for (int i = 0; i < subs.size(); i++) {
            resolveSwivel(i, parent, delta, swivel, new HashSet<>());
        }
        if (TRACE) {
            for (int i = 0; i < subs.size(); i++) {
                System.out.printf("[assembly] sub%d swivelOffset=%s%n", i,
                                  vec3(swivel.getOrDefault(i, new Vector3f())));
            }
        }

        // --- welds ------------------------------------------------------------
        ListTag welds = ged.getCompound("synaxis:sable_blueprint/weld_constraints")
                           .getList("entries", Tag.TAG_COMPOUND);
        for (int i = 0; i < welds.size(); i++) {
            CompoundTag weld = welds.getCompound(i);
            CompoundTag bodyA = weld.getCompound("body_a_ref");
            CompoundTag bodyB = weld.getCompound("body_b_ref");
            if (bodyA.isEmpty() || bodyB.isEmpty()) {
                continue;
            }
            int aId = bodyA.getInt("sub_level_id");
            int bId = bodyB.getInt("sub_level_id");
            if (aId == bId) {
                continue;
            }
            // In this data A is the parent and B the child. When B is the basis, the
            // relation is pointing the wrong way for what we need - A is not a body
            // that should be moved - and inverting it produces a transform that looks
            // right (fresh rotation, fresh anchor) while placing the body wrongly.
            // Skipping is what the add-on settled on after hitting the same thing.
            if (bId == basis) {
                if (TRACE) {
                    System.out.printf(
                        "[assembly] weld %d a=%d b=%d: b is the basis, skip%n", i, aId, bId);
                }
                continue;
            }

            float[][] rotA = quatToMatrix(weld.getCompound("frame_a"));
            float[][] rotB = quatToMatrix(weld.getCompound("frame_b"));
            Vector3f normalA = vec(weld.getCompound("normal_a"));
            Vector3f normalB = vec(weld.getCompound("normal_b"));

            // R = frameA * frameB^T. Keep whichever of R / R^T lands normal_b on
            // normal_a; that check is the only thing that separates them.
            float[][] product = mul3(rotA, transpose3(rotB));
            float[][] transposed = transpose3(product);
            boolean plainWorks = mapsOnto(product, normalB, normalA);
            boolean transposeWorks = mapsOnto(transposed, normalB, normalA);
            float[][] rotation = plainWorks ? product : transposed;

            Vector3f anchorA = vec(weld.getCompound("anchor_a"));
            Vector3f anchorB = vec(weld.getCompound("anchor_b"));
            Vector3f rotatedAnchorB = apply3(rotation, anchorB.x, anchorB.y, anchorB.z);
            Vector3f shift = new Vector3f(anchorA).sub(rotatedAnchorB);

            if (TRACE) {
                System.out.printf(
                    "[assembly] weld %d a=%d b=%d  R=%s%n"
                    + "    plain maps nb->na: %s, transpose: %s, using %s%n"
                    + "    R*nb=%s  nb=%s na=%s%n"
                    + "    anchorA=%s anchorB=%s R*anchorB=%s shift=%s%n",
                    i, aId, bId, fmt(rotation), plainWorks, transposeWorks,
                    plainWorks ? "R" : "R^T",
                    vec3(apply3(rotation, normalB.x, normalB.y, normalB.z)),
                    vec3(normalB), vec3(normalA),
                    vec3(anchorA), vec3(anchorB), vec3(rotatedAnchorB), vec3(shift));
            }
            if (!plainWorks && !transposeWorks) {
                BlueprintExporter.LOGGER.warn(
                    "weld {}: neither R nor R^T maps normal_b {} onto normal_a {} -"
                    + " the frame quaternions and the normals disagree",
                    i, vec3(normalB), vec3(normalA));
            }

            Placement p = out.get(bId);
            if (p == null) {
                continue;
            }
            p.rotation = rotation;
            // The swivel offset is in the child's own grid, so it has to ride through
            // the rotation before the weld's shift is added.
            Vector3f subOffset = swivel.getOrDefault(bId, new Vector3f());
            p.translation = new Vector3f(apply3(rotation, subOffset.x, subOffset.y, subOffset.z))
                .add(shift);
            if (TRACE) {
                System.out.printf("[assembly]   sub%d placement t=%s%n",
                                  bId, vec3(p.translation));
            }
        }

        // Bodies that are swivel-attached but not welded move by the offset alone.
        for (Map.Entry<Integer, Vector3f> e : swivel.entrySet()) {
            int id = e.getKey();
            Placement p = out.get(id);
            if (p == null || id == basis || !p.isIdentity()) {
                continue;
            }
            p.translation = new Vector3f(e.getValue());
        }
        return out;
    }

    private static Vector3f resolveSwivel(int subId, Map<Integer, Integer> parent,
                                          Map<Integer, Vector3f> delta,
                                          Map<Integer, Vector3f> out, Set<Integer> seen) {
        Vector3f cached = out.get(subId);
        if (cached != null) {
            return cached;
        }
        if (seen.contains(subId) || !parent.containsKey(subId)) {
            Vector3f zero = new Vector3f();
            out.put(subId, zero);
            return zero;
        }
        seen.add(subId);
        Vector3f up = resolveSwivel(parent.get(subId), parent, delta, out, seen);
        Vector3f value = new Vector3f(up).add(delta.get(subId));
        out.put(subId, value);
        return value;
    }

    /** Does {@code r} carry {@code from} onto {@code to}? Tolerant: frames are floats. */
    private static boolean mapsOnto(float[][] r, Vector3f from, Vector3f to) {
        return apply3(r, from.x, from.y, from.z).distance(to) < 0.05f;
    }

    /** A reference's block-local point, as a block centre. */
    private static Vector3f refPoint(CompoundTag ref) {
        CompoundTag pos = ref.getCompound("local_pos");
        if (pos.isEmpty()) {
            return null;
        }
        return new Vector3f(pos.getFloat("x") + 0.5f, pos.getFloat("y") + 0.5f,
                            pos.getFloat("z") + 0.5f);
    }

    private static Vector3f vec(CompoundTag tag) {
        return new Vector3f(tag.getFloat("x"), tag.getFloat("y"), tag.getFloat("z"));
    }

    /** (x, y, z, w) quaternion -> row-major rotation matrix. */
    private static float[][] quatToMatrix(CompoundTag q) {
        float x = q.getFloat("x");
        float y = q.getFloat("y");
        float z = q.getFloat("z");
        float w = q.getFloat("w");
        float n = (float) Math.sqrt(x * x + y * y + z * z + w * w);
        if (n < 1e-12f) {
            return identity3();
        }
        x /= n;
        y /= n;
        z /= n;
        w /= n;
        return new float[][] {
            {1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)},
            {2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)},
            {2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)},
        };
    }

    // ------------------------------------------------------------- 3x3 helpers

    private static float[][] identity3() {
        return new float[][] {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
    }

    private static float[][] mul3(float[][] a, float[][] b) {
        float[][] out = new float[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                out[i][j] = a[i][0] * b[0][j] + a[i][1] * b[1][j] + a[i][2] * b[2][j];
            }
        }
        return out;
    }

    private static float[][] transpose3(float[][] m) {
        float[][] out = new float[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                out[i][j] = m[j][i];
            }
        }
        return out;
    }

    private static Vector3f apply3(float[][] m, float x, float y, float z) {
        return new Vector3f(
            m[0][0] * x + m[0][1] * y + m[0][2] * z,
            m[1][0] * x + m[1][1] * y + m[1][2] * z,
            m[2][0] * x + m[2][1] * y + m[2][2] * z);
    }

    private static String fmt(float[][] m) {
        return String.format("[%.2f %.2f %.2f | %.2f %.2f %.2f | %.2f %.2f %.2f]",
            m[0][0], m[0][1], m[0][2], m[1][0], m[1][1], m[1][2], m[2][0], m[2][1], m[2][2]);
    }

    private static String vec3(Vector3f v) {
        return String.format("(%.2f, %.2f, %.2f)", v.x, v.y, v.z);
    }

    /** A 4x4 for a placement, ready to push onto a PoseStack. */
    static Matrix4f toMatrix(Placement p) {
        Matrix4f m = new Matrix4f();
        m.m00(p.rotation[0][0]).m01(p.rotation[0][1]).m02(p.rotation[0][2]);
        m.m10(p.rotation[1][0]).m11(p.rotation[1][1]).m12(p.rotation[1][2]);
        m.m20(p.rotation[2][0]).m21(p.rotation[2][1]).m22(p.rotation[2][2]);
        m.m30(p.translation.x).m31(p.translation.y).m32(p.translation.z);
        return m;
    }
}

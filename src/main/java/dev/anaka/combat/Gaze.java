package dev.anaka.combat;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.mob.EndermanEntity;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/** Never look an enderman in the eyes while walking (vanilla: a stare within 64 provokes it). */
public final class Gaze {
    private Gaze() {}

    public static final double RANGE = 64.0;
    /** Added to vanilla's 0.025 (EndermanEntity.isPlayerStaring: dot > 1 − 0.025/d): a few degrees to spare. */
    public static final double MARGIN = 0.1;

    /** Pure: the look (yaw, pitch in degrees) from `eye` falls on `target` (an enderman's eye), with MARGIN. */
    public static boolean staring(double[] eye, float yaw, float pitch, double[] target) {
        double y = Math.toRadians(yaw), p = Math.toRadians(pitch);
        double lx = -Math.sin(y) * Math.cos(p), ly = -Math.sin(p), lz = Math.cos(y) * Math.cos(p);
        double dx = target[0] - eye[0], dy = target[1] - eye[1], dz = target[2] - eye[2];
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (d < 1e-6 || d > RANGE) return false;
        return (lx * dx + ly * dy + lz * dz) / d > 1.0 - (0.025 + MARGIN) / d;
    }

    /** Pure: the nearest pitch to `pitch` (yaw kept: the feet keep their way) that stares at none of `eyes`. */
    public static float safePitch(double[] eye, float yaw, float pitch, List<double[]> eyes) {
        for (int step = 0; step <= 180; step += 5) {
            for (float cand : new float[]{pitch + step, pitch - step}) {
                if (cand < -90f || cand > 90f) continue;
                boolean any = false;
                for (double[] t : eyes) any |= staring(eye, yaw, cand, t);
                if (!any) return cand;
            }
        }
        return pitch;
    }

    /** A walking task's look, pitched off every enderman's eye line within RANGE. */
    public static void avoid(MinecraftClient c, ClientPlayerEntity p) {
        List<double[]> eyes = new ArrayList<>();
        for (EndermanEntity e : c.world.getEntitiesByClass(EndermanEntity.class, p.getBoundingBox().expand(RANGE),
            EndermanEntity::isAlive)) {
            Vec3d at = e.getEyePos();
            eyes.add(new double[]{at.x, at.y, at.z});
        }
        if (eyes.isEmpty()) return;
        Vec3d eye = p.getEyePos();
        p.setPitch(safePitch(new double[]{eye.x, eye.y, eye.z}, p.getYaw(), p.getPitch(), eyes));
    }
}

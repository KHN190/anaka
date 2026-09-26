package dev.anaka.util;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * Survival reflex for lava, the counterpart of the anti-drown safety net. It remembers the last safe standing spot
 * and, whenever the agent drives and the player is in lava (or burning next to it), says where to jump back to.
 * No strategy: it only reverses the last few steps; deciding how to deal with the lava is the client's job.
 */
public final class LavaGuard {
    /** Where to flee: yaw to face and whether to push forward (always jump). */
    public record Escape(float yaw, boolean forward) {}

    private static BlockPos lastSafe;

    private LavaGuard() {}

    public static boolean lavaAt(World w, BlockPos p) {
        return w.getFluidState(p).isIn(FluidTags.LAVA);
    }

    /** Lava touching the cell a body would stand in, its head, or the floor around it. */
    public static boolean nearLava(World w, BlockPos feet) {
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (lavaAt(w, feet.add(dx, dy, dz))) return true;
                }
            }
        }
        return false;
    }

    /** Call every tick while the agent drives. Returns an escape when the player must get out now, else null. */
    public static Escape tick(World w, ClientPlayerEntity p) {
        BlockPos feet = p.getBlockPos();
        boolean inDanger = p.isInLava() || (p.isOnFire() && nearLava(w, feet));
        if (!inDanger) {
            if (p.isOnGround() && !p.isOnFire() && !nearLava(w, feet)) lastSafe = feet;
            return null;
        }
        Vec3d target;
        if (lastSafe != null && lastSafe.getSquaredDistance(feet) <= 64) {
            target = Vec3d.ofBottomCenter(lastSafe);
        } else {
            // No remembered spot close by: move directly away from the nearest lava.
            BlockPos lava = nearestLava(w, feet);
            if (lava == null) return new Escape(p.getYaw(), false);
            target = p.getEntityPos().add(p.getEntityPos().subtract(Vec3d.ofCenter(lava)).multiply(3));
        }
        double dx = target.x - p.getX();
        double dz = target.z - p.getZ();
        float yaw = dev.anaka.Agent.yawTo(dx, dz);
        return new Escape(yaw, dx * dx + dz * dz > 0.04);
    }

    private static BlockPos nearestLava(World w, BlockPos feet) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos q : BlockPos.iterate(feet.add(-2, -1, -2), feet.add(2, 1, 2))) {
            if (lavaAt(w, q)) {
                double d = q.getSquaredDistance(feet);
                if (d < bestD) {
                    bestD = d;
                    best = q.toImmutable();
                }
            }
        }
        return best;
    }
}

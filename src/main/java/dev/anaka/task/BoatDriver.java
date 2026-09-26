package dev.anaka.task;

import dev.anaka.Agent;
import dev.anaka.util.InvUtil;
import dev.anaka.util.WorldUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.vehicle.AbstractBoatEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.List;
import java.util.function.Predicate;

/**
 * Crosses a water stretch of a path by boat: place, board, steer along the water waypoints, get out at the
 * shore and break the boat to take it back. Steering goes through the normal movement input.
 */
final class BoatDriver {
    enum Phase { PLACE, MOUNT, RIDE, EXIT, RECOVER, DONE, FAILED }

    static final int MIN_STRETCH = 6;
    private static final Predicate<ItemStack> BOAT = s -> {
        String id = InvUtil.id(s);
        return id.endsWith("_boat") || id.endsWith("_raft");
    };

    Phase phase = Phase.PLACE;
    private final List<BlockPos> path;
    private final int landIndex;
    private int index;
    private int ticks;
    private int boatId = -1;
    private boolean failAfterRecover;
    private final java.util.Set<Integer> existingBoats = new java.util.HashSet<>();


    /** First route cell ahead that is still water with air above and whose surface is within reach. */
    private BlockPos placementTarget(World w, ClientPlayerEntity p) {
        Vec3d eye = p.getEyePos();
        for (int k = index; k < landIndex && k < path.size(); k++) {
            BlockPos cell = path.get(k);
            if (!WorldUtil.isWater(w, cell) || !w.getFluidState(cell).isStill()) continue;
            if (!w.getBlockState(cell.up()).isAir()) continue;
            Vec3d top = new Vec3d(cell.getX() + 0.5, cell.getY() + 0.9, cell.getZ() + 0.5);
            if (eye.distanceTo(top) <= WorldUtil.blockReach(p)
                && !p.getBoundingBox().intersects(new net.minecraft.util.math.Box(cell))) {
                return cell;
            }
        }
        return null;
    }

    /** The crosshair ray (as the boat item sees it) hits water at or next to the target. */
    private static boolean aimsAtWater(MinecraftClient c, ClientPlayerEntity p, BlockPos target) {
        Vec3d eye = p.getEyePos();
        Vec3d end = eye.add(p.getRotationVec(1f).multiply(p.getBlockInteractionRange()));
        net.minecraft.util.hit.BlockHitResult hit = c.world.raycast(new net.minecraft.world.RaycastContext(eye, end,
            net.minecraft.world.RaycastContext.ShapeType.OUTLINE, net.minecraft.world.RaycastContext.FluidHandling.ANY, p));
        if (hit.getType() != net.minecraft.util.hit.HitResult.Type.BLOCK) return false;
        BlockPos at = hit.getBlockPos();
        return WorldUtil.isWater(c.world, at) && at.getSquaredDistance(target) <= 2;
    }

    BoatDriver(List<BlockPos> path, int index, int landIndex) {
        this.path = path;
        this.index = index;
        this.landIndex = landIndex;
    }

    static boolean hasBoat(ClientPlayerEntity p) {
        return InvUtil.find(p, BOAT) >= 0;
    }

    /** If a long water stretch starts at or right after {@code from}, returns the index of the landing waypoint. */
    static int landIndexOfStretch(World w, List<BlockPos> path, int from) {
        int start = from;
        if (start < path.size() && !WorldUtil.isWater(w, path.get(start))) start++;
        int end = start;
        while (end < path.size() && WorldUtil.isWater(w, path.get(end))) end++;
        return end - start >= MIN_STRETCH ? Math.min(end, path.size() - 1) : -1;
    }

    String describe() {
        return "boat: " + phase.name().toLowerCase();
    }

    /** Returns true once finished (phase DONE or FAILED). */
    boolean tick(MinecraftClient c, Agent a) {
        ClientPlayerEntity p = c.player;
        ticks++;
        switch (phase) {
            case PLACE -> {
                if (ticks == 1) {
                    for (AbstractBoatEntity b : c.world.getEntitiesByClass(AbstractBoatEntity.class,
                        p.getBoundingBox().expand(8), b -> true)) {
                        existingBoats.add(b.getId());
                    }
                }
                if (ticks > 80 || !InvUtil.select(c, BOAT)) return finish(Phase.FAILED);
                // Only a still-water surface cell within reach on the route is a valid spot. Without one, don't
                // throw the boat at whatever the crosshair hits (a bank, a wall): keep walking / swimming.
                BlockPos water = placementTarget(c.world, p);
                if (water == null) return finish(Phase.FAILED);
                Vec3d surface = new Vec3d(water.getX() + 0.5, water.getY() + 0.9, water.getZ() + 0.5);
                if (Agent.lookAt(p, surface, 30f) && ticks % 5 == 0 && aimsAtWater(c, p, water)) {
                    c.interactionManager.interactItem(p, Hand.MAIN_HAND);
                    p.swingHand(Hand.MAIN_HAND);
                }
                List<AbstractBoatEntity> boats = c.world.getEntitiesByClass(AbstractBoatEntity.class,
                    p.getBoundingBox().expand(6), b -> !b.hasPassengers() && !existingBoats.contains(b.getId()));
                if (!boats.isEmpty()) {
                    AbstractBoatEntity boat = boats.get(0);
                    boatId = boat.getId();
                    if (boat.isTouchingWater()) {
                        next(Phase.MOUNT);
                    } else {
                        // Landed on a bank after all: take it back and give up on the boat for this stretch.
                        failAfterRecover = true;
                        next(Phase.RECOVER);
                    }
                }
            }
            case MOUNT -> {
                Entity boat = c.world.getEntityById(boatId);
                if (boat == null) return finish(Phase.FAILED);
                if (p.hasVehicle()) {
                    next(Phase.RIDE);
                } else if (ticks > 60) {
                    next(Phase.RECOVER);
                } else if (Agent.lookAt(p, boat.getEntityPos().add(0, 0.3, 0), 40f) && ticks % 4 == 0) {
                    c.interactionManager.interactEntity(p, boat, Hand.MAIN_HAND);
                }
            }
            case RIDE -> {
                Entity boat = p.getVehicle();
                if (boat == null) {
                    next(Phase.RECOVER);
                    return false;
                }
                while (index < landIndex && horizontal(p, path.get(index)) < 2.0) index++;
                BlockPos target = path.get(Math.min(index, path.size() - 1));
                double dx = target.getX() + 0.5 - p.getX(), dz = target.getZ() + 0.5 - p.getZ();
                float bearing = Agent.yawTo(dx, dz);
                float diff = MathHelper.wrapDegrees(bearing - boat.getYaw());
                a.input.left = diff < -8f;
                a.input.right = diff > 8f;
                a.input.forward = Math.abs(diff) < 50f;
                Agent.rotateTowards(p, bearing, 10f, 20f);
                boolean atShore = index >= landIndex && horizontal(p, path.get(landIndex)) < 2.3;
                if (atShore || (boat.horizontalCollision && index >= landIndex - 2) || ticks > 20 * 180) {
                    next(Phase.EXIT);
                }
            }
            case EXIT -> {
                a.input.sneak = true;
                if (!p.hasVehicle()) next(Phase.RECOVER);
                else if (ticks > 40) return finish(Phase.FAILED);
            }
            case RECOVER -> {
                Entity boat = c.world.getEntityById(boatId);
                if (boat == null || boat.isRemoved() || ticks > 100) {
                    return finish(failAfterRecover ? Phase.FAILED : Phase.DONE);
                }
                if (Agent.lookAt(p, boat.getEntityPos().add(0, 0.3, 0), 45f) && p.getAttackCooldownProgress(0.5f) >= 0.9f) {
                    c.interactionManager.attackEntity(p, boat);
                    p.swingHand(Hand.MAIN_HAND);
                }
            }
            default -> {
                return true;
            }
        }
        return false;
    }

    int index() {
        return index;
    }

    private void next(Phase phase) {
        this.phase = phase;
        ticks = 0;
    }

    private boolean finish(Phase phase) {
        this.phase = phase;
        return true;
    }

    private static double horizontal(ClientPlayerEntity p, BlockPos wp) {
        double dx = wp.getX() + 0.5 - p.getX(), dz = wp.getZ() + 0.5 - p.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }
}

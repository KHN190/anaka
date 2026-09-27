package dev.anaka.task;

import dev.anaka.Agent;
import dev.anaka.util.Pathfinder;
import dev.anaka.util.WorldUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/** Walks along an A* path using simulated movement keys (real physics, jumping, swimming). */
public final class GotoTask extends Task {
    private static final int SEARCH_BUDGET_PER_TICK = 4000;
    static final int MAX_NODES = 60_000;
    private static final int MAX_REPATHS = 5;

    private final Pathfinder.Goal goal;
    private final String label;
    private final boolean allowPartial;
    private final boolean sprint;

    private Pathfinder planner;
    private List<BlockPos> path;
    private boolean partial;
    private int index;
    private BlockPos openedDoor;
    private int doorCooldown;
    private int centerTicks;
    private boolean center = true;
    private int progressIndex = -1;
    private double bestWaypointDistance;
    private int noProgressTicks;
    private Vec3d anchor;
    private int repaths;
    private Vec3d lastCheckPos;
    private int stuckChecks;
    private int sinceCheck;

    private final boolean useBoat;
    private final int maxNodes;
    private BoatDriver boat;
    private int boatCooldown;

    public GotoTask(Pathfinder.Goal goal, String label, boolean allowPartial, boolean sprint) {
        this(goal, label, allowPartial, sprint, false);
    }

    public GotoTask(Pathfinder.Goal goal, String label, boolean allowPartial, boolean sprint, boolean useBoat) {
        this(goal, label, allowPartial, sprint, useBoat, MAX_NODES);
    }

    /** {@code maxNodes} bounds the search: short approaches (placing, using a block) give up fast instead of
     * exploring 60 000 positions for a goal that is simply unreachable. */
    public GotoTask(Pathfinder.Goal goal, String label, boolean allowPartial, boolean sprint, boolean useBoat,
                    int maxNodes) {
        super("goto");
        this.goal = goal;
        this.label = label;
        this.allowPartial = allowPartial;
        this.sprint = sprint;
        this.useBoat = useBoat;
        this.maxNodes = maxNodes;
        this.timeoutTicks = 20 * 300;
    }

    public static GotoTask near(BlockPos target, double range, boolean allowPartial, boolean sprint) {
        return near(target, range, allowPartial, sprint, false);
    }

    public static GotoTask near(BlockPos target, double range, boolean allowPartial, boolean sprint, boolean useBoat) {
        double rangeSq = range * range;
        Pathfinder.Goal goal = new Pathfinder.Goal(p -> p.getSquaredDistance(target) <= rangeSq, target);
        return new GotoTask(goal, "walking to " + target.toShortString(), allowPartial, sprint, useBoat);
    }

    @Override
    public boolean walking() {
        return boat == null;
    }

    /** Arrive on reaching the goal cell, without settling into its centre: a chase, not a placement. */
    public GotoTask noCentering() {
        center = false;
        return this;
    }

    @Override
    public String describe() {
        if (boat != null) return label + " (" + boat.describe() + ")";
        if (path == null) return label + " (planning)";
        return label + " (" + Math.min(index, path.size()) + "/" + path.size() + ")";
    }

    private static List<BlockPos> nodes(List<Pathfinder.Step> steps) {
        return steps.stream().map(Pathfinder.Step::node).toList();
    }

    static BlockPos feet(ClientPlayerEntity p) {
        return BlockPos.ofFloored(p.getX(), p.getY() + 0.2, p.getZ());
    }

    @Override
    protected void start(MinecraftClient c, Agent a) {
        plan(c);
    }

    private void plan(MinecraftClient c) {
        planner = new Pathfinder(c.world, c.player, feet(c.player), goal, Pathfinder.WALK_ONLY, maxNodes);
        path = null;
        anchor = null;
        sinceCheck = 0;
        stuckChecks = 0;
        lastCheckPos = null;
    }

    @Override
    protected void tick(MinecraftClient c, Agent a) {
        ClientPlayerEntity p = c.player;
        BlockPos feet = feet(p);
        if (goal.reached().test(feet) && heldUp(c, p, feet)) {
            // Goals are judged from the block centre (eye position, reach, sight lines); the body can stand at the
            // block's edge where those judgements are false. Settle into the centre before reporting arrival.
            double cx = feet.getX() + 0.5 - p.getX(), cz = feet.getZ() + 0.5 - p.getZ();
            if (center && Math.sqrt(cx * cx + cz * cz) > 0.2 && ++centerTicks <= 20) {
                Agent.rotateTowards(p, Agent.yawTo(cx, cz), p.getPitch(), 90f);
                a.input.forward = true;
                a.input.sneak = true; // slow and never walks off an edge
                return;
            }
            result.addProperty("x", feet.getX());
            result.addProperty("y", feet.getY());
            result.addProperty("z", feet.getZ());
            succeed("arrived");
            return;
        }
        centerTicks = 0;

        if (path == null) {
            planner.step(SEARCH_BUDGET_PER_TICK);
            if (!planner.isDone()) return;
            partial = !planner.found();
            if (partial && (!allowPartial || planner.path().size() <= 1)) {
                List<BlockPos> closest = nodes(planner.path());
                if (!closest.isEmpty()) {
                    BlockPos end = closest.get(closest.size() - 1);
                    result.add("closest", posJson(end));
                }
                fail("no path found (" + planner.expanded() + " positions explored)");
                return;
            }
            path = nodes(planner.path());
            index = 1;
        }

        while (index < path.size() && (reached(p, path.get(index)) || reachedSwimming(c, p, feet, path.get(index)))) {
            index++;
            anchor = p.getEntityPos();
        }
        if (anchor == null) anchor = p.getEntityPos();
        // String-pulling: aim straight at a later waypoint when the ground between is flat and clear.
        while (index + 1 < path.size() && path.get(index + 1).getY() == path.get(index).getY()
            && path.get(index).getY() == feet.getY() && p.isOnGround()
            && straightWalkable(c, p, path.get(index + 1))) {
            index++;
        }
        if (index >= path.size()) {
            if (partial) {
                result.addProperty("partial", true);
                fail("target unreachable; stopped at the closest reachable point");
            } else if (heldUp(c, p, feet)) {
                succeed("arrived");
            }
            return;
        }

        if (boat != null) {
            if (boat.tick(c, a)) {
                boolean ok = boat.phase == BoatDriver.Phase.DONE;
                boat = null;
                boatCooldown = ok ? 0 : 20 * 30;
                // Pick up the broken boat, then plan the rest of the way from the shore.
                child = ok ? new CollectTask(p.getBlockPos(), 4, 10) : null;
                plan(c);
            }
            return;
        }
        if (child != null) {
            if (!runChild(c, a)) return;
            child = null;
        }
        if (useBoat && --boatCooldown <= 0 && BoatDriver.hasBoat(p)) {
            int land = BoatDriver.landIndexOfStretch(c.world, path, index);
            if (land > 0) {
                boat = new BoatDriver(path, index, land);
                return;
            }
        }

        if (handleDoors(c, p)) return;

        BlockPos wp = path.get(index);
        double dx = wp.getX() + 0.5 - p.getX();
        double dz = wp.getZ() + 0.5 - p.getZ();
        double dy = wp.getY() - p.getY();
        double horizontal = Math.sqrt(dx * dx + dz * dz);

        // Deviation from the line we are actually walking: from where we last reached a waypoint to the current one.
        if (distanceToSegment(p.getX(), p.getZ(), anchor.x, anchor.z, wp.getX() + 0.5, wp.getZ() + 0.5) > 2.5
            || dy < -5) {
            repath(c, "fell off the path");
            return;
        }

        float yaw = Agent.yawTo(dx, dz);
        Agent.rotateTowards(p, yaw, 0f, 40f);
        float yawError = Math.abs(MathHelper.wrapDegrees(yaw - p.getYaw()));
        // Close to a waypoint a wide turn overshoots and orbits it; turn in place first.
        a.input.forward = yawError < (horizontal < 1.5 ? 25f : 50f);
        a.input.sprint = sprint && path.size() - index > 3 && p.getHungerManager().getFoodLevel() > 6 && !p.isTouchingWater();
        boolean climb = dy > 0.5 && horizontal < 1.6;
        a.input.jump = (p.isOnGround() && (climb || p.horizontalCollision))
            || (p.isTouchingWater() && (dy > -0.5 || p.isSubmergedInWater()))
            || (p.isClimbing() && dy > 0.3); // holding jump climbs a ladder
        if (p.isClimbing() && horizontal < 0.5) {
            a.input.forward = false; // straight vertical: don't push off the ladder; releasing everything descends
            a.input.sprint = false;
        }

        // Moving but never getting closer (orbiting a waypoint in water, sliding along a corner) is also stuck.
        if (index != progressIndex) {
            progressIndex = index;
            bestWaypointDistance = horizontal;
            noProgressTicks = 0;
        } else if (horizontal < bestWaypointDistance - 0.25) {
            bestWaypointDistance = horizontal;
            noProgressTicks = 0;
        } else if (++noProgressTicks > 50) {
            noProgressTicks = 0;
            if (index + 1 < path.size() && horizontal < 2.5 && Math.abs(dy) < 1.5) {
                index++;
            } else {
                repath(c, "no progress toward the next waypoint");
            }
            return;
        }

        if (++sinceCheck >= 20) {
            sinceCheck = 0;
            Vec3d now = new Vec3d(p.getX(), p.getY(), p.getZ());
            if (lastCheckPos != null && now.squaredDistanceTo(lastCheckPos) < 0.04) {
                if (++stuckChecks >= 3) repath(c, "stuck");
            } else {
                stuckChecks = 0;
            }
            lastCheckPos = now;
        }
    }

    /**
     * Opens a closed wooden door on the next waypoints and closes doors behind us (keeps shelters mob-proof).
     * Returns true while busy with a door this tick.
     */
    private boolean handleDoors(MinecraftClient c, ClientPlayerEntity p) {
        if (doorCooldown > 0) {
            doorCooldown--;
            return false;
        }
        if (openedDoor != null) {
            double d = Math.sqrt(p.squaredDistanceTo(Vec3d.ofCenter(openedDoor)));
            if (!dev.anaka.util.WorldUtil.openWoodenDoor(c.world, openedDoor)) {
                openedDoor = null;
            } else if (d > 1.8 && d < 3.5 && !p.getBoundingBox().intersects(new net.minecraft.util.math.Box(openedDoor))) {
                if (Agent.lookAt(p, Vec3d.ofCenter(openedDoor), 45f)) {
                    c.interactionManager.interactBlock(p, net.minecraft.util.Hand.MAIN_HAND,
                        new net.minecraft.util.hit.BlockHitResult(Vec3d.ofCenter(openedDoor), net.minecraft.util.math.Direction.UP, openedDoor, false));
                    openedDoor = null;
                    doorCooldown = 5;
                }
                return true;
            } else if (d >= 3.5) {
                openedDoor = null;
            }
        }
        for (int k = index; k < Math.min(index + 2, path.size()); k++) {
            for (BlockPos cell : new BlockPos[]{path.get(k), path.get(k).up()}) {
                if (!dev.anaka.util.WorldUtil.closedWoodenDoor(c.world, cell)) continue;
                if (p.getEyePos().squaredDistanceTo(Vec3d.ofCenter(cell)) > 9) return false;
                if (Agent.lookAt(p, Vec3d.ofCenter(cell), 45f)) {
                    c.interactionManager.interactBlock(p, net.minecraft.util.Hand.MAIN_HAND,
                        new net.minecraft.util.hit.BlockHitResult(Vec3d.ofCenter(cell), net.minecraft.util.math.Direction.UP, cell, false));
                    p.swingHand(net.minecraft.util.Hand.MAIN_HAND);
                    openedDoor = cell;
                    doorCooldown = 5;
                }
                return true;
            }
        }
        return false;
    }

    private static double distanceToSegment(double px, double pz, double ax, double az, double bx, double bz) {
        double vx = bx - ax, vz = bz - az;
        double lenSq = vx * vx + vz * vz;
        double t = lenSq == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * vx + (pz - az) * vz) / lenSq));
        double cx = ax + t * vx - px, cz = az + t * vz - pz;
        return Math.sqrt(cx * cx + cz * cz);
    }

    /** Samples the straight line (and both shoulders of the body) for standable ground at the same height. */
    private static boolean straightWalkable(MinecraftClient c, ClientPlayerEntity p, BlockPos target) {
        double tx = target.getX() + 0.5, tz = target.getZ() + 0.5;
        double dx = tx - p.getX(), dz = tz - p.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len > 12) return false;
        double nx = -dz / Math.max(len, 1e-6) * 0.3, nz = dx / Math.max(len, 1e-6) * 0.3;
        int y = target.getY();
        for (double t = 0; t <= len; t += 0.4) {
            double x = p.getX() + dx * t / len, z = p.getZ() + dz * t / len;
            for (int side = -1; side <= 1; side++) {
                BlockPos cell = BlockPos.ofFloored(x + nx * side, y, z + nz * side);
                if (!dev.anaka.util.WorldUtil.standable(c.world, cell)
                    || dev.anaka.util.WorldUtil.isWater(c.world, cell)
                    || dev.anaka.util.LavaGuard.nearLava(c.world, cell)) return false;
            }
        }
        return true;
    }

    static com.google.gson.JsonObject posJson(BlockPos pos) {
        com.google.gson.JsonObject o = new com.google.gson.JsonObject();
        o.addProperty("x", pos.getX());
        o.addProperty("y", pos.getY());
        o.addProperty("z", pos.getZ());
        return o;
    }

    /**
     * Held where it stands: on the ground out of the water, or in the water where the target cell itself is water.
     * Touching water alone counted a swimmer beside the bank as arrived on it.
     */
    private static boolean heldUp(MinecraftClient c, ClientPlayerEntity p, BlockPos feet) {
        return WorldUtil.isWater(c.world, feet) ? p.isTouchingWater() || p.isOnGround() : p.isOnGround();
    }

    /** In the water, the next node is dry land above the feet (or a solid-floored cell out of the water). */
    private static boolean ashoreNext(MinecraftClient c, ClientPlayerEntity p, BlockPos feet, BlockPos wp) {
        return p.isTouchingWater() && !WorldUtil.isWater(c.world, wp)
            && (wp.getY() >= feet.getY() + 1
                || !c.world.getBlockState(wp.down()).isAir() && !WorldUtil.isWater(c.world, wp.down()));
    }

    /** Floating bodies drift and bob, so accept a wider radius and ignore height while swimming — for water nodes;
     * a dry node above the water is reached only by standing on it (`reached`). */
    private static boolean reachedSwimming(MinecraftClient c, ClientPlayerEntity p, BlockPos feet, BlockPos wp) {
        if (!p.isTouchingWater() || ashoreNext(c, p, feet, wp)) return false;
        double dx = wp.getX() + 0.5 - p.getX();
        double dz = wp.getZ() + 0.5 - p.getZ();
        return dx * dx + dz * dz < 0.9 * 0.9 && Math.abs(wp.getY() - p.getY()) < 1.5;
    }

    private static boolean reached(ClientPlayerEntity p, BlockPos wp) {
        double dx = wp.getX() + 0.5 - p.getX();
        double dz = wp.getZ() + 0.5 - p.getZ();
        double dy = wp.getY() - p.getY();
        return dx * dx + dz * dz < 0.35 * 0.35 && dy > -1.0 && dy < 0.6;
    }

    private void repath(MinecraftClient c, String why) {
        if (++repaths > MAX_REPATHS) {
            fail("gave up: " + why);
            return;
        }
        plan(c);
    }
}

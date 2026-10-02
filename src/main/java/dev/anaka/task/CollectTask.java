package dev.anaka.task;

import dev.anaka.Agent;
import dev.anaka.util.Pathfinder;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Walks next to dropped items near a point until none reachable are left. */
public final class CollectTask extends Task {
    /** Items are picked up within about one block horizontally and half a block vertically of the body. */
    private static final double PICKUP_HORIZONTAL = 1.3;

    private final BlockPos center;
    private final double radius;
    private final int idleLimit;
    /** Item ids worth walking to; null = everything. Lets a nearly full bag skip junk instead of collecting it. */
    private final Set<String> only;

    private final Set<Integer> unreachable = new HashSet<>();
    /** Items whose cell was dug once already: a drop buried by falling sand is dug out, once, before giving up. */
    private final Set<Integer> dugOut = new HashSet<>();
    private int idleTicks;
    private int arrivedTicks;
    private boolean digging;
    private int targetId = -1;
    private int collected;

    public CollectTask(BlockPos center, double radius, int idleLimit) {
        this(center, radius, idleLimit, null);
    }

    public CollectTask(BlockPos center, double radius, int idleLimit, Set<String> only) {
        super("collect");
        this.center = center;
        this.radius = radius;
        this.idleLimit = idleLimit;
        this.only = only;
        this.timeoutTicks = 20 * 90;
    }

    @Override
    public String describe() {
        return "collecting items (" + collected + ")";
    }

    @Override
    protected void tick(MinecraftClient c, Agent a) {
        ClientPlayerEntity p = c.player;
        BlockPos origin = center != null ? center : p.getBlockPos();
        List<ItemEntity> items = c.world.getEntitiesByClass(ItemEntity.class, new Box(origin).expand(radius),
            e -> e.isAlive() && !unreachable.contains(e.getId())
                && (only == null || only.contains(net.minecraft.registry.Registries.ITEM.getId(e.getStack().getItem()).toString())));

        if (targetId >= 0 && items.stream().noneMatch(e -> e.getId() == targetId)) {
            if (!unreachable.contains(targetId)) collected++;
            targetId = -1;
            if (child != null && !child.isFinished()) child.cancel("item picked up");
            child = null;
        }

        Optional<ItemEntity> nearest = items.stream().min(Comparator.comparingDouble(e -> e.squaredDistanceTo(p)));
        if (nearest.isEmpty()) {
            if (++idleTicks >= idleLimit) {
                result.addProperty("collected", collected);
                result.addProperty("unreachable", unreachable.size());
                succeed(unreachable.isEmpty() ? "no items left nearby" : unreachable.size() + " items unreachable");
            }
            return;
        }
        idleTicks = 0;
        ItemEntity item = nearest.get();
        if (digging && child != null) {
            if (!child.isFinished()) {
                runChild(c, a);
                return;
            }
            digging = false;             // dug (or not): walk to it again
            child = null;
        }
        if (child != null && child.status() == Status.SUCCEEDED && item.getId() == targetId) {
            // "Arrived" next to the item but it isn't being picked up (inside a block, on a ledge, ...).
            if (++arrivedTicks > 20) {
                BlockPos cell = item.getBlockPos();
                if (!c.world.getBlockState(cell).isAir() && dugOut.add(targetId)) {
                    // Buried: sand or gravel fell onto the drop (mine_stone__buried_by_sand lost 1 of 3). Dig its
                    // cell out, then walk to it again.
                    child = new MineTask(cell, false, false).down(true);
                    digging = true;
                    arrivedTicks = 0;
                    return;
                }
                unreachable.add(targetId);
                targetId = -1;
                child = null;
                arrivedTicks = 0;
            }
            return;
        }
        arrivedTicks = 0;
        if (child == null || child.isFinished() || item.getId() != targetId) {
            if (child != null && child.status() == Status.FAILED) unreachable.add(targetId);
            if (child != null && !child.isFinished()) child.cancel("retarget");
            if (unreachable.contains(item.getId())) return;
            targetId = item.getId();
            BlockPos at = item.getBlockPos();
            Pathfinder.Goal goal = new Pathfinder.Goal(feet -> {
                double dx = feet.getX() + 0.5 - item.getX();
                double dz = feet.getZ() + 0.5 - item.getZ();
                int dy = at.getY() - feet.getY();
                // Standing on the rim of the hole a mined block left, the drop in it is out of pickup reach (the
                // box reaches half a block down): stand at its level or one below, never above it. From the rim the
                // sweep waited out arrivedTicks at every hole (mine_stone__interrupt_mid_work: ~4 s a sweep).
                return dx * dx + dz * dz <= PICKUP_HORIZONTAL * PICKUP_HORIZONTAL && dy >= 0 && dy <= 1;
            }, at);
            // walk only: a drop with no standing cell in pickup range on foot is left, Python decides a way to it
            child = ApproachTask.to(goal, "picking up item", false, GotoTask.MAX_NODES);
        }
        if (runChild(c, a) && child.status() == Status.FAILED) {
            unreachable.add(targetId);
            targetId = -1;
            child = null;
        }
    }
}

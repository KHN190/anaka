package dev.anaka.util;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;
import java.util.function.Predicate;

/**
 * Incremental A* over standable block positions: walk, diagonal (no corner cutting), 1-block step up,
 * drops of up to 3 blocks, and swimming. Call {@link #step(int)} each tick until {@link #isDone()}.
 */
public final class Pathfinder {
    public record Goal(Predicate<BlockPos> reached, BlockPos target) {
        double heuristic(BlockPos p) {
            return Math.sqrt(p.getSquaredDistance(target));
        }
    }

    private static final int[][] CARDINAL = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[][] DIAGONAL = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
    private static final int MAX_DROP = 3;

    private static final class Node {
        final BlockPos pos;
        Node parent;
        double g;
        boolean closed;

        Node(BlockPos pos) {
            this.pos = pos;
        }
    }

    private record Entry(Node node, double g, double f) {}

    private final World world;
    private final Goal goal;
    private final int maxNodes;
    private final Long2ObjectOpenHashMap<Node> nodes = new Long2ObjectOpenHashMap<>();
    private final PriorityQueue<Entry> open = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
    private Node best;
    private double bestH = Double.MAX_VALUE;
    private int expanded;
    private boolean done;
    private boolean found;
    private List<BlockPos> path = List.of();

    public Pathfinder(World world, BlockPos start, Goal goal, int maxNodes) {
        this.world = world;
        this.goal = goal;
        this.maxNodes = maxNodes;
        Node s = new Node(start);
        nodes.put(start.asLong(), s);
        open.add(new Entry(s, 0, goal.heuristic(start)));
    }

    public boolean isDone() {
        return done;
    }

    public boolean found() {
        return found;
    }

    public int expanded() {
        return expanded;
    }

    /** Full path when found, otherwise the path to the closest explored point. */
    public List<BlockPos> path() {
        return path;
    }

    public void step(int budget) {
        while (!done && budget-- > 0) {
            Entry e = open.poll();
            if (e == null || expanded >= maxNodes) {
                finish(best, false);
                return;
            }
            Node n = e.node;
            if (n.closed || e.g > n.g) continue;
            n.closed = true;
            expanded++;
            if (goal.reached.test(n.pos)) {
                finish(n, true);
                return;
            }
            double h = goal.heuristic(n.pos);
            if (h < bestH) {
                bestH = h;
                best = n;
            }
            expand(n);
        }
    }

    private void expand(Node n) {
        BlockPos p = n.pos;
        boolean inWater = WorldUtil.isWater(world, p);
        for (int[] d : CARDINAL) {
            BlockPos side = p.add(d[0], 0, d[1]);
            if (WorldUtil.standable(world, side)) {
                offer(n, side, 1.0);
                continue;
            }
            BlockPos up = side.up();
            if (WorldUtil.standable(world, up) && WorldUtil.passable(world, p.up(2))) {
                offer(n, up, 2.0);
                continue;
            }
            if (WorldUtil.passable(world, side) && WorldUtil.passable(world, side.up())) {
                for (int k = 1; k <= MAX_DROP; k++) {
                    BlockPos down = side.down(k);
                    if (WorldUtil.standable(world, down)) {
                        offer(n, down, 1.0 + k * 0.5);
                        break;
                    }
                    if (!WorldUtil.passable(world, down)) break;
                }
            }
        }
        for (int[] d : DIAGONAL) {
            BlockPos diag = p.add(d[0], 0, d[1]);
            // The body sweeps over both corner cells: they must be standable too, never a hole or a lava pit.
            if (WorldUtil.standable(world, diag)
                && WorldUtil.standable(world, p.add(d[0], 0, 0)) && WorldUtil.standable(world, p.add(0, 0, d[1]))) {
                offer(n, diag, 1.42);
            }
        }
        if (inWater) {
            // From anywhere underwater, swimming straight up is always possible until we reach the surface.
            if (WorldUtil.isWater(world, p.up()) || WorldUtil.standable(world, p.up())) offer(n, p.up(), 1.0);
            if (WorldUtil.standable(world, p.down())) offer(n, p.down(), 1.5);
        }
        // Ladders: climb straight up or down a climbable column.
        BlockPos up = p.up();
        BlockPos down = p.down();
        if ((WorldUtil.climbable(world, p) || WorldUtil.climbable(world, up))
            && WorldUtil.passable(world, up) && WorldUtil.passable(world, up.up())) {
            offer(n, up, 1.3);
        }
        if (WorldUtil.climbable(world, down) && WorldUtil.passable(world, down)) {
            offer(n, down, 1.1);
        }
    }

    private void offer(Node from, BlockPos to, double cost) {
        if (WorldUtil.isWater(world, to)) cost += 2.0;
        // Walking beside lava is legal but one slip (sprint momentum, a knockback) is fatal: detour when possible.
        if (LavaGuard.nearLava(world, to)) cost += 8.0;
        double g = from.g + cost;
        Node n = nodes.get(to.asLong());
        if (n == null) {
            n = new Node(to);
            n.g = Double.MAX_VALUE;
            nodes.put(to.asLong(), n);
        }
        if (n.closed || g >= n.g) return;
        n.g = g;
        n.parent = from;
        open.add(new Entry(n, g, g + goal.heuristic(to)));
    }

    private void finish(Node end, boolean reached) {
        done = true;
        found = reached;
        List<BlockPos> out = new ArrayList<>();
        for (Node n = end; n != null; n = n.parent) out.add(n.pos);
        Collections.reverse(out);
        path = out;
    }
}

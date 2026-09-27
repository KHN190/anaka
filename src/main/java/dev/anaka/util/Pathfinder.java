package dev.anaka.util;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;
import java.util.function.Predicate;

/**
 * The one incremental A*. Edges may carry work, Baritone-style: walk / diagonal (no corner cutting) / step / drop /
 * swim / climb, plus break through (feet+head, or straight down), bridge (place a floor ahead) and pillar
 * (place a block under ourselves). One world model plans and executes the route, so a planned move is always one
 * the walker can do.
 */
public final class Pathfinder {
    public record Goal(Predicate<BlockPos> reached, BlockPos target) {}
    public enum Kind { MINE, FLOOR, PILLAR }

    public record Action(Kind kind, BlockPos pos, BlockPos against) {}

    public record Step(BlockPos node, List<Action> actions) {}

    /** {@code voidBridge}: may a floor be placed where nothing (no ground, no water) lies within a survivable drop
     * below it — a bridge out over the void. A walk with a known far side may; an evade or an explore leg may not
     * (an explore leg bridged off the sky platform and a knockback threw the body 125 blocks down). */
    public record Options(boolean allowBreak, boolean allowPlace, int placeBudget, LongSet avoid, boolean voidBridge) {
        public Options(boolean allowBreak, boolean allowPlace, int placeBudget, LongSet avoid) {
            this(allowBreak, allowPlace, placeBudget, avoid, true);
        }
    }

    /** Pure over the world: nothing to land on — no solid block, no water — within MAX_DROP + 1 below `cell`. */
    static boolean overVoid(net.minecraft.world.World world, BlockPos cell) {
        for (int k = 1; k <= MAX_DROP + 1; k++) {
            BlockPos below = cell.down(k);
            if (WorldUtil.isWater(world, below) || !WorldUtil.passable(world, below)) return false;
        }
        return true;
    }

    /** Walking only: no block is broken or placed. */
    public static final Options WALK_ONLY =
        new Options(false, false, 0, it.unimi.dsi.fastutil.longs.LongSets.EMPTY_SET);

    private static final int[][] CARDINAL = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[][] DIAGONAL = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
    private static final int MAX_DROP = 3;
    private static final double MAX_HAND_HARDNESS = 1.6;   // stone-ish by hand is ~7 s; deepslate/ores are walls

    private static final class Node {
        final BlockPos pos;
        Node parent;
        double g;
        boolean closed;
        List<Action> actions = List.of();
        int placed;
        boolean madeFloor;

        Node(BlockPos pos) {
            this.pos = pos;
        }
    }

    private record Entry(Node node, double g, double f) {}

    private final World world;
    private final ClientPlayerEntity player;
    private final Predicate<BlockPos> reached;
    private final BlockPos target;
    private final Options opts;
    private final int maxNodes;
    private final Long2ObjectOpenHashMap<Node> nodes = new Long2ObjectOpenHashMap<>();
    private final PriorityQueue<Entry> open = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
    private Node best;
    private double bestH = Double.MAX_VALUE;
    private int expanded;
    private boolean done;
    private boolean found;
    private List<Step> path = List.of();

    public Pathfinder(World world, ClientPlayerEntity player, BlockPos start, Goal goal, Options opts, int maxNodes) {
        this(world, player, start, goal.reached(), goal.target(), opts, maxNodes);
    }

    public Pathfinder(World world, ClientPlayerEntity player, BlockPos start, Predicate<BlockPos> reached,
                           BlockPos target, Options opts, int maxNodes) {
        this.world = world;
        this.player = player;
        this.reached = reached;
        this.target = target;
        this.opts = opts;
        this.maxNodes = maxNodes;
        Node s = new Node(start);
        nodes.put(start.asLong(), s);
        open.add(new Entry(s, 0, h(start)));
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

    public List<Step> path() {
        return path;
    }

    // Edge costs are seconds, so "shortest" means fastest: over the hill or through it, around or bridge across.
    // Moves are measured on the bench (plan estimate vs walked time) and tuned here. Walking is the cheapest move per
    // block, so distance × WALK never overestimates: the heuristic stays admissible.
    static final double WALK = 0.2;          // sprinting ≈ 5.6 blocks/s
    static final double STEP_UP = 0.35;      // a jump onto the next block
    static final double DROP_PER_BLOCK = 0.08;
    static final double SWIM = 0.45;
    static final double CLIMB = 0.4;
    static final double PILLAR = 0.6;        // jump + place under the feet
    static final double BRIDGE = 0.8;        // sneak to the edge, place against the block below
    static final double SCARCITY = 2.5;      // seconds added to the last placement the stock allows (0 to the first)
    static final double MINE_OVERHEAD = 0.55; // per broken block: aim, swing rhythm, step into the gap
    static final double BRIDGE_OVER_LAVA = 0.4;   // extra care at a lava edge
    static final double AIM = 0.15;          // turning to face a block before breaking or placing

    private double h(BlockPos p) {
        return Math.sqrt(p.getSquaredDistance(target)) * WALK;
    }

    public double estimatedSeconds() {
        return endSeconds;
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
            if (reached.test(n.pos)) {
                finish(n, true);
                return;
            }
            double hn = h(n.pos);
            if (hn < bestH) {
                bestH = hn;
                best = n;
            }
            expand(n);
        }
    }

    // ------------------------------------------------------------------ world model

    private boolean lavaOrWaterAround(BlockPos p) {
        for (Direction d : Direction.values()) {
            var f = world.getFluidState(p.offset(d));
            if (f.isIn(FluidTags.LAVA) || f.isIn(FluidTags.WATER)) return true;
        }
        return false;
    }

    private boolean anyToolCanHarvest(BlockState s) {
        if (!s.isToolRequired()) return true;
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < InvUtil.MAIN_SLOTS; i++) {
            ItemStack st = inv.getStack(i);
            if (!st.isEmpty() && st.isSuitableFor(s) && (!st.isDamageable() || st.getMaxDamage() - st.getDamage() > 1)) {
                return true;
            }
        }
        return false;
    }

    /** Cost to clear a cell so a body can pass, or -1 if it can't / mustn't be broken. 0 when already passable. */
    private double clearCost(BlockPos p) {
        if (WorldUtil.passable(world, p)) return 0;
        if (!opts.allowBreak || opts.avoid.contains(p.asLong())) return -1;
        BlockState s = world.getBlockState(p);
        if (!s.getFluidState().isEmpty()) return -1;
        float hardness = s.getHardness(world, p);
        if (hardness < 0 || s.isOf(Blocks.BEDROCK)) return -1;
        boolean harvest = anyToolCanHarvest(s);
        if (!harvest && hardness > MAX_HAND_HARDNESS) return -1;
        if (lavaOrWaterAround(p)) return -1;
        // Breaking a block is never just its hardness: aiming, the swing rhythm and stepping into the gap cost about
        // half a second each, whatever the tool. Without that, soft terrain looked free and a 150-block Nether trek
        // tunnelled through 167 netherrack (160 s) instead of walking over it (the same distance in the open: 63 s).
        return MINE_OVERHEAD + breakSeconds(s, hardness);
    }

    /** Vanilla break time with the best carried tool: progress per tick = speed / hardness / (harvestable ? 30 : 100). */
    private double breakSeconds(BlockState s, float hardness) {
        if (hardness <= 0) return AIM;
        double speed = 1.0;
        boolean harvest = !s.isToolRequired();
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < InvUtil.MAIN_SLOTS; i++) {
            ItemStack st = inv.getStack(i);
            if (st.isEmpty() || (st.isDamageable() && st.getMaxDamage() - st.getDamage() <= 1)) continue;
            boolean ok = !s.isToolRequired() || st.isSuitableFor(s);
            double sp = st.getMiningSpeedMultiplier(s);
            if (ok && (sp > speed || (ok && !harvest))) {
                speed = Math.max(sp, 1.0);
                harvest = true;
            }
        }
        double ticks = Math.ceil(hardness * (harvest ? 30.0 : 100.0) / speed);
        return ticks / 20.0 + AIM;
    }

    private boolean supported(Node n) {
        return WorldUtil.floor(world, n.pos.down()) || n.madeFloor;
    }

    // ------------------------------------------------------------------ moves

    private void expand(Node n) {
        BlockPos p = n.pos;
        boolean inWater = WorldUtil.isWater(world, p);
        for (int[] d : CARDINAL) {
            BlockPos side = p.add(d[0], 0, d[1]);
            if (WorldUtil.standable(world, side)) {
                offer(n, side, WALK, List.of(), 0, false);
                continue;
            }
            BlockPos up = side.up();
            if (WorldUtil.standable(world, up) && WorldUtil.passable(world, p.up(2))) {
                offer(n, up, STEP_UP, List.of(), 0, false);
                continue;
            }
            boolean dropped = false;
            if (WorldUtil.passable(world, side) && WorldUtil.passable(world, side.up())) {
                for (int k = 1; k <= MAX_DROP; k++) {
                    BlockPos down = side.down(k);
                    if (WorldUtil.standable(world, down)) {
                        offer(n, down, WALK + k * DROP_PER_BLOCK, List.of(), 0, false);
                        dropped = true;
                        break;
                    }
                    if (!WorldUtil.passable(world, down)) break;
                }
            }
            if (inWater) continue;
            // Break through: floor ahead exists, feet/head cells need clearing.
            if (WorldUtil.floor(world, side.down()) && supported(n)) {
                double c1 = clearCost(side.up()), c2 = clearCost(side);
                if (c1 >= 0 && c2 >= 0 && (c1 > 0 || c2 > 0)) {
                    offer(n, side, WALK + c1 + c2, mines(side.up(), side), 0, false);
                }
            }
            // Break a step up: stand on `side`, clear our head+1, side head, side head+1.
            if (WorldUtil.floor(world, side) && supported(n)) {
                double a = clearCost(p.up(2)), b = clearCost(side.up(2)), c = clearCost(side.up());
                if (a >= 0 && b >= 0 && c >= 0 && (a + b + c) > 0 && !LavaGuard.nearLava(world, side.up())) {
                    offer(n, side.up(), STEP_UP + a + b + c, mines(p.up(2), side.up(2), side.up()), 0, false);
                }
            }
            // Bridge: nothing to stand on ahead → place a floor against the block under us.
            // supported(n), not the world's floor: a floor this plan places counts too. With the world check a
            // plan bridged one block per travel (bench: 3-wide lava took 3 travels, 8-wide failed).
            if (!dropped && opts.allowPlace && n.placed < opts.placeBudget && supported(n)
                && WorldUtil.passable(world, side) && WorldUtil.passable(world, side.up())
                && world.getBlockState(side.down()).isReplaceable()) {
                // Over lava too (a lake between fortress and portal): the placed block replaces the lava. Only when
                // body cells stay dry, and expensive so a dry detour wins when one exists.
                boolean overLava = world.getFluidState(side.down()).isIn(FluidTags.LAVA)
                    || LavaGuard.nearLava(world, side.down());
                boolean bodyDry = world.getFluidState(side).isEmpty() && world.getFluidState(side.up()).isEmpty();
                boolean voidOk = opts.voidBridge() || !overVoid(world, side.down());
                if (bodyDry && voidOk) offer(n, side, overLava ? BRIDGE + BRIDGE_OVER_LAVA : BRIDGE,
                    List.of(new Action(Kind.FLOOR, side.down(), p.down())), 1, true);
            }
        }
        for (int[] d : DIAGONAL) {
            BlockPos diag = p.add(d[0], 0, d[1]);
            // The body sweeps over both corner cells: they must be standable too, never a hole or a lava pit.
            if (WorldUtil.standable(world, diag)
                && WorldUtil.standable(world, p.add(d[0], 0, 0)) && WorldUtil.standable(world, p.add(0, 0, d[1]))) {
                offer(n, diag, WALK * Math.sqrt(2), List.of(), 0, false);
            }
        }
        if (inWater) {
            if (WorldUtil.isWater(world, p.up()) || WorldUtil.standable(world, p.up())) offer(n, p.up(), SWIM, List.of(), 0, false);
            if (WorldUtil.standable(world, p.down())) offer(n, p.down(), SWIM, List.of(), 0, false);
            return;
        }
        BlockPos up = p.up();
        BlockPos down = p.down();
        if ((WorldUtil.climbable(world, p) || WorldUtil.climbable(world, up))
            && WorldUtil.passable(world, up) && WorldUtil.passable(world, up.up())) {
            offer(n, up, CLIMB, List.of(), 0, false);
        }
        if (WorldUtil.climbable(world, down) && WorldUtil.passable(world, down)) {
            offer(n, down, CLIMB, List.of(), 0, false);
        }
        // Pillar: our own cell must be really empty (a ladder or torch there can't take a block).
        if (opts.allowPlace && n.placed < opts.placeBudget && supported(n)
            && world.getBlockState(p).isReplaceable() && world.getFluidState(p).isEmpty()
            && !WorldUtil.climbable(world, p)) {
            double head = clearCost(p.up(2));
            if (head >= 0) {
                List<Action> acts = new ArrayList<>();
                if (head > 0) acts.add(new Action(Kind.MINE, p.up(2), null));
                acts.add(new Action(Kind.PILLAR, p, null));
                offer(n, up, PILLAR + head, acts, 1, true);
            }
        }
        // Dig straight down onto solid ground (never into a cave, lava or water).
        if (opts.allowBreak && WorldUtil.floor(world, p.down(2))) {
            double c = clearCost(down);
            if (c > 0 && !LavaGuard.nearLava(world, down)) {
                offer(n, down, DROP_PER_BLOCK + c, List.of(new Action(Kind.MINE, down, null)), 0, false);
            }
        }
    }

    private static List<Action> mines(BlockPos... cells) {
        List<Action> out = new ArrayList<>();
        for (BlockPos c : cells) out.add(new Action(Kind.MINE, c, null));
        return out;
    }

    private void offer(Node from, BlockPos to, double cost, List<Action> actions, int placed, boolean madeFloor) {
        if (!WorldUtil.isLoaded(world, to)) return;
        // Blocks are finite: the deeper a plan digs into the stock, the dearer each further placement, so a longer dry
        // detour wins before the bag runs out (a 200-block trek spent all 64 cobblestone bridging small dips).
        if (placed > 0 && opts.placeBudget > 0) cost += SCARCITY * (from.placed + placed) / opts.placeBudget;
        if (WorldUtil.isWater(world, to)) cost += SWIM - WALK;
        // Lava next to the body: not slower, but one slip is fatal. A risk surcharge in seconds (a 5-block detour).
        if (LavaGuard.nearLava(world, to)) cost += 1.0;
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
        n.actions = actions;
        n.placed = from.placed + placed;
        n.madeFloor = madeFloor;
        open.add(new Entry(n, g, g + h(to)));
    }

    private double endSeconds;

    private void finish(Node end, boolean ok) {
        done = true;
        found = ok;
        endSeconds = end == null ? 0 : end.g;
        List<Step> out = new ArrayList<>();
        for (Node n = end; n != null; n = n.parent) out.add(new Step(n.pos, n.actions));
        Collections.reverse(out);
        path = out;
    }
}

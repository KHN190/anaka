package dev.anaka.task;

import dev.anaka.Agent;
import dev.anaka.util.Pathfinder;
import dev.anaka.util.InvUtil;
import dev.anaka.util.WorldUtil;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Gets somewhere by any legal means — walk, swim, climb, break through, bridge, pillar — planned and executed with
 * one world model ({@link Pathfinder}). A failed step blacklists its cell and the route is planned again.
 */
public final class TravelTask extends Task {
    private static final String[] BUILDING = {"minecraft:cobblestone", "minecraft:cobbled_deepslate", "minecraft:dirt",
        "minecraft:andesite", "minecraft:diorite", "minecraft:granite", "minecraft:tuff", "minecraft:netherrack",
        "minecraft:blackstone", "minecraft:stone"};
    private static final int MAX_REPLANS = 8;
    private static final int PLAN_BUDGET_PER_TICK = 3000;

    private final BlockPos target;
    private final Pathfinder.Goal goal;
    private final boolean allowBreak;
    private final boolean allowPlace;
    private final int placeBudget;
    private final LongOpenHashSet avoid;

    private Pathfinder planner;
    private List<Pathfinder.Step> path;
    private int index;
    private Deque<Pathfinder.Action> pending;
    private Pathfinder.Action current;
    private int replans;
    private String lastError = "";
    private boolean partialRoute;

    public TravelTask(BlockPos target, double range, boolean allowBreak, boolean allowPlace, int placeBudget,
                      LongOpenHashSet avoid) {
        this(new Pathfinder.Goal(pos -> Math.sqrt(pos.getSquaredDistance(target)) <= range + 0.5, target),
            allowBreak, allowPlace, placeBudget, avoid);
    }

    /** Travel until `goal` holds (an approach: within reach of a block, a face to place against). */
    public TravelTask(Pathfinder.Goal goal, boolean allowBreak, boolean allowPlace, int placeBudget,
                      LongOpenHashSet avoid) {
        super("travel");
        this.goal = goal;
        this.target = goal.target();
        this.allowBreak = allowBreak;
        this.allowPlace = allowPlace;
        this.placeBudget = placeBudget;
        this.avoid = avoid;
        this.timeoutTicks = 20 * 900;
    }

    @Override
    public String describe() {
        if (planner != null) return "travel: planning (" + planner.expanded() + " positions)";
        if (path == null) return "travel to " + target.toShortString();
        String doing = current == null ? "walking" : current.kind().name().toLowerCase() + " " + current.pos().toShortString();
        return "travel to " + target.toShortString() + " (" + index + "/" + path.size() + ", " + doing + ")";
    }

    private boolean arrived(ClientPlayerEntity p) {
        return goal.reached().test(p.getBlockPos());
    }

    private void replan(MinecraftClient c, String why, BlockPos culprit) {
        lastError = why;
        if (culprit != null) avoid.add(culprit.asLong());
        if (++replans > MAX_REPLANS) {
            fail("gave up after " + MAX_REPLANS + " replans: " + why);
            return;
        }
        path = null;
        planner = null;
        pending = null;
        current = null;
        child = null;
    }

    @Override
    protected void tick(MinecraftClient c, Agent a) {
        ClientPlayerEntity p = c.player;
        if (arrived(p)) {
            succeed("arrived");
            return;
        }
        if (path == null) {
            if (planner == null) {
                BlockPos start = p.getBlockPos();
                // Never plan on more blocks than the bag holds right now: every replan used to get the full budget
                // again while the stock shrank, so a trek bridged until "no building blocks to bridge with".
                int budget = Math.min(placeBudget, stock(p));
                planner = new Pathfinder(c.world, p, start, goal.reached(), target,
                    new Pathfinder.Options(allowBreak, allowPlace, budget, avoid), 120000);
            }
            planner.step(PLAN_BUDGET_PER_TICK);
            if (!planner.isDone()) return;
            path = planner.path();
            partialRoute = !planner.found();
            planner = null;
            if (path.size() <= 1) {
                fail("no route" + (lastError.isEmpty() ? "" : " (last: " + lastError + ")"));
                return;
            }
            index = 1;
        }

        if (child != null) {
            if (!runChild(c, a)) return;
            boolean ok = child.status() == Status.SUCCEEDED;
            String msg = child.message();
            child = null;
            if (!ok) {
                replan(c, (current == null ? "walk" : current.kind().name().toLowerCase()) + " failed: " + msg,
                    current == null ? null : current.pos());
                return;
            }
            current = null;
        }

        if (index >= path.size()) {
            if (partialRoute) {
                result.addProperty("partial", true);
                fail("target unreachable; stopped at the closest reachable point");
            } else {
                succeed("arrived");
            }
            return;
        }

        Pathfinder.Step step = path.get(index);
        if (pending == null) pending = new ArrayDeque<>(step.actions());
        while (!pending.isEmpty()) {
            Pathfinder.Action act = pending.poll();
            current = act;
            switch (act.kind()) {
                case MINE -> {
                    if (WorldUtil.passable(c.world, act.pos())) continue;
                    child = new MineTask(act.pos(), false, false).walkOnly();
                }
                case FLOOR -> {
                    if (!c.world.getBlockState(act.pos()).isReplaceable()) continue;
                    String block = pickBlock(p);
                    if (block == null) {
                        fail("no building blocks to bridge with");
                        return;
                    }
                    child = new PlaceTask(act.pos(), block, act.against(), null).walkOnly();
                }
                case PILLAR -> {
                    String block = pickBlock(p);
                    if (block == null) {
                        fail("no building blocks to pillar with");
                        return;
                    }
                    if (!p.getBlockPos().equals(act.pos())) {
                        // Stand on the exact cell first, then pillar.
                        pending.addFirst(act);
                        child = GotoTask.near(act.pos(), 0.4, false, false, false);
                    } else {
                        child = new PillarTask(block);
                    }
                }
            }
            return;
        }
        current = null;
        if (p.getBlockPos().equals(step.node())) {
            index++;
            pending = null;
            return;
        }
        // Sprint between route nodes (~30 % faster than walking; travel was the biggest time sink).
        child = GotoTask.near(step.node(), 0.6, false, true, false);
        // the walk completes this step; advance once it succeeds
        index++;
        pending = null;
    }

    private static int stock(ClientPlayerEntity p) {
        int n = 0;
        for (String id : BUILDING) n += InvUtil.count(p, id);
        return n;
    }

    private static String pickBlock(ClientPlayerEntity p) {
        String best = null;
        int bestCount = 0;
        for (String id : BUILDING) {
            int n = InvUtil.count(p, id);
            if (n > bestCount) {
                best = id;
                bestCount = n;
            }
        }
        return best;
    }
}

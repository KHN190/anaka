package dev.anaka.task;

import dev.anaka.Agent;
import dev.anaka.util.Pathfinder;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.MinecraftClient;

/**
 * The one way a task gets its body to where it can work (mine, place, use a block): walk there (walk-only A*), and
 * when walking finds no way — a sealed 1×2 hole has no neighbour to walk to, "no path found (1 positions
 * explored)" — travel there, breaking and placing ({@link TravelTask}: the MINE/FLOOR/PILLAR it already runs).
 * {@code walkOnly} is for the tasks TravelTask itself runs as its steps: no travel inside a travel.
 */
public final class ApproachTask extends Task {
    /** Blocks a dig-through approach may place (bridging a gap, a pillar), at most; TravelTask caps it by the bag. */
    static final int PLACE_BUDGET = 16;

    private final Pathfinder.Goal goal;
    private final String label;
    private final boolean sprint;
    private final int maxNodes;
    private final boolean walkOnly;
    private boolean travelling;

    private ApproachTask(Pathfinder.Goal goal, String label, boolean sprint, int maxNodes, boolean walkOnly) {
        super("approach");
        this.goal = goal;
        this.label = label;
        this.sprint = sprint;
        this.maxNodes = maxNodes;
        this.walkOnly = walkOnly;
        this.timeoutTicks = 20 * 300;
    }

    public static ApproachTask to(Pathfinder.Goal goal, String label, boolean sprint, int maxNodes, boolean walkOnly) {
        return new ApproachTask(goal, label, sprint, maxNodes, walkOnly);
    }

    @Override
    public String describe() {
        return child == null ? label : child.describe();
    }

    @Override
    protected void tick(MinecraftClient c, Agent a) {
        if (child == null) child = new GotoTask(goal, label, false, sprint, false, maxNodes);
        if (!runChild(c, a)) return;
        if (child.status() == Status.SUCCEEDED) {
            succeed(child.message());
            return;
        }
        String why = child.message();
        if (child.result.has("closest")) result.add("closest", child.result.get("closest"));
        if (walkOnly || travelling) {
            fail(travelling ? "no way dug through: " + why : why);
            return;
        }
        travelling = true;
        result.addProperty("walk", why);
        child = new TravelTask(goal, true, true, PLACE_BUDGET, new LongOpenHashSet());
    }
}

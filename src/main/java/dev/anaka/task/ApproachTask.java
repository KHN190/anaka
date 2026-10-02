package dev.anaka.task;

import dev.anaka.Agent;
import dev.anaka.util.Pathfinder;
import net.minecraft.client.MinecraftClient;

/**
 * The one way a task gets its body to where it can work (mine, place, use a block, pick up a drop): walk there
 * (walk-only A*). A walk that finds no way fails with its reason and the closest cell reached: the way (dig, bridge,
 * climb) is Python's to plan and name, never dug or built here (E1, E3).
 */
public final class ApproachTask extends Task {
    private final Pathfinder.Goal goal;
    private final String label;
    private final boolean sprint;
    private final int maxNodes;

    private ApproachTask(Pathfinder.Goal goal, String label, boolean sprint, int maxNodes) {
        super("approach");
        this.goal = goal;
        this.label = label;
        this.sprint = sprint;
        this.maxNodes = maxNodes;
        this.timeoutTicks = 20 * 300;
    }

    public static ApproachTask to(Pathfinder.Goal goal, String label, boolean sprint, int maxNodes) {
        return new ApproachTask(goal, label, sprint, maxNodes);
    }

    /** Pure: the approach's end from its walk's — arrived, or failed with the walk's own reason (nothing else tried). */
    static Status outcome(Status walk) {
        return walk == Status.SUCCEEDED ? Status.SUCCEEDED : Status.FAILED;
    }

    @Override
    public String describe() {
        return child == null ? label : child.describe();
    }

    @Override
    protected void tick(MinecraftClient c, Agent a) {
        if (child == null) child = new GotoTask(goal, label, false, sprint, false, maxNodes);
        if (!runChild(c, a)) return;
        if (child.result.has("closest")) result.add("closest", child.result.get("closest"));
        if (outcome(child.status()) == Status.SUCCEEDED) succeed(child.message());
        else fail(child.message());
    }
}

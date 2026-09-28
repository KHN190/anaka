package dev.anaka.task;

import com.google.gson.JsonObject;
import dev.anaka.Agent;
import dev.anaka.Anaka;
import net.minecraft.client.MinecraftClient;

import java.util.concurrent.CompletableFuture;

/** A unit of agent work, ticked on the client thread until it finishes. */
public abstract class Task {
    public enum Status { RUNNING, SUCCEEDED, FAILED, CANCELLED }

    public int id;
    /** Tasks submitted together share a chain id; with stopOnFailure a failure cancels the rest of the chain. */
    public int chain;
    public boolean stopOnFailure;
    public final String type;
    public final JsonObject result = new JsonObject();
    public final CompletableFuture<Void> done = new CompletableFuture<>();

    protected int timeoutTicks = 20 * 120;
    protected Task child;

    private volatile Status status = Status.RUNNING;
    private volatile String message = "";
    private boolean started;
    private int ticks;
    /** The world's tick when the task started and when it finished (-1: not yet): where a chain's time goes. */
    private long startTick = -1, endTick = -1;

    private static long worldTick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc != null && mc.world != null ? mc.world.getTime() : -1;
    }


    protected Task(String type) {
        this.type = type;
    }

    protected void start(MinecraftClient c, Agent a) {}

    protected abstract void tick(MinecraftClient c, Agent a);

    /** Called once when the task ends for any reason. */
    protected void cleanup(MinecraftClient c) {}

    public String describe() {
        return type;
    }

    /** Only moving right now (the hand is free): what AutoEat may eat during. */
    public boolean walking() {
        return child != null && child.walking();
    }

    public final void run(MinecraftClient c, Agent a) {
        if (isFinished()) return;
        try {
            if (!started) {
                started = true;
                startTick = worldTick();
                start(c, a);
            }
            if (!isFinished()) tick(c, a);
            if (!isFinished() && ++ticks > timeoutTicks) fail("timed out after " + ticks / 20 + "s");
        } catch (RuntimeException e) {
            Anaka.LOG.error("Task {} ({}) crashed", id, type, e);
            fail("internal error: " + e);
        }
    }

    /** Ticks between a chase's replans: the target moves, the walk to where it was goes stale. */
    static final int CHASE_REPLAN_TICKS = 20;
    private int chaseCooldown;

    /**
     * One tick of walking to within {@code range} of a moving entity, as this task's child: the walk is replanned
     * when it ended or every CHASE_REPLAN_TICKS. The one chase (attack and interact both call it); {@code centre}
     * false arrives without settling into the cell's centre.
     */
    protected void chase(MinecraftClient c, Agent a, net.minecraft.entity.Entity target, double range, boolean sprint,
                         boolean centre) {
        if (child == null || child.isFinished() || --chaseCooldown <= 0) {
            if (child != null && !child.isFinished()) child.cancel("target moved");
            GotoTask walk = GotoTask.near(target.getBlockPos(), range, true, sprint);
            child = centre ? walk : walk.noCentering();
            chaseCooldown = CHASE_REPLAN_TICKS;
        }
        runChild(c, a);
    }

    /** The chase (or any child walk) given up: cancelled with `why`. */
    protected void endChase(String why) {
        if (child != null && !child.isFinished()) child.cancel(why);
        child = null;
    }

    /** Runs the child task one tick; returns true when it has finished. */
    protected boolean runChild(MinecraftClient c, Agent a) {
        child.run(c, a);
        return child.isFinished();
    }

    public boolean isFinished() {
        return status != Status.RUNNING;
    }

    public Status status() {
        return status;
    }

    public String message() {
        return message;
    }

    protected void succeed(String msg) {
        finish(Status.SUCCEEDED, msg);
    }

    protected void fail(String msg) {
        finish(Status.FAILED, msg);
    }

    public void cancel(String reason) {
        finish(Status.CANCELLED, reason);
    }

    private void finish(Status s, String msg) {
        if (isFinished()) return;
        endTick = worldTick();
        status = s;
        message = msg;
        if (child != null && !child.isFinished()) child.cancel("parent finished");
        cleanup(MinecraftClient.getInstance());
        done.complete(null);
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("type", type);
        o.addProperty("status", status.name().toLowerCase());
        o.addProperty("message", message);
        o.addProperty("seconds", ticks / 20.0);
        o.addProperty("startTick", startTick);
        o.addProperty("endTick", endTick);
        o.addProperty("doing", describe());
        o.add("result", result);
        return o;
    }
}

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

    public final void run(MinecraftClient c, Agent a) {
        if (isFinished()) return;
        try {
            if (!started) {
                started = true;
                start(c, a);
            }
            if (!isFinished()) tick(c, a);
            if (!isFinished() && ++ticks > timeoutTicks) fail("timed out after " + ticks / 20 + "s");
        } catch (RuntimeException e) {
            Anaka.LOG.error("Task {} ({}) crashed", id, type, e);
            fail("internal error: " + e);
        }
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
        o.addProperty("doing", describe());
        o.add("result", result);
        return o;
    }
}

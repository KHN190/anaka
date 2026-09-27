package dev.anaka.task;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.anaka.Agent;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Runs sub-tasks, choosing the next one dynamically so the player walks as little as possible.
 * Mining never takes a block while another pending block sits above it in the same column; building always
 * finishes the lowest layer first so every block has support. Failed steps are retried once at the end.
 */
public final class SequenceTask extends Task {
    public enum Order { MINE, BUILD }

    public record Step(BlockPos pos, Supplier<Task> factory) {}

    private final List<Step> pending;
    private final List<Step> retry = new ArrayList<>();
    private final Order order;
    private final Supplier<Task> finisher;
    private final int total;
    private boolean retrying;
    private boolean finishing;
    private Step currentStep;
    private int succeeded;
    private final JsonArray failures = new JsonArray();

    public SequenceTask(String type, List<Step> steps, Order order, Supplier<Task> finisher) {
        super(type);
        this.pending = new ArrayList<>(steps);
        this.total = steps.size();
        this.order = order;
        this.finisher = finisher;
        this.timeoutTicks = 20 * 60 * 30;
    }

    @Override
    public String describe() {
        String inner = child != null ? child.describe() : "";
        return type + " " + succeeded + "/" + total + (inner.isEmpty() ? "" : ": " + inner);
    }

    @Override
    protected void tick(MinecraftClient c, Agent a) {
        if (child == null) {
            if (pending.isEmpty() && !retrying && !retry.isEmpty()) {
                retrying = true;
                pending.addAll(retry);
                retry.clear();
            }
            currentStep = next(c.player.getBlockPos());
            if (currentStep == null) {
                if (finisher != null && !finishing) {
                    finishing = true;
                    child = finisher.get();
                    return;
                }
                result.addProperty("succeeded", succeeded);
                result.addProperty("total", total);
                result.add("failures", failures);
                if (failures.isEmpty()) succeed(type + " finished");
                else fail(failures.size() + " of " + total + " steps failed");
                return;
            }
            child = currentStep.factory().get();
        }
        if (!runChild(c, a)) return;
        if (currentStep != null) record(child);
        child = null;
        if (finishing) {
            result.addProperty("succeeded", succeeded);
            result.addProperty("total", total);
            result.add("failures", failures);
            if (failures.isEmpty()) succeed(type + " finished");
            else fail(failures.size() + " of " + total + " steps failed");
        }
    }

    private int unreachableInARow;
    /** Failures that mean "the body cannot get at this block from here" (one set: fast-fail counts these). */
    static final java.util.List<String> UNREACHABLE =
        java.util.List.of("cannot reach", "no line of sight", "no path found", MineTask.NO_STAND.trim());

    private void record(Task done) {
        String msg = done.message() == null ? "" : done.message();
        boolean unreachable = done.status() != Status.SUCCEEDED && UNREACHABLE.stream().anyMatch(msg::contains);
        boolean again = !msg.startsWith(MineTask.NO_STAND);   // the same spot again is the same flip: not retried
        unreachableInARow = unreachable ? unreachableInARow + 1 : 0;
        if (unreachableInARow >= 2) {
            // Fail fast: two unreachable blocks in a row means this spot can't get at the rest either. Give the
            // remaining steps back as failures instead of searching for each one (≈1 100 s lost in 2.5 h).
            for (Step s : pending) {
                JsonObject f = new JsonObject();
                f.addProperty("x", s.pos().getX());
                f.addProperty("y", s.pos().getY());
                f.addProperty("z", s.pos().getZ());
                f.addProperty("reason", "skipped after repeated unreachable blocks: " + msg);
                failures.add(f);
            }
            pending.clear();
            retry.clear();
            retrying = true;
        }
        if (done.status() == Status.SUCCEEDED) {
            succeeded++;
        } else if (!retrying && again) {
            retry.add(currentStep);
        } else {
            JsonObject f = new JsonObject();
            f.addProperty("x", currentStep.pos().getX());
            f.addProperty("y", currentStep.pos().getY());
            f.addProperty("z", currentStep.pos().getZ());
            f.addProperty("reason", done.message());
            failures.add(f);
        }
        currentStep = null;
    }

    private Step next(BlockPos from) {
        Step best = null;
        double bestDist = Double.MAX_VALUE;
        int lowestY = Integer.MAX_VALUE;
        if (order == Order.BUILD) {
            for (Step s : pending) lowestY = Math.min(lowestY, s.pos().getY());
        }
        for (Step s : pending) {
            BlockPos p = s.pos();
            if (order == Order.BUILD && p.getY() != lowestY) continue;
            if (order == Order.MINE && hasPendingAbove(p)) continue;
            double d = p.getSquaredDistance(from);
            if (d < bestDist) {
                bestDist = d;
                best = s;
            }
        }
        if (best == null && !pending.isEmpty()) best = pending.get(0);
        if (best != null) pending.remove(best);
        return best;
    }

    private boolean hasPendingAbove(BlockPos p) {
        for (Step s : pending) {
            BlockPos q = s.pos();
            if (q.getX() == p.getX() && q.getZ() == p.getZ() && q.getY() > p.getY()) return true;
        }
        return false;
    }
}

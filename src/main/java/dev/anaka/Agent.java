package dev.anaka;

import dev.anaka.task.Task;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.input.KeyboardInput;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Owns player control while an agent is driving. All methods must be called on the client thread.
 */
public final class Agent {
    private static final Agent INSTANCE = new Agent();
    private static final int HISTORY_LIMIT = 100;

    public static Agent get() {
        return INSTANCE;
    }

    public final AgentInput input = new AgentInput();
    /** Set by tasks each tick; applied to the use key before vanilla input handling. */
    public boolean holdUse;
    /** The last task was a click on a block and the next is one too: keep the sneak held between them. */
    private boolean sneakHeld;
    /** Tasks one tick may start, one after another, when each finishes at once. */
    private static final int SAME_TICK_TASKS = 3;

    private volatile boolean controlling;
    /** The player took over with the toggle key; the API refuses new work until they hand control back. */
    private volatile boolean paused;
    private final Deque<Task> queue = new ArrayDeque<>();
    private Task current;
    private final Map<Integer, Task> history = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, Task> eldest) {
            return size() > HISTORY_LIMIT;
        }
    };
    private int nextId = 1;
    private int hudCooldown;

    public boolean isControlling() {
        return controlling;
    }

    public boolean isPaused() {
        return paused;
    }

    /** Toggle key: agent in control → player takes over (paused); paused → hand control back to the agent. */
    public void toggle(MinecraftClient client) {
        if (controlling) setPaused(client, true);
        else if (paused) setPaused(client, false);
    }

    /** The toggle's two states, also set from the API (/control): the bench simulates a player exactly as the key does. */
    public void setPaused(MinecraftClient client, boolean on) {
        if (on == paused) return;
        if (on) {
            if (controlling) release(client, "released by player");
            paused = true;
            client.inGameHud.setOverlayMessage(Text.translatable("anaka.hud.paused", Anaka.releaseKeyName()), false);
        } else {
            paused = false;
            takeover(client);
            client.inGameHud.setOverlayMessage(Text.translatable("anaka.hud.resumed"), false);
        }
    }

    private static void requireNotPaused() {
        if (INSTANCE.paused) throw new IllegalStateException("player has control (press the toggle key to hand it back)");
    }

    public void takeover(MinecraftClient client) {
        requireNotPaused();
        controlling = true;
        // The agent drives the game while the window is in the background.
        client.options.pauseOnLostFocus = false;
        if (client.currentScreen instanceof GameMenuScreen) client.setScreen(null);
        hudCooldown = 0;
    }

    public void release(MinecraftClient client, String reason) {
        cancelAll(reason);
        controlling = false;
        input.clear();
        holdUse = false;
        client.options.useKey.setPressed(false);
        if (client.player != null && client.player.input == input) {
            client.player.input = new KeyboardInput(client.options);
        }
        client.inGameHud.setOverlayMessage(Text.translatable("anaka.hud.released"), false);
    }

    public Task submit(MinecraftClient client, Task task, boolean append) {
        requireNotPaused();
        if (!append) cancelAll("replaced by a new task");
        task.id = nextId++;
        history.put(task.id, task);
        queue.add(task);
        takeover(client);
        return task;
    }

    public void cancelAll(String reason) {
        if (current != null) current.cancel(reason);
        for (Task t : queue) t.cancel(reason);
        queue.clear();
        current = null;
    }

    public Task find(int id) {
        return history.get(id);
    }

    public Collection<Task> history() {
        return history.values();
    }

    public Task current() {
        return current;
    }

    public int queued() {
        return queue.size();
    }

    /** Requests this recent keep the client out of its idle frame limit. */
    static final long AWAKE_MS = 60_000;

    public void onStartTick(MinecraftClient client) {
        // Driven over HTTP is not idle: the "afk" frame limit (10 fps after a minute with no key or mouse) put every
        // request behind a 100 ms frame — ~0.1 s a call, 2-4 s a brain round. Any request or control counts as input.
        if (controlling || System.currentTimeMillis() - HttpApi.lastRequestMs < AWAKE_MS) {
            client.getInactivityFpsLimiter().onInput();
        }
        if (!controlling || client.player == null) return;
        if (client.player.input != input) client.player.input = input;
        client.options.useKey.setPressed(holdUse);
    }

    public void onEndTick(MinecraftClient client) {
        if (!controlling) return;
        if (client.player == null || client.world == null) {
            cancelAll("left the world");
            controlling = false;
            return;
        }
        if (!Anaka.automationAllowed(client)) {
            client.inGameHud.setOverlayMessage(Text.translatable("anaka.hud.disabled_multiplayer"), false);
            release(client, "automation disabled on this server");
            return;
        }

        input.clear();
        holdUse = false;
        if (current == null) current = queue.poll();
        // the sneak stays held from one click on a block to the next (a chain of them: no tick lost re-applying it);
        // released as soon as anything else runs
        if (current instanceof dev.anaka.task.UseItemTask u && u.onBlock() && sneakHeld) input.sneak = true;
        else sneakHeld = false;
        // the next queued task starts in the tick the last one finished (a few at most: instant tasks chain on)
        for (int step = 0; current != null && step < SAME_TICK_TASKS; step++) {
            current.run(client, this);
            if (!current.isFinished()) break;
            sneakHeld = current instanceof dev.anaka.task.UseItemTask u && u.onBlock();
            if (current.stopOnFailure && current.status() != Task.Status.SUCCEEDED) {
                int chain = current.chain;
                String why = "step " + current.id + " of the chain failed: " + current.message();
                queue.removeIf(t -> {
                    if (t.chain != chain) return false;
                    t.cancel(why);
                    return true;
                });
            }
            current = queue.poll();
            if (!(current instanceof dev.anaka.task.UseItemTask u2 && u2.onBlock())) sneakHeld = false;
        }

        // Safety net that no task can override: while the agent drives, never let the player drown.
        // Vanilla surfacing is just holding jump in water, which a failed or idle task never does.
        net.minecraft.client.network.ClientPlayerEntity p = client.player;
        if (p.isSubmergedInWater() && p.getAir() < p.getMaxAir() / 2) {
            input.jump = true;
            input.sneak = false;
        }
        // Same for lava: jump and walk back to the last safe standing spot, whatever the task wanted.
        dev.anaka.util.LavaGuard.Escape escape = dev.anaka.util.LavaGuard.tick(client.world, p);
        if (escape != null) {
            rotateTowards(p, escape.yaw(), 0f, 90f);
            input.jump = true;
            input.sneak = false;
            input.forward = escape.forward();
        }
        // And for long falls: pour water just before landing, then take it back.
        dev.anaka.util.WaterClutch.tick(client, p);
        // Eat on the way: only while the task is just moving (never mid-dig, mid-swing, mid-place).
        if (dev.anaka.util.AutoEat.tick(client, p, current != null && current.walking())) holdUse = true;

        if (--hudCooldown <= 0) {
            hudCooldown = 20;
            if (Anaka.config().showHud) {
                String what = current != null ? current.describe() : "idle";
                client.inGameHud.setOverlayMessage(
                    Text.translatable("anaka.hud.active", what, Anaka.releaseKeyName()), false);
            }
        }
    }

    public static float yawTo(double dx, double dz) {
        return (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
    }

    /** Turns at most {@code maxStep} degrees per call, like a player moving the mouse. Returns true once aligned. */
    public static boolean rotateTowards(ClientPlayerEntity p, float yaw, float pitch, float maxStep) {
        float dYaw = MathHelper.wrapDegrees(yaw - p.getYaw());
        float dPitch = pitch - p.getPitch();
        p.setYaw(p.getYaw() + MathHelper.clamp(dYaw, -maxStep, maxStep));
        p.setPitch(MathHelper.clamp(p.getPitch() + MathHelper.clamp(dPitch, -maxStep, maxStep), -90f, 90f));
        return Math.abs(dYaw) <= maxStep + 2f && Math.abs(dPitch) <= maxStep + 2f;
    }

    public static boolean lookAt(ClientPlayerEntity p, Vec3d target, float maxStep) {
        Vec3d eye = p.getEyePos();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        return rotateTowards(p, yawTo(dx, dz), pitch, maxStep);
    }
}

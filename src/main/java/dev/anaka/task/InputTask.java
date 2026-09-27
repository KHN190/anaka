package dev.anaka.task;

import dev.anaka.Agent;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;

import java.util.Set;

/**
 * Holds movement keys for up to {@code ticks} ticks, optionally facing {@code yaw}, until a body condition holds:
 * "onGround" (standing) or "notInWater" (out of the water). The general move the planner-driven tasks do not make —
 * climbing out of water onto a bank the size of a block is forward+jump held until the ground takes the body.
 * Succeeds when the condition holds (or when the ticks run out with no condition asked); fails when they run out
 * first. The result carries the body's end state: onGround, inWater.
 */
public final class InputTask extends Task {
    public static final Set<String> KEYS = Set.of("forward", "back", "left", "right", "jump", "sneak", "sprint");

    private final Set<String> keys;
    private final float yaw;
    private final String until;
    private int ticks;

    public InputTask(Set<String> keys, float yaw, int ticks, String until) {
        super("input");
        for (String k : keys) {
            if (!KEYS.contains(k)) throw new IllegalArgumentException("unknown key \"" + k + "\" (" + KEYS + ")");
        }
        if (until != null && !until.equals("onGround") && !until.equals("notInWater")) {
            throw new IllegalArgumentException("\"until\" is onGround or notInWater");
        }
        this.keys = keys;
        this.yaw = yaw;
        this.ticks = Math.max(1, ticks);
        this.until = until;
        this.timeoutTicks = this.ticks + 20 * 5;
    }

    @Override
    public String describe() {
        return "holding " + String.join("+", keys) + (until == null ? "" : " until " + until);
    }

    private boolean met(ClientPlayerEntity p) {
        if (until == null) return false;
        return until.equals("onGround") ? p.isOnGround() && !p.isTouchingWater() : !p.isTouchingWater();
    }

    private void report(ClientPlayerEntity p) {
        result.addProperty("onGround", p.isOnGround());
        result.addProperty("inWater", p.isTouchingWater());
    }

    @Override
    protected void tick(MinecraftClient c, Agent a) {
        ClientPlayerEntity p = c.player;
        if (met(p)) {
            report(p);
            succeed(until);
            return;
        }
        if (ticks-- <= 0) {
            report(p);
            if (until == null) succeed("held");
            else fail(until + " not reached");
            return;
        }
        if (!Float.isNaN(yaw)) Agent.rotateTowards(p, yaw, p.getPitch(), 40f);
        a.input.forward = keys.contains("forward");
        a.input.back = keys.contains("back");
        a.input.left = keys.contains("left");
        a.input.right = keys.contains("right");
        a.input.jump = keys.contains("jump");
        a.input.sneak = keys.contains("sneak");
        a.input.sprint = keys.contains("sprint");
    }
}

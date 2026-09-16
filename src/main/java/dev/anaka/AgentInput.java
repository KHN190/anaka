package dev.anaka;

import net.minecraft.client.input.Input;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.Vec2f;

/** Replaces the keyboard input while the agent is in control; fed by the running task every tick. */
public final class AgentInput extends Input {
    public boolean forward;
    public boolean back;
    public boolean left;
    public boolean right;
    public boolean jump;
    public boolean sneak;
    public boolean sprint;

    @Override
    public void tick() {
        playerInput = new PlayerInput(forward, back, left, right, jump, sneak, sprint);
        float forwardAxis = forward == back ? 0f : (forward ? 1f : -1f);
        float sideAxis = left == right ? 0f : (left ? 1f : -1f);
        movementVector = new Vec2f(sideAxis, forwardAxis).normalize();
    }

    public void clear() {
        forward = back = left = right = jump = sneak = sprint = false;
    }
}

package dev.anaka.task;

import dev.anaka.Agent;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.Vec3d;

/** Turns the camera toward a point or to an absolute yaw/pitch, then optionally waits. */
public final class LookTask extends Task {
    private final Vec3d target;
    private final float yaw;
    private final float pitch;
    private int waitTicks;

    public LookTask(Vec3d target, float yaw, float pitch, int waitTicks) {
        super(target == null && waitTicks > 0 && Float.isNaN(yaw) ? "wait" : "look");
        this.target = target;
        this.yaw = yaw;
        this.pitch = pitch;
        this.waitTicks = waitTicks;
        this.timeoutTicks = Math.max(20 * 10, waitTicks + 20 * 5);
    }

    @Override
    public String describe() {
        return type.equals("wait") ? "waiting" : "looking around";
    }

    @Override
    protected void tick(MinecraftClient c, Agent a) {
        boolean aligned = true;
        if (target != null) aligned = Agent.lookAt(c.player, target, 30f);
        else if (!Float.isNaN(yaw)) aligned = Agent.rotateTowards(c.player, yaw, pitch, 30f);
        if (aligned && waitTicks-- <= 0) succeed("done");
    }
}

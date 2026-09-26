package dev.anaka.task;

import dev.anaka.Agent;
import dev.anaka.util.InvUtil;
import dev.anaka.util.WorldUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;

/**
 * Walks up to an entity and right-clicks it once, optionally holding an item first — feeding animals wheat to breed
 * them, shearing, trading. Succeeds a few ticks after the click (the result reports what the game answered).
 */
public final class InteractEntityTask extends Task {
    private final int entityId;
    private final String itemId;
    private int replanCooldown;
    private int settle = -1;

    public InteractEntityTask(int entityId, String itemId) {
        super("interact");
        this.entityId = entityId;
        this.itemId = itemId;
        this.timeoutTicks = 20 * 45;
    }

    @Override
    public String describe() {
        return "interacting with entity " + entityId + (itemId != null ? " using " + itemId : "");
    }

    @Override
    protected void tick(MinecraftClient c, Agent a) {
        ClientPlayerEntity p = c.player;
        if (settle >= 0) {
            if (++settle >= 4) succeed("interacted");
            return;
        }
        Entity target = c.world.getEntityById(entityId);
        if (target == null || !target.isAlive() || target.isRemoved()) {
            fail("entity not found");
            return;
        }
        if (itemId != null && !InvUtil.id(p.getMainHandStack()).equals(itemId)
            && !InvUtil.select(c, s -> InvUtil.id(s).equals(itemId))) {
            fail(itemId + " is not in the inventory");
            return;
        }
        Vec3d aim = new Vec3d(target.getX(), target.getY() + target.getHeight() * 0.5, target.getZ());
        double reach = WorldUtil.entityReach(p);
        if (p.getEyePos().squaredDistanceTo(aim) > reach * reach) {
            if (child == null || child.isFinished() || --replanCooldown <= 0) {
                if (child != null && !child.isFinished()) child.cancel("target moved");
                child = GotoTask.near(target.getBlockPos(), 1.8, true, false);
                replanCooldown = 20;
            }
            runChild(c, a);
            Agent.lookAt(p, aim, 25f);
            return;
        }
        if (child != null && !child.isFinished()) child.cancel("in reach");
        child = null;
        if (!Agent.lookAt(p, aim, 45f)) return;
        ActionResult r = c.interactionManager.interactEntity(p, target, Hand.MAIN_HAND);
        p.swingHand(Hand.MAIN_HAND);
        result.addProperty("result", r.toString());
        settle = 0;
    }
}

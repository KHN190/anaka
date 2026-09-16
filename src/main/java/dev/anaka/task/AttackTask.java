package dev.anaka.task;

import dev.anaka.Agent;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;

/** Chases an entity and hits it whenever the attack cooldown is full. */
public final class AttackTask extends Task {
    private final int entityId;
    private int hits;
    private int replanCooldown;

    public AttackTask(int entityId) {
        super("attack");
        this.entityId = entityId;
        this.timeoutTicks = 20 * 60;
    }

    @Override
    public String describe() {
        return "attacking entity " + entityId + " (" + hits + " hits)";
    }

    @Override
    protected void start(MinecraftClient c, Agent a) {
        dev.anaka.util.InvUtil.selectBestWeapon(c);
    }

    @Override
    protected void tick(MinecraftClient c, Agent a) {
        ClientPlayerEntity p = c.player;
        Entity target = c.world.getEntityById(entityId);
        if (target == null || !target.isAlive() || target.isRemoved()) {
            result.addProperty("hits", hits);
            succeed(hits > 0 ? "target defeated or gone" : "target not found");
            return;
        }
        // The ender dragon only takes damage through its body parts (head, neck, body, wings, tail): hitting the
        // parent entity does nothing. Aim at the part nearest to us.
        if (target instanceof net.minecraft.entity.boss.dragon.EnderDragonEntity dragon) {
            Entity nearest = null;
            for (var part : dragon.getBodyParts()) {
                if (nearest == null || part.squaredDistanceTo(p) < nearest.squaredDistanceTo(p)) nearest = part;
            }
            if (nearest != null) target = nearest;
        }
        Vec3d aim = new Vec3d(target.getX(), target.getY() + target.getHeight() * 0.6, target.getZ());
        double reach = p.getEntityInteractionRange() - 0.3;
        if (p.getEyePos().squaredDistanceTo(aim) > reach * reach) {
            if (child == null || child.isFinished() || --replanCooldown <= 0) {
                if (child != null && !child.isFinished()) child.cancel("target moved");
                child = GotoTask.near(target.getBlockPos(), 2.0, true, true);
                replanCooldown = 20;
            }
            runChild(c, a);
            Agent.lookAt(p, aim, 25f);
            return;
        }
        if (child != null && !child.isFinished()) child.cancel("in reach");
        child = null;
        if (Agent.lookAt(p, aim, 45f) && p.getAttackCooldownProgress(0.5f) >= 0.95f) {
            c.interactionManager.attackEntity(p, target);
            p.swingHand(Hand.MAIN_HAND);
            hits++;
        }
    }
}

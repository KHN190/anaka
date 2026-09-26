package dev.anaka.task;

import dev.anaka.Agent;
import dev.anaka.util.WorldUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
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
        double reach = WorldUtil.entityReach(p);
        if (p.getEyePos().squaredDistanceTo(aim) > reach * reach) {
            if (straightLine(c, p, target)) {
                // Close, level and open: run at it like a player would, facing it the whole way. A* would detour
                // through cell centres and arrive after the target has moved.
                if (child != null && !child.isFinished()) child.cancel("straight line");
                child = null;
                Agent.lookAt(p, aim, 25f);
                a.input.forward = true;
                a.input.sprint = true;
                return;
            }
            if (child == null || child.isFinished() || --replanCooldown <= 0) {
                if (child != null && !child.isFinished()) child.cancel("target moved");
                child = GotoTask.near(target.getBlockPos(), 2.0, true, true).noCentering();
                replanCooldown = 20;
            }
            runChild(c, a);
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

    static final double STRAIGHT_MAX = 6.0;

    /** Within STRAIGHT_MAX, on the same level, and every cell on the line standable with head room. */
    private static boolean straightLine(MinecraftClient c, ClientPlayerEntity p, Entity target) {
        double dx = target.getX() - p.getX(), dz = target.getZ() - p.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist > STRAIGHT_MAX || Math.abs(target.getY() - p.getY()) > 0.6 || !p.isOnGround()) return false;
        int steps = (int) Math.ceil(dist * 2);
        for (int i = 1; i <= steps; i++) {
            double f = (double) i / steps;
            BlockPos cell = BlockPos.ofFloored(p.getX() + dx * f, p.getY() + 0.2, p.getZ() + dz * f);
            if (!WorldUtil.standable(c.world, cell)) return false;
        }
        return true;
    }
}

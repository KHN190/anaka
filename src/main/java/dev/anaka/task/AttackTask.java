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
    private final boolean shield;
    /** Between swings: "back" steps out of the target's reach and in again as the cooldown fills (hit-and-back),
     * "strafe" sidesteps across its line of fire (a ranged target); null stands and swings. */
    private final String footwork;
    private int hits;
    private int ticks;
    private boolean clearing;          // keepoff: out past the blast after a hit, until it stops swelling

    public AttackTask(int entityId) {
        this(entityId, false);
    }

    /** {@code shield}: raise the offhand shield between swings (while the attack cooldown refills), lower it to hit. */
    public AttackTask(int entityId, boolean shield) {
        this(entityId, shield, null);
    }

    public AttackTask(int entityId, boolean shield, String footwork) {
        super("attack");
        if (footwork != null && !footwork.equals("back") && !footwork.equals("strafe") && !footwork.equals("keepoff")) {
            throw new IllegalArgumentException("\"footwork\" is back, strafe or keepoff");
        }
        this.entityId = entityId;
        this.shield = shield;
        this.footwork = footwork;
        this.timeoutTicks = 20 * 60;
    }

    @Override
    public boolean walking() {
        return false;                        // the hand swings: never eat in a fight's chase
    }

    @Override
    public String describe() {
        return "attacking entity " + entityId + " (" + hits + " hits)";
    }

    /** The weapon Python named (the task's "item"; null keeps what is in hand). */
    private String weapon;

    public AttackTask holding(String item) {
        weapon = item;
        return this;
    }

    @Override
    protected void start(MinecraftClient c, Agent a) {
        if (!dev.anaka.util.InvUtil.holdItem(c, weapon)) result.addProperty("weaponMissing", weapon);
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
        ticks++;
        float cooldown = p.getAttackCooldownProgress(0.5f);
        double dist = Math.sqrt(p.getEyePos().squaredDistanceTo(aim));
        // Keep off (a creeper): after each hit, and whenever it swells, out past its blast; in again only once it
        // stopped swelling. It dies, or blows up into the air — both end it (target gone).
        if ("keepoff".equals(footwork)) {
            boolean swelling = target instanceof net.minecraft.entity.mob.CreeperEntity cr && cr.getFuseSpeed() > 0;
            if (swelling) clearing = true;
            if (clearing && dist >= KEEP_OFF && !swelling) clearing = false;
            if (clearing) {
                endChase("keep off");
                Agent.lookAt(p, aim, 45f);
                if (safeStep(c, p, target, 0)) {
                    a.input.back = true;
                } else if (safeStep(c, p, target, 1)) {
                    a.input.left = true;           // backed against an edge or a wall: out sideways
                } else if (safeStep(c, p, target, -1)) {
                    a.input.right = true;
                }
                return;
            }
        }
        // Footwork while the swing refills: never forward into its reach before the hit is ready.
        if (footwork != null && !"keepoff".equals(footwork) && cooldown < READY && dist <= reach + BACK_OFF) {
            endChase("footwork");
            Agent.lookAt(p, aim, 45f);
            if (footwork.equals("back")) {
                if (dist < reach + 0.5 && safeStep(c, p, target, 0)) a.input.back = true;
            } else {
                int side = (ticks / STRAFE_TICKS) % 2 == 0 ? 1 : -1;       // left, then right: across the line
                if (safeStep(c, p, target, side)) {
                    if (side > 0) a.input.left = true;
                    else a.input.right = true;
                }
            }
            return;
        }
        if (p.getEyePos().squaredDistanceTo(aim) > reach * reach) {
            if (straightLine(c, p, target)) {
                // Close, level and open: run at it like a player would, facing it the whole way. A* would detour
                // through cell centres and arrive after the target has moved.
                endChase("straight line");
                Agent.lookAt(p, aim, 25f);
                a.input.forward = true;
                a.input.sprint = true;
                return;
            }
            chase(c, a, target, 2.0, true, false);
            return;
        }
        endChase("in reach");
        boolean aimed = Agent.lookAt(p, aim, 45f);
        if (aimed && cooldown >= 0.95f) {
            if (p.isUsingItem()) c.interactionManager.stopUsingItem(p);      // lower the shield to swing
            c.interactionManager.attackEntity(p, target);
            p.swingHand(Hand.MAIN_HAND);
            hits++;
            if ("keepoff".equals(footwork)) clearing = true;
        } else if (shield && dev.anaka.util.InvUtil.id(p.getOffHandStack()).equals("minecraft:shield")) {
            if (!p.isUsingItem()) c.interactionManager.interactItem(p, Hand.OFF_HAND);
            a.holdUse = true;                                                  // up while the cooldown refills
        }
    }

    static final double STRAIGHT_MAX = 6.0;
    static final float READY = 0.85f;        // the swing nearly refilled: step in for it
    static final double BACK_OFF = 1.5;      // how far past reach the footwork stays (then it closes normally)
    static final int STRAFE_TICKS = 12;      // one sidestep's length before turning the other way
    static final double KEEP_OFF = 5.0;      // eye-to-target distance a swelling creeper's blast does not reach

    /**
     * The cell one step away — backward from the target ({@code side} 0) or to its left (1) / right (-1) — holds the
     * body: floor under it, head room (WorldUtil.standable). A step back off a platform's edge is never taken.
     */
    private static boolean safeStep(MinecraftClient c, ClientPlayerEntity p, Entity target, int side) {
        double dx = p.getX() - target.getX(), dz = p.getZ() - target.getZ();
        double n = Math.sqrt(dx * dx + dz * dz);
        if (n < 1e-6) return false;
        dx /= n;
        dz /= n;
        double sx = side == 0 ? dx : -dz * side, sz = side == 0 ? dz : dx * side;
        BlockPos cell = BlockPos.ofFloored(p.getX() + sx, p.getY() + 0.2, p.getZ() + sz);
        return p.isOnGround() && WorldUtil.standable(c.world, cell);
    }

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

package dev.anaka.task;

import dev.anaka.Agent;
import dev.anaka.util.InvUtil;
import dev.anaka.util.WorldUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * Pillars up one block the way players do: centre on the block, look straight down, jump, and at the top of the jump
 * place a block into the cell just left. Ends standing one block higher.
 */
public final class PillarTask extends Task {
    private static final int MAX_JUMPS = 5;

    private final String itemId;
    private BlockPos cell;
    private int jumps;

    public PillarTask(String itemId) {
        super("pillar");
        this.itemId = itemId;
        this.timeoutTicks = 20 * 8;
    }

    @Override
    public String describe() {
        return "pillaring up with " + itemId;
    }

    @Override
    protected void tick(MinecraftClient c, Agent a) {
        ClientPlayerEntity p = c.player;
        if (cell == null) {
            if (!p.isOnGround()) return;
            cell = p.getBlockPos();
            if (c.world.getBlockState(cell.down()).isReplaceable()) {
                fail("nothing solid to stand on");
                return;
            }
            if (!WorldUtil.passable(c.world, cell.up(2))) {
                fail("no headroom above");
                return;
            }
            if (!c.world.getBlockState(cell).isReplaceable()) {
                // A ladder, torch or vine hangs in our own cell: a block can't go there. Say so at once instead
                // of mistaking it for our placed block and waiting to rise.
                fail("own cell is occupied by " + WorldUtil.id(c.world.getBlockState(cell).getBlock()));
                return;
            }
        }
        if (!c.world.getBlockState(cell).isReplaceable()) {
            if (p.isOnGround() && p.getBlockPos().getY() > cell.getY()) {
                succeed("pillared up to y=" + p.getBlockPos().getY());
            }
            return;
        }
        if (!InvUtil.select(c, st -> InvUtil.id(st).equals(itemId))) {
            fail(itemId + " is not in the inventory (or a container is open)");
            return;
        }
        double dx = cell.getX() + 0.5 - p.getX();
        double dz = cell.getZ() + 0.5 - p.getZ();
        if (p.isOnGround() && dx * dx + dz * dz > 0.04) {
            // Shuffle to the centre so the body clears the cell when it rises.
            Agent.rotateTowards(p, Agent.yawTo(dx, dz), 90f, 90f);
            a.input.forward = true;
            a.input.sneak = true;
            return;
        }
        Agent.rotateTowards(p, p.getYaw(), 90f, 90f);
        if (p.isOnGround()) {
            if (++jumps > MAX_JUMPS) {
                fail("could not get off the ground");
                return;
            }
            a.input.jump = true;
            return;
        }
        if (p.getY() >= cell.getY() + 1.05 && InvUtil.id(p.getMainHandStack()).equals(itemId)) {
            BlockPos support = cell.down();
            Vec3d hit = Vec3d.ofCenter(support).add(0, 0.5, 0);
            c.interactionManager.interactBlock(p, Hand.MAIN_HAND, new BlockHitResult(hit, Direction.UP, support, false));
            p.swingHand(Hand.MAIN_HAND);
        }
    }
}

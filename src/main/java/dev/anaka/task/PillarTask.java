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
            String why = unfit(c, p.getBlockPos());
            if (why != null) {
                fail(why);
                return;
            }
            cell = p.getBlockPos();
        }
        BlockPos under = new BlockPos(p.getBlockX(), cell.getY(), p.getBlockZ());
        if (!under.equals(cell) && c.world.getBlockState(cell).isReplaceable()
                && (p.isOnGround() ? p.getBlockPos().getY() == cell.getY() : p.getY() >= cell.getY())) {
            // Knocked off the cell (a zombie's hit) before the block went in: pillar where the body now is. Walking
            // back to the first cell under knockback kept it shuffling for seconds and never rising.
            String why = unfit(c, under);
            if (why == null) {
                cell = under;
            } else if (p.isOnGround()) {
                fail("knocked off to " + under.toShortString() + ": " + why);
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

    /** Why a block cannot go into `at` to stand on (null: it can): solid below, air to fill, headroom above. */
    private static String unfit(MinecraftClient c, BlockPos at) {
        if (c.world.getBlockState(at.down()).isReplaceable()) return "nothing solid to stand on";
        if (!WorldUtil.passable(c.world, at.up(2))) return "no headroom above";
        if (!c.world.getBlockState(at).isReplaceable()) {
            // A ladder, torch or vine hangs in the cell: a block can't go there. Said at once, not mistaken for our
            // placed block while waiting to rise.
            return "own cell is occupied by " + WorldUtil.id(c.world.getBlockState(at).getBlock());
        }
        return null;
    }
}

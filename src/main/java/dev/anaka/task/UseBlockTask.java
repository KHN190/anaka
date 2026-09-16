package dev.anaka.task;

import dev.anaka.Agent;
import dev.anaka.util.Pathfinder;
import dev.anaka.util.WorldUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;

/** Right-clicks a block: opens chests, crafting tables, furnaces, doors, beds, levers... */
public final class UseBlockTask extends Task {
    private final BlockPos pos;
    private int waitTicks = -1;
    private int approaches;

    public UseBlockTask(BlockPos pos) {
        super("use");
        this.pos = pos;
        this.timeoutTicks = 20 * 120;
    }

    @Override
    public String describe() {
        return "using block at " + pos.toShortString();
    }

    @Override
    protected void tick(MinecraftClient c, Agent a) {
        ClientPlayerEntity p = c.player;
        if (waitTicks >= 0) {
            // Containers open a few ticks after the click (server round trip); doors and levers never open one.
            if (++waitTicks >= 20 || (waitTicks >= 3 && c.currentScreen != null)) {
                result.addProperty("screen", c.currentScreen == null ? "none" : c.currentScreen.getClass().getSimpleName());
                succeed("used " + WorldUtil.id(c.world.getBlockState(pos).getBlock()));
            }
            return;
        }
        double range = p.getBlockInteractionRange() - 0.2;
        BlockHitResult hit = WorldUtil.visibleHit(c.world, p, p.getEyePos(), pos, range);
        if (hit == null) {
            if (child == null) {
                if (++approaches > 3) {
                    fail("no line of sight to the block from any reachable spot");
                    return;
                }
                Pathfinder.Goal goal = new Pathfinder.Goal(
                    feet -> WorldUtil.visibleHit(c.world, p, WorldUtil.eyeAt(feet), pos, range - 0.3) != null, pos);
                child = new GotoTask(goal, "walking to " + pos.toShortString(), false, false);
            }
            if (runChild(c, a)) {
                if (child.status() != Status.SUCCEEDED) {
                    fail("cannot reach block: " + child.message());
                    return;
                }
                child = null;
            }
            return;
        }
        child = null;
        if (Agent.lookAt(p, hit.getPos(), 35f)) {
            c.interactionManager.interactBlock(p, Hand.MAIN_HAND, hit);
            p.swingHand(Hand.MAIN_HAND);
            waitTicks = 0;
        }
    }
}

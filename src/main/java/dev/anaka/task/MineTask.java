package dev.anaka.task;

import dev.anaka.Agent;
import dev.anaka.util.InvUtil;
import dev.anaka.util.Pathfinder;
import dev.anaka.util.WorldUtil;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;

/** Walks within reach, picks the best tool and breaks the block with normal break progress. */
public final class MineTask extends Task {
    private final BlockPos pos;
    private final boolean collect;
    private final boolean requireDrops;

    private String blockId;
    private String toolId = "";
    private int approaches;
    /** Times sight of the block was lost mid-break (mining -> walking back): the stand spot does not hold. */
    private int flips;
    static final int FLIP_LIMIT = 3;
    static final String NO_STAND = "cannot hold a stand spot at ";
    private boolean breaking;
    private boolean broken;
    /** The last hit while breaking: a tick without sight from a spot that still holds keeps breaking toward it. */
    private BlockHitResult lastHit;
    private int blindTicks;
    static final int BLIND_TICKS = 6;

    private final java.util.Set<String> only;
    /** The item Python named to break with (null: what is in hand, "hand": a free hand). */
    private String tool;
    private boolean down;

    public MineTask(BlockPos pos, boolean collect, boolean requireDrops) {
        this(pos, collect, requireDrops, null);
    }

    public MineTask(BlockPos pos, boolean collect, boolean requireDrops, java.util.Set<String> only) {
        super("mine");
        this.pos = pos;
        this.collect = collect;
        this.requireDrops = requireDrops;
        this.only = only;
        this.timeoutTicks = 20 * 180;
    }


    /** Break with the item Python named (the task's "item"). */
    public MineTask holding(String item) {
        tool = item;
        return this;
    }

    /** The block is under the feet on purpose (a dig down, a buried drop): its own column is a stand spot. */
    public MineTask down(boolean d) {
        down = d;
        return this;
    }

    @Override
    public String describe() {
        if (broken) return "collecting drops";
        return (breaking ? "mining " : "going to mine ") + (blockId == null ? "" : blockId + " at ") + pos.toShortString();
    }

    @Override
    protected void start(MinecraftClient c, Agent a) {
        BlockState s = c.world.getBlockState(pos);
        blockId = WorldUtil.id(s.getBlock());
        result.addProperty("block", blockId);
        if (s.isAir() || s.getHardness(c.world, pos) < 0) {
            fail(s.isAir() ? "nothing to mine (air)" : blockId + " is unbreakable");
        }
    }

    @Override
    protected void tick(MinecraftClient c, Agent a) {
        ClientPlayerEntity p = c.player;

        if (broken) {
            if (child == null || runChild(c, a)) succeed("mined " + blockId);
            return;
        }

        BlockState s = c.world.getBlockState(pos);
        // broken = the cell no longer holds a block: air or something replaceable (water flowing in, grass growing
        // back). A different id is not a break: grass turning to dirt under random ticks, or under a block placed
        // above it, faked a successful mine (the farm's centre stayed solid and the water went on it).
        if (s.isAir() || s.isReplaceable()) {
            broken = true;
            breaking = false;
            if (collect) child = new CollectTask(pos, 5, 6, only);
            return;
        }

        double range = p.getBlockInteractionRange();
        BlockHitResult hit = WorldUtil.visibleHit(c.world, p, p.getEyePos(), pos, range - 0.2);
        if (hit == null && breaking && lastHit != null && ++blindTicks <= BLIND_TICKS
                && holds(c, p.getBlockPos(), range)) {
            // the body still stands on a spot that holds: a tick of lost sight (the body settling) is not a flip
            hit = lastHit;
        } else if (hit != null) {
            blindTicks = 0;
        }
        if (hit == null) {
            if (breaking) {
                c.interactionManager.cancelBlockBreaking();
                breaking = false;
                // Mining <-> approaching every 0.3 s on one block (mine_while_hungry: ~10 times, y 198<->199): no
                // spot holds the body in sight of it. Give the block up instead of walking back again.
                if (++flips >= FLIP_LIMIT) {
                    fail(NO_STAND + pos.toShortString());
                    return;
                }
            }
            if (child == null) {
                if (++approaches > 3) {
                    fail("no line of sight to " + pos.toShortString() + " from any reachable spot");
                    return;
                }
                // A spot to mine from is always close: 6 000 nodes, not 60 000 (unreachable blocks burned minutes).
                child = ApproachTask.to(reachGoal(c, range), "walking to mine " + blockId, true, 6_000);
            }
            if (runChild(c, a)) {
                if (child.status() != Status.SUCCEEDED) {
                    if (child.result.has("closest")) result.add("closest", child.result.get("closest"));
                    fail("cannot reach " + pos.toShortString() + ": " + child.message());
                    return;
                }
                child = null;
            }
            return;
        }
        child = null;

        if (breaking && !InvUtil.id(p.getMainHandStack()).equals(toolId)) {
            // The tool broke (or was moved) mid-break: restart holding the named item again.
            c.interactionManager.cancelBlockBreaking();
            breaking = false;
            result.addProperty("toolBroke", toolId);
        }
        if (!breaking) {
            if (!InvUtil.holdItem(c, tool)) {
                fail(tool + " is not in the inventory");
                return;
            }
            ItemStack held = p.getMainHandStack();
            if (requireDrops && s.isToolRequired() && (held.isEmpty() || !held.getItem().isCorrectForDrops(held, s))) {
                fail("the " + (held.isEmpty() ? "bare hand" : InvUtil.id(held)) + " cannot harvest " + blockId);
                return;
            }
            toolId = InvUtil.id(held);
        }
        if (Agent.lookAt(p, hit.getPos(), 180f)) {        // the view set on the block at once, the break begun this tick
            c.interactionManager.updateBlockBreakingProgress(pos, hit.getSide());
            p.swingHand(Hand.MAIN_HAND);
            breaking = true;
            lastHit = hit;
        }
    }

    private Pathfinder.Goal reachGoal(MinecraftClient c, double range) {
        return new Pathfinder.Goal(feet -> holds(c, feet, range), pos);
    }

    /** A stand spot that holds while the block breaks: never on the block itself (its own column above: the body
     * stands on what it mines, and an ore one down under flat ground was picked that way), and the block in sight
     * from anywhere the body settles in the cell (the centre and 0.3 off it each way), not only from its centre —
     * the arrival leaves the body off-centre, so a spot seen only from the centre lost sight mid-break (NO_STAND). */
    private boolean holds(MinecraftClient c, BlockPos feet, double range) {
        if (!down && feet.getX() == pos.getX() && feet.getZ() == pos.getZ() && feet.getY() > pos.getY()) return false;
        ClientPlayerEntity p = c.player;
        net.minecraft.util.math.Vec3d eye = WorldUtil.eyeAt(feet);
        double[][] offs = {{0, 0}, {0.3, 0}, {-0.3, 0}, {0, 0.3}, {0, -0.3}};
        for (double[] o : offs) {
            if (WorldUtil.visibleHit(c.world, p, eye.add(o[0], 0, o[1]), pos, range - 0.5) == null) return false;
        }
        return true;
    }

    @Override
    protected void cleanup(MinecraftClient c) {
        if (breaking && c.interactionManager != null) c.interactionManager.cancelBlockBreaking();
    }
}

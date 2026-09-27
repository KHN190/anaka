package dev.anaka.task;

import dev.anaka.Agent;
import dev.anaka.util.InvUtil;
import dev.anaka.util.Pathfinder;
import dev.anaka.util.WorldUtil;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
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
    private boolean breaking;
    private boolean broken;

    private final java.util.Set<String> only;
    private boolean walkOnly;
    private it.unimi.dsi.fastutil.longs.LongOpenHashSet avoid;

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

    /** Approach on foot only: TravelTask's own steps, which must not start a travel inside the travel. */
    public MineTask walkOnly() {
        walkOnly = true;
        return this;
    }

    /** Cells the approach may never dig or build through (the task JSON's "avoid"). */
    public MineTask avoiding(it.unimi.dsi.fastutil.longs.LongOpenHashSet cells) {
        avoid = cells;
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
        if (s.isAir() || !WorldUtil.id(s.getBlock()).equals(blockId)) {
            broken = true;
            breaking = false;
            if (collect) child = new CollectTask(pos, 5, 6, only);
            return;
        }

        double range = p.getBlockInteractionRange();
        BlockHitResult hit = WorldUtil.visibleHit(c.world, p, p.getEyePos(), pos, range - 0.2);
        if (hit == null) {
            if (breaking) {
                c.interactionManager.cancelBlockBreaking();
                breaking = false;
            }
            if (child == null) {
                if (++approaches > 3) {
                    fail("no line of sight to " + pos.toShortString() + " from any reachable spot");
                    return;
                }
                // A spot to mine from is always close: 6 000 nodes, not 60 000 (unreachable blocks burned minutes).
                child = ApproachTask.to(reachGoal(c, range), "walking to mine " + blockId, true, 6_000, walkOnly, avoid);
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
            // The tool broke (or was moved) mid-break: restart with the next best tool.
            c.interactionManager.cancelBlockBreaking();
            breaking = false;
            result.addProperty("toolBroke", toolId);
        }
        if (!breaking) {
            if (!InvUtil.selectBestTool(c, s) && requireDrops) {
                fail("no tool in inventory can harvest " + blockId);
                return;
            }
            toolId = InvUtil.id(p.getMainHandStack());
        }
        if (Agent.lookAt(p, hit.getPos(), 35f)) {
            c.interactionManager.updateBlockBreakingProgress(pos, hit.getSide());
            p.swingHand(Hand.MAIN_HAND);
            breaking = true;
        }
    }

    private Pathfinder.Goal reachGoal(MinecraftClient c, double range) {
        ClientPlayerEntity p = c.player;
        return new Pathfinder.Goal(
            feet -> WorldUtil.visibleHit(c.world, p, WorldUtil.eyeAt(feet), pos, range - 0.5) != null, pos);
    }

    @Override
    protected void cleanup(MinecraftClient c) {
        if (breaking && c.interactionManager != null) c.interactionManager.cancelBlockBreaking();
    }
}

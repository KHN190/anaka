package dev.anaka.task;

import dev.anaka.Agent;
import dev.anaka.util.InvUtil;
import dev.anaka.util.Pathfinder;
import dev.anaka.util.WorldUtil;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.util.math.Direction;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

/**
 * Places a block from the inventory by clicking an adjacent face, like a player would.
 * Optional orientation, for blocks whose state depends on how they were placed:
 * {@code against} = the neighbour to click (a hopper then outputs into it), {@code facing} = the facing the placed
 * block must have: the body rotation that gives it is asked of the block itself (its own placement rule).
 */
public final class PlaceTask extends Task {
    private static final int MAX_ATTEMPTS = 3;
    private static final int HOLD_ORIENTATION_TICKS = 4;

    private final BlockPos pos;
    private final String itemId;
    private final BlockPos against;
    private final Direction facing;
    private float[] look;

    private int attempts;
    private int verifyTicks = -1;
    private int sneakTicks;
    private int stepAwayTicks;
    private int approaches;
    private int creepTicks;
    private boolean oriented;
    private int orientedTicks;

    private boolean walkOnly;

    /** Approach on foot only: TravelTask's own steps, which must not start a travel inside the travel. */
    public PlaceTask walkOnly() {
        walkOnly = true;
        return this;
    }

    public PlaceTask(BlockPos pos, String itemId) {
        this(pos, itemId, null, null);
    }

    public PlaceTask(BlockPos pos, String itemId, BlockPos against, Direction facing) {
        super("place");
        this.pos = pos;
        this.itemId = itemId;
        this.against = against;
        this.facing = facing;
        this.timeoutTicks = 20 * 120;
    }

    @Override
    public String describe() {
        return "placing " + itemId + " at " + pos.toShortString();
    }

    @Override
    protected void tick(MinecraftClient c, Agent a) {
        ClientPlayerEntity p = c.player;
        BlockState s = c.world.getBlockState(pos);

        if (verifyTicks >= 0) {
            a.input.sneak = true;
            if (!s.isReplaceable()) {
                result.addProperty("block", WorldUtil.id(s.getBlock()));
                succeed("placed " + WorldUtil.id(s.getBlock()));
            } else if (++verifyTicks > 8) {
                verifyTicks = -1;
                oriented = false;
                orientedTicks = 0;
                if (attempts >= MAX_ATTEMPTS) fail("the game rejected the placement");
            }
            return;
        }

        if (!s.isReplaceable()) {
            fail("position is occupied by " + WorldUtil.id(s.getBlock()));
            return;
        }
        if (InvUtil.find(p, st -> InvUtil.id(st).equals(itemId)) < 0) {
            fail(itemId + " is not in the inventory");
            return;
        }

        double range = WorldUtil.blockReach(p);
        // Ladders and vines are thin plates on a wall: vanilla lets a player place them in the cell they stand in.
        boolean thinPlate = net.minecraft.block.Block.getBlockFromItem(
                net.minecraft.registry.Registries.ITEM.get(net.minecraft.util.Identifier.of(itemId)))
            .getDefaultState().isIn(net.minecraft.registry.tag.BlockTags.CLIMBABLE);
        if (!thinPlate && p.getBoundingBox().intersects(new Box(pos))) {
            // Part of our body is inside the target cell: take a small step straight away from it.
            if (++stepAwayTicks > 20) {
                fail("can't step out of the target block");
                return;
            }
            double dx = p.getX() - (pos.getX() + 0.5), dz = p.getZ() - (pos.getZ() + 0.5);
            Agent.rotateTowards(p, Agent.yawTo(dx, dz), p.getPitch(), 90f);
            a.input.forward = true;
            return;
        }
        stepAwayTicks = 0;
        WorldUtil.Placement pl = WorldUtil.findPlacement(c.world, p, p.getEyePos(), pos, range, against);
        if (pl == null) {
            // Bridging: the side face of the block under us is only visible from the very edge. Creep toward the
            // target sneaking (sneak stops at the ledge, as a player bridges) before looking for another stand spot.
            double hx = pos.getX() + 0.5 - p.getX(), hz = pos.getZ() + 0.5 - p.getZ();
            if (child == null && creepTicks < 30 && pos.getY() < p.getBlockY() && hx * hx + hz * hz <= 6.25) {
                creepTicks++;
                a.input.sneak = true;
                Agent.rotateTowards(p, Agent.yawTo(hx, hz), 70f, 90f);
                a.input.forward = true;
                return;
            }
            if (child == null) {
                if (++approaches > 3) {
                    fail("could not get into a position to place " + itemId);
                    return;
                }
                // A stand spot to place from is always close: 6 000 nodes, not the 60 000 of a long walk.
                child = ApproachTask.to(placeGoal(c, range), "walking to place " + itemId, false, 6_000, walkOnly);
            }
            if (runChild(c, a)) {
                if (child.status() != Status.SUCCEEDED) {
                    fail("no reachable face to place against: " + child.message());
                    return;
                }
                child = null;
            }
            return;
        }
        child = null;

        if (!InvUtil.select(c, st -> InvUtil.id(st).equals(itemId))) {
            fail("could not move " + itemId + " into the hand (close open containers first)");
            return;
        }
        // Sneak so clicking chests or crafting tables places against them instead of opening them.
        a.input.sneak = true;
        boolean aimed = oriented || Agent.lookAt(p, pl.hit(), 35f);
        if (aimed && facing != null) {
            // Face-sensitive blocks read the body orientation, not the clicked face: turn to it and hold it a few
            // ticks so the server has the rotation before the click arrives.
            if (look == null) look = lookFor(p, new BlockHitResult(pl.hit(), pl.face(), pl.support(), false));
            if (look == null) {
                fail("no body rotation places " + itemId + " facing " + facing.asString());
                return;
            }
            oriented = true;
            if (look.length == 2) {
                p.setYaw(look[0]);
                p.setPitch(look[1]);
                if (++orientedTicks < HOLD_ORIENTATION_TICKS) return;
            }
        }
        if (aimed && ++sneakTicks >= 2 && InvUtil.id(p.getMainHandStack()).equals(itemId)) {
            c.interactionManager.interactBlock(p, Hand.MAIN_HAND, new BlockHitResult(pl.hit(), pl.face(), pl.support(), false));
            p.swingHand(Hand.MAIN_HAND);
            attempts++;
            verifyTicks = 0;
        }
    }

    /**
     * The rotation at which the block's own placement rule gives {@code facing}: every cardinal yaw at level, up and
     * down, tried through {@code getPlacementState}. Empty when the block has no facing (nothing to hold); null when
     * no rotation gives it.
     */
    private float[] lookFor(ClientPlayerEntity p, BlockHitResult hit) {
        Block block = Block.getBlockFromItem(p.getMainHandStack().getItem());
        if (facingOf(block.getDefaultState()) == null) return new float[0];
        float yaw0 = p.getYaw(), pitch0 = p.getPitch();
        try {
            for (float pitch : new float[] {0f, 90f, -90f}) {
                for (float yaw : new float[] {0f, 90f, 180f, -90f}) {
                    p.setYaw(yaw);
                    p.setPitch(pitch);
                    BlockState s = block.getPlacementState(
                        new ItemPlacementContext(p, Hand.MAIN_HAND, p.getMainHandStack(), hit));
                    if (s != null && facingOf(s) == facing) return new float[] {yaw, pitch};
                }
            }
            return null;
        } finally {
            p.setYaw(yaw0);
            p.setPitch(pitch0);
        }
    }

    private static Direction facingOf(BlockState s) {
        for (var prop : s.getProperties()) {
            if (prop.getName().equals("facing") && s.get(prop) instanceof Direction d) return d;
        }
        return null;
    }

    private Pathfinder.Goal placeGoal(MinecraftClient c, double range) {
        ClientPlayerEntity p = c.player;
        return new Pathfinder.Goal(feet -> !feet.equals(pos) && !feet.up().equals(pos)
            && WorldUtil.findPlacement(c.world, p, WorldUtil.eyeAt(feet), pos, range - WorldUtil.REACH_MARGIN, against) != null, pos);
    }
}

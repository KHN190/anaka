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
import net.minecraft.util.math.Box;

/**
 * Places a block from the inventory by clicking an adjacent face, like a player would.
 * Optional orientation, for blocks whose state depends on how they were placed:
 * {@code against} = the neighbour to click (a hopper then outputs into it), {@code yaw}/{@code pitch} = the body
 * orientation held while clicking (pistons, observers, furnaces and repeaters take their facing from it).
 */
public final class PlaceTask extends Task {
    private static final int MAX_ATTEMPTS = 3;
    private static final int HOLD_ORIENTATION_TICKS = 4;

    private final BlockPos pos;
    private final String itemId;
    private final BlockPos against;
    private final Float yaw;
    private final Float pitch;

    private int attempts;
    private int verifyTicks = -1;
    private int sneakTicks;
    private int stepAwayTicks;
    private int approaches;
    private int creepTicks;
    private boolean oriented;
    private int orientedTicks;

    public PlaceTask(BlockPos pos, String itemId) {
        this(pos, itemId, null, null, null);
    }

    public PlaceTask(BlockPos pos, String itemId, BlockPos against, Float yaw, Float pitch) {
        super("place");
        this.pos = pos;
        this.itemId = itemId;
        this.against = against;
        this.yaw = yaw;
        this.pitch = pitch;
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

        double range = p.getBlockInteractionRange() - 0.3;
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
                child = new GotoTask(placeGoal(c, range), "walking to place " + itemId, false, false, false, 6_000);
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
        if (aimed && yaw != null) {
            // Face-sensitive blocks read the body orientation, not the clicked face: turn to it and hold it a few
            // ticks so the server has the rotation before the click arrives.
            oriented = true;
            p.setYaw(yaw);
            if (pitch != null) p.setPitch(pitch);
            if (++orientedTicks < HOLD_ORIENTATION_TICKS) return;
        }
        if (aimed && ++sneakTicks >= 2 && InvUtil.id(p.getMainHandStack()).equals(itemId)) {
            c.interactionManager.interactBlock(p, Hand.MAIN_HAND, new BlockHitResult(pl.hit(), pl.face(), pl.support(), false));
            p.swingHand(Hand.MAIN_HAND);
            attempts++;
            verifyTicks = 0;
        }
    }

    private Pathfinder.Goal placeGoal(MinecraftClient c, double range) {
        ClientPlayerEntity p = c.player;
        return new Pathfinder.Goal(feet -> !feet.equals(pos) && !feet.up().equals(pos)
            && WorldUtil.findPlacement(c.world, p, WorldUtil.eyeAt(feet), pos, range - 0.3, against) != null, pos);
    }
}

package dev.anaka.task;

import dev.anaka.Agent;
import dev.anaka.util.InvUtil;
import net.minecraft.block.BedBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * One atomic bed bomb (the End / the Nether, where beds explode): place a bed on the floor cell and use it on the
 * very next tick. Python decides when (dragon perched, player behind cover, health full) and where; this task only
 * does the two clicks in two ticks, which a 5 Hz HTTP loop can't. The bed is placed facing away from the player, so
 * its head half extends toward the target.
 */
public final class BedBombTask extends Task {
    private final BlockPos bed;
    private final String item;
    private int stage;

    public BedBombTask(BlockPos bed, String item) {
        super("bed_bomb");
        this.bed = bed;
        this.item = item;
        this.timeoutTicks = 40;
    }

    @Override
    public String describe() {
        return "bed bomb at " + bed.toShortString() + " (stage " + stage + ")";
    }

    @Override
    protected void tick(MinecraftClient c, Agent a) {
        ClientPlayerEntity p = c.player;
        a.input.sneak = false;                 // sneaking would place, not use, on the second click
        if (stage == 0) {
            if (!InvUtil.id(p.getMainHandStack()).equals(item)
                && !InvUtil.select(c, s -> InvUtil.id(s).equals(item))) {
                fail(item + " is not in the inventory");
                return;
            }
            if (!InvUtil.id(p.getMainHandStack()).equals(item)) return;          // hotbar swap lands next tick
            BlockPos floor = bed.down();
            if (c.world.getBlockState(floor).getCollisionShape(c.world, floor).isEmpty()) {
                fail("no floor under the bed cell " + bed.toShortString());
                return;
            }
            if (!c.world.getBlockState(bed).isReplaceable()) {
                fail("bed cell " + bed.toShortString() + " is occupied");
                return;
            }
            Vec3d aim = new Vec3d(floor.getX() + 0.5, floor.getY() + 1.0, floor.getZ() + 0.5);
            if (p.getEyePos().distanceTo(aim) > p.getBlockInteractionRange()) {
                fail("bed cell out of reach (" + String.format("%.1f", p.getEyePos().distanceTo(aim)) + " m)");
                return;
            }
            Agent.lookAt(p, aim, 180f);
            ActionResult r = c.interactionManager.interactBlock(p, Hand.MAIN_HAND,
                new BlockHitResult(aim, Direction.UP, floor, false));
            p.swingHand(Hand.MAIN_HAND);
            if (!r.isAccepted()) {
                fail("bed not placed: " + r);
                return;
            }
            stage = 1;
            return;
        }
        if (!(c.world.getBlockState(bed).getBlock() instanceof BedBlock)) {
            fail("no bed at " + bed.toShortString() + " after placing");
            return;
        }
        Vec3d top = new Vec3d(bed.getX() + 0.5, bed.getY() + 0.5625, bed.getZ() + 0.5);
        Agent.lookAt(p, top, 180f);
        c.interactionManager.interactBlock(p, Hand.MAIN_HAND, new BlockHitResult(top, Direction.UP, bed, false));
        p.swingHand(Hand.MAIN_HAND);
        succeed("bed detonated at " + bed.toShortString());
    }
}

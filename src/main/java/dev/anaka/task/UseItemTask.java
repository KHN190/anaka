package dev.anaka.task;

import dev.anaka.Agent;
import dev.anaka.util.InvUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

/**
 * Uses a held item the way a player does: aim, then right-click. {@code onBlock} clicks the block under the
 * crosshair (flint and steel on obsidian, placing water from a bucket against a block); otherwise the item is used
 * in the air (filling a bucket from a source in view, throwing an eye of ender). {@code holdTicks} keeps the use
 * key down and then releases it (drawing and shooting a bow).
 */
public final class UseItemTask extends Task {
    private final String itemId;
    private final Vec3d aim;
    private final float yaw;
    private final float pitch;
    private final boolean onBlock;

    /** A click on a block: the chain keeps the sneak held from one such click to the next (Agent). */
    public boolean onBlock() {
        return onBlock;
    }
    private final int holdTicks;

    private boolean used;
    private int held;
    private int settle;

    public UseItemTask(String itemId, Vec3d aim, float yaw, float pitch, boolean onBlock, int holdTicks) {
        super("use_item");
        this.itemId = itemId;
        this.aim = aim;
        this.yaw = yaw;
        this.pitch = pitch;
        this.onBlock = onBlock;
        this.holdTicks = holdTicks;
        this.timeoutTicks = 20 * 30;
    }

    @Override
    public String describe() {
        return "using " + itemId;
    }

    @Override
    protected void tick(MinecraftClient c, Agent a) {
        ClientPlayerEntity p = c.player;
        if (used) {
            if (held < holdTicks) {
                a.holdUse = true;
                held++;
                return;
            }
            if (++settle >= 4) {
                result.addProperty("hand", InvUtil.id(p.getMainHandStack()));
                succeed("used " + itemId);
            }
            return;
        }
        if (!InvUtil.id(p.getMainHandStack()).equals(itemId) && !InvUtil.select(c, s -> InvUtil.id(s).equals(itemId))) {
            fail(itemId + " is not in the inventory");
            return;
        }
        // Sneak while clicking a block: a bucket or hoe aimed past a chest or crafting table must use the item, not
        // open the container (a water pour once opened a cache chest instead).
        if (onBlock) a.input.sneak = true;
        // a real turn, up to 90 degrees a tick (a ring of clicks 45 degrees apart: one tick each, not two)
        boolean aligned = aim != null ? Agent.lookAt(p, aim, 90f) : Agent.rotateTowards(p, yaw, pitch, 90f);
        if (!aligned || !InvUtil.id(p.getMainHandStack()).equals(itemId)) return;
        if (onBlock) {
            Vec3d eye = p.getEyePos();
            Vec3d end = eye.add(p.getRotationVec(1f).multiply(p.getBlockInteractionRange()));
            BlockHitResult hit = c.world.raycast(new RaycastContext(eye, end, RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE, p));
            // what the click saw: the eye, the look, the reach, where the ray ended (why a click missed is provable)
            result.addProperty("eyeX", eye.x);
            result.addProperty("eyeY", eye.y);
            result.addProperty("eyeZ", eye.z);
            result.addProperty("yaw", p.getYaw());
            result.addProperty("pitch", p.getPitch());
            result.addProperty("reach", p.getBlockInteractionRange());
            result.addProperty("sneaking", p.isSneaking());
            if (aim != null) {
                result.addProperty("aimX", aim.x);
                result.addProperty("aimY", aim.y);
                result.addProperty("aimZ", aim.z);
                result.addProperty("aimDist", eye.distanceTo(aim));
            }
            result.addProperty("rayEndX", hit.getPos().x);
            result.addProperty("rayEndY", hit.getPos().y);
            result.addProperty("rayEndZ", hit.getPos().z);
            if (hit.getType() != HitResult.Type.BLOCK) {
                fail("no block under the crosshair");
                return;
            }
            if (!p.isSneaking()) return;   // wait a tick until the sneak state is applied
            result.addProperty("hitX", hit.getBlockPos().getX());
            result.addProperty("hitY", hit.getBlockPos().getY());
            result.addProperty("hitZ", hit.getBlockPos().getZ());
            result.addProperty("face", hit.getSide().asString());
            net.minecraft.util.ActionResult r = c.interactionManager.interactBlock(p, Hand.MAIN_HAND, hit);
            result.addProperty("blockResult", r.toString());
            // Like the vanilla client: when the block doesn't take the click (buckets have no use-on-block), use the
            // item itself while looking at that face — this is how water and lava get poured.
            if (!r.isAccepted()) {
                net.minecraft.util.ActionResult ir = c.interactionManager.interactItem(p, Hand.MAIN_HAND);
                result.addProperty("itemResult", ir.toString());
            }
        } else {
            c.interactionManager.interactItem(p, Hand.MAIN_HAND);
        }
        p.swingHand(Hand.MAIN_HAND);
        used = true;
        if (holdTicks > 0) a.holdUse = true;
    }
}

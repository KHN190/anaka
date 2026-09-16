package dev.anaka.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

/**
 * Per-tick reflex (like the drowning and lava nets): a long fall with a water bucket → look straight down and pour
 * water on the ground just before landing, then scoop it back up. A 5 Hz Python poll missed the 2–5 block window
 * (falls cover 10+ blocks between polls) and landed at 5 hp. Overworld only: water evaporates in the Nether.
 */
public final class WaterClutch {
    private static final double MIN_FALL = 4.0;     // below this a landing costs ≤ 1 heart
    private static BlockPos placed;
    private static int scoopTicks;

    private WaterClutch() {}

    public static void tick(MinecraftClient c, ClientPlayerEntity p) {
        if (c.world == null || c.interactionManager == null) return;
        if (placed != null) {
            scoopBack(c, p);
            return;
        }
        // Overworld and End (the dragon flings players 80+ blocks up; only the Nether evaporates water).
        if (c.world.getRegistryKey() == World.NETHER || p.isOnGround() || p.isTouchingWater()
            || p.fallDistance < MIN_FALL || p.getVelocity().y >= 0) return;
        Vec3d eye = p.getEyePos();
        BlockHitResult hit = c.world.raycast(new RaycastContext(eye, eye.add(0, -p.getBlockInteractionRange(), 0),
            RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.ANY, p));
        if (hit.getType() != HitResult.Type.BLOCK) return;                  // ground not in reach yet
        if (!c.world.getFluidState(hit.getBlockPos()).isEmpty()) return;   // falling into water/lava already
        // Pour only when the next tick would land us: the ground is within one tick of fall plus a margin.
        double gap = p.getY() - (hit.getBlockPos().getY() + 1.0);
        if (gap > Math.max(2.5, -p.getVelocity().y * 2.0)) return;
        if (!InvUtil.id(p.getMainHandStack()).equals("minecraft:water_bucket")
            && !InvUtil.select(c, s -> InvUtil.id(s).equals("minecraft:water_bucket"))) return;
        if (!InvUtil.id(p.getMainHandStack()).equals("minecraft:water_bucket")) return;   // hotbar swap next tick
        p.setPitch(90f);
        BlockHitResult down = c.world.raycast(new RaycastContext(eye, eye.add(0, -p.getBlockInteractionRange(), 0),
            RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, p));
        if (down.getType() != HitResult.Type.BLOCK) return;
        ActionResult r = c.interactionManager.interactBlock(p, Hand.MAIN_HAND, down);
        if (!r.isAccepted()) c.interactionManager.interactItem(p, Hand.MAIN_HAND);
        p.swingHand(Hand.MAIN_HAND);
        placed = down.getBlockPos().offset(down.getSide());
        scoopTicks = 40;
    }

    /** After landing in the poured water: take it back (the bucket is needed again for obsidian and the next fall). */
    private static void scoopBack(MinecraftClient c, ClientPlayerEntity p) {
        if (--scoopTicks <= 0) {
            placed = null;
            return;
        }
        if (!p.isOnGround() && !p.isTouchingWater()) return;
        if (!c.world.getFluidState(placed).isIn(FluidTags.WATER) || !c.world.getFluidState(placed).isStill()) {
            placed = null;
            return;
        }
        if (!InvUtil.id(p.getMainHandStack()).equals("minecraft:bucket")) return;
        Vec3d target = Vec3d.ofCenter(placed);
        dev.anaka.Agent.lookAt(p, target, 90f);
        c.interactionManager.interactItem(p, Hand.MAIN_HAND);
        p.swingHand(Hand.MAIN_HAND);
        placed = null;
    }
}

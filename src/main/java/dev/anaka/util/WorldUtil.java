package dev.anaka.util;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

import java.util.Set;

public final class WorldUtil {
    public static final double EYE_HEIGHT = 1.62;
    /** Held back from the game's reach so an aim a hair off still lands; approach goals take it off once more. */
    public static final double REACH_MARGIN = 0.3;

    public static double blockReach(net.minecraft.entity.player.PlayerEntity p) {
        return p.getBlockInteractionRange() - REACH_MARGIN;
    }

    public static double entityReach(net.minecraft.entity.player.PlayerEntity p) {
        return p.getEntityInteractionRange() - REACH_MARGIN;
    }

    private static final Set<Block> HAZARDS = Set.of(
        Blocks.FIRE, Blocks.SOUL_FIRE, Blocks.CACTUS, Blocks.SWEET_BERRY_BUSH, Blocks.POWDER_SNOW,
        Blocks.WITHER_ROSE, Blocks.CAMPFIRE, Blocks.SOUL_CAMPFIRE, Blocks.COBWEB, Blocks.POINTED_DRIPSTONE);
    private static final Set<Block> BAD_FLOORS = Set.of(
        Blocks.MAGMA_BLOCK, Blocks.CAMPFIRE, Blocks.SOUL_CAMPFIRE, Blocks.CACTUS, Blocks.POINTED_DRIPSTONE);

    private WorldUtil() {}

    public static String id(Block block) {
        return Registries.BLOCK.getId(block).toString();
    }

    public static boolean isLoaded(World w, BlockPos p) {
        return w.isChunkLoaded(p.getX() >> 4, p.getZ() >> 4);
    }

    public static boolean isWater(World w, BlockPos p) {
        return w.getBlockState(p).getFluidState().isIn(FluidTags.WATER);
    }

    /** A body can occupy this block. Ladders and vines have a thin collision plate but are meant to be stood in. */
    public static boolean passable(World w, BlockPos p) {
        BlockState s = w.getBlockState(p);
        FluidState fluid = s.getFluidState();
        if (!fluid.isEmpty() && fluid.isIn(FluidTags.LAVA)) return false;
        if (HAZARDS.contains(s.getBlock())) return false;
        Boolean open = s.contains(net.minecraft.state.property.Properties.OPEN)
            ? s.get(net.minecraft.state.property.Properties.OPEN) : null;
        // an open door of any kind (iron too) keeps a thin panel collision: still passed through (Passable.by)
        return Passable.by(s.isIn(BlockTags.CLIMBABLE), s.isIn(BlockTags.WOODEN_DOORS), open,
            s.getCollisionShape(w, p).isEmpty());
    }

    public static boolean closedWoodenDoor(World w, BlockPos p) {
        BlockState s = w.getBlockState(p);
        return s.isIn(BlockTags.WOODEN_DOORS) && s.contains(net.minecraft.block.DoorBlock.OPEN)
            && !s.get(net.minecraft.block.DoorBlock.OPEN);
    }

    public static boolean openWoodenDoor(World w, BlockPos p) {
        BlockState s = w.getBlockState(p);
        return s.isIn(BlockTags.WOODEN_DOORS) && s.contains(net.minecraft.block.DoorBlock.OPEN)
            && s.get(net.minecraft.block.DoorBlock.OPEN);
    }

    public static boolean climbable(World w, BlockPos p) {
        return w.getBlockState(p).isIn(BlockTags.CLIMBABLE);
    }

    /** A body can stand on top of this block. Fences and walls are taller than a block, so they don't count. */
    public static boolean floor(World w, BlockPos p) {
        BlockState s = w.getBlockState(p);
        if (BAD_FLOORS.contains(s.getBlock())) return false;
        VoxelShape shape = s.getCollisionShape(w, p);
        return !shape.isEmpty() && shape.getMax(Direction.Axis.Y) <= 1.0;
    }

    public static boolean standable(World w, BlockPos feet) {
        if (!isLoaded(w, feet) || !passable(w, feet) || !passable(w, feet.up())) return false;
        if (floor(w, feet.down())) return true;
        if (climbable(w, feet)) return true; // holding onto a ladder
        // Swim only at the surface: feet in water, head in air. Diving routes are unreachable while floating.
        return isWater(w, feet) && !isWater(w, feet.up());
    }

    public static Vec3d eyeAt(BlockPos feet) {
        return new Vec3d(feet.getX() + 0.5, feet.getY() + EYE_HEIGHT, feet.getZ() + 0.5);
    }

    /**
     * Finds a point on {@code pos} visible from {@code eye} within {@code range}.
     * Tries the block center first, then each face center.
     */
    public static BlockHitResult visibleHit(World w, Entity viewer, Vec3d eye, BlockPos pos, double range) {
        Vec3d center = Vec3d.ofCenter(pos);
        double maxSq = range * range;
        BlockHitResult hit = rayTo(w, viewer, eye, center, pos, maxSq);
        if (hit != null) return hit;
        for (Direction d : Direction.values()) {
            Vec3d face = center.add(d.getOffsetX() * 0.45, d.getOffsetY() * 0.45, d.getOffsetZ() * 0.45);
            hit = rayTo(w, viewer, eye, face, pos, maxSq);
            if (hit != null) return hit;
        }
        return null;
    }

    private static BlockHitResult rayTo(World w, Entity viewer, Vec3d eye, Vec3d point, BlockPos pos, double maxSq) {
        if (eye.squaredDistanceTo(point) > maxSq) return null;
        Vec3d end = point.add(point.subtract(eye).normalize().multiply(0.1));
        BlockHitResult r = w.raycast(new RaycastContext(eye, end, RaycastContext.ShapeType.OUTLINE,
            RaycastContext.FluidHandling.NONE, viewer));
        if (r.getType() == HitResult.Type.BLOCK && r.getBlockPos().equals(pos)) return r;
        return null;
    }

    public record Placement(BlockPos support, Direction face, Vec3d hit) {}

    /** Finds a solid neighbour face that can be clicked from {@code eye} to place a block at {@code pos}. */
    public static Placement findPlacement(World w, Entity viewer, Vec3d eye, BlockPos pos, double range) {
        return findPlacement(w, viewer, eye, pos, range, null);
    }

    /** Same, restricted to clicking {@code against} when it is not null. */
    public static Placement findPlacement(World w, Entity viewer, Vec3d eye, BlockPos pos, double range, BlockPos against) {
        double maxSq = range * range;
        for (Direction d : Direction.values()) {
            BlockPos support = pos.offset(d);
            if (against != null && !support.equals(against)) continue;
            BlockState s = w.getBlockState(support);
            if (s.isReplaceable() || s.getCollisionShape(w, support).isEmpty()) continue;
            Direction face = d.getOpposite();
            Vec3d hit = Vec3d.ofCenter(support).add(face.getOffsetX() * 0.5, face.getOffsetY() * 0.5, face.getOffsetZ() * 0.5);
            if (eye.squaredDistanceTo(hit) > maxSq) continue;
            Vec3d toEye = eye.subtract(hit);
            if (toEye.x * face.getOffsetX() + toEye.y * face.getOffsetY() + toEye.z * face.getOffsetZ() <= 0.05) continue;
            BlockHitResult r = w.raycast(new RaycastContext(eye, hit.add(hit.subtract(eye).normalize().multiply(0.05)),
                RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, viewer));
            if (r.getType() == HitResult.Type.MISS || r.getBlockPos().equals(support)) {
                return new Placement(support, face, hit);
            }
        }
        return null;
    }
}

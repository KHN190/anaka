package dev.anaka.util;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/** Cells a dig-through may never break or build in: single cells plus whole boxes (player-declared zones), asked
 * by `contains` — a box is never expanded into cells. */
public class AvoidSet extends LongOpenHashSet {
    /** Each box: {x1, y1, z1, x2, y2, z2}, inclusive, any corner order. */
    public final List<int[]> boxes = new ArrayList<>();

    public AvoidSet() {}

    public AvoidSet(AvoidSet other) {
        super(other);
        boxes.addAll(other.boxes);
    }

    /** A copy keeping the boxes when `s` is an AvoidSet. */
    public static AvoidSet of(LongOpenHashSet s) {
        if (s instanceof AvoidSet a) return new AvoidSet(a);
        AvoidSet out = new AvoidSet();
        if (s != null) out.addAll(s);
        return out;
    }

    public static boolean inBox(int[] b, int x, int y, int z) {
        return Math.min(b[0], b[3]) <= x && x <= Math.max(b[0], b[3]) && Math.min(b[1], b[4]) <= y && y <= Math.max(b[1], b[4])
            && Math.min(b[2], b[5]) <= z && z <= Math.max(b[2], b[5]);
    }

    @Override
    public boolean contains(long k) {
        if (super.contains(k)) return true;
        if (boxes.isEmpty()) return false;
        int x = BlockPos.unpackLongX(k), y = BlockPos.unpackLongY(k), z = BlockPos.unpackLongZ(k);
        for (int[] b : boxes) if (inBox(b, x, y, z)) return true;
        return false;
    }
}

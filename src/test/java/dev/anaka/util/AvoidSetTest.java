package dev.anaka.util;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** AvoidSet: cells and boxes, asked by `contains`; a copy keeps the boxes. */
class AvoidSetTest {
    @Test
    void containsTable() {
        AvoidSet s = new AvoidSet();
        s.add(BlockPos.asLong(1, 64, 1));
        s.boxes.add(new int[]{10, 60, 10, 5, 70, 20});       // corners in any order
        assertTrue(s.contains(BlockPos.asLong(1, 64, 1)), "a single cell");
        assertTrue(s.contains(BlockPos.asLong(7, 65, 15)), "inside the box");
        assertFalse(s.contains(BlockPos.asLong(11, 65, 15)), "must fail: just outside the box");
        assertTrue(AvoidSet.of(s).contains(BlockPos.asLong(7, 65, 15)), "must fail when the copy drops the boxes");
    }
}

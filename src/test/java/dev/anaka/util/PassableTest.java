package dev.anaka.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Passable.by: an open door of any kind is passed through; a closed iron one stays a wall. */
class PassableTest {
    @Test
    void doorsTable() {
        // (climbable, wooden door, open, no collision)
        assertTrue(Passable.by(false, false, true, false), "must fail: an open iron door read solid");
        assertFalse(Passable.by(false, false, false, false), "must fail: a closed iron door read passable");
        assertTrue(Passable.by(false, true, false, false), "a closed wooden door: GotoTask opens it");
        assertTrue(Passable.by(false, false, null, true), "air");
        assertFalse(Passable.by(false, false, null, false), "stone");
        assertTrue(Passable.by(true, false, null, false), "a ladder");
    }
}

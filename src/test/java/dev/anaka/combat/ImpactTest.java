package dev.anaka.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Impact: tables of projectiles and melee approaches against a standing player's box. */
class ImpactTest {
    static final double[] LO = {-0.3, 0.0, -0.3}, HI = {0.3, 1.8, 0.3};
    static final Impact.Motion STILL = new Impact.Motion(0, 1.0, 0, false);      // constant velocity
    static final Impact.Motion FIREBALL = Impact.Motion.explosive(0.1, 0.95);
    static final Impact.Motion ARROW = Impact.Motion.persistent(0.99, 0.05);

    static Impact.Hit at(double[] pos, double[] vel, Impact.Motion m) {
        return Impact.projectile(pos, vel, m, LO, HI, 0.25, 60);
    }

    @Test
    void constantVelocityEntersOnTheExactTick() {
        // the near face grown by 0.25 is z = -0.55; from -5.55 at 1 b/t it arrives at the end of tick 5
        Impact.Hit h = at(new double[]{0, 1, -5.55}, new double[]{0, 0, 1}, STILL);
        assertNotNull(h);
        assertEquals(5, h.ticks());
        assertEquals(-0.55, h.z(), 1e-9);
        assertEquals(1.0, h.y(), 1e-9);
    }

    @Test
    void alreadyInsideIsNow() {
        assertEquals(0, at(new double[]{0, 1, 0}, new double[]{0, 0, 1}, STILL).ticks());
    }

    @Test
    void aFireballAcceleratesStraightAtUs() {
        Impact.Hit h = at(new double[]{0, 1, -20}, new double[]{0, 0, 0.5}, FIREBALL);
        assertNotNull(h);
        assertEquals(1.0, h.y(), 1e-9, "no gravity: the line holds its height");
        // (v + 0.1)·0.95 converges on 1.9 b/t: faster than the 0.5 it left with, so under 40 ticks for 19.45 blocks
        assertTrue(h.ticks() < 39, "accelerated, not coasting: " + h.ticks());
        assertTrue(h.ticks() > 10, "but not at its terminal speed at once: " + h.ticks());
    }

    @Test
    void mustFailAFireballPassingBesideUs() {
        assertNull(at(new double[]{3, 1, -20}, new double[]{0, 0, 0.5}, FIREBALL));
    }

    @Test
    void anArrowFalls() {
        double[] level = {0, 1.6, -30}, flat = {0, 0, 3};
        // must fail: aimed level from 30 blocks it drops under the box before it arrives (gravity 0.05/tick)
        Impact.Hit dropped = at(level, flat, ARROW);
        assertTrue(dropped == null || dropped.y() < 1.6, "gravity pulls it down");
        Impact.Hit noGravity = at(level, flat, new Impact.Motion(0, 0.99, 0, false));
        assertNotNull(noGravity, "without gravity the level shot lands");
        assertEquals(1.6, noGravity.y(), 1e-9);
        Impact.Hit lobbed = at(new double[]{0, 1.6, -30}, new double[]{0, 0.25, 3}, ARROW);
        assertNotNull(lobbed, "aimed a little up, it lands");
        assertEquals(11, lobbed.ticks(), "29.45 blocks: ten ticks of 3·0.99^k cover only 28.7");
    }

    @Test
    void meleeTable() {
        // (gap, closing b/t, wind-up) → ticks
        Object[][] rows = {
            {0.0, 0.0, 0, 0},            // in range: now
            {-0.5, 0.0, 3, 3},           // in range, a wind-up
            {2.0, 0.25, 0, 8},           // two blocks at a zombie's walk
            {2.0, 0.25, 2, 10},
            {2.0, 0.0, 0, -1},           // must fail: not coming: no hit
            {2.0, -0.2, 0, -1},          // must fail: walking away
            {100.0, 0.25, 0, -1},        // past the horizon
        };
        for (Object[] r : rows) {
            assertEquals((int) r[3], Impact.melee((double) r[0], (double) r[1], (int) r[2], 60),
                "gap " + r[0] + " closing " + r[1]);
        }
    }

    @Test
    void segmentTable() {
        double[] a = {0, 0, 0}, b = {1, 1, 1};
        assertEquals(0.5, Impact.segmentEnters(-1, 0.5, 0.5, 1, 0.5, 0.5, a, b), 1e-9);
        assertEquals(-1, Impact.segmentEnters(-1, 2, 0.5, 1, 2, 0.5, a, b), 1e-9);      // must fail: above it
        assertEquals(-1, Impact.segmentEnters(-2, 0.5, 0.5, -1, 0.5, 0.5, a, b), 1e-9); // must fail: short of it
    }
}

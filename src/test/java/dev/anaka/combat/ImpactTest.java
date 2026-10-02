package dev.anaka.combat;

import org.junit.jupiter.api.Test;

import java.util.List;

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
    void drawnTable() {
        assertEquals(5 + 7, Impact.drawn(15, 20, 7));      // a bow at 15 of 20 ticks, 7 ticks of flight
        assertEquals(7, Impact.drawn(25, 20, 7));          // must fail: an over-pulled bow is not early
        assertEquals(20 + 7, Impact.drawn(0, 20, 7));      // just started drawing
    }

    @Test
    void segmentTable() {
        double[] a = {0, 0, 0}, b = {1, 1, 1};
        assertEquals(0.5, Impact.segmentEnters(-1, 0.5, 0.5, 1, 0.5, 0.5, a, b), 1e-9);
        assertEquals(-1, Impact.segmentEnters(-1, 2, 0.5, 1, 2, 0.5, a, b), 1e-9);      // must fail: above it
        assertEquals(-1, Impact.segmentEnters(-2, 0.5, 0.5, -1, 0.5, 0.5, a, b), 1e-9); // must fail: short of it
    }

    @Test
    void aFlightStopsAtTheFirstCollider() {
        // a level shot at 1 b/t from 10 out: open air lands on tick 10; its block test runs from its position (the box
        // bottom, 0.25 under the centre), the game's point ray
        double[] level = {0, 1, -10}, high = {0, 1.9, -10}, ahead = {0, 0, 1};
        double[] post = BoxTerrain.box(-0.125, 0, -3.125, 0.125, 1.5, -2.875);
        Object[][] rows = {
            // (situation, from, terrain) → tick it lands, or null
            {"open air", level, BoxTerrain.of(), 10},
            {"must fail: a wall between (today's prediction flew through it)", level,
                BoxTerrain.of(BoxTerrain.box(-0.5, 0, -4, 0.5, 2, -3)), null},
            {"a bottom slab under the line: passes", level, BoxTerrain.of(BoxTerrain.box(-0.5, 0, -4, 0.5, 0.5, -3)),
                10},
            {"a top slab on the line: stops it", level, BoxTerrain.of(BoxTerrain.box(-0.5, 0.5, -4, 0.5, 1, -3)), null},
            {"a fence post on the line: stops it", level, BoxTerrain.of(post), null},
            {"over the fence post (1.5 high): lands", high, BoxTerrain.of(post), 10},
            {"grass, flowers, a cobweb: no collider, no slowing (vanilla moves projectiles by setPosition)", level,
                BoxTerrain.of(), 10},
            {"started inside a block (stuck): never lands", level,
                BoxTerrain.of(BoxTerrain.box(-0.5, 0, -10.5, 0.5, 1, -9.5)), null},
        };
        for (Object[] r : rows) {
            Impact.Hit h = Impact.projectile((double[]) r[1], ahead, STILL, LO, HI, 0.25, 60, (Impact.Terrain) r[2]);
            assertEquals(r[3], h == null ? null : h.ticks(), (String) r[0]);
        }
    }

    @Test
    void waterSlowsAFlight() {
        Impact.Motion arrow = Impact.Motion.persistent(0.99, 0.05, 0.6);
        double[] from = {0, 1.6, -30}, vel = {0, 0.25, 3};
        Impact.Hit dry = Impact.projectile(from, vel, arrow, LO, HI, 0.25, 60, Impact.Terrain.OPEN);
        Impact.Hit wet = Impact.projectile(from, vel, arrow, LO, HI, 0.25, 60,
            new BoxTerrain(List.of(), List.of(BoxTerrain.box(-5, -5, -20, 5, 10, -8))));
        assertEquals(11, dry.ticks(), "open air as anArrowFalls");
        assertTrue(wet == null || wet.ticks() > dry.ticks(), "must fail: water left out (drag 0.6 a tick in it)");
        Impact.Motion fireball = Impact.Motion.explosive(0.1, 0.95, 0.8);
        double[] fFrom = {0, 1, -20}, fVel = {0, 0, 0.5};
        Impact.Hit fd = Impact.projectile(fFrom, fVel, fireball, LO, HI, 0.5, 60, Impact.Terrain.OPEN);
        Impact.Hit fw = Impact.projectile(fFrom, fVel, fireball, LO, HI, 0.5, 60,
            new BoxTerrain(List.of(), List.of(BoxTerrain.box(-5, -5, -15, 5, 10, -5))));
        assertNotNull(fd);
        assertNotNull(fw);
        assertTrue(fw.ticks() > fd.ticks(), "a fireball through water: 0.8 drag, later");
    }

    @Test
    void exposureMirrorsTheExplosion() {
        // a player box 0.6 × 1.8 × 0.6, the blast 2 blocks off, a creeper on a ledge (feet at 1.2)
        double[] lo = {-0.3, 0, -0.3}, hi = {0.3, 1.8, 0.3}, blast = {0, 1.2, -2};
        Object[][] rows = {
            // (situation, terrain) → exposure within {min, max}
            {"open: every point reached", BoxTerrain.of(), 1.0, 1.0},
            {"must fail: a wall between: none (a shield raised for a blast it never feels)",
                BoxTerrain.of(BoxTerrain.box(-2, -1, -1.5, 2, 3, -1)), 0.0, 0.0},
            {"a wall to the knees: the upper points reached", BoxTerrain.of(BoxTerrain.box(-2, -1, -1.5, 2, 0.6, -1)),
                0.01, 0.99},
        };
        for (Object[] r : rows) {
            double e = Impact.exposure(blast, lo, hi, (Impact.Terrain) r[1]);
            assertTrue(e >= (double) r[2] && e <= (double) r[3], r[0] + ": " + e);
        }
    }
}

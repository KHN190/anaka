package dev.anaka.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Threats: a hit counts only when the game would let it land — the shooter's sight (canSee), a melee mob's range and
 * sight, a blast's exposure — and a crossbow's CHARGED wait. */
class ThreatsTest {
    static final double[] OURS = {0, 64.62, 0};                 // our eye, standing at (0, 63, 0)
    static final double[] BELOW = {0, 59.74, 3};                // a skeleton's eye, 5 below and 3 out (17:57)
    static final Impact.Terrain ROCK = BoxTerrain.of(BoxTerrain.box(-0.5, 61.5, 1, 0.5, 62.5, 2));

    @Test
    void sight() {
        Object[][] rows = {
            // (situation, terrain, shooter eye) → seen
            {"open: seen", BoxTerrain.of(), BELOW, true},
            {"must fail: rock between (17:57): not seen", ROCK, BELOW, false},
            {"water between: seen (fluids ignored)", BoxTerrain.of(), BELOW, true},
            {"past 128: not seen", BoxTerrain.of(), new double[]{0, 64.62, 129}, false},
        };
        for (Object[] r : rows) {
            assertEquals(r[3], Threats.seen((Impact.Terrain) r[1], (double[]) r[2], OURS), (String) r[0]);
        }
    }

    @Test
    void ranged() {
        double dist = 5.8;
        int flight = (int) Math.ceil(dist / Threats.ARROW_SPEED);
        Object[][] rows = {
            // (situation, tti) → expected
            {"must fail: a bow fully drawn behind rock (17:57): no hit, no shield",
                Threats.drawnTti(false, 20, Threats.BOW_DRAW, 0, dist), -1},
            {"a bow fully drawn, seen: the flight", Threats.drawnTti(true, 20, Threats.BOW_DRAW, 0, dist), flight},
            {"a bow half drawn, seen: the draw left, then the flight",
                Threats.drawnTti(true, 10, Threats.BOW_DRAW, 0, dist), 10 + flight},
            {"a crossbow charging: the pull left, its CHARGED wait, the flight",
                Threats.drawnTti(true, 10, Threats.CROSSBOW_DRAW, Threats.CHARGED_WAIT, dist),
                15 + Threats.CHARGED_WAIT + flight},
            {"charged, its charge end not seen: it may fire now", Threats.chargedTti(true, -1, dist), flight},
            {"charged 5 ticks ago: the rest of the least wait", Threats.chargedTti(true, 5, dist),
                Threats.CHARGED_WAIT - 5 + flight},
            {"must fail: charged, unseen: no hit", Threats.chargedTti(false, 30, dist), -1},
        };
        for (Object[] r : rows) assertEquals(r[2], r[1], (String) r[0]);
    }

    @Test
    void melee() {
        Object[][] rows = {
            // (situation, in range, seen, gap, closing) → tti
            {"in range, seen: now", true, true, 0.0, 0.0, 0},
            {"must fail: in range behind a wall: no hit", true, false, 0.0, 0.0, -1},
            {"two blocks off, closing: as before", false, true, 2.0, 0.25, 8},
            {"two blocks off behind a wall, closing: the approach as before (in range unseen reads -1)", false, false,
                2.0, 0.25, 8},
        };
        for (Object[] r : rows) {
            assertEquals(r[5], Threats.meleeTti((boolean) r[1], (boolean) r[2], (double) r[3], (double) r[4], 0),
                (String) r[0]);
        }
    }

    @Test
    void blast() {
        Object[][] rows = {
            // (situation, fuse, exposure, distance, power) → tti
            {"exposed, in reach: the fuse", 12, 1.0, 3.0, Threats.CREEPER_POWER, 12},
            {"must fail: behind a wall (exposure 0): no blast to shield", 12, 0.0, 3.0, Threats.CREEPER_POWER, -1},
            {"past power·2: no blast", 12, 1.0, 7.0, Threats.CREEPER_POWER, -1},
            {"charged (power ×2): it reaches 7", 12, 1.0, 7.0, Threats.CREEPER_POWER * 2, 12},
        };
        for (Object[] r : rows) {
            assertEquals(r[5], Threats.blastTti((int) r[1], (double) r[2], (double) r[3], (double) r[4]),
                (String) r[0]);
        }
    }

    @Test
    void anArcherSteppingOutOfCover() {
        // a skeleton fully drawn behind a pillar; we walk out sideways a block per tick: no hit while hidden, the flight
        // on the first tick the line opens (it fires at once, BowAttackGoal), never a shield while hidden
        Impact.Terrain pillar = BoxTerrain.of(BoxTerrain.box(-0.5, 60, 4, 0.5, 70, 5));
        double[] archer = {0, 64.74, 10};
        int firstSeen = -1;
        for (int x = 0; x <= 4; x++) {
            double[] eye = {x, 64.62, 0};
            boolean seen = Threats.seen(pillar, archer, eye);
            int tti = Threats.drawnTti(seen, 20, Threats.BOW_DRAW, 0, Math.hypot(x, 10));
            if (seen && firstSeen < 0) firstSeen = x;
            assertEquals(seen ? (int) Math.ceil(Math.hypot(x, 10) / Threats.ARROW_SPEED) : -1, tti, "x " + x);
        }
        assertEquals(2, firstSeen, "must fail: hidden behind the pillar read as seen (x 1 grazes its edge)");
    }
}

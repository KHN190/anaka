package dev.anaka.combat;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** A ghast fireball flown as the game flies it (v = (v + v̂·0.1)·0.95, then move) at a standing player, the client
 * seeing it 0–2 ticks late: the reflex punches it on some tick before it lands. */
class DeflectTest {
    static final double[] LO = {-0.3, 0.0, -0.3}, HI = {0.3, 1.8, 0.3}, EYE = {0.0, 1.62, 0.0};
    static final double REACH = 2.7, HALF = 0.5, ACCEL = 0.1, DRAG = 0.95;       // reach: 3.0 less REACH_MARGIN
    static final Impact.Motion FIREBALL = Impact.Motion.explosive(ACCEL, DRAG);
    static final Reflex.Policy DEFLECT = new Reflex.Policy(true, false, true, true);
    static final Reflex.Body BODY = new Reflex.Body(true, 1f, 0, -1);

    /** First tick it is deflected, or -1: landed. The reflex reads it {@code lag} ticks old; landing is the server's. */
    static int flown(double[] from, double speed, int lag) {
        double[] aim = {EYE[0] - from[0], EYE[1] - (from[1] + HALF), EYE[2] - from[2]};
        double n = Math.sqrt(aim[0] * aim[0] + aim[1] * aim[1] + aim[2] * aim[2]);
        double[] pos = from.clone(), vel = {aim[0] / n * speed, aim[1] / n * speed, aim[2] / n * speed};
        List<double[][]> seen = new ArrayList<>();
        double[] lo = {LO[0] - HALF, LO[1] - HALF, LO[2] - HALF}, hi = {HI[0] + HALF, HI[1] + HALF, HI[2] + HALF};
        for (int t = 0; t < 200; t++) {
            seen.add(new double[][]{pos.clone(), vel.clone()});
            double[][] old = seen.get(Math.max(0, seen.size() - 1 - lag));
            Reflex.Contact c = Reflex.projectile(1, "fireball", true, old[0], old[1], FIREBALL, HALF, LO, HI, EYE,
                REACH, 60, Impact.Terrain.OPEN);
            if ("deflect".equals(Reflex.decide(List.of(c), DEFLECT, BODY).what())) return t;
            double s = Math.sqrt(vel[0] * vel[0] + vel[1] * vel[1] + vel[2] * vel[2]);
            for (int i = 0; i < 3; i++) {
                vel[i] = (vel[i] + vel[i] / s * ACCEL) * DRAG;
                pos[i] += vel[i];
            }
            if (Impact.inside(pos[0], pos[1] + HALF, pos[2], lo, hi)) return -1;       // the server's: it landed
        }
        return -1;
    }

    /**
     * The volley as read in game (bench deflect__volley 20260929-214831): the client's copy of a fireball moves only
     * when the server sends its position, every {@code interval} ticks (FIREBALL trackingTickInterval 10) — its time
     * to impact held for 6 ticks, then jumped 8-10. First tick deflected, or -1: landed. {@code reckoned}: the reading
     * stepped forward from the last fix (Threats.fix/reckon); else the stale copy as it is.
     */
    static int flownTracked(double[] from, double speed, int interval, int phase, boolean reckoned) {
        double[] aim = {EYE[0] - from[0], EYE[1] - (from[1] + HALF), EYE[2] - from[2]};
        double n = Math.sqrt(aim[0] * aim[0] + aim[1] * aim[1] + aim[2] * aim[2]);
        double[] pos = from.clone(), vel = {aim[0] / n * speed, aim[1] / n * speed, aim[2] / n * speed};
        double[] lo = {LO[0] - HALF, LO[1] - HALF, LO[2] - HALF}, hi = {HI[0] + HALF, HI[1] + HALF, HI[2] + HALF};
        double[] copyPos = pos.clone(), copyVel = vel.clone();
        Threats.Fix fix = null;
        for (int t = 0; t < 200; t++) {
            if ((t + phase) % interval == 0) {          // a server update reaches the client
                copyPos = pos.clone();
                copyVel = vel.clone();
            }
            double[] p = copyPos, v = copyVel;
            if (reckoned) {
                fix = Threats.fix(fix, copyPos, copyVel, t);
                double[][] at = Threats.reckon(fix, t, FIREBALL);
                p = at[0];
                v = at[1];
            }
            Reflex.Contact c = Reflex.projectile(1, "fireball", true, p, v, FIREBALL, HALF, LO, HI, EYE, REACH, 60, Impact.Terrain.OPEN);
            if ("deflect".equals(Reflex.decide(List.of(c), DEFLECT, BODY).what())) {
                return t;
            }
            double s = Math.sqrt(vel[0] * vel[0] + vel[1] * vel[1] + vel[2] * vel[2]);
            for (int i = 0; i < 3; i++) {
                vel[i] = (vel[i] + vel[i] / s * ACCEL) * DRAG;
                pos[i] += vel[i];
            }
            if (Impact.inside(pos[0], pos[1] + HALF, pos[2], lo, hi)) return -1;
        }
        return -1;
    }

    @Test
    void aFireballSeenEveryTenTicksIsStillPunched() {
        int interval = 10;                                      // FIREBALL trackingTickInterval
        double[] volley = {12, 1.12, 0};                        // the volley's shot: 12 out, at the eye
        int staleMissed = 0;
        for (int phase = 0; phase < interval; phase++) {
            if (flownTracked(volley, 0.1, interval, phase, false) < 0) staleMissed++;
            assertTrue(flownTracked(volley, 0.1, interval, phase, true) >= 0,
                "reckoned, update phase " + phase + ": landed without a swing");
        }
        assertTrue(staleMissed > 0, "must fail: the stale copy (the reading before the fix) misses some phase");
    }

    @Test
    void aCopySeenEveryTenTicksReportsItsVelocity() {
        int interval = 10;                                      // FIREBALL trackingTickInterval
        double[] pos = {0, 1.12, -12}, vel = {0, 0, 0.1};
        double[] copy = pos.clone(), copyVel = vel.clone(), lastCopy = pos.clone();
        Threats.Fix fix = null;
        boolean zeroDelta = false;
        for (int t = 0; t < 30; t++) {
            if (t % interval == 0) {
                copy = pos.clone();
                copyVel = vel.clone();
            }
            fix = Threats.fix(fix, copy, copyVel, t);
            double[] reported = Threats.reported(Threats.reckon(fix, t, FIREBALL)[1],
                new double[]{copy[0] - lastCopy[0], copy[1] - lastCopy[1], copy[2] - lastCopy[2]});
            for (int i = 0; i < 3; i++) {
                assertTrue(Math.abs(reported[i] - vel[i]) < 1e-9, "tick " + t + ": the server's velocity, axis " + i);
            }
            zeroDelta |= t % interval != 0 && copy[2] - lastCopy[2] == 0;
            lastCopy = copy.clone();
            double s = Math.sqrt(vel[0] * vel[0] + vel[1] * vel[1] + vel[2] * vel[2]);
            for (int i = 0; i < 3; i++) {
                vel[i] = (vel[i] + vel[i] / s * ACCEL) * DRAG;
                pos[i] += vel[i];
            }
        }
        assertTrue(zeroDelta, "must fail: the last-tick delta of the copy reads 0 between updates");
    }

    @Test
    void aPunchSendsTheFireballAlongTheLookSent() {
        double[] ball = {0, 1.62, -2.5};                        // the fireball in reach, ahead at eye height
        float[] sent = Reflex.look(EYE, ball);
        double[] away = Reflex.facing(sent[0], sent[1]);        // the server's REDIRECTED heading
        double[] toBall = {ball[0] - EYE[0], ball[1] - EYE[1], ball[2] - EYE[2]};
        double n = Math.sqrt(toBall[0] * toBall[0] + toBall[1] * toBall[1] + toBall[2] * toBall[2]);
        for (int i = 0; i < 3; i++) {
            assertTrue(Math.abs(away[i] - toBall[i] / n) < 1e-4, "the look sent points at the fireball: axis " + i);
        }
        float[] restored = {90f, 45f};                          // a task's look (down and aside), put back
        double[] wrong = Reflex.facing(restored[0], restored[1]);
        double along = wrong[0] * toBall[0] / n + wrong[1] * toBall[1] / n + wrong[2] * toBall[2] / n;
        assertTrue(along < 0.5, "must fail: the restored look sends it elsewhere (down and aside)");
    }

    @Test
    void aFireballIsPunchedBeforeItLands() {
        // (situation, where it was shot from, its speed then, ticks the client lags) → deflected on some tick
        Object[][] rows = {
            {"shot from 18 blocks, level", new double[]{0, 1.1, -18}, 0.1, 0},
            {"shot from 8 blocks, level", new double[]{0, 1.1, -8}, 0.1, 0},
            {"an angled shot from above and aside", new double[]{12, 7, -10}, 0.1, 0},
            {"arriving at terminal speed (1.9 b/t)", new double[]{0, 1.1, -18}, 1.9, 0},
            {"from 18, the client a tick behind", new double[]{0, 1.1, -18}, 0.1, 1},
            {"at terminal speed, the client a tick behind", new double[]{0, 1.1, -18}, 1.9, 1},
            {"angled, the client a tick behind", new double[]{12, 7, -10}, 0.1, 1},
            {"at terminal speed, the client two ticks behind", new double[]{0, 1.1, -18}, 1.9, 2},
            {"from 18, the client two ticks behind", new double[]{0, 1.1, -18}, 0.1, 2},
            {"angled, the client two ticks behind", new double[]{12, 7, -10}, 0.1, 2},
        };
        for (Object[] r : rows) {
            int t = flown((double[]) r[1], (double) r[2], (int) r[3]);
            assertTrue(t >= 0, r[0] + ": landed without a swing");
        }
    }
}

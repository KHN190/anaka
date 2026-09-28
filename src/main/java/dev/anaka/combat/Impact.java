package dev.anaka.combat;

/**
 * When and where an attack lands on the body: the one prediction, pure (no game classes), so the reflex, /entities and
 * the Python planner read the same numbers. Integrates the game's own per-tick motion:
 * <ul>
 *   <li>an explosive projectile (fireball, wind charge, blaze charge): {@code v = (v + |v|̂·accel) · drag}, then
 *       {@code pos += v} (ExplosiveProjectileEntity.applyDrag before the move; no gravity) — a straight line;</li>
 *   <li>an arrow or trident: {@code pos += v}, then {@code v *= drag}, then {@code v.y -= gravity}
 *       (PersistentProjectileEntity.tick: move, applyDrag, applyGravity);</li>
 *   <li>a melee mob: already in its attack range → its wind-up; else the gap closed at its approach speed, then the
 *       wind-up (the mob's attack cooldown is the server's: the client cannot see it, so a mob in range may hit at once).</li>
 * </ul>
 * A projectile hits on the first tick whose movement segment enters the body's box (grown by the projectile's half
 * size). Ticks are counted from now: 1 = it lands during the next tick.
 */
public final class Impact {
    private Impact() {}

    /** Where and when: {@code ticks} until it lands, and the point on its path where it enters the box. */
    public record Hit(int ticks, double x, double y, double z) {}

    /** A projectile's motion model, as the game ticks it. */
    public record Motion(double accel, double drag, double gravity, boolean dragFirst) {
        /** A fireball / blaze charge / wind charge: accelerated along its heading, dragged, no gravity. */
        public static Motion explosive(double accel, double drag) {
            return new Motion(accel, drag, 0.0, true);
        }

        /** An arrow / trident: moved, dragged, then pulled down. */
        public static Motion persistent(double drag, double gravity) {
            return new Motion(0.0, drag, gravity, false);
        }
    }

    /**
     * The first tick (1..maxTicks) the projectile at {@code pos} moving {@code vel} (blocks/tick) enters the box
     * [{@code lo}, {@code hi}] grown by {@code radius}, or null when it does not within {@code maxTicks}.
     */
    public static Hit projectile(double[] pos, double[] vel, Motion m, double[] lo, double[] hi, double radius,
                                 int maxTicks) {
        double px = pos[0], py = pos[1], pz = pos[2];
        double vx = vel[0], vy = vel[1], vz = vel[2];
        double[] a = {lo[0] - radius, lo[1] - radius, lo[2] - radius};
        double[] b = {hi[0] + radius, hi[1] + radius, hi[2] + radius};
        if (inside(px, py, pz, a, b)) return new Hit(0, px, py, pz);
        for (int t = 1; t <= maxTicks; t++) {
            if (m.dragFirst()) {
                double n = Math.sqrt(vx * vx + vy * vy + vz * vz);
                if (n > 1e-9 && m.accel() != 0) {
                    vx += vx / n * m.accel();
                    vy += vy / n * m.accel();
                    vz += vz / n * m.accel();
                }
                vx *= m.drag();
                vy *= m.drag();
                vz *= m.drag();
            }
            double nx = px + vx, ny = py + vy, nz = pz + vz;
            double f = segmentEnters(px, py, pz, nx, ny, nz, a, b);
            if (f >= 0) return new Hit(t, px + (nx - px) * f, py + (ny - py) * f, pz + (nz - pz) * f);
            px = nx;
            py = ny;
            pz = nz;
            if (!m.dragFirst()) {
                vx *= m.drag();
                vy *= m.drag();
                vz *= m.drag();
                vy -= m.gravity();
            }
        }
        return null;
    }

    /** A drawn shot: the draw left ({@code full} − {@code pulled}, never below 0), then the flight. */
    public static int drawn(int pulled, int full, int flightTicks) {
        return Math.max(0, full - pulled) + flightTicks;
    }

    /** Positions after each of the next {@code n} ticks, stepped as {@link #projectile} steps them. */
    public static double[][] path(double[] pos, double[] vel, Motion m, int n) {
        double[][] out = new double[n][];
        double px = pos[0], py = pos[1], pz = pos[2], vx = vel[0], vy = vel[1], vz = vel[2];
        for (int t = 0; t < n; t++) {
            if (m.dragFirst()) {
                double s = Math.sqrt(vx * vx + vy * vy + vz * vz);
                if (s > 1e-9 && m.accel() != 0) {
                    vx += vx / s * m.accel();
                    vy += vy / s * m.accel();
                    vz += vz / s * m.accel();
                }
                vx *= m.drag();
                vy *= m.drag();
                vz *= m.drag();
            }
            px += vx;
            py += vy;
            pz += vz;
            out[t] = new double[]{px, py, pz};
            if (!m.dragFirst()) {
                vx *= m.drag();
                vy *= m.drag();
                vz *= m.drag();
                vy -= m.gravity();
            }
        }
        return out;
    }

    /**
     * Ticks until a melee mob can land a hit: {@code gap} blocks still to close before it is in its attack range
     * (0 or less: in range), closed at {@code closing} blocks/tick, then {@code windup} ticks. -1 when it is not
     * closing and not in range (no hit coming), capped at {@code maxTicks} (-1 past it).
     */
    public static int melee(double gap, double closing, int windup, int maxTicks) {
        if (gap <= 0) return Math.min(windup, maxTicks);
        if (closing <= 1e-3) return -1;
        int t = (int) Math.ceil(gap / closing) + windup;
        return t <= maxTicks ? t : -1;
    }

    static boolean inside(double x, double y, double z, double[] a, double[] b) {
        return x >= a[0] && x <= b[0] && y >= a[1] && y <= b[1] && z >= a[2] && z <= b[2];
    }

    /** The fraction 0..1 along p→q where it first enters the box [a, b], or -1 (slab test). */
    static double segmentEnters(double px, double py, double pz, double qx, double qy, double qz, double[] a, double[] b) {
        double t0 = 0, t1 = 1;
        double[] p = {px, py, pz}, d = {qx - px, qy - py, qz - pz};
        for (int i = 0; i < 3; i++) {
            if (Math.abs(d[i]) < 1e-12) {
                if (p[i] < a[i] || p[i] > b[i]) return -1;
                continue;
            }
            double u = (a[i] - p[i]) / d[i], v = (b[i] - p[i]) / d[i];
            if (u > v) {
                double s = u;
                u = v;
                v = s;
            }
            t0 = Math.max(t0, u);
            t1 = Math.min(t1, v);
            if (t0 > t1) return -1;
        }
        return t0;
    }
}

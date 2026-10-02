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

    /**
     * What stops a flight or a line: the game's own raycast ({@code COLLIDER}, fluids ignored — LivingEntity.canSee,
     * the projectiles' block hit) and its water test (Entity.updateMovementInFluid over a box). Pure callers and tests
     * pass a box world; the client passes its world.
     */
    public interface Terrain {
        /** Fraction 0..1 along from→to of the first collider hit, -1 when none. */
        double firstHit(double[] from, double[] to);

        /** The box [lo, hi] touches water as the game reads it (fluid top at or above the box bottom). */
        boolean inWater(double[] lo, double[] hi);

        Terrain OPEN = new Terrain() {
            public double firstHit(double[] from, double[] to) {
                return -1;
            }

            public boolean inWater(double[] lo, double[] hi) {
                return false;
            }
        };
    }

    /** A projectile's motion model, as the game ticks it; {@code waterDrag} replaces the drag while it touches water. */
    public record Motion(double accel, double drag, double gravity, boolean dragFirst, double waterDrag) {
        public Motion(double accel, double drag, double gravity, boolean dragFirst) {
            this(accel, drag, gravity, dragFirst, drag);
        }

        /** A fireball / blaze charge / wind charge: accelerated along its heading, dragged, no gravity. */
        public static Motion explosive(double accel, double drag, double waterDrag) {
            return new Motion(accel, drag, 0.0, true, waterDrag);
        }

        public static Motion explosive(double accel, double drag) {
            return explosive(accel, drag, drag);
        }

        /** An arrow / trident: water drag before the move, the move, air drag after it (dry only), then gravity. */
        public static Motion persistent(double drag, double gravity, double waterDrag) {
            return new Motion(0.0, drag, gravity, false, waterDrag);
        }

        public static Motion persistent(double drag, double gravity) {
            return persistent(drag, gravity, drag);
        }
    }

    /**
     * One game tick of a projectile whose box centre is {@code p} (half size {@code half}), in place: the water read
     * at the tick's start (the game's flag is the last move's), the drag the game applies, the move. Returns the
     * fraction of this move where its position (the box bottom centre) first hits a collider, or -1.
     */
    static double step(double[] p, double[] v, Motion m, Terrain t, double half) {
        boolean wet = t.inWater(new double[]{p[0] - half, p[1] - half, p[2] - half},
            new double[]{p[0] + half, p[1] + half, p[2] + half});
        if (m.dragFirst()) {
            double n = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
            if (n > 1e-9 && m.accel() != 0) {
                for (int i = 0; i < 3; i++) v[i] += v[i] / n * m.accel();
            }
            double d = wet ? m.waterDrag() : m.drag();
            for (int i = 0; i < 3; i++) v[i] *= d;
        } else if (wet) {
            for (int i = 0; i < 3; i++) v[i] *= m.waterDrag();
        }
        double fb = t.firstHit(new double[]{p[0], p[1] - half, p[2]},
            new double[]{p[0] + v[0], p[1] - half + v[1], p[2] + v[2]});
        for (int i = 0; i < 3; i++) p[i] += v[i];
        if (!m.dragFirst()) {
            if (!wet) {
                for (int i = 0; i < 3; i++) v[i] *= m.drag();
            }
            v[1] -= m.gravity();
        }
        return fb;
    }

    /**
     * The first tick (1..maxTicks) the projectile at {@code pos} moving {@code vel} (blocks/tick) enters the box
     * [{@code lo}, {@code hi}] grown by {@code radius}, or null when it does not within {@code maxTicks} — or a
     * collider stops it first ({@code t}: the game's block hit; the body is struck when it comes first on the move).
     */
    public static Hit projectile(double[] pos, double[] vel, Motion m, double[] lo, double[] hi, double radius,
                                 int maxTicks, Terrain t) {
        double[] p = pos.clone(), v = vel.clone();
        double[] a = {lo[0] - radius, lo[1] - radius, lo[2] - radius};
        double[] b = {hi[0] + radius, hi[1] + radius, hi[2] + radius};
        if (inside(p[0], p[1], p[2], a, b)) return new Hit(0, p[0], p[1], p[2]);
        for (int tick = 1; tick <= maxTicks; tick++) {
            double[] from = p.clone();
            double fb = step(p, v, m, t, radius);
            double f = segmentEnters(from[0], from[1], from[2], p[0], p[1], p[2], a, b);
            if (f >= 0 && (fb < 0 || f <= fb)) {
                return new Hit(tick, from[0] + (p[0] - from[0]) * f, from[1] + (p[1] - from[1]) * f,
                    from[2] + (p[2] - from[2]) * f);
            }
            if (fb >= 0) return null;
        }
        return null;
    }

    public static Hit projectile(double[] pos, double[] vel, Motion m, double[] lo, double[] hi, double radius,
                                 int maxTicks) {
        return projectile(pos, vel, m, lo, hi, radius, maxTicks, Terrain.OPEN);
    }

    /** A drawn shot: the draw left ({@code full} − {@code pulled}, never below 0), then the flight. */
    public static int drawn(int pulled, int full, int flightTicks) {
        return Math.max(0, full - pulled) + flightTicks;
    }

    /** {position, velocity} after {@code n} ticks in open air, stepped as {@link #projectile} steps them. */
    public static double[][] advance(double[] pos, double[] vel, Motion m, int n) {
        double[] p = pos.clone(), v = vel.clone();
        for (int t = 0; t < n; t++) step(p, v, m, Terrain.OPEN, 0);
        return new double[][]{p, v};
    }

    /** Positions after each of the next {@code n} ticks in open air, stepped as {@link #projectile} steps them. */
    public static double[][] path(double[] pos, double[] vel, Motion m, int n) {
        double[][] out = new double[n][];
        double[] p = pos.clone(), v = vel.clone();
        for (int t = 0; t < n; t++) {
            step(p, v, m, Terrain.OPEN, 0);
            out[t] = p.clone();
        }
        return out;
    }

    /**
     * The share of the box [lo, hi] an explosion at {@code centre} reaches: ExplosionImpl.calculateReceivedDamage —
     * a grid of points over the box (step 1/(size·2+1) per axis, x and z shifted to centre the grid), each a ray to
     * the centre; the misses over the points.
     */
    public static double exposure(double[] centre, double[] lo, double[] hi, Terrain t) {
        double d = 1.0 / ((hi[0] - lo[0]) * 2 + 1), e = 1.0 / ((hi[1] - lo[1]) * 2 + 1);
        double f = 1.0 / ((hi[2] - lo[2]) * 2 + 1);
        double g = (1.0 - Math.floor(1.0 / d) * d) / 2, h = (1.0 - Math.floor(1.0 / f) * f) / 2;
        if (d < 0 || e < 0 || f < 0) return 0;
        int miss = 0, all = 0;
        for (double k = 0; k <= 1; k += d) {
            for (double l = 0; l <= 1; l += e) {
                for (double n = 0; n <= 1; n += f) {
                    double[] at = {lo[0] + (hi[0] - lo[0]) * k + g, lo[1] + (hi[1] - lo[1]) * l,
                        lo[2] + (hi[2] - lo[2]) * n + h};
                    if (t.firstHit(at, centre) < 0) miss++;
                    all++;
                }
            }
        }
        return all == 0 ? 0 : (double) miss / all;
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

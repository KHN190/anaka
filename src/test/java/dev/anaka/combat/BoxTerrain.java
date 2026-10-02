package dev.anaka.combat;

import java.util.List;

/** Test terrain: collider boxes (the shapes the game's COLLIDER raycast meets) and water boxes. */
record BoxTerrain(List<double[]> colliders, List<double[]> water) implements Impact.Terrain {
    /** A box {x0, y0, z0, x1, y1, z1}. */
    static double[] box(double x0, double y0, double z0, double x1, double y1, double z1) {
        return new double[]{x0, y0, z0, x1, y1, z1};
    }

    static BoxTerrain of(double[]... colliders) {
        return new BoxTerrain(List.of(colliders), List.of());
    }

    public double firstHit(double[] from, double[] to) {
        double best = -1;
        for (double[] b : colliders) {
            double f = Impact.segmentEnters(from[0], from[1], from[2], to[0], to[1], to[2],
                new double[]{b[0], b[1], b[2]}, new double[]{b[3], b[4], b[5]});
            if (f >= 0 && (best < 0 || f < best)) best = f;
        }
        return best;
    }

    public boolean inWater(double[] lo, double[] hi) {
        for (double[] b : water) {
            if (lo[0] <= b[3] && hi[0] >= b[0] && lo[1] <= b[4] && hi[1] >= b[1] && lo[2] <= b[5] && hi[2] >= b[2]) {
                return true;
            }
        }
        return false;
    }
}

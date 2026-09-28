package dev.anaka.combat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Gaze: a walk's look is pitched off an enderman's eye line; yaw 0 faces +z. */
class GazeTest {
    static final double[] EYE = {0, 1.62, 0};

    @Test
    void staringTable() {
        double[] ahead = {0, 2.55, 10};                         // an enderman's eyes 10 blocks ahead
        assertTrue(Gaze.staring(EYE, 0f, (float) -Math.toDegrees(Math.atan2(0.93, 10)), ahead), "looking right at it");
        assertFalse(Gaze.staring(EYE, 0f, 30f, ahead), "must fail: looking at the ground ahead is no stare");
        assertFalse(Gaze.staring(EYE, 90f, 0f, ahead), "looking away");
        assertFalse(Gaze.staring(EYE, 0f, 0f, new double[]{0, 2.55, 70}), "past 64: no stare");
    }

    @Test
    void safePitchTable() {
        double[] ahead = {0, 2.55, 10};
        float stare = (float) -Math.toDegrees(Math.atan2(0.93, 10));
        float got = Gaze.safePitch(EYE, 0f, stare, List.of(ahead));
        assertFalse(Gaze.staring(EYE, 0f, got, ahead), "must fail: still on its eyes");
        assertEquals(20f, Gaze.safePitch(EYE, 90f, 20f, List.of(ahead)), "no stare: the pitch is kept");
        assertEquals(10f, Gaze.safePitch(EYE, 0f, 10f, List.of()), "no enderman: kept");
    }
}

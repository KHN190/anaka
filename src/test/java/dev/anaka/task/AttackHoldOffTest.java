package dev.anaka.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** AttackTask.holdOff: no footwork closes into its reach before the swing is READY. */
class AttackHoldOffTest {
    @Test
    void table() {
        double reach = 3.0;
        Object[][] rows = {
            // (situation, footwork, cooldown, distance) → held off
            {"must fail: keepoff, swing refilling, just past reach: held off (closing in lit the creeper)", "keepoff",
                0.3f, reach + 0.5, true},
            {"strafe, refilling, in reach: held off", "strafe", 0.3f, reach - 0.5, true},
            {"keepoff, swing ready: in for the hit", "keepoff", AttackTask.READY, reach, false},
            {"keepoff, refilling, far past reach: the walk in goes on", "keepoff", 0.3f,
                reach + AttackTask.BACK_OFF + 0.1, false},
            {"no footwork: never held", null, 0.3f, reach, false},
        };
        for (Object[] r : rows) {
            assertEquals(r[4], AttackTask.holdOff((String) r[1], (float) r[2], (double) r[3], reach), (String) r[0]);
        }
    }
}

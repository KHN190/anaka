package dev.anaka.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Gaze.on (K1's list): the gaze reflex runs only when Python's policy has it on and a task walks. */
class GazeOnTest {
    @Test
    void table() {
        Reflex.Policy on = new Reflex.Policy(false, false, false, true, true);
        Reflex.Policy off = new Reflex.Policy(false, false, false, true, false);
        Object[][] rows = {
            {"on, walking: runs", on, true, true},
            {"must fail: off, walking: does not run (Python turned it off)", off, true, false},
            {"on, not walking: does not run", on, false, false},
            {"the default policy: off", Reflex.Policy.OFF, true, false},
        };
        for (Object[] r : rows) {
            assertEquals(r[3], Gaze.on((Reflex.Policy) r[1], (boolean) r[2]), (String) r[0]);
        }
    }
}

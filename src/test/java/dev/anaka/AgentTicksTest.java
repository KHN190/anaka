package dev.anaka;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Agent.ticks (S6): the player's takeover stops the task and every reflex — they all run inside the one gate. */
class AgentTicksTest {
    @Test
    void table() {
        Object[][] rows = {
            {"the agent holds the body: its tick runs", true, true},
            {"must fail: the player took over: nothing runs (reflexes included)", false, false},
        };
        for (Object[] r : rows) assertEquals(r[2], Agent.ticks((boolean) r[1]), (String) r[0]);
    }

    @Test
    void everyReflexIsBehindTheGate() throws IOException {
        String src = Files.readString(Path.of("src/main/java/dev/anaka/Agent.java"));
        int gate = src.indexOf("if (!ticks(controlling)) return;");
        assertTrue(gate > 0, "the end tick opens with the gate");
        for (String reflex : new String[]{"LavaGuard.tick", "WaterClutch.tick", "AutoEat.tick", "Gaze.avoid",
            "ReflexRunner.tick"}) {
            int at = src.indexOf(reflex);
            assertTrue(at > gate, "must fail: " + reflex + " runs outside the takeover gate");
        }
    }
}

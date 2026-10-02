package dev.anaka.task;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** ApproachTask (E1, E3): a walk, else a failure handed back — never a dig or a build of its own. */
class ApproachTaskTest {
    @Test
    void table() {
        Object[][] rows = {
            // (situation, the walk's end) → the approach's end
            {"walked there: arrived", Task.Status.SUCCEEDED, Task.Status.SUCCEEDED},
            {"must fail: the walk found no way: failed, handed back (no travel that digs)", Task.Status.FAILED,
                Task.Status.FAILED},
            {"the walk cancelled: failed", Task.Status.CANCELLED, Task.Status.FAILED},
        };
        for (Object[] r : rows) assertEquals(r[2], ApproachTask.outcome((Task.Status) r[1]), (String) r[0]);
    }

    @Test
    void noWayOfItsOwn() throws IOException {
        String src = Files.readString(Path.of("src/main/java/dev/anaka/task/ApproachTask.java"));
        assertFalse(src.contains("TravelTask"), "must fail: the approach starts a travel that digs and places");
    }
}

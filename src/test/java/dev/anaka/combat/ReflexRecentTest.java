package dev.anaka.combat;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The reflex's recent acts (GET /reflex "recent"): each change of act, the newest KEEP. */
class ReflexRecentTest {
    static List<String> whats(Deque<ReflexRunner.Last> ring) {
        return ring.stream().map(a -> a.what() + a.id()).toList();
    }

    @Test
    void noteTable() {
        Deque<ReflexRunner.Last> ring = new ArrayDeque<>();
        ReflexRunner.note(ring, new ReflexRunner.Last("shield", 5, 1), 3);
        ReflexRunner.note(ring, new ReflexRunner.Last("shield", 5, 2), 3);
        assertEquals(List.of("shield5"), whats(ring), "a held shield is one entry");
        ReflexRunner.note(ring, new ReflexRunner.Last("deflect", 7, 3), 3);
        ReflexRunner.note(ring, new ReflexRunner.Last("shield", -1, 4), 3);
        assertEquals(List.of("shield5", "deflect7", "shield-1"), whats(ring), "each change noted, in order");
        ReflexRunner.note(ring, new ReflexRunner.Last("attack", 9, 5), 3);
        assertEquals(List.of("deflect7", "shield-1", "attack9"), whats(ring), "must fail: past keep, the oldest kept");
    }
}

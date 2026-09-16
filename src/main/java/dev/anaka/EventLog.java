package dev.anaka;

import java.util.ArrayDeque;
import java.util.Deque;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Changes worth waking Python for, numbered and held until it asks.
 *
 * Polling costs what it saves. A GET /state round trip measures ~98 ms on this machine, which is a quarter of the
 * shortest attack window the tapes recorded (0.4 s); polling at 5 Hz also means a change is on average 100 ms old
 * before anyone sees it. So instead the client records a change the tick it happens and hands it over the moment a
 * request is waiting — the request blocks on an HTTP worker thread, never on the client thread.
 *
 * Perception only. What an event means, and what to do about it, is Python's business: this class knows about
 * "the dragon's phase changed from 3 to 6" and nothing whatsoever about windows, bombs or tunnels.
 */
public final class EventLog {
    /** Enough to cover a Python round trip many times over; older events are dropped, and `seq` says so. */
    private static final int CAPACITY = 256;
    private static final Object LOCK = new Object();
    private static final Deque<JsonObject> EVENTS = new ArrayDeque<>();
    private static long nextSeq = 1;

    private EventLog() {}

    /** Record a change. Called from the server tick; never blocks. */
    public static void record(String type, JsonObject data) {
        synchronized (LOCK) {
            JsonObject e = data == null ? new JsonObject() : data.deepCopy();
            e.addProperty("seq", nextSeq++);
            e.addProperty("type", type);
            if (EVENTS.size() >= CAPACITY) {
                EVENTS.pollFirst();
            }
            EVENTS.addLast(e);
            LOCK.notifyAll();
        }
    }

    /**
     * Events newer than `since`, waiting up to `timeoutMs` for one to arrive.
     *
     * Returns immediately when something is already pending, so a caller that keeps up never waits, and a caller
     * that has nothing to do parks here instead of asking again every 200 ms. An empty answer after the timeout is a
     * normal keep-alive, not an error.
     */
    public static JsonObject since(long since, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + Math.max(0, timeoutMs);
        synchronized (LOCK) {
            JsonArray out = collect(since);
            while (out.isEmpty()) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    break;
                }
                LOCK.wait(left);
                out = collect(since);
            }
            JsonObject o = new JsonObject();
            o.add("events", out);
            o.addProperty("latest", nextSeq - 1);
            // The oldest sequence still held: a caller whose `since` is older than this has missed events and should
            // re-read the world rather than assume nothing happened.
            o.addProperty("oldest", EVENTS.isEmpty() ? nextSeq : EVENTS.peekFirst().get("seq").getAsLong());
            return o;
        }
    }

    private static JsonArray collect(long since) {
        JsonArray out = new JsonArray();
        for (JsonObject e : EVENTS) {
            if (e.get("seq").getAsLong() > since) {
                out.add(e);
            }
        }
        return out;
    }
}

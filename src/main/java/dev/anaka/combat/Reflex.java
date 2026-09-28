package dev.anaka.combat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The combat reflex: one decision a tick, a fallback under Python's positioning. Pure ({@link #decide}) over what
 * {@link Threats} read; the client side is in {@link ReflexRunner}. Policy is Python's (POST /reflex), off by default.
 * <p>
 * Order: deflect a fireball in its last ticks &gt; shield the soonest hit &gt; counter-attack. Shield before attack:
 * a swing lowers the shield (vanilla), so none is taken while a hit is due.
 * <p>
 * Timing. A raised shield blocks only after {@link #SHIELD_DELAY} ticks of use (vanilla shield: blocks_attacks
 * block_delay_seconds 0.25 = 5 ticks), and the use reaches the server one tick after the client presses it. So the
 * shield goes up {@link #LEAD} = 6 ticks before the predicted impact — the least that still blocks it — and stays up
 * {@link #AFTER} = 6 ticks past it (the prediction's error in either direction is the same size), facing that hit.
 */
public final class Reflex {
    private Reflex() {}

    public static final int SHIELD_DELAY = 5;
    public static final int LEAD = SHIELD_DELAY + 1;
    public static final int AFTER = LEAD;
    /** A fireball is punched back in its last ticks: close enough to be in reach, not yet in the body. */
    public static final int DEFLECT_TICKS = 3;
    /** A counter-attack while a hit is coming stays inside the shield's arc: at most this far off the soonest hit. */
    public static final double ARC_DEG = 60.0;
    public static final float READY = 0.95f;          // attack cooldown full (the game's own swing is at 1.0)

    /** Python's policy: what the reflex may do. Off: nothing at all. */
    public record Policy(boolean shield, boolean counter, boolean deflect, boolean creeperFirst) {
        public static final Policy OFF = new Policy(false, false, false, true);

        public boolean off() {
            return !shield && !counter && !deflect;
        }
    }

    /**
     * One thing near the body. {@code tti}: ticks until its hit lands (-1: none coming); {@code x,y,z}: where to face
     * it (the projectile, the mob); {@code dx,dz}: its direction from the body; {@code inReach}: we can hit it now.
     */
    public record Contact(int id, String kind, int tti, double x, double y, double z, double dx, double dz,
                          boolean inReach, boolean hostile, boolean deflectable, boolean creeper, float health,
                          double dist) {}

    /** The body now: a shield in the offhand and free to raise, the attack cooldown 0..1, a hold still running. */
    public record Body(boolean canShield, float cooldown, int holdLeft, double holdX, double holdY, double holdZ) {}

    /** What to do this tick: {@code what} none | shield | deflect | attack; the entity, the point to face, and for a
     * new shield window how long it holds. */
    public record Act(String what, int id, double x, double y, double z, int hold) {
        static final Act NONE = new Act("none", -1, 0, 0, 0, 0);
    }

    public static Act decide(List<Contact> contacts, Policy pol, Body body) {
        if (pol.off()) return Act.NONE;
        Contact soonest = contacts.stream().filter(c -> c.tti() >= 0)
            .min(Comparator.comparingInt(Contact::tti)).orElse(null);
        if (pol.deflect()) {
            for (Contact c : contacts) {
                if (c.deflectable() && c.inReach() && c.tti() >= 0 && c.tti() <= DEFLECT_TICKS) {
                    return new Act("deflect", c.id(), c.x(), c.y(), c.z(), 0);
                }
            }
        }
        if (pol.shield() && body.canShield()) {
            if (soonest != null && soonest.tti() <= LEAD) {
                return new Act("shield", soonest.id(), soonest.x(), soonest.y(), soonest.z(), soonest.tti() + AFTER);
            }
            if (body.holdLeft() > 0) {
                return new Act("shield", -1, body.holdX(), body.holdY(), body.holdZ(), 0);
            }
        }
        if (pol.counter() && body.cooldown() >= READY) {
            List<Contact> targets = new ArrayList<>();
            for (Contact c : contacts) {
                if (!c.hostile() || !c.inReach()) continue;
                if (soonest != null && soonest.id() != c.id() && angle(soonest, c) > ARC_DEG) continue;
                targets.add(c);
            }
            Comparator<Contact> order = Comparator.comparing((Contact c) -> pol.creeperFirst() && c.creeper() ? 0 : 1)
                .thenComparingDouble(Contact::health).thenComparingDouble(Contact::dist);
            Contact t = targets.stream().min(order).orElse(null);
            if (t != null) return new Act("attack", t.id(), t.x(), t.y(), t.z(), 0);
        }
        return Act.NONE;
    }

    /** Degrees between two contacts' directions from the body (horizontal). */
    static double angle(Contact a, Contact b) {
        double na = Math.hypot(a.dx(), a.dz()), nb = Math.hypot(b.dx(), b.dz());
        if (na < 1e-6 || nb < 1e-6) return 0;
        double cos = (a.dx() * b.dx() + a.dz() * b.dz()) / (na * nb);
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cos))));
    }
}

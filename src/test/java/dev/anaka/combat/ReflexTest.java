package dev.anaka.combat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Reflex.decide: deflect &gt; shield &gt; counter, the soonest hit faced, counters inside the shield's arc. */
class ReflexTest {
    static final Reflex.Policy ALL = new Reflex.Policy(true, true, true, true);
    static final Reflex.Body READY = new Reflex.Body(true, 1f, 0, 0, 0, 0);

    static Reflex.Contact zombie(int id, int tti, double dx, double dz, boolean inReach, float hp) {
        return new Reflex.Contact(id, "melee", tti, dx, 1, dz, dx, dz, inReach, true, false, false, hp,
            Math.hypot(dx, dz));
    }

    static Reflex.Contact creeper(int id, double dx, double dz) {
        return new Reflex.Contact(id, "blast", -1, dx, 1, dz, dx, dz, true, true, false, true, 20f, Math.hypot(dx, dz));
    }

    static Reflex.Contact arrow(int id, int tti, double dx, double dz) {
        return new Reflex.Contact(id, "projectile", tti, dx, 1.5, dz, dx, dz, false, false, false, false, 0f,
            Math.hypot(dx, dz));
    }

    static Reflex.Contact fireball(int id, int tti, boolean inReach) {
        return new Reflex.Contact(id, "fireball", tti, 0, 1.5, -2, 0, -2, inReach, false, true, false, 0f, 2);
    }

    static String act(List<Reflex.Contact> cs, Reflex.Policy p, Reflex.Body b) {
        Reflex.Act a = Reflex.decide(cs, p, b);
        return a.what() + ":" + a.id();
    }

    @Test
    void table() {
        Reflex.Body noShield = new Reflex.Body(false, 1f, 0, 0, 0, 0);
        Reflex.Body cooling = new Reflex.Body(true, 0.4f, 0, 0, 0, 0);
        Reflex.Body holding = new Reflex.Body(true, 1f, 4, 0, 1, -3);
        Object[][] rows = {
            // (situation, contacts, policy, body) → act
            {"nothing near: nothing", List.of(), ALL, READY, "none:-1"},
            {"policy off: nothing, whatever comes", List.of(arrow(1, 3, 0, -8)), Reflex.Policy.OFF, READY, "none:-1"},
            {"an arrow in 3 ticks: shield", List.of(arrow(1, 3, 0, -8)), ALL, READY, "shield:1"},
            {"an arrow in 6 ticks (the lead): shield", List.of(arrow(1, Reflex.LEAD, 0, -8)), ALL, READY, "shield:1"},
            {"must fail: an arrow in 7 ticks: not yet (up too soon is a lowered sword)",
                List.of(arrow(1, Reflex.LEAD + 1, 0, -8)), ALL, READY, "none:-1"},
            {"shield beats a ready counter", List.of(zombie(2, 9, 0, -2, true, 20f), arrow(1, 2, 0, -8)), ALL, READY,
                "shield:1"},
            {"the soonest of two hits is faced", List.of(arrow(1, 5, 0, -8), arrow(3, 2, 8, 0)), ALL, READY, "shield:3"},
            {"no shield in hand: the counter instead", List.of(zombie(2, 0, 0, -2, true, 20f)), ALL, noShield,
                "attack:2"},
            {"must fail: the cooldown not full: no swing", List.of(zombie(2, 9, 0, -2, true, 20f)), ALL, cooling,
                "none:-1"},
            {"a hold still running keeps the shield up", List.of(), ALL, holding, "shield:-1"},
            {"a fireball in its last ticks is punched, before any shield", List.of(fireball(5, 2, true)), ALL, READY,
                "deflect:5"},
            {"must fail: out of reach it is shielded, not punched", List.of(fireball(5, 2, false)), ALL, READY,
                "shield:5"},
            {"creeper first", List.of(zombie(2, 20, 0, -2, true, 4f), creeper(7, 1, -2)), ALL, READY, "attack:7"},
            {"then the fastest kill (least health)",
                List.of(zombie(2, 20, 0, -2, true, 12f), zombie(4, 20, 1, -2, true, 3f)),
                new Reflex.Policy(true, true, true, false), READY, "attack:4"},
            {"must fail: a target outside the arc of the coming hit is left alone",
                List.of(zombie(2, 40, 0, -2, true, 3f), arrow(1, 30, 0, 8)), ALL, READY, "none:-1"},
            {"inside the arc it is hit", List.of(zombie(2, 40, 0.5, 2, true, 3f), arrow(1, 30, 0, 8)), ALL, READY,
                "attack:2"},
            {"3 zombies in their range, cooldown full: swing (the least health)",
                List.of(zombie(2, 0, 0, -2, true, 12f), zombie(4, 0, 2, 0, true, 5f), zombie(6, 0, -2, 0, true, 20f)),
                ALL, READY, "attack:4"},
            {"must fail: the same, cooldown 0.4: no swing, shield the nearest",
                List.of(zombie(2, 0, 0, -1.5, true, 12f), zombie(4, 0, 2, 0, true, 5f), zombie(6, 0, -2, 0, true, 20f)),
                ALL, cooling, "shield:2"},
            {"an arrow in 3 ticks beside a zombie, cooldown full: shield the arrow",
                List.of(zombie(2, 0, 0, -2, true, 12f), arrow(1, 3, 0, 8)), ALL, READY, "shield:1"},
            {"counter off: no swing", List.of(zombie(2, 20, 0, -2, true, 3f)),
                new Reflex.Policy(true, false, true, true), READY, "none:-1"},
        };
        for (Object[] r : rows) {
            @SuppressWarnings("unchecked") List<Reflex.Contact> cs = (List<Reflex.Contact>) r[1];
            assertEquals(r[4], act(cs, (Reflex.Policy) r[2], (Reflex.Body) r[3]), (String) r[0]);
        }
    }

    @Test
    void aNewShieldHoldsPastTheImpact() {
        Reflex.Act a = Reflex.decide(List.of(arrow(1, 4, 0, -8)), ALL, READY);
        assertEquals(4 + Reflex.AFTER, a.hold());
    }
}

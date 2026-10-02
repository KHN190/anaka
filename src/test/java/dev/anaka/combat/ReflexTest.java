package dev.anaka.combat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Reflex.decide: deflect &gt; shield &gt; counter, the soonest hit faced, counters inside the shield's arc. */
class ReflexTest {
    static final Reflex.Policy ALL = new Reflex.Policy(true, true, true, true);
    static final Reflex.Body READY = new Reflex.Body(true, 1f, 0, -1);

    static Reflex.Contact zombie(int id, int tti, double dx, double dz, boolean inReach, float hp) {
        return new Reflex.Contact(id, "melee", tti, dx, 1, dz, dx, dz, inReach, true, false, false, hp,
            Math.hypot(dx, dz));
    }

    static Reflex.Contact neutral(int id, double dx, double dz) {         // an enderman not provoked: tti 0, not hostile
        return new Reflex.Contact(id, "melee", 0, dx, 1, dz, dx, dz, true, false, false, false, 40f, Math.hypot(dx, dz));
    }

    static Reflex.Contact creeper(int id, double dx, double dz) {        // walking: a melee mob
        return new Reflex.Contact(id, "melee", -1, dx, 1, dz, dx, dz, true, true, false, true, 20f, Math.hypot(dx, dz));
    }

    static Reflex.Contact litCreeper(int id, int fuse, double dx, double dz) {
        return new Reflex.Contact(id, "blast", fuse, dx, 1, dz, dx, dz, true, true, false, true, 20f, Math.hypot(dx, dz));
    }

    static Reflex.Contact skeleton(int id, String kind, int tti, double dx, double dz) {     // drawing its bow
        return new Reflex.Contact(id, kind, tti, dx, 1, dz, dx, dz, false, true, false, false, 20f, Math.hypot(dx, dz));
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
        Reflex.Body noShield = new Reflex.Body(false, 1f, 0, -1);
        Reflex.Body cooling = new Reflex.Body(true, 0.4f, 0, -1);
        Reflex.Body holding = new Reflex.Body(true, 1f, 4, 1);
        Object[][] rows = {
            // (situation, contacts, policy, body) → act
            {"nothing near: nothing", List.of(), ALL, READY, "none:-1"},
            {"policy off: nothing, whatever comes", List.of(arrow(1, 3, 0, -8)), Reflex.Policy.OFF, READY, "none:-1"},
            {"an arrow in 3 ticks: shield", List.of(arrow(1, 3, 0, -8)), ALL, READY, "shield:1"},
            {"an arrow in 6 ticks (the lead): shield", List.of(arrow(1, Reflex.LEAD, 0, -8)), ALL, READY, "shield:1"},
            {"a drawing skeleton, its arrow due in the lead (draw left + flight): shield before the arrow exists",
                List.of(skeleton(9, "draw", Reflex.LEAD, 0, -13)), ALL, READY, "shield:9"},
            {"must fail: the same draw labelled melee (Threats before the fix): no shield until the arrow is seen",
                List.of(skeleton(9, "melee", Reflex.LEAD, 0, -13)), ALL, READY, "none:-1"},
            {"must fail: an archer guarded and seen, nothing due yet: the shield up toward it, no swing",
                List.of(skeleton(9, "melee", -1, 0, -13), zombie(2, 20, 0, -2, true, 3f)), ALL,
                new Reflex.Body(true, 1f, 0, -1, 9), "shield:9"},
            {"the same archer not seen (guard -1): nothing held for it",
                List.of(skeleton(9, "melee", -1, 0, -13)), ALL, READY, "none:-1"},
            {"must fail: an arrow in 7 ticks: not yet (up too soon is a lowered sword)",
                List.of(arrow(1, Reflex.LEAD + 1, 0, -8)), ALL, READY, "none:-1"},
            {"shield beats a ready counter", List.of(zombie(2, 9, 0, -2, true, 20f), arrow(1, 2, 0, -8)), ALL, READY,
                "shield:1"},
            {"the soonest of two hits is faced", List.of(arrow(1, 5, 0, -8), arrow(3, 2, 8, 0)), ALL, READY, "shield:3"},
            {"no shield in hand: the counter instead", List.of(zombie(2, 0, 0, -2, true, 20f)), ALL, noShield,
                "attack:2"},
            {"must fail: the cooldown not full: no swing", List.of(zombie(2, 9, 0, -2, true, 20f)), ALL, cooling,
                "none:-1"},
            {"a hold still running keeps the shield up while its arrow still comes", List.of(arrow(1, 9, 0, -8)),
                ALL, holding, "shield:1"},
            {"must fail: a hold whose hit is gone (the bow lost its line): dropped", List.of(arrow(1, -1, 0, -8)),
                ALL, holding, "none:-1"},
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
            {"must fail: a lit creeper in reach, cooldown full: never struck while it hisses",
                List.of(litCreeper(7, 20, 1, -2)), ALL, READY, "none:-1"},
            {"a lit creeper beside a zombie: the zombie is struck, not the fuse",
                List.of(litCreeper(7, 20, 1, -2), zombie(2, 40, 0.5, -2, true, 12f)), ALL, READY, "attack:2"},
            {"must fail: counter off (not engaged), a zombie in its range, cooldown refilling: no between-swings shield",
                List.of(zombie(2, 0, 0, -1.5, true, 12f)), new Reflex.Policy(true, false, true, true), cooling,
                "none:-1"},
            {"a mob not attacking (neutral) in range, cooldown refilling: no shield, no swing",
                List.of(neutral(8, 0, -1.5)), ALL, cooling, "none:-1"},
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

    @Test
    void theFeetKeepTheirWay() {
        // (forward, back, left, right, look before, look after) → the keys that walk the same way
        Object[][] rows = {
            {new boolean[]{true, false, false, false}, 0f, 0f, new boolean[]{true, false, false, false}},
            {new boolean[]{true, false, false, false}, 0f, 180f, new boolean[]{false, true, false, false}},
            // yaw 0 faces +z (south); turned to 90 (west), south is on the left
            {new boolean[]{true, false, false, false}, 0f, 90f, new boolean[]{false, false, true, false}},
            {new boolean[]{true, false, false, false}, 0f, -90f, new boolean[]{false, false, false, true}},
            {new boolean[]{false, false, false, false}, 0f, 90f, new boolean[]{false, false, false, false}},
        };
        for (Object[] r : rows) {
            boolean[] k = (boolean[]) r[0];
            boolean[] got = Reflex.remap(k[0], k[1], k[2], k[3], (float) r[1], (float) r[2]);
            assertEquals(java.util.Arrays.toString((boolean[]) r[3]), java.util.Arrays.toString(got),
                "must fail when the look steers the feet: " + r[1] + "→" + r[2]);
        }
    }
}

package dev.anaka.combat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The combat reflex: one decision a tick, a fallback under Python's positioning. Pure ({@link #decide}) over what
 * {@link Threats} read; the client side is in {@link ReflexRunner}. Policy is Python's (POST /reflex), off by default.
 * <p>
 * Order ({@link #decide}): deflect &gt; shield an exact hit due &gt; swing at full cooldown &gt; shield a melee mob
 * between swings. Only look and the use/attack keys: the task keeps the feet.
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
    /** Ticks the client's copy of a projectile trails the server's: reach is judged this far ahead too. */
    public static final int LAG_TICKS = 2;
    /** A counter-attack while a hit is coming stays inside the shield's arc: at most this far off the soonest hit. */
    public static final double ARC_DEG = 60.0;
    public static final float READY = 0.95f;          // attack cooldown full (the game's own swing is at 1.0)

    /** Python's policy: what the reflex may do. Off: nothing at all. {@code gaze}: a walk's look kept off every
     * enderman's eyes (Gaze, K1's list). */
    public record Policy(boolean shield, boolean counter, boolean deflect, boolean creeperFirst, boolean gaze) {
        public static final Policy OFF = new Policy(false, false, false, true, false);

        public Policy(boolean shield, boolean counter, boolean deflect, boolean creeperFirst) {
            this(shield, counter, deflect, creeperFirst, false);
        }

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

    /** The body now: a shield in the offhand and free to raise, the attack cooldown 0..1, a hold still running
     * against {@code holdId}. */
    public record Body(boolean canShield, float cooldown, int holdLeft, int holdId) {}

    /** What to do this tick: {@code what} none | shield | deflect | attack; the entity, the point to face, and for a
     * new shield window how long it holds. */
    public record Act(String what, int id, double x, double y, double z, int hold) {
        static final Act NONE = new Act("none", -1, 0, 0, 0, 0);
    }

    /** A hit whose time is known to the tick (a projectile, a lit fuse): a melee mob's cooldown is not synced. */
    static boolean exact(Contact c) {
        return !"melee".equals(c.kind());
    }

    /**
     * Deflect a fireball in its last ticks &gt; shield an exact hit within LEAD (and its hold) &gt; swing at full
     * cooldown at a target in reach &gt; shield a melee mob in its range while the cooldown refills. The melee rhythm:
     * the swing is instant, a raised shield needs SHIELD_DELAY ticks and a swing drops it.
     */
    public static Act decide(List<Contact> contacts, Policy pol, Body body) {
        if (pol.off()) return Act.NONE;
        Contact soonest = contacts.stream().filter(c -> c.tti() >= 0 && exact(c))
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
            // a hold runs on only while its hit is still coming (a drawn bow that lost its line never fires)
            Contact src = contacts.stream().filter(c -> c.id() == body.holdId() && c.tti() >= 0).findFirst()
                .orElse(null);
            if (body.holdLeft() > 0 && src != null) {
                return new Act("shield", src.id(), src.x(), src.y(), src.z(), 0);
            }
            // a bow drawn at us in sight: up from the draw's start, held past the release (its arrow's hit + AFTER)
            Contact d = contacts.stream().filter(c -> "draw".equals(c.kind()) && c.tti() >= 0)
                .min(Comparator.comparingInt(Contact::tti)).orElse(null);
            if (d != null) return new Act("shield", d.id(), d.x(), d.y(), d.z(), d.tti() + AFTER);
        }
        if (pol.counter() && body.cooldown() >= READY) {
            List<Contact> targets = new ArrayList<>();
            for (Contact c : contacts) {
                if (!c.hostile() || !c.inReach() || "blast".equals(c.kind())) continue;   // never swing at a lit fuse
                if (soonest != null && soonest.id() != c.id() && angle(soonest, c) > ARC_DEG) continue;
                targets.add(c);
            }
            Comparator<Contact> order = Comparator.comparing((Contact c) -> pol.creeperFirst() && c.creeper() ? 0 : 1)
                .thenComparingDouble(Contact::health).thenComparingDouble(Contact::dist);
            Contact t = targets.stream().min(order).orElse(null);
            if (t != null) return new Act("attack", t.id(), t.x(), t.y(), t.z(), 0);
        }
        if (pol.shield() && pol.counter() && body.canShield()) {
            // engaged, between swings: up against the nearest hostile melee mob that can hit now (in range, seen —
            // tti 0; no hold: the next full cooldown swings)
            Contact m = contacts.stream().filter(c -> !exact(c) && c.hostile() && c.tti() == 0)
                .min(Comparator.comparingDouble(Contact::dist)).orElse(null);
            if (m != null) return new Act("shield", m.id(), m.x(), m.y(), m.z(), 0);
        }
        return Act.NONE;
    }

    /** Pure: a projectile's contact — tti on the body box, and a swing reaching its box ({@code half}) from the eye. */
    public static Contact projectile(int id, String kind, boolean deflectable, double[] pos, double[] vel,
                                     Impact.Motion m, double half, double[] lo, double[] hi, double[] eye,
                                     double reach, int maxTicks, Impact.Terrain t) {
        Impact.Hit hit = Impact.projectile(new double[]{pos[0], pos[1] + half, pos[2]}, vel, m, lo, hi, half, maxTicks,
            t);
        // now or on the next LAG_TICKS: the client sees it late (DeflectTest, two ticks behind: missed)
        boolean inReach = reaches(eye, pos, half, reach);
        for (double[] next : Impact.path(pos, vel, m, LAG_TICKS)) inReach |= reaches(eye, next, half, reach);
        return new Contact(id, kind, hit != null ? hit.ticks() : -1, pos[0], pos[1] + half, pos[2],
            pos[0] - (lo[0] + hi[0]) / 2, pos[2] - (lo[2] + hi[2]) / 2, inReach, false, deflectable, false, 0f,
            Math.sqrt(sq(pos[0] - eye[0]) + sq(pos[1] + half - eye[1]) + sq(pos[2] - eye[2])));
    }

    /** Pure: the eye within {@code reach} of a box of half size {@code half} standing at {@code feet}. */
    static boolean reaches(double[] eye, double[] feet, double half, double reach) {
        double cx = Math.max(feet[0] - half, Math.min(eye[0], feet[0] + half));
        double cy = Math.max(feet[1], Math.min(eye[1], feet[1] + 2 * half));
        double cz = Math.max(feet[2] - half, Math.min(eye[2], feet[2] + half));
        return sq(cx - eye[0]) + sq(cy - eye[1]) + sq(cz - eye[2]) <= reach * reach;
    }

    static double sq(double v) {
        return v * v;
    }

    /**
     * Pure: the move keys {forward, back, left, right} that walk the same world direction after the look turns from
     * {@code fromYaw} to {@code toYaw} (degrees): a reflex's look must not steer the task's feet.
     */
    public static boolean[] remap(boolean f, boolean b, boolean l, boolean r, float fromYaw, float toYaw) {
        double fw = (f ? 1 : 0) - (b ? 1 : 0), side = (l ? 1 : 0) - (r ? 1 : 0);
        if (fw == 0 && side == 0) return new boolean[]{false, false, false, false};
        double a = Math.toRadians(fromYaw), a2 = Math.toRadians(toYaw);
        double wx = side * Math.cos(a) - fw * Math.sin(a), wz = fw * Math.cos(a) + side * Math.sin(a);
        double f2 = -wx * Math.sin(a2) + wz * Math.cos(a2), s2 = wx * Math.cos(a2) + wz * Math.sin(a2);
        double n = Math.hypot(f2, s2), cut = Math.sin(Math.toRadians(22.5)) * n;        // 8 ways
        return new boolean[]{f2 > cut, f2 < -cut, s2 > cut, s2 < -cut};
    }

    /** Pure: {yaw, pitch} (degrees) facing {@code at} from {@code eye}, as Agent.lookAt turns. */
    public static float[] look(double[] eye, double[] at) {
        double dx = at[0] - eye[0], dy = at[1] - eye[1], dz = at[2] - eye[2];
        return new float[]{(float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0),
            (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)))};
    }

    /** Pure: the unit vector of a rotation, as the server reads it (Entity.getRotationVector): a punched fireball's
     * new heading (ProjectileDeflection.REDIRECTED sets its velocity to the attacker's). */
    public static double[] facing(float yaw, float pitch) {
        double f = Math.toRadians(pitch), g = Math.toRadians(-yaw);
        return new double[]{Math.sin(g) * Math.cos(f), -Math.sin(f), Math.cos(g) * Math.cos(f)};
    }

    /** Degrees between two contacts' directions from the body (horizontal). */
    static double angle(Contact a, Contact b) {
        double na = Math.hypot(a.dx(), a.dz()), nb = Math.hypot(b.dx(), b.dz());
        if (na < 1e-6 || nb < 1e-6) return 0;
        double cos = (a.dx() * b.dx() + a.dz() * b.dz()) / (na * nb);
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cos))));
    }
}

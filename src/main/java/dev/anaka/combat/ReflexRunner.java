package dev.anaka.combat;

import com.google.gson.JsonObject;
import dev.anaka.Agent;
import dev.anaka.util.InvUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** The reflex on the client thread: reads {@link Threats}, asks {@link Reflex#decide}, carries it out. Runs last in the
 * agent's tick (after the task), so a due hit's shield wins over what the task pressed. */
public final class ReflexRunner {
    private ReflexRunner() {}

    private static volatile Reflex.Policy policy = Reflex.Policy.OFF;
    private static int tick, holdUntil, holdId = -1;

    public static Reflex.Policy policy() {
        return policy;
    }

    private static volatile boolean guarding;

    /** A shield window is open, or a guard held: a task must not swing (a swing lowers the shield; shield > attack). */
    public static boolean shielding() {
        return holdUntil > tick || guarding;
    }

    /** POST /reflex: any of shield, counter, deflect (booleans) and priority ("creeper" first, or "fastest"). */
    public static Reflex.Policy set(JsonObject body) {
        Reflex.Policy p = policy;
        policy = new Reflex.Policy(flag(body, "shield", p.shield()), flag(body, "counter", p.counter()),
            flag(body, "deflect", p.deflect()),
            body.has("priority") ? !"fastest".equals(body.get("priority").getAsString()) : p.creeperFirst(),
            flag(body, "gaze", p.gaze()), guard(body, p.guard()));
        return policy;
    }

    public static JsonObject json() {
        Reflex.Policy p = policy;
        JsonObject o = new JsonObject();
        o.addProperty("shield", p.shield());
        o.addProperty("counter", p.counter());
        o.addProperty("deflect", p.deflect());
        o.addProperty("gaze", p.gaze());
        o.addProperty("priority", p.creeperFirst() ? "creeper" : "fastest");
        o.addProperty("shieldDelay", Reflex.SHIELD_DELAY);
        o.addProperty("lead", Reflex.LEAD);
        if (p.guard() >= 0) o.addProperty("guard", p.guard());
        com.google.gson.JsonArray ts = new com.google.gson.JsonArray();
        synchronized (TICKS) {
            for (TickNote n : TICKS) {             // the shield per tick, oldest first: in use, which hand, blocking
                JsonObject j = new JsonObject();
                j.addProperty("tick", n.tick());
                j.addProperty("using", n.using());
                j.addProperty("hand", n.hand());
                j.addProperty("blocking", n.blocking());
                j.addProperty("health", n.health());
                ts.add(j);
            }
        }
        o.add("ticks", ts);
        Last l = last;
        if (l != null) {                     // the last act taken: what, on whom, the world tick (a bench trace)
            JsonObject j = new JsonObject();
            j.addProperty("what", l.what());
            j.addProperty("id", l.id());
            j.addProperty("tick", l.tick());
            o.add("last", j);
        }
        com.google.gson.JsonArray r = new com.google.gson.JsonArray();
        synchronized (RECENT) {
            for (Last a : RECENT) {               // each change of act, oldest first: what the reflex did in a window
                JsonObject j = new JsonObject();
                j.addProperty("what", a.what());
                j.addProperty("id", a.id());
                j.addProperty("tick", a.tick());
                r.add(j);
            }
        }
        o.add("recent", r);
        return o;
    }

    /** The body's shield per tick, kept for GET /reflex (a hit is a health drop; blocking: the shield took it). */
    record TickNote(long tick, boolean using, String hand, boolean blocking, float health) {}

    static final int KEEP_TICKS = 128;
    private static final Deque<TickNote> TICKS = new ArrayDeque<>();

    /** The recent acts kept for GET /reflex. */
    static final int KEEP = 32;
    private static final Deque<Last> RECENT = new ArrayDeque<>();

    /** Pure on the ring: an act noted when it differs from the last noted (a held shield is one entry), at most keep. */
    static void note(Deque<Last> ring, Last a, int keep) {
        Last prev = ring.peekLast();
        if (prev != null && prev.what().equals(a.what()) && prev.id() == a.id()) return;
        ring.addLast(a);
        while (ring.size() > keep) ring.removeFirst();
    }

    /** The last act the reflex took (never "none"), read by GET /reflex. */
    record Last(String what, int id, long tick) {}

    private static volatile Last last;

    /** A reflex act taken: the last one and the recent ring (GET /reflex), every reflex through here (E1's event). */
    public static void acted(String what, int id, long worldTick) {
        last = new Last(what, id, worldTick);
        synchronized (RECENT) {
            note(RECENT, last, KEEP);
        }
    }

    /** "guard": an entity id to keep the shield up toward while it is seen, or null to stop guarding. */
    private static int guard(JsonObject o, int def) {
        if (!o.has("guard")) return def;
        if (o.get("guard").isJsonNull()) return -1;
        if (!o.get("guard").isJsonPrimitive() || !o.get("guard").getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("\"guard\" must be an entity id or null");
        return o.get("guard").getAsInt();
    }

    private static boolean flag(JsonObject o, String key, boolean def) {
        if (!o.has(key) || o.get(key).isJsonNull()) return def;
        if (!o.get(key).isJsonPrimitive() || !o.get(key).getAsJsonPrimitive().isBoolean())
            throw new IllegalArgumentException("\"" + key + "\" must be true or false");
        return o.get(key).getAsBoolean();
    }

    public static void tick(MinecraftClient c, Agent a) {
        tick++;
        Reflex.Policy pol = policy;
        ClientPlayerEntity p = c.player;
        if (pol.off() || p == null || c.world == null || c.interactionManager == null) {
            guarding = false;
            return;
        }
        List<Threats.Seen> seen = Threats.read(c);
        List<Reflex.Contact> contacts = new ArrayList<>(seen.size());
        for (Threats.Seen s : seen) contacts.add(s.contact());
        synchronized (TICKS) {
            TICKS.addLast(new TickNote(c.world.getTime(), p.isUsingItem(),
                p.isUsingItem() ? (p.getActiveHand() == Hand.OFF_HAND ? "off" : "main") : null, p.isBlocking(),
                p.getHealth()));
            while (TICKS.size() > KEEP_TICKS) TICKS.removeFirst();
        }
        boolean eating = p.isUsingItem() && p.getActiveHand() == Hand.MAIN_HAND;
        boolean shield = InvUtil.id(p.getOffHandStack()).equals("minecraft:shield") && !eating;
        Reflex.Act act = Reflex.decide(contacts, pol, new Reflex.Body(shield, p.getAttackCooldownProgress(0.5f),
            holdUntil - tick, holdId, guarded(c, p, pol.guard())));
        guarding = "shield".equals(act.what()) && act.id() == pol.guard();
        if (!"none".equals(act.what())) acted(act.what(), act.id(), c.world.getTime());
        switch (act.what()) {
            case "shield" -> {
                if (act.hold() > 0 && tick + act.hold() > holdUntil) {
                    holdUntil = tick + act.hold();
                    holdId = act.id();
                }
                look(p, a, new Vec3d(act.x(), act.y(), act.z()));
                a.holdUse = true;
                if (!p.isUsingItem()) c.interactionManager.interactItem(p, Hand.OFF_HAND);
            }
            case "deflect", "attack" -> {
                Entity target = c.world.getEntityById(act.id());
                if (target == null) return;
                // the swing names its target: the task's look is put back in the same tick, its feet never turned
                float yaw = p.getYaw(), pitch = p.getPitch();
                Agent.lookAt(p, new Vec3d(act.x(), act.y(), act.z()), 180f);
                boolean deflect = "deflect".equals(act.what());
                // the server redirects a punched fireball along ITS copy of our look (REDIRECTED: the attacker's
                // rotation vector); this tick's look went out before the reflex ran — send the look at it first
                if (deflect) sendLook(c, p, p.getYaw(), p.getPitch());
                if (p.isUsingItem()) c.interactionManager.stopUsingItem(p);      // a swing lowers the shield
                c.interactionManager.attackEntity(p, target);
                p.swingHand(Hand.MAIN_HAND);
                p.setYaw(yaw);
                p.setPitch(pitch);
                if (deflect) sendLook(c, p, yaw, pitch);          // the server back on the task's look
            }
            default -> { }
        }
    }

    /** The guarded mob's id while it is alive with a clear line to us (Threats.seen), else -1. */
    private static int guarded(MinecraftClient c, ClientPlayerEntity p, int id) {
        if (id < 0) return -1;
        Entity e = c.world.getEntityById(id);
        if (e == null || !e.isAlive()) return -1;
        return Threats.seen(Threats.terrain(c.world, e), new double[]{e.getX(), e.getEyeY(), e.getZ()},
            new double[]{p.getX(), p.getEyeY(), p.getZ()}) ? id : -1;
    }

    private static void sendLook(MinecraftClient c, ClientPlayerEntity p, float yaw, float pitch) {
        if (c.getNetworkHandler() == null) return;
        c.getNetworkHandler().sendPacket(new net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket.LookAndOnGround(
            yaw, pitch, p.isOnGround(), p.horizontalCollision));
    }

    /** Face `at` at once; the task's move keys re-mapped so its feet keep their world direction (a travel runs on). */
    private static void look(ClientPlayerEntity p, Agent a, Vec3d at) {
        float before = p.getYaw();
        Agent.lookAt(p, at, 180f);
        boolean[] k = Reflex.remap(a.input.forward, a.input.back, a.input.left, a.input.right, before, p.getYaw());
        a.input.forward = k[0];
        a.input.back = k[1];
        a.input.left = k[2];
        a.input.right = k[3];
        if (!k[0]) a.input.sprint = false;
    }
}

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
    private static int tick, holdUntil;
    private static double holdX, holdY, holdZ;

    public static Reflex.Policy policy() {
        return policy;
    }

    /** A shield window is open: a task must not swing (a swing lowers the shield; shield > attack). */
    public static boolean shielding() {
        return holdUntil > tick;
    }

    /** POST /reflex: any of shield, counter, deflect (booleans) and priority ("creeper" first, or "fastest"). */
    public static Reflex.Policy set(JsonObject body) {
        Reflex.Policy p = policy;
        policy = new Reflex.Policy(flag(body, "shield", p.shield()), flag(body, "counter", p.counter()),
            flag(body, "deflect", p.deflect()),
            body.has("priority") ? !"fastest".equals(body.get("priority").getAsString()) : p.creeperFirst());
        return policy;
    }

    public static JsonObject json() {
        Reflex.Policy p = policy;
        JsonObject o = new JsonObject();
        o.addProperty("shield", p.shield());
        o.addProperty("counter", p.counter());
        o.addProperty("deflect", p.deflect());
        o.addProperty("priority", p.creeperFirst() ? "creeper" : "fastest");
        o.addProperty("shieldDelay", Reflex.SHIELD_DELAY);
        o.addProperty("lead", Reflex.LEAD);
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
        if (pol.off() || p == null || c.world == null || c.interactionManager == null) return;
        List<Threats.Seen> seen = Threats.read(c);
        List<Reflex.Contact> contacts = new ArrayList<>(seen.size());
        for (Threats.Seen s : seen) contacts.add(s.contact());
        boolean eating = p.isUsingItem() && p.getActiveHand() == Hand.MAIN_HAND;
        boolean shield = InvUtil.id(p.getOffHandStack()).equals("minecraft:shield") && !eating;
        Reflex.Act act = Reflex.decide(contacts, pol, new Reflex.Body(shield, p.getAttackCooldownProgress(0.5f),
            holdUntil - tick, holdX, holdY, holdZ));
        if (!"none".equals(act.what())) {
            last = new Last(act.what(), act.id(), c.world.getTime());
            synchronized (RECENT) {
                note(RECENT, last, KEEP);
            }
        }
        switch (act.what()) {
            case "shield" -> {
                if (act.hold() > 0 && tick + act.hold() > holdUntil) {
                    holdUntil = tick + act.hold();
                    holdX = act.x();
                    holdY = act.y();
                    holdZ = act.z();
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

package dev.anaka;

import java.util.ArrayDeque;
import java.util.Deque;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.EndermanEntity;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

/**
 * A ring buffer of combat frames, sampled every SERVER tick.
 *
 * Python polls /state at about 5 Hz, far too coarse to fit dragon-breath spread, head-sweep reach or knockback, and
 * the polling costs fight time. So the game records the last 20 s of frames itself and Python pulls them in batches
 * (GET /combat/frames?since=tick) to study offline.
 *
 * Sampling is on the server tick, not the client tick, because /tick sprint runs the server flat out while the client
 * keeps rendering at its own pace: a client-side recorder silently drops most of a sprinted fight. Server-side it is
 * one frame per game tick no matter how fast the game is run, which is what makes fast data collection possible.
 *
 * Recording only. Nothing here steers anything.
 */
public final class CombatRecorder {
    /**
     * 5 minutes of game time at 20 ticks/s — a whole dragon fight.
     *
     * 20 s was enough while the game ran at wall-clock speed, but `/tick sprint` generates ticks far faster than an
     * HTTP poll can drain them: a small buffer means most of a sprinted fight is overwritten before anyone reads it,
     * and fast data collection is the entire point of sprinting.
     */
    private static final int CAPACITY = 6000;

    private static final Deque<JsonObject> FRAMES = new ArrayDeque<>(CAPACITY);
    private static final JsonArray PENDING_DAMAGE = new JsonArray();
    private static int lastTick = -1;
    private static double[] lastPlayerPos = null;
    private static int lastPhase = Integer.MIN_VALUE;

    private CombatRecorder() {}

    /** Hooked from the world tick. Only the End is recorded: nothing else produces a fight worth modelling. */
    public static synchronized void tick(ServerWorld world) {
        ServerPlayerEntity p = world.getPlayers().isEmpty() ? null : world.getPlayers().get(0);
        if (p == null) {
            return;
        }
        int tick = p.age;
        if (tick == lastTick) {
            return;
        }
        lastTick = tick;

        JsonObject f = new JsonObject();
        f.addProperty("tick", tick);
        f.addProperty("dimension", world.getRegistryKey().getValue().toString());
        f.add("player", player(p));
        f.add("dragon", dragon(world));
        f.add("breath", breath(world, p));
        f.add("endermen", endermen(world, p));
        f.add("damage", PENDING_DAMAGE.deepCopy());
        PENDING_DAMAGE.remove(PENDING_DAMAGE);   // cleared below; see clearDamage

        clearDamage();
        if (FRAMES.size() >= CAPACITY) {
            FRAMES.pollFirst();
        }
        FRAMES.addLast(f);
        lastPlayerPos = new double[] {p.getX(), p.getY(), p.getZ()};
    }

    private static void clearDamage() {
        while (!PENDING_DAMAGE.isEmpty()) {
            PENDING_DAMAGE.remove(0);
        }
    }

    /**
     * Hooked from the damage event, so the source is the game's own, not a guess from a health drop. Which source hurt
     * us is the whole point: a plan that survives the breath but not the head sweep needs the two told apart.
     */
    public static synchronized void damaged(LivingEntity entity, DamageSource source, float amount) {
        if (!(entity instanceof ServerPlayerEntity)) {
            return;
        }
        JsonObject o = new JsonObject();
        o.addProperty("amount", round(amount));
        o.addProperty("source", source.getName());
        Entity attacker = source.getAttacker();
        o.addProperty("nearest", attacker == null ? "none" : Registries.ENTITY_TYPE.getId(attacker.getType()).toString());
        PENDING_DAMAGE.add(o);
        // Pushed as well as recorded: taking a hit is the other thing worth interrupting a wait for.
        JsonObject e = o.deepCopy();
        e.addProperty("health", entity.getHealth());
        e.addProperty("tick", lastTick);
        EventLog.record("damage", e);
        // the last hit for /state: its source by the game's own name (fall, mob, onFire…), stamped with world time
        JsonObject l = o.deepCopy();
        l.addProperty("gameTime", entity.getEntityWorld().getTime());
        last = l;
    }

    private static volatile JsonObject last;

    /** The last damage the player took (source, nearest attacker, amount, gameTime), or null. */
    public static JsonObject lastDamage() {
        JsonObject l = last;
        return l == null ? null : l.deepCopy();
    }

    /** Frames newer than `since` (a tick number). `since < 0` returns everything held. */
    public static synchronized JsonObject since(int since) {
        JsonArray out = new JsonArray();
        for (JsonObject f : FRAMES) {
            if (f.get("tick").getAsInt() > since) {
                out.add(f);
            }
        }
        JsonObject o = new JsonObject();
        o.addProperty("capacity", CAPACITY);
        o.addProperty("held", FRAMES.size());
        o.addProperty("latest", lastTick);
        o.add("frames", out);
        return o;
    }

    private static JsonObject player(ServerPlayerEntity p) {
        JsonObject o = new JsonObject();
        o.add("pos", vec(p));
        // Velocity from the position delta: a knockback into the air shows up here before fallDistance does.
        JsonObject v = new JsonObject();
        v.addProperty("x", round(lastPlayerPos == null ? 0 : p.getX() - lastPlayerPos[0]));
        v.addProperty("y", round(lastPlayerPos == null ? 0 : p.getY() - lastPlayerPos[1]));
        v.addProperty("z", round(lastPlayerPos == null ? 0 : p.getZ() - lastPlayerPos[2]));
        o.add("vel", v);
        o.addProperty("hp", p.getHealth());
        o.addProperty("food", p.getHungerManager().getFoodLevel());
        o.addProperty("onGround", p.isOnGround());
        o.addProperty("yaw", p.getYaw());
        o.addProperty("pitch", p.getPitch());
        return o;
    }

    private static JsonObject dragon(ServerWorld world) {
        JsonObject o = new JsonObject();
        EnderDragonEntity d = null;
        for (Entity e : world.iterateEntities()) {
            if (e instanceof EnderDragonEntity found) {
                d = found;
                break;
            }
        }
        if (d == null) {
            o.addProperty("present", false);
            return o;
        }
        o.addProperty("present", true);
        int phase = d.getPhaseManager().getCurrent().getType().getTypeId();
        if (phase != lastPhase) {
            // A phase change is the one perception worth pushing rather than waiting to be asked for: the shortest
            // window the tapes recorded is 0.4 s, and a 5 Hz poll would spend a quarter of it just finding out.
            JsonObject e = new JsonObject();
            e.addProperty("from", lastPhase == Integer.MIN_VALUE ? -1 : lastPhase);
            e.addProperty("to", phase);
            e.addProperty("tick", lastTick);
            e.addProperty("health", d.getHealth());
            EventLog.record("dragon_phase", e);
            lastPhase = phase;
        }
        o.addProperty("phase", phase);
        o.addProperty("health", d.getHealth());
        o.add("pos", vec(d));
        // The head is what a bed blast has to reach and what the sweep hits with; its hitbox is a part, and the entity
        // position sits several blocks away from it while perched.
        o.add("head", vec(d.head));
        JsonArray parts = new JsonArray();
        for (Entity part : d.getBodyParts()) {
            parts.add(vec(part));
        }
        o.add("parts", parts);
        return o;
    }

    private static JsonArray breath(ServerWorld world, ServerPlayerEntity p) {
        JsonArray out = new JsonArray();
        for (Entity e : world.iterateEntities()) {
            String id = Registries.ENTITY_TYPE.getId(e.getType()).toString();
            if (!id.equals("minecraft:area_effect_cloud") && !id.equals("minecraft:dragon_fireball")) {
                continue;
            }
            JsonObject o = new JsonObject();
            o.addProperty("type", id);
            o.add("pos", vec(e));
            o.addProperty("distance", round(e.distanceTo(p)));
            // Width stands in for the cloud's radius: it grows as the breath spreads.
            o.addProperty("radius", round(e.getWidth() / 2.0));
            o.addProperty("age", e.age);
            out.add(o);
        }
        return out;
    }

    private static JsonArray endermen(ServerWorld world, ServerPlayerEntity p) {
        JsonArray out = new JsonArray();
        for (Entity e : world.iterateEntities()) {
            if (!(e instanceof EndermanEntity en) || en.distanceTo(p) > 32) {
                continue;
            }
            JsonObject o = new JsonObject();
            o.add("pos", vec(en));
            o.addProperty("distance", round(en.distanceTo(p)));
            o.addProperty("angry", en.isAngry());
            out.add(o);
        }
        return out;
    }

    private static JsonObject vec(Entity e) {
        JsonObject o = new JsonObject();
        o.addProperty("x", round(e.getX()));
        o.addProperty("y", round(e.getY()));
        o.addProperty("z", round(e.getZ()));
        return o;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}

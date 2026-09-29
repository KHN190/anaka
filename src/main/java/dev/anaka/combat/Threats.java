package dev.anaka.combat;

import dev.anaka.util.WorldUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.GhastEntity;
import net.minecraft.entity.mob.PillagerEntity;
import net.minecraft.item.RangedWeaponItem;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.projectile.AbstractWindChargeEntity;
import net.minecraft.entity.projectile.ExplosiveProjectileEntity;
import net.minecraft.entity.projectile.FireballEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Reads, from the client's world, every incoming hit on the body: each projectile's time to impact and impact point
 * ({@link Impact#projectile}), each hostile's melee time ({@link Impact#melee}). One reading serves the reflex and
 * /entities (tti_ticks, impact), so Python plans from the same numbers.
 */
public final class Threats {
    private Threats() {}

    public static final int MAX_TICKS = 60;          // 3 s: past that a hit is the planner's, not a reflex's
    public static final double RADIUS = 24.0;
    static final double FIREBALL_DRAG = 0.95;       // ExplosiveProjectileEntity.getDrag
    static final double WIND_DRAG = 1.0;            // AbstractWindChargeEntity.getDrag
    static final double ARROW_DRAG = 0.99;          // PersistentProjectileEntity's air drag
    static final int CREEPER_FUSE = 30;             // CreeperEntity's default fuse (ticks)

    /**
     * A projectile's last fix: the position the server last sent (its tracked position), the velocity then, and the
     * world tick it came. The client never moves a fireball between the server's updates (every 10 ticks: EntityType
     * FIREBALL trackingTickInterval; in the volley's reads its time to impact held 6 ticks, then jumped 8-10), so the
     * reading is stepped forward from the fix by the ticks since (dead reckoning, not the lerp toward it).
     */
    record Fix(double[] pos, double[] vel, long tick) {}

    private static final Map<Integer, Fix> FIXES = new java.util.concurrent.ConcurrentHashMap<>();

    /** Pure: a new server position starts a new fix; the same one keeps the old fix (its tick). */
    static Fix fix(Fix last, double[] tracked, double[] vel, long now) {
        if (last == null || !Arrays.equals(last.pos(), tracked)) return new Fix(tracked, vel, now);
        return last;
    }

    /** Pure: {position, velocity} now, stepped from the fix along the motion by the ticks since it came. */
    static double[][] reckon(Fix f, long now, Impact.Motion m) {
        int ticks = (int) Math.max(0, Math.min(MAX_TICKS, now - f.tick()));
        return Impact.advance(f.pos(), f.vel(), m, ticks);
    }

    /** One entity's reading: the reflex's view of it and, when a hit is coming, where and when it lands. */
    public record Seen(Entity entity, Reflex.Contact contact, Impact.Hit hit, double[] vel) {
        public Seen(Entity entity, Reflex.Contact contact, Impact.Hit hit) {
            this(entity, contact, hit, null);
        }
    }

    /** Pure: the velocity to report (blocks/tick): a projectile's reckoned one, else the last tick's move (a mob's
     * copy is lerped every tick; a fireball's moves only on the server's updates, its last-tick move reads 0). */
    public static double[] reported(double[] reckoned, double[] lastTick) {
        return reckoned != null ? reckoned : lastTick;
    }

    public static List<Seen> read(MinecraftClient c) {
        ClientPlayerEntity p = c.player;
        List<Seen> out = new ArrayList<>();
        if (p == null || c.world == null) return out;
        Box body = p.getBoundingBox();
        double[] lo = {body.minX, body.minY, body.minZ}, hi = {body.maxX, body.maxY, body.maxZ};
        Vec3d eye = p.getEyePos();
        double reach = WorldUtil.entityReach(p);
        long now = c.world.getTime();
        java.util.Set<Integer> seenIds = new java.util.HashSet<>();
        for (Entity e : c.world.getOtherEntities(p, body.expand(RADIUS))) {
            Impact.Hit hit = null;
            String kind;
            boolean deflectable = false, hostile = false, creeper = false, inReach;
            float health = 0f;
            if (e instanceof ProjectileEntity pr) {
                if (pr.getOwner() == p) continue;
                Vec3d v = e.getVelocity();
                Impact.Motion m;
                if (e instanceof ExplosiveProjectileEntity ex) {
                    m = Impact.Motion.explosive(ex.accelerationPower,
                        e instanceof AbstractWindChargeEntity ? WIND_DRAG : FIREBALL_DRAG);
                    kind = e instanceof FireballEntity ? "fireball" : "projectile";
                    deflectable = e instanceof FireballEntity;
                } else if (e instanceof PersistentProjectileEntity pp) {
                    if (v.lengthSquared() < 1e-4) continue;            // stuck in the ground
                    m = Impact.Motion.persistent(ARROW_DRAG, pp.getFinalGravity());
                    kind = "projectile";
                } else {
                    continue;                                           // thrown items: no harm to shield against
                }
                double half = e.getWidth() / 2;
                Vec3d tracked = e.getTrackedPosition().getPos();
                Fix f = fix(FIXES.get(e.getId()), new double[]{tracked.x, tracked.y, tracked.z},
                    new double[]{v.x, v.y, v.z}, now);
                FIXES.put(e.getId(), f);
                seenIds.add(e.getId());
                double[][] at = reckon(f, now, m);
                double[] feet = at[0], vel = at[1];
                hit = Impact.projectile(new double[]{feet[0], feet[1] + half, feet[2]}, vel, m, lo, hi, half,
                    MAX_TICKS);
                out.add(new Seen(e, Reflex.projectile(e.getId(), kind, deflectable, feet, vel, m, half, lo, hi,
                    new double[]{eye.x, eye.y, eye.z}, reach, MAX_TICKS), hit, vel));
                continue;
            } else if (e instanceof Monster && e instanceof LivingEntity le && le.isAlive()) {
                hostile = true;
                health = le.getHealth();
                creeper = e instanceof CreeperEntity;
                // a lit creeper is a blast on a known fuse; walking, it is a melee mob like any other
                kind = e instanceof CreeperEntity cr && (cr.getFuseSpeed() > 0 || cr.isIgnited()) ? "blast" : "melee";
                int tti = mobTti(e, le, p, body, lo, hi);
                if (tti >= 0) hit = new Impact.Hit(tti, p.getX(), p.getY() + p.getHeight() / 2, p.getZ());
            } else {
                continue;
            }
            Vec3d aim = new Vec3d(e.getX(), e.getY() + e.getHeight() * 0.6, e.getZ());
            inReach = eye.squaredDistanceTo(closest(e.getBoundingBox(), eye)) <= reach * reach;
            Reflex.Contact contact = new Reflex.Contact(e.getId(), kind, hit != null ? hit.ticks() : -1,
                aim.x, aim.y, aim.z, e.getX() - p.getX(), e.getZ() - p.getZ(), inReach, hostile, deflectable,
                creeper, health, Math.sqrt(e.squaredDistanceTo(p)));
            out.add(new Seen(e, contact, hit));
        }
        FIXES.keySet().retainAll(seenIds);          // gone: its fix with it
        return out;
    }

    static final int GHAST_WARN = 10;       // ghast: shooting flag up 10 ticks before the fireball leaves
    static final int BOW_DRAW = 20;         // skeleton/stray: loosed at 20 ticks of pull
    static final int CROSSBOW_DRAW = 25;    // pillager: crossbow charged at 25
    static final double ARROW_SPEED = 1.6;  // blocks/tick a mob's arrow leaves at

    /** A mob's hit, from what the client syncs: a lit fuse, a ghast shooting, a bow drawn, else its melee. */
    static int mobTti(Entity e, LivingEntity le, LivingEntity p, Box body, double[] lo, double[] hi) {
        if (e instanceof CreeperEntity cr && (cr.getFuseSpeed() > 0 || cr.isIgnited())) {
            return Math.max(0, Math.round((1f - cr.getLerpedFuseTime(0f)) * CREEPER_FUSE));
        }
        double dist = Math.sqrt(e.squaredDistanceTo(p));
        if (e instanceof GhastEntity g && g.isShooting()) {
            Vec3d from = e.getEntityPos().add(0, e.getHeight() / 2, 0);
            Vec3d dir = p.getEyePos().subtract(from).normalize().multiply(0.1);
            Impact.Hit h = Impact.projectile(new double[]{from.x, from.y, from.z}, new double[]{dir.x, dir.y, dir.z},
                Impact.Motion.explosive(0.1, FIREBALL_DRAG), lo, hi, 0.5, MAX_TICKS);
            return h == null ? -1 : Impact.drawn(0, GHAST_WARN, h.ticks());
        }
        if (drawing(le)) {
            int full = e instanceof PillagerEntity ? CROSSBOW_DRAW : BOW_DRAW;
            return Impact.drawn(le.getItemUseTime(), full, (int) Math.ceil(dist / ARROW_SPEED));
        }
        boolean inRange = e instanceof MobEntity mob && mob.isInAttackRange(p);
        double gap = inRange ? 0 : gap(e.getBoundingBox(), body) - MELEE_REACH;
        return Impact.melee(gap, closing(e, p), e instanceof CreeperEntity ? CREEPER_FUSE : 0, MAX_TICKS);
    }

    /** Drawing a bow or charging a crossbow now. */
    public static boolean drawing(LivingEntity le) {
        return le.isUsingItem() && le.getActiveItem().getItem() instanceof RangedWeaponItem;
    }

    /** How far past the boxes' horizontal gap a mob's attack still lands (MobEntity's attack box: √2.04 − 0.6). */
    static final double MELEE_REACH = Math.sqrt(2.04) - 0.6;

    static double gap(Box a, Box b) {
        double dx = Math.max(0, Math.max(a.minX - b.maxX, b.minX - a.maxX));
        double dz = Math.max(0, Math.max(a.minZ - b.maxZ, b.minZ - a.maxZ));
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Blocks per tick the entity moved toward the body last tick. */
    static double closing(Entity e, Entity p) {
        double dx = p.getX() - e.getX(), dz = p.getZ() - e.getZ();
        double n = Math.sqrt(dx * dx + dz * dz);
        if (n < 1e-6) return 0;
        return ((e.getX() - e.lastX) * dx + (e.getZ() - e.lastZ) * dz) / n;
    }

    static Vec3d closest(Box b, Vec3d p) {
        return new Vec3d(Math.max(b.minX, Math.min(p.x, b.maxX)), Math.max(b.minY, Math.min(p.y, b.maxY)),
            Math.max(b.minZ, Math.min(p.z, b.maxZ)));
    }
}

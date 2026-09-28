package dev.anaka.combat;

import dev.anaka.util.WorldUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.CreeperEntity;
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
import java.util.List;

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

    /** One entity's reading: the reflex's view of it and, when a hit is coming, where and when it lands. */
    public record Seen(Entity entity, Reflex.Contact contact, Impact.Hit hit) {}

    public static List<Seen> read(MinecraftClient c) {
        ClientPlayerEntity p = c.player;
        List<Seen> out = new ArrayList<>();
        if (p == null || c.world == null) return out;
        Box body = p.getBoundingBox();
        double[] lo = {body.minX, body.minY, body.minZ}, hi = {body.maxX, body.maxY, body.maxZ};
        Vec3d eye = p.getEyePos();
        double reach = WorldUtil.entityReach(p);
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
                hit = Impact.projectile(new double[]{e.getX(), e.getY() + e.getHeight() / 2, e.getZ()},
                    new double[]{v.x, v.y, v.z}, m, lo, hi, e.getWidth() / 2, MAX_TICKS);
            } else if (e instanceof Monster && e instanceof LivingEntity le && le.isAlive()) {
                hostile = true;
                health = le.getHealth();
                creeper = e instanceof CreeperEntity;
                kind = creeper ? "blast" : "melee";
                int tti;
                if (e instanceof CreeperEntity cr && cr.getFuseSpeed() > 0) {
                    tti = Math.max(0, Math.round((1f - cr.getLerpedFuseTime(0f)) * CREEPER_FUSE));
                } else {
                    boolean inRange = e instanceof MobEntity mob && mob.isInAttackRange(p);
                    double gap = inRange ? 0 : gap(e.getBoundingBox(), body) - MELEE_REACH;
                    tti = Impact.melee(gap, closing(e, p), creeper ? CREEPER_FUSE : 0, MAX_TICKS);
                }
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
        return out;
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

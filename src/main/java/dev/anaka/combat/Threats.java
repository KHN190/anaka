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
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.mob.Angerable;
import net.minecraft.entity.mob.BlazeEntity;
import net.minecraft.entity.mob.BreezeEntity;
import net.minecraft.entity.mob.CreakingEntity;
import net.minecraft.entity.mob.GiantEntity;
import net.minecraft.entity.mob.GuardianEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.mob.PhantomEntity;
import net.minecraft.entity.mob.ShulkerEntity;
import net.minecraft.entity.mob.SlimeEntity;
import net.minecraft.entity.mob.VexEntity;
import net.minecraft.entity.mob.WardenEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.projectile.TridentEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.item.CrossbowItem;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
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
    static final double FIREBALL_WATER_DRAG = 0.8;  // ExplosiveProjectileEntity.getDragInWater (wind charge: its drag)
    static final double ARROW_WATER_DRAG = 0.6;     // PersistentProjectileEntity.getDragInWater
    static final double TRIDENT_WATER_DRAG = 0.99;  // TridentEntity.getDragInWater
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
        java.util.Set<Integer> seenIds = new java.util.HashSet<>(), mobIds = new java.util.HashSet<>();
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
                    boolean wind = e instanceof AbstractWindChargeEntity;
                    m = Impact.Motion.explosive(ex.accelerationPower, wind ? WIND_DRAG : FIREBALL_DRAG,
                        wind ? WIND_DRAG : FIREBALL_WATER_DRAG);
                    kind = e instanceof FireballEntity ? "fireball" : "projectile";
                    deflectable = e instanceof FireballEntity;
                } else if (e instanceof PersistentProjectileEntity pp) {
                    if (v.lengthSquared() < 1e-4) continue;            // stuck in the ground
                    m = Impact.Motion.persistent(ARROW_DRAG, pp.getFinalGravity(),
                        e instanceof TridentEntity ? TRIDENT_WATER_DRAG : ARROW_WATER_DRAG);
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
                Impact.Terrain t = terrain(c.world, e);
                out.add(new Seen(e, Reflex.projectile(e.getId(), kind, deflectable, feet, vel, m, half, lo, hi,
                    new double[]{eye.x, eye.y, eye.z}, reach, MAX_TICKS, t),
                    Impact.projectile(new double[]{feet[0], feet[1] + half, feet[2]}, vel, m, lo, hi, half, MAX_TICKS,
                        t), vel));
                continue;
            } else if (e instanceof MobEntity mob && mob.isAlive() && (e instanceof Monster || hostile(mob, p))) {
                hostile = hostile(mob, p);
                health = mob.getHealth();
                creeper = e instanceof CreeperEntity;
                // a lit creeper is a blast on a known fuse; walking, it is a melee mob like any other
                kind = lit(e) ? "blast" : "melee";
                mobIds.add(e.getId());
                int tti = hostile ? mobTti(c.world, mob, p, lo, hi, now) : -1;
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
        CHARGING.keySet().retainAll(mobIds);
        return out;
    }

    static final int GHAST_WARN = 10;       // ghast: shooting flag up 10 ticks before the fireball leaves
    static final int BOW_DRAW = 20;         // skeleton/stray: loosed at 20 ticks of pull
    static final int CROSSBOW_DRAW = 25;    // pillager: crossbow charged at 25
    static final double ARROW_SPEED = 1.6;  // blocks/tick a mob's arrow leaves at

    static final double SIGHT = 128.0;      // LivingEntity.canSee: no sight past it
    static final double GHAST_RANGE = 64.0; // ShootFireballGoal: dist² < 4096
    static final int CHARGED_WAIT = 20;     // CrossbowAttackGoal: CHARGED waits 20 + rand(20) ticks, then fires seen
    static final double CREEPER_POWER = 3;  // CreeperEntity.explosionRadius (×2 charged)

    /** Monsters whose attack sets no isAttacking flag (no goal or brain of theirs calls setAttacking; 1.21.11 scan):
     * hostile by their class. Every other mob is hostile only while its synced flag says it attacks. */
    static final List<Class<?>> FLAGLESS = List.of(SlimeEntity.class, PhantomEntity.class, VexEntity.class,
        WardenEntity.class, BlazeEntity.class, BreezeEntity.class, GhastEntity.class, GuardianEntity.class,
        ShulkerEntity.class, WitherEntity.class, EnderDragonEntity.class, CreakingEntity.class, GiantEntity.class,
        CreeperEntity.class);

    /** Last world tick each pillager was seen charging its crossbow: its CHARGED wait counts from then. */
    private static final Map<Integer, Long> CHARGING = new java.util.concurrent.ConcurrentHashMap<>();

    /** Hostile as vanilla decides it: a flagless Monster by class; else the synced isAttacking — and, not a Monster,
     * its synced anger too, never our own tamed animal. */
    static boolean hostile(MobEntity m, LivingEntity p) {
        if (m instanceof Monster) return FLAGLESS.stream().anyMatch(k -> k.isInstance(m)) || m.isAttacking();
        return m.isAttacking() && m instanceof Angerable a && a.hasAngerTime()
            && !(m instanceof TameableEntity t && t.isOwner(p));
    }

    static boolean lit(Entity e) {
        return e instanceof CreeperEntity cr && (cr.getFuseSpeed() > 0 || cr.isIgnited());
    }

    /** The client world as the game's own line and flight checks read it, `ctx` the raycast's entity. */
    static Impact.Terrain terrain(World w, Entity ctx) {
        return new Impact.Terrain() {
            public double firstHit(double[] from, double[] to) {
                Vec3d a = new Vec3d(from[0], from[1], from[2]), b = new Vec3d(to[0], to[1], to[2]);
                BlockHitResult r = w.raycast(new RaycastContext(a, b, RaycastContext.ShapeType.COLLIDER,
                    RaycastContext.FluidHandling.NONE, ctx));
                if (r.getType() == HitResult.Type.MISS) return -1;
                double len = a.distanceTo(b);
                return len < 1e-9 ? 0 : a.distanceTo(r.getPos()) / len;
            }

            public boolean inWater(double[] lo, double[] hi) {
                // Entity.updateMovementInFluid: the box contracted 0.001, a water cell whose fluid top reaches its bottom
                double y0 = lo[1] + 0.001;
                for (int x = (int) Math.floor(lo[0] + 0.001); x < Math.ceil(hi[0] - 0.001); x++) {
                    for (int y = (int) Math.floor(y0); y < Math.ceil(hi[1] - 0.001); y++) {
                        for (int z = (int) Math.floor(lo[2] + 0.001); z < Math.ceil(hi[2] - 0.001); z++) {
                            BlockPos at = new BlockPos(x, y, z);
                            FluidState f = w.getFluidState(at);
                            if (f.isIn(FluidTags.WATER) && y + f.getHeight(w, at) >= y0) return true;
                        }
                    }
                }
                return false;
            }
        };
    }

    /** Pure: LivingEntity.canSee — eye to eye, colliders only, within SIGHT. */
    static boolean seen(Impact.Terrain t, double[] eye, double[] target) {
        double dx = target[0] - eye[0], dy = target[1] - eye[1], dz = target[2] - eye[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz) <= SIGHT && t.firstHit(eye, target) < 0;
    }

    /** Pure: a drawn bow's (or charging crossbow's) hit — none unseen (it fires only seen); else the draw left, the
     * crossbow's CHARGED wait ({@code wait}), the flight. */
    static int drawnTti(boolean seen, int pulled, int full, int wait, double dist) {
        return seen ? Impact.drawn(pulled, full, wait + (int) Math.ceil(dist / ARROW_SPEED)) : -1;
    }

    /** Pure: a charged crossbow's hit — none unseen; else the earliest end of its CHARGED wait ({@code since}: ticks
     * since it was last seen charging, -1 never seen: it may fire now), then the flight. */
    static int chargedTti(boolean seen, long since, double dist) {
        if (!seen) return -1;
        int wait = since < 0 ? 0 : (int) Math.max(0, CHARGED_WAIT - since);
        return wait + (int) Math.ceil(dist / ARROW_SPEED);
    }

    /** Pure: a melee mob's hit — in its attack range it lands only seen (MeleeAttackGoal.canAttack); out of range the
     * gap closed at its approach speed, then the wind-up. */
    static int meleeTti(boolean inRange, boolean seen, double gap, double closing, int windup) {
        if (inRange && !seen) return -1;
        return Impact.melee(inRange ? 0 : gap, closing, windup, MAX_TICKS);
    }

    /** Pure: a lit fuse's blast — none past its reach (distance / (power·2) &gt; 1) or with nothing of the box exposed
     * (ExplosionImpl); else the fuse left. */
    static int blastTti(int fuse, double exposure, double dist, double power) {
        return exposure > 0 && dist / (power * 2) <= 1 ? fuse : -1;
    }

    /** A hostile mob's hit, from what the client syncs and the world between: a lit fuse, a ghast shooting, a bow
     * drawn, a crossbow charged, else its melee. */
    static int mobTti(World w, MobEntity e, ClientPlayerEntity p, double[] lo, double[] hi, long now) {
        double dist = Math.sqrt(e.squaredDistanceTo(p));
        if (e instanceof CreeperEntity cr && lit(e)) {
            int fuse = Math.max(0, Math.round((1f - cr.getLerpedFuseTime(0f)) * CREEPER_FUSE));
            return blastTti(fuse, Impact.exposure(new double[]{e.getX(), e.getY(), e.getZ()}, lo, hi, terrain(w, p)),
                dist, CREEPER_POWER * (cr.isCharged() ? 2 : 1));
        }
        Impact.Terrain t = terrain(w, e);
        boolean seen = seen(t, new double[]{e.getX(), e.getEyeY(), e.getZ()},
            new double[]{p.getX(), p.getEyeY(), p.getZ()});
        if (e instanceof GhastEntity g && g.isShooting()) {
            if (!seen || dist >= GHAST_RANGE) return -1;
            Vec3d from = e.getEntityPos().add(0, e.getHeight() / 2, 0);
            Vec3d dir = p.getEyePos().subtract(from).normalize().multiply(0.1);
            Impact.Hit h = Impact.projectile(new double[]{from.x, from.y, from.z}, new double[]{dir.x, dir.y, dir.z},
                Impact.Motion.explosive(0.1, FIREBALL_DRAG, FIREBALL_WATER_DRAG), lo, hi, 0.5, MAX_TICKS, t);
            return h == null ? -1 : Impact.drawn(0, GHAST_WARN, h.ticks());
        }
        boolean crossbow = e instanceof PillagerEntity;
        if (drawing(e)) {
            if (crossbow) CHARGING.put(e.getId(), now);
            return drawnTti(seen, e.getItemUseTime(), crossbow ? CROSSBOW_DRAW : BOW_DRAW, crossbow ? CHARGED_WAIT : 0,
                dist);
        }
        if (crossbow && (CrossbowItem.isCharged(e.getMainHandStack()) || CrossbowItem.isCharged(e.getOffHandStack()))) {
            Long end = CHARGING.get(e.getId());
            return chargedTti(seen, end == null ? -1 : now - end, dist);
        }
        return meleeTti(e.isInAttackRange(p), seen, gap(e.getBoundingBox(), p.getBoundingBox()) - MELEE_REACH,
            closing(e, p), e instanceof CreeperEntity ? CREEPER_FUSE : 0);
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

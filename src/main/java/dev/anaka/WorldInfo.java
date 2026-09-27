package dev.anaka;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.anaka.task.Task;
import dev.anaka.util.InvUtil;
import dev.anaka.util.WorldUtil;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.registry.Registries;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.LightType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Read-only JSON views of the game. Call on the client thread. */
final class WorldInfo {
    private static final int MAX_FIND_RADIUS = 48;
    private static final int MAX_REGION_VOLUME = 32 * 32 * 32;

    private WorldInfo() {}

    static JsonObject state(MinecraftClient c) {
        ClientPlayerEntity p = c.player;
        ClientWorld w = c.world;
        JsonObject o = new JsonObject();
        o.addProperty("x", p.getX());
        o.addProperty("y", p.getY());
        o.addProperty("z", p.getZ());
        BlockPos feet = p.getBlockPos();
        o.addProperty("blockX", feet.getX());
        o.addProperty("blockY", feet.getY());
        o.addProperty("blockZ", feet.getZ());
        o.addProperty("yaw", p.getYaw());
        o.addProperty("pitch", p.getPitch());
        o.addProperty("dimension", w.getRegistryKey().getValue().toString());
        o.addProperty("blockLight", w.getLightLevel(LightType.BLOCK, feet));
        o.addProperty("skyLight", w.getLightLevel(LightType.SKY, feet));
        o.addProperty("timeOfDay", w.getTimeOfDay() % 24000);
        o.addProperty("gameTime", w.getTime());
        o.addProperty("health", p.getHealth());
        o.addProperty("maxHealth", p.getMaxHealth());
        o.addProperty("food", p.getHungerManager().getFoodLevel());
        o.addProperty("saturation", p.getHungerManager().getSaturationLevel());
        o.addProperty("air", p.getAir());
        o.addProperty("armor", p.getArmor());
        o.addProperty("xpLevel", p.experienceLevel);
        o.addProperty("onGround", p.isOnGround());
        o.addProperty("inWater", p.isTouchingWater());
        o.addProperty("climbing", p.isClimbing());
        // Standing in a portal (feet or head): Python used to read /blocks for it after every portal step.
        boolean portal = false;
        for (BlockPos q : new BlockPos[]{feet, feet.up()}) {
            var b = w.getBlockState(q).getBlock();
            portal |= b == net.minecraft.block.Blocks.NETHER_PORTAL || b == net.minecraft.block.Blocks.END_PORTAL;
        }
        o.addProperty("inPortal", portal);
        o.addProperty("inLava", p.isInLava());
        o.addProperty("onFire", p.isOnFire());
        o.addProperty("dead", p.isDead());
        o.addProperty("selectedSlot", p.getInventory().getSelectedSlot());
        o.add("mainHand", InvUtil.stackJson(p.getMainHandStack()));
        o.addProperty("screen", c.currentScreen == null ? "none" : c.currentScreen.getClass().getSimpleName());
        o.add("lookingAt", lookingAt(c));
        o.add("control", control(c));
        return o;
    }

    static JsonObject control(MinecraftClient c) {
        Agent a = Agent.get();
        JsonObject o = new JsonObject();
        o.addProperty("active", a.isControlling());
        o.addProperty("paused", a.isPaused());
        o.addProperty("allowed", Anaka.automationAllowed(c));
        Task cur = a.current();
        o.add("task", cur == null ? null : cur.toJson());
        o.addProperty("queued", a.queued());
        return o;
    }

    private static JsonObject lookingAt(MinecraftClient c) {
        HitResult hit = c.crosshairTarget;
        JsonObject o = new JsonObject();
        if (hit instanceof BlockHitResult b && hit.getType() == HitResult.Type.BLOCK) {
            BlockPos pos = b.getBlockPos();
            o.addProperty("kind", "block");
            o.addProperty("block", WorldUtil.id(c.world.getBlockState(pos).getBlock()));
            o.addProperty("x", pos.getX());
            o.addProperty("y", pos.getY());
            o.addProperty("z", pos.getZ());
            o.addProperty("side", b.getSide().asString());
        } else if (hit instanceof EntityHitResult e) {
            o.addProperty("kind", "entity");
            o.addProperty("entity", e.getEntity().getId());
        } else {
            o.addProperty("kind", "none");
        }
        return o;
    }

    static JsonObject inventory(MinecraftClient c) {
        ClientPlayerEntity p = c.player;
        PlayerInventory inv = p.getInventory();
        JsonObject o = new JsonObject();
        JsonArray slots = new JsonArray();
        for (int i = 0; i < InvUtil.MAIN_SLOTS; i++) {
            if (inv.getStack(i).isEmpty()) continue;
            JsonObject s = InvUtil.stackJson(inv.getStack(i));
            s.addProperty("slot", i);
            slots.add(s);
        }
        o.add("slots", slots);
        o.addProperty("selectedSlot", inv.getSelectedSlot());
        JsonObject armor = new JsonObject();
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.OFFHAND}) {
            armor.add(slot.asString(), InvUtil.stackJson(p.getEquippedStack(slot)));
        }
        o.add("equipment", armor);
        return o;
    }

    static JsonObject find(MinecraftClient c, Set<String> ids, int radius, int limit, boolean exposedOnly) {
        return find(c, ids, radius, limit, exposedOnly, 0);
    }

    /** {@code perBlock} > 0: keep only the nearest {@code perBlock} of each id — one scan answers "how far is the
     * nearest of each of these" (the planner's cost model asked it once per kind: fifteen scans a round). */
    static JsonObject find(MinecraftClient c, Set<String> ids, int radius, int limit, boolean exposedOnly, int perBlock) {
        ClientWorld w = c.world;
        ClientPlayerEntity p = c.player;
        int r = Math.max(1, Math.min(MAX_FIND_RADIUS, radius));
        BlockPos origin = p.getBlockPos();
        record Hit(BlockPos pos, String id, double distSq) {}
        List<Hit> hits = new ArrayList<>();
        BlockPos.Mutable m = new BlockPos.Mutable();
        int minY = Math.max(w.getBottomY(), origin.getY() - r);
        int maxY = Math.min(w.getTopYInclusive(), origin.getY() + r);
        for (int x = origin.getX() - r; x <= origin.getX() + r; x++) {
            for (int z = origin.getZ() - r; z <= origin.getZ() + r; z++) {
                if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
                for (int y = minY; y <= maxY; y++) {
                    m.set(x, y, z);
                    BlockState s = w.getBlockState(m);
                    if (s.isAir()) continue;
                    String id = WorldUtil.id(s.getBlock());
                    if (!ids.contains(id)) continue;
                    BlockPos pos = m.toImmutable();
                    if (exposedOnly && !exposed(c, pos)) continue;
                    hits.add(new Hit(pos, id, pos.getSquaredDistance(origin)));
                }
            }
        }
        hits.sort((a, b) -> Double.compare(a.distSq, b.distSq));
        if (perBlock > 0) {
            java.util.Map<String, Integer> seen = new java.util.HashMap<>();
            hits.removeIf(h -> seen.merge(h.id, 1, Integer::sum) > perBlock);
        }
        JsonObject o = new JsonObject();
        o.addProperty("found", hits.size());
        JsonArray arr = new JsonArray();
        for (int i = 0; i < Math.min(limit, hits.size()); i++) {
            Hit h = hits.get(i);
            JsonObject e = new JsonObject();
            e.addProperty("block", h.id);
            e.addProperty("x", h.pos.getX());
            e.addProperty("y", h.pos.getY());
            e.addProperty("z", h.pos.getZ());
            e.addProperty("distance", Math.round(Math.sqrt(h.distSq) * 10) / 10.0);
            arr.add(e);
        }
        o.add("blocks", arr);
        return o;
    }

    /**
     * An open face a body can work: a free neighbour cell that a standing body occupies with its feet or head, or
     * looks up into from right under it. "Free" alone listed faces onto sealed pockets and portal interiors (a
     * stone block behind an obsidian frame was mined for "277 positions explored" four times over).
     */
    private static boolean exposed(MinecraftClient c, BlockPos pos) {
        for (Direction d : Direction.values()) {
            BlockPos n = pos.offset(d);
            BlockState s = c.world.getBlockState(n);
            if (!WorldUtil.passable(c.world, n) || s.isOpaqueFullCube()) continue;
            if (s.isOf(net.minecraft.block.Blocks.NETHER_PORTAL) || s.isOf(net.minecraft.block.Blocks.END_PORTAL)) continue;
            if (WorldUtil.standable(c.world, n) || WorldUtil.standable(c.world, n.down())
                || WorldUtil.standable(c.world, n.down(2))) return true;
        }
        return false;
    }

    /** Standable spots (air with a floor) whose block light is at or below maxLight, nearest first. */
    static JsonObject dark(MinecraftClient c, int radius, int maxLight, int limit) {
        ClientWorld w = c.world;
        BlockPos origin = c.player.getBlockPos();
        int r = Math.max(1, Math.min(16, radius));
        record Spot(BlockPos pos, int light, double distSq) {}
        List<Spot> spots = new ArrayList<>();
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int x = -r; x <= r; x++) {
            for (int y = -r; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    m.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    if (!WorldUtil.isLoaded(w, m) || !w.getBlockState(m).isAir() || !WorldUtil.floor(w, m.down())) continue;
                    int light = w.getLightLevel(LightType.BLOCK, m);
                    if (light > maxLight) continue;
                    spots.add(new Spot(m.toImmutable(), light, m.getSquaredDistance(origin)));
                }
            }
        }
        spots.sort((a, b) -> Double.compare(a.distSq, b.distSq));
        JsonArray arr = new JsonArray();
        for (int i = 0; i < Math.min(limit, spots.size()); i++) {
            Spot s = spots.get(i);
            JsonObject e = new JsonObject();
            e.addProperty("x", s.pos.getX());
            e.addProperty("y", s.pos.getY());
            e.addProperty("z", s.pos.getZ());
            e.addProperty("blockLight", s.light);
            e.addProperty("skyLight", w.getLightLevel(LightType.SKY, s.pos));
            arr.add(e);
        }
        JsonObject o = new JsonObject();
        o.addProperty("found", spots.size());
        o.add("spots", arr);
        return o;
    }

    static JsonObject region(MinecraftClient c, BlockPos a, BlockPos b) {
        return region(c, a, b, false);
    }

    /** With {@code props}, entries of blocks that have state properties get a 5th element: {facing, powered, ...}. */
    static JsonObject region(MinecraftClient c, BlockPos a, BlockPos b, boolean props) {
        int minX = Math.min(a.getX(), b.getX()), maxX = Math.max(a.getX(), b.getX());
        int minY = Math.min(a.getY(), b.getY()), maxY = Math.max(a.getY(), b.getY());
        int minZ = Math.min(a.getZ(), b.getZ()), maxZ = Math.max(a.getZ(), b.getZ());
        long volume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        if (volume > MAX_REGION_VOLUME) throw new IllegalArgumentException("region too large (max " + MAX_REGION_VOLUME + " blocks)");
        Map<String, Integer> palette = new HashMap<>();
        JsonArray paletteJson = new JsonArray();
        JsonArray blocks = new JsonArray();
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    m.set(x, y, z);
                    BlockState s = c.world.getBlockState(m);
                    if (s.isAir()) continue;
                    String id = WorldUtil.id(s.getBlock());
                    Integer idx = palette.get(id);
                    if (idx == null) {
                        idx = palette.size();
                        palette.put(id, idx);
                        paletteJson.add(id);
                    }
                    JsonArray e = new JsonArray();
                    e.add(x);
                    e.add(y);
                    e.add(z);
                    e.add(idx);
                    if (props && !s.getEntries().isEmpty()) {
                        JsonObject po = new JsonObject();
                        s.getEntries().forEach((k, v) -> po.addProperty(k.getName(), String.valueOf(v)));
                        e.add(po);
                    }
                    blocks.add(e);
                }
            }
        }
        JsonObject o = new JsonObject();
        o.add("palette", paletteJson);
        o.add("blocks", blocks);
        o.addProperty("note", "non-air blocks as [x, y, z, paletteIndex]");
        return o;
    }

    static JsonObject entities(MinecraftClient c, double radius) {
        ClientPlayerEntity p = c.player;
        // Up to 128: the dragon circles farther than 64 from the player and was taken for dead.
        List<Entity> list = new ArrayList<>(c.world.getOtherEntities(p, p.getBoundingBox().expand(Math.min(radius, 128))));
        list.sort((a, b) -> Double.compare(a.squaredDistanceTo(p), b.squaredDistanceTo(p)));
        JsonArray arr = new JsonArray();
        for (Entity e : list) {
            JsonObject o = new JsonObject();
            o.addProperty("id", e.getId());
            o.addProperty("type", Registries.ENTITY_TYPE.getId(e.getType()).toString());
            o.addProperty("x", e.getX());
            o.addProperty("y", e.getY());
            o.addProperty("z", e.getZ());
            o.addProperty("distance", Math.round(Math.sqrt(e.squaredDistanceTo(p)) * 10) / 10.0);
            o.addProperty("hostile", e instanceof Monster);
            if (e instanceof LivingEntity le) o.addProperty("health", le.getHealth());
            // The dragon's phase is synced to the client: perching (landing / sitting) is a number, not a guess from
            // its position. 3 landing, 5 sitting flaming, 6 sitting scanning, 7 sitting attacking, 4 takeoff.
            if (e instanceof net.minecraft.entity.boss.dragon.EnderDragonEntity d) {
                o.addProperty("phase", d.getPhaseManager().getCurrent().getType().getTypeId());
            }
            // Endermen are neutral until provoked: the brain must know which ones are actually after us, so it can
            // break line of sight or step into water instead of picking a fight it never needed.
            if (e instanceof net.minecraft.entity.mob.EndermanEntity en) o.addProperty("angry", en.isAngry());
            // Breeding is proven by a baby, not by the food that went into it.
            if (e instanceof net.minecraft.entity.passive.PassiveEntity pe) o.addProperty("baby", pe.isBaby());
            if (e instanceof ItemEntity ie) o.add("item", InvUtil.stackJson(ie.getStack()));
            arr.add(o);
        }
        JsonObject o = new JsonObject();
        o.add("entities", arr);
        return o;
    }

    static JsonObject container(MinecraftClient c) {
        ClientPlayerEntity p = c.player;
        ScreenHandler h = p.currentScreenHandler;
        JsonObject o = new JsonObject();
        String type;
        if (h instanceof PlayerScreenHandler) {
            type = "player_inventory";
        } else {
            try {
                type = Registries.SCREEN_HANDLER.getId(h.getType()).toString();
            } catch (UnsupportedOperationException e) {
                type = h.getClass().getSimpleName();
            }
        }
        o.addProperty("type", type);
        o.addProperty("syncId", h.syncId);
        o.add("cursor", InvUtil.stackJson(h.getCursorStack()));
        JsonArray slots = new JsonArray();
        for (Slot s : h.slots) {
            JsonObject so = InvUtil.stackJson(s.getStack());
            so.addProperty("slot", s.id);
            so.addProperty("owner", s.inventory instanceof PlayerInventory ? "player" : "container");
            so.addProperty("index", s.getIndex());
            slots.add(so);
        }
        o.add("slots", slots);
        // Screen details the Python side needs to decide which button/offer to press.
        if (h instanceof net.minecraft.screen.EnchantmentScreenHandler e) {
            JsonArray options = new JsonArray();
            for (int i = 0; i < 3; i++) {
                JsonObject opt = new JsonObject();
                opt.addProperty("cost", e.enchantmentPower[i]);
                opt.addProperty("id", e.enchantmentId[i]);
                opt.addProperty("level", e.enchantmentLevel[i]);
                options.add(opt);
            }
            o.add("enchant", options);
            o.addProperty("lapis", e.getLapisCount());
        }
        if (h instanceof net.minecraft.screen.MerchantScreenHandler m) {
            JsonArray offers = new JsonArray();
            for (var offer : m.getRecipes()) {
                JsonObject of = new JsonObject();
                var first = offer.getDisplayedFirstBuyItem();
                of.addProperty("buy", Registries.ITEM.getId(first.getItem()).toString());
                of.addProperty("buyCount", first.getCount());
                var second = offer.getDisplayedSecondBuyItem();
                if (!second.isEmpty()) {
                    of.addProperty("buy2", Registries.ITEM.getId(second.getItem()).toString());
                    of.addProperty("buy2Count", second.getCount());
                }
                var sell = offer.getSellItem();
                of.addProperty("sell", Registries.ITEM.getId(sell.getItem()).toString());
                of.addProperty("sellCount", sell.getCount());
                of.addProperty("disabled", offer.isDisabled());
                offers.add(of);
            }
            o.add("offers", offers);
        }
        if (h instanceof net.minecraft.screen.AnvilScreenHandler a) {
            o.addProperty("levelCost", a.getLevelCost());
        }
        return o;
    }
}

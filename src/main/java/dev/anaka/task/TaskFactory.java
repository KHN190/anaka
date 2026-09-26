package dev.anaka.task;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/** Builds tasks from API JSON. Throws IllegalArgumentException with a user-facing message on bad input. */
public final class TaskFactory {
    private TaskFactory() {}

    public static Task create(MinecraftClient c, JsonObject o) {
        String type = str(o, "type", null);
        if (type == null) throw new IllegalArgumentException("missing \"type\"");
        return switch (type) {
            case "goto" -> GotoTask.near(pos(o), dbl(o, "range", 1.0), bool(o, "partial", true), bool(o, "sprint", true),
                bool(o, "useBoat", true));
            case "mine" -> new MineTask(pos(o), bool(o, "collect", true), bool(o, "requireDrops", true), onlySet(o));
            case "place" -> new PlaceTask(pos(o), itemId(o), optPos(o, "against"), optFacing(o));
            case "pillar" -> new PillarTask(itemId(o));
            case "travel" -> new TravelTask(pos(o), dbl(o, "range", 1.5), bool(o, "break", true), bool(o, "place", true),
                (int) dbl(o, "placeBudget", 64), avoidSet(o));
            case "use" -> new UseBlockTask(pos(o));
            case "attack" -> new AttackTask(integer(o, "entity"));
            case "bed_bomb" -> new BedBombTask(pos(o), itemId(o));
            case "interact" -> new InteractEntityTask(integer(o, "entity"), o.has("item") ? itemId(o) : null);
            case "eat" -> new EatTask(o.has("item") ? itemId(o) : null);
            case "collect" -> new CollectTask(null, dbl(o, "radius", 8.0), 20, onlySet(o));
            case "craft" -> new CraftTask(pattern(o), (int) dbl(o, "count", 1));
            case "look" -> o.has("x")
                ? new LookTask(new Vec3d(dbl(o, "x", 0), dbl(o, "y", 0), dbl(o, "z", 0)), Float.NaN, 0, 0)
                : new LookTask(null, (float) dbl(o, "yaw", 0), (float) dbl(o, "pitch", 0), 0);
            case "wait" -> new LookTask(null, Float.NaN, 0, (int) dbl(o, "ticks", 20));
            case "use_item" -> new UseItemTask(itemId(o),
                o.has("x") ? new Vec3d(dbl(o, "x", 0), dbl(o, "y", 0), dbl(o, "z", 0)) : null,
                (float) dbl(o, "yaw", 0), (float) dbl(o, "pitch", 0), bool(o, "onBlock", false), (int) dbl(o, "holdTicks", 0));
            case "build" -> build(c, o);
            case "mine_many" -> mineMany(c, o);
            default -> throw new IllegalArgumentException("unknown task type \"" + type + "\"");
        };
    }

    private static Task build(MinecraftClient c, JsonObject o) {
        List<SequenceTask.Step> steps = new ArrayList<>();
        for (JsonObject b : objects(o, "blocks")) {
            BlockPos p = pos(b);
            String item = itemId(b);
            Block target = Block.getBlockFromItem(Registries.ITEM.get(Identifier.of(item)));
            if (c.world.getBlockState(p).isOf(target)) continue;
            BlockPos against = optPos(b, "against");
            Direction facing = optFacing(b);
            steps.add(new SequenceTask.Step(p, () -> new PlaceTask(p, item, against, facing)));
        }
        return new SequenceTask("build", steps, SequenceTask.Order.BUILD, null);
    }

    private static Task mineMany(MinecraftClient c, JsonObject o) {
        boolean collect = bool(o, "collect", true);
        boolean requireDrops = bool(o, "requireDrops", true);
        List<SequenceTask.Step> steps = new ArrayList<>();
        for (JsonObject b : objects(o, "blocks")) {
            BlockPos p = pos(b);
            // Drops near the next block get picked up on the way; one sweep at the end gets the rest.
            steps.add(new SequenceTask.Step(p, () -> new MineTask(p, false, requireDrops)));
        }
        java.util.Set<String> only = onlySet(o);
        return new SequenceTask("mine_many", steps, SequenceTask.Order.MINE,
            collect ? () -> new CollectTask(null, 10, 10, only) : null);
    }

    /** Optional "only": [item ids] — the collect sweep walks only to these drops (a nearly full bag skips junk). */
    private static java.util.Set<String> onlySet(JsonObject o) {
        if (!o.has("only") || !o.get("only").isJsonArray()) return null;
        java.util.Set<String> out = new java.util.HashSet<>();
        for (JsonElement e : o.getAsJsonArray("only")) out.add(e.getAsString());
        return out;
    }

    private static it.unimi.dsi.fastutil.longs.LongOpenHashSet avoidSet(JsonObject o) {
        it.unimi.dsi.fastutil.longs.LongOpenHashSet out = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        if (o.has("avoid") && o.get("avoid").isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray("avoid")) out.add(pos(e.getAsJsonObject()).asLong());
        }
        return out;
    }

    private static Direction optFacing(JsonObject o) {
        if (!o.has("facing")) return null;
        String name = o.get("facing").getAsString().toLowerCase(java.util.Locale.ROOT);
        for (Direction d : Direction.values()) if (d.asString().equals(name)) return d;
        throw new IllegalArgumentException("unknown facing: " + name);
    }

    private static BlockPos optPos(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonObject() ? pos(o.getAsJsonObject(key)) : null;
    }


    private static BlockPos pos(JsonObject o) {
        if (!o.has("x") || !o.has("y") || !o.has("z")) throw new IllegalArgumentException("x, y, z are required");
        return BlockPos.ofFloored(o.get("x").getAsDouble(), o.get("y").getAsDouble(), o.get("z").getAsDouble());
    }

    private static String itemId(JsonObject o) {
        String raw = str(o, "item", null);
        if (raw == null) throw new IllegalArgumentException("\"item\" is required");
        Identifier id = Identifier.tryParse(raw);
        if (id == null || !Registries.ITEM.containsId(id)) throw new IllegalArgumentException("unknown item " + raw);
        Item item = Registries.ITEM.get(id);
        return Registries.ITEM.getId(item).toString();
    }

    private static List<String> pattern(JsonObject o) {
        if (!o.has("pattern") || !o.get("pattern").isJsonArray()) throw new IllegalArgumentException("\"pattern\" array is required");
        List<String> out = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray("pattern")) {
            if (e.isJsonNull() || e.getAsString().isEmpty()) {
                out.add(null);
            } else {
                JsonObject tmp = new JsonObject();
                tmp.addProperty("item", e.getAsString());
                out.add(itemId(tmp));
            }
        }
        return out;
    }

    private static List<JsonObject> objects(JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonArray()) throw new IllegalArgumentException("\"" + key + "\" array is required");
        JsonArray arr = o.getAsJsonArray(key);
        List<JsonObject> out = new ArrayList<>();
        for (JsonElement e : arr) out.add(e.getAsJsonObject());
        return out;
    }

    static String str(JsonObject o, String key, String def) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : def;
    }

    static double dbl(JsonObject o, String key, double def) {
        return o.has(key) ? o.get(key).getAsDouble() : def;
    }

    static boolean bool(JsonObject o, String key, boolean def) {
        return o.has(key) ? o.get(key).getAsBoolean() : def;
    }

    static int integer(JsonObject o, String key) {
        if (!o.has(key)) throw new IllegalArgumentException("\"" + key + "\" is required");
        return o.get(key).getAsInt();
    }
}

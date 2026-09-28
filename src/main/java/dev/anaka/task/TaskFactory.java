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
        try {
            return build(c, o, type);
        } catch (UnsupportedOperationException | ClassCastException | IllegalStateException e) {
            // a field of the wrong shape a typed read below did not name: still the caller's mistake, a 400
            throw new IllegalArgumentException(type + ": a field has the wrong type (" + e.getMessage() + ")");
        }
    }

    private static Task build(MinecraftClient c, JsonObject o, String type) {
        return switch (type) {
            case "goto" -> GotoTask.near(pos(o), dbl(o, "range", 1.0), bool(o, "partial", true), bool(o, "sprint", true),
                bool(o, "useBoat", true));
            case "mine" -> new MineTask(pos(o), bool(o, "collect", true), bool(o, "requireDrops", true), onlySet(o))
                .avoiding(avoidSet(o)).down(bool(o, "down", false)).holding(str(o, "item", null));
            case "place" -> new PlaceTask(pos(o), itemId(o), optPos(o, "against"), optFacing(o)).avoiding(avoidSet(o));
            case "pillar" -> new PillarTask(itemId(o));
            case "travel" -> {
                TravelTask t = new TravelTask(pos(o), dbl(o, "range", 1.5), bool(o, "break", true), bool(o, "place", true),
                    (int) dbl(o, "placeBudget", 64), avoidSet(o)).holding(str(o, "item", null));
                yield bool(o, "voidBridge", true) ? t : t.noVoidBridge();
            }
            case "use" -> new UseBlockTask(pos(o)).avoiding(avoidSet(o));
            case "attack" -> new AttackTask(integer(o, "entity"), str(o, "footwork", null))
                .keepingOff(dbl(o, "keepOff", AttackTask.KEEP_OFF)).holding(str(o, "item", null));
            case "bed_bomb" -> new BedBombTask(pos(o), itemId(o));
            case "interact" -> new InteractEntityTask(integer(o, "entity"), o.has("item") ? itemId(o) : null);
            case "eat" -> new EatTask(o.has("item") ? itemId(o) : null);
            // centred on x/y/z when given (a batch's closing sweep: its cells' centre), else round the body
            case "collect" -> new CollectTask(o.has("x") ? pos(o) : null, dbl(o, "radius", 8.0), (int) dbl(o, "idle", 20),
                onlySet(o));
            case "craft" -> new CraftTask(pattern(o), (int) dbl(o, "count", 1));
            case "look" -> o.has("x")
                ? new LookTask(new Vec3d(dbl(o, "x", 0), dbl(o, "y", 0), dbl(o, "z", 0)), Float.NaN, 0, 0)
                : new LookTask(null, (float) dbl(o, "yaw", 0), (float) dbl(o, "pitch", 0), 0);
            case "wait" -> new LookTask(null, Float.NaN, 0, (int) dbl(o, "ticks", 20));
            case "input" -> new InputTask(new java.util.HashSet<>(strings(o, "keys")),
                o.has("yaw") ? (float) dbl(o, "yaw", 0) : Float.NaN, (int) dbl(o, "ticks", 20),
                str(o, "until", null));
            case "use_item" -> new UseItemTask(itemId(o),
                o.has("x") ? new Vec3d(dbl(o, "x", 0), dbl(o, "y", 0), dbl(o, "z", 0)) : null,
                (float) dbl(o, "yaw", 0), (float) dbl(o, "pitch", 0), bool(o, "onBlock", false), (int) dbl(o, "holdTicks", 0));
            default -> throw new IllegalArgumentException("unknown task type \"" + type + "\"");
        };
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
        if (!present(o, "x") || !present(o, "y") || !present(o, "z")) throw new IllegalArgumentException("x, y, z are required");
        return BlockPos.ofFloored(number(o, "x").getAsDouble(), number(o, "y").getAsDouble(), number(o, "z").getAsDouble());
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

    private static List<String> strings(JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonArray()) throw new IllegalArgumentException("\"" + key + "\" array is required");
        List<String> out = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray(key)) out.add(e.getAsString());
        return out;
    }

    private static List<JsonObject> objects(JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonArray()) throw new IllegalArgumentException("\"" + key + "\" array is required");
        JsonArray arr = o.getAsJsonArray(key);
        List<JsonObject> out = new ArrayList<>();
        for (JsonElement e : arr) out.add(e.getAsJsonObject());
        return out;
    }

    /** Present and not JSON null: an optional field sent as null is absent (its default). */
    static boolean present(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull();
    }

    /** The field as a number, or a 400 naming it: a null or a string where a number belongs was a 500 (JsonNull). */
    private static JsonElement number(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) throw new IllegalArgumentException("\"" + key + "\" is required (a number)");
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("\"" + key + "\" must be a number, not " + e);
        return e;
    }

    static String str(JsonObject o, String key, String def) {
        if (!present(o, key)) return def;
        JsonElement e = o.get(key);
        if (!e.isJsonPrimitive()) throw new IllegalArgumentException("\"" + key + "\" must be a string, not " + e);
        return e.getAsString();
    }

    static double dbl(JsonObject o, String key, double def) {
        return present(o, key) ? number(o, key).getAsDouble() : def;
    }

    static boolean bool(JsonObject o, String key, boolean def) {
        if (!present(o, key)) return def;
        JsonElement e = o.get(key);
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean())
            throw new IllegalArgumentException("\"" + key + "\" must be true or false, not " + e);
        return e.getAsBoolean();
    }

    static int integer(JsonObject o, String key) {
        return number(o, key).getAsInt();
    }
}

package dev.anaka;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.anaka.task.Task;
import dev.anaka.task.TaskFactory;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * Loopback-only JSON API. Every request needs {@code Authorization: Bearer <token>} from config/anaka.json.
 * Requests carrying an Origin header are refused so web pages can't drive the game.
 */
final class HttpApi {
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();
    private static final long MAX_WAIT_SECONDS = 600;

    private record Request(String method, Map<String, String> query, JsonObject body) {
        int intParam(String key, int def) {
            return query.containsKey(key) ? Integer.parseInt(query.get(key)) : def;
        }
    }

    private interface Handler {
        JsonObject handle(Request req) throws Exception;
    }

    private final MinecraftClient client;
    private final Config config;
    private HttpServer server;

    HttpApi(MinecraftClient client, Config config) {
        this.client = client;
        this.config = config;
    }

    void start() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), config.port), 0);
        } catch (IOException e) {
            Anaka.LOG.error("Anaka could not bind 127.0.0.1:{}", config.port, e);
            return;
        }
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "anaka-http");
            t.setDaemon(true);
            return t;
        }));

        route("/status", false, req -> status());
        route("/state", true, req -> onClient(WorldInfo::state));
        route("/inventory", true, req -> onClient(WorldInfo::inventory));
        route("/entities", true, req -> onClient(c -> WorldInfo.entities(c, req.intParam("radius", 16))));
        route("/container", true, req -> onClient(WorldInfo::container));
        route("/combat/frames", true, req -> onClient(c -> CombatRecorder.since(req.intParam("since", -1))));
        // Long poll, deliberately NOT onClient: it waits on an HTTP worker thread for something to happen, and
        // running that on the client thread would stall the game for the whole timeout.
        route("/events", true, req -> EventLog.since(
            Long.parseLong(req.query.getOrDefault("since", "0")), req.intParam("timeout", 1000)));
        route("/find", true, req -> {
            String blocks = req.query.get("blocks");
            if (blocks == null) throw new IllegalArgumentException("?blocks=minecraft:coal_ore,... is required");
            Set<String> ids = new HashSet<>();
            for (String id : blocks.split(",")) ids.add(id.contains(":") ? id.trim() : "minecraft:" + id.trim());
            boolean exposed = Boolean.parseBoolean(req.query.getOrDefault("exposed", "false"));
            return onClient(c -> WorldInfo.find(c, ids, req.intParam("radius", 32), req.intParam("limit", 50), exposed));
        });
        route("/dark", true, req -> onClient(c -> WorldInfo.dark(c, req.intParam("radius", 8),
            req.intParam("maxLight", 0), req.intParam("limit", 20))));
        route("/blocks", true, req -> {
            BlockPos a = parsePos(req.query.get("from"));
            BlockPos b = parsePos(req.query.get("to"));
            boolean props = "1".equals(req.query.get("props")) || "true".equals(req.query.get("props"));
            return onClient(c -> WorldInfo.region(c, a, b, props));
        });
        // Plan only (no movement): the route travel would take from here — walk/bridge/pillar/mine steps. A fast
        // regression check for pathfinder changes ("does the plan bridge the lava?") without walking it.
        route("/plan", true, req -> {
            BlockPos to = parsePos(req.query.get("to"));
            double range = Double.parseDouble(req.query.getOrDefault("range", "1.5"));
            boolean brk = !"false".equals(req.query.get("break"));
            boolean place = !"false".equals(req.query.get("place"));
            int nodes = req.intParam("nodes", 60000);
            return onClient(c -> {
                if (c.player == null || c.world == null) throw new IllegalStateException("not in a world");
                var planner = new dev.anaka.util.Pathfinder(c.world, c.player, c.player.getBlockPos(),
                    pos -> Math.sqrt(pos.getSquaredDistance(to)) <= range + 0.5, to,
                    new dev.anaka.util.Pathfinder.Options(brk, place, 64,
                        new it.unimi.dsi.fastutil.longs.LongOpenHashSet()), nodes);
                while (!planner.isDone()) planner.step(5000);
                var out = new com.google.gson.JsonObject();
                long t0 = System.nanoTime();
                out.addProperty("found", planner.found());
                out.addProperty("expanded", planner.expanded());
                out.addProperty("seconds", Math.round(planner.estimatedSeconds() * 10) / 10.0);   // estimated travel time
                var steps = new com.google.gson.JsonArray();
                int floors = 0, pillars = 0, mines = 0;
                for (var s : planner.path()) {
                    var st = new com.google.gson.JsonObject();
                    st.addProperty("x", s.node().getX());
                    st.addProperty("y", s.node().getY());
                    st.addProperty("z", s.node().getZ());
                    var acts = new com.google.gson.JsonArray();
                    for (var act : s.actions()) {
                        acts.add(act.kind() + " " + act.pos().getX() + "," + act.pos().getY() + "," + act.pos().getZ());
                        switch (act.kind()) {
                            case FLOOR -> floors++;
                            case PILLAR -> pillars++;
                            case MINE -> mines++;
                        }
                    }
                    st.add("actions", acts);
                    steps.add(st);
                }
                out.add("steps", steps);
                out.addProperty("floors", floors);
                out.addProperty("pillars", pillars);
                out.addProperty("mines", mines);
                return out;
            });
        });

        route("/task", true, req -> {
            long wait = Math.min(MAX_WAIT_SECONDS, req.intParam("wait", 0));
            if (req.method.equals("GET")) {
                int id = req.intParam("id", -1);
                Task task = onClientTask(c -> Agent.get().find(id));
                if (task == null) throw new IllegalArgumentException("no task " + id);
                awaitQuietly(task, wait);
                return onClient(c -> task.toJson());
            }
            boolean append = req.body.has("append") && req.body.get("append").getAsBoolean();
            if (req.body.has("tasks")) {
                // Validate every task before touching the queue so a typo can't leave half a plan running.
                boolean stopOnFailure = req.body.has("stopOnFailure") && req.body.get("stopOnFailure").getAsBoolean();
                List<Task> tasks = onClientTasks(c -> {
                    List<Task> built = new ArrayList<>();
                    for (var el : req.body.getAsJsonArray("tasks")) built.add(TaskFactory.create(c, el.getAsJsonObject()));
                    for (int i = 0; i < built.size(); i++) {
                        Task t = built.get(i);
                        t.stopOnFailure = stopOnFailure;
                        Agent.get().submit(c, t, append || i > 0);
                        t.chain = built.get(0).id;
                    }
                    return built;
                });
                if (!tasks.isEmpty()) awaitQuietly(tasks.get(tasks.size() - 1), wait);
                return onClient(c -> {
                    JsonArray arr = new JsonArray();
                    for (Task t : tasks) arr.add(t.toJson());
                    JsonObject o = new JsonObject();
                    o.add("tasks", arr);
                    return o;
                });
            }
            Task task = onClientTask(c -> Agent.get().submit(c, TaskFactory.create(c, req.body), append));
            awaitQuietly(task, wait);
            return onClient(c -> task.toJson());
        });
        route("/tasks", true, req -> onClient(c -> {
            JsonArray arr = new JsonArray();
            for (Task t : Agent.get().history()) arr.add(t.toJson());
            JsonObject o = new JsonObject();
            o.add("tasks", arr);
            return o;
        }));
        route("/stop", true, req -> onClient(c -> {
            Agent.get().cancelAll("stopped via API");
            return ok();
        }));
        route("/takeover", true, req -> onClient(c -> {
            Agent.get().takeover(c);
            return ok();
        }));
        route("/release", true, req -> onClient(c -> {
            Agent.get().release(c, "released via API");
            return ok();
        }));
        route("/control", true, req -> onClient(c -> {
            Agent.get().setPaused(c, req.body.get("paused").getAsBoolean());
            return ok();
        }));
        route("/resume", true, req -> onClient(c -> {
            c.options.pauseOnLostFocus = false;
            if (c.currentScreen instanceof GameMenuScreen) c.setScreen(null);
            return ok();
        }));
        route("/respawn", true, req -> onClient(c -> {
            if (!c.player.isDead()) throw new IllegalArgumentException("player is alive");
            c.player.requestRespawn();
            c.setScreen(null);
            return ok();
        }));
        route("/hotbar", true, req -> onClient(c -> {
            int slot = req.body.get("slot").getAsInt();
            if (slot < 0 || slot > 8) throw new IllegalArgumentException("slot must be 0-8");
            c.player.getInventory().setSelectedSlot(slot);
            return ok();
        }));
        route("/click", true, req -> onClient(c -> {
            int slot = req.body.get("slot").getAsInt();
            int button = req.body.has("button") ? req.body.get("button").getAsInt() : 0;
            String action = req.body.has("action") ? req.body.get("action").getAsString() : "PICKUP";
            SlotActionType type = SlotActionType.valueOf(action.toUpperCase());
            var handler = c.player.currentScreenHandler;
            if (slot < -999 || slot >= handler.slots.size()) throw new IllegalArgumentException("slot out of range");
            c.interactionManager.clickSlot(handler.syncId, slot, button, type, c.player);
            return WorldInfo.container(c);
        }));
        route("/close", true, req -> onClient(c -> {
            c.player.closeHandledScreen();
            return ok();
        }));
        // Screen buttons the slot clicks can't reach: enchanting options (0-2), stonecutter/loom entries, …
        route("/button", true, req -> onClient(c -> {
            int id = req.body.get("id").getAsInt();
            var handler = c.player.currentScreenHandler;
            c.interactionManager.clickButton(handler.syncId, id);
            return WorldInfo.container(c);
        }));
        // Villager trading: select an offer (the game moves the payment from the inventory into the input slots).
        route("/trade", true, req -> onClient(c -> {
            int index = req.body.get("index").getAsInt();
            if (!(c.player.currentScreenHandler instanceof net.minecraft.screen.MerchantScreenHandler merchant)) {
                throw new IllegalArgumentException("no villager trade screen is open");
            }
            merchant.setRecipeIndex(index);
            merchant.switchTo(index);
            c.getNetworkHandler().sendPacket(new net.minecraft.network.packet.c2s.play.SelectMerchantTradeC2SPacket(index));
            return WorldInfo.container(c);
        }));
        // Anvil: the new item name (sending the current name keeps it) — the output slot then shows the result.
        route("/rename", true, req -> onClient(c -> {
            String name = req.body.has("name") ? req.body.get("name").getAsString() : "";
            if (!(c.player.currentScreenHandler instanceof net.minecraft.screen.AnvilScreenHandler anvil)) {
                throw new IllegalArgumentException("no anvil screen is open");
            }
            anvil.setNewItemName(name);
            c.getNetworkHandler().sendPacket(new net.minecraft.network.packet.c2s.play.RenameItemC2SPacket(name));
            return WorldInfo.container(c);
        }));
        route("/chat", true, req -> onClient(c -> {
            String msg = req.body.get("message").getAsString();
            if (msg.startsWith("/")) c.player.networkHandler.sendChatCommand(msg.substring(1));
            else c.player.networkHandler.sendChatMessage(msg);
            return ok();
        }));

        server.start();
        Anaka.LOG.info("Anaka API listening on http://127.0.0.1:{} (token in {})", config.port, Config.path());
    }

    void stop() {
        if (server != null) server.stop(0);
    }

    private void route(String path, boolean needsWorld, Handler handler) {
        server.createContext(path, exchange -> {
            try (exchange) {
                if (exchange.getRequestHeaders().containsKey("Origin")) {
                    send(exchange, 403, error("browser requests are not allowed"));
                    return;
                }
                if (!authorized(exchange)) {
                    send(exchange, 401, error("missing or wrong token; see " + Config.path()));
                    return;
                }
                if (needsWorld && (client.player == null || client.world == null)) {
                    send(exchange, 409, error("not in a world"));
                    return;
                }
                if (needsWorld && !Anaka.automationAllowed(client)) {
                    send(exchange, 403, error("automation is disabled on multiplayer servers (allowMultiplayer in config)"));
                    return;
                }
                String method = exchange.getRequestMethod().toUpperCase();
                if (method.equals("POST") && Agent.get().isPaused() && !path.equals("/release") && !path.equals("/control")) {
                    // The player took over: nothing may change their game (close screens, click, move) until handed back.
                    send(exchange, 409, error("player has control (press the toggle key to hand it back)"));
                    return;
                }
                JsonObject body = new JsonObject();
                if (method.equals("POST")) {
                    String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    if (!raw.isBlank()) body = JsonParser.parseString(raw).getAsJsonObject();
                }
                JsonObject out = handler.handle(new Request(method, parseQuery(exchange.getRequestURI().getRawQuery()), body));
                send(exchange, 200, out);
            } catch (IllegalArgumentException | IllegalStateException e) {
                sendQuietly(exchange, 400, error(e.getMessage()));
            } catch (Exception e) {
                Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
                if (cause instanceof IllegalArgumentException) {
                    sendQuietly(exchange, 400, error(cause.getMessage()));
                } else {
                    Anaka.LOG.error("Anaka request {} failed", path, cause);
                    sendQuietly(exchange, 500, error(String.valueOf(cause)));
                }
            }
        });
    }

    private boolean authorized(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith("Bearer ")) return false;
        byte[] given = header.substring(7).trim().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(given, config.token.getBytes(StandardCharsets.UTF_8));
    }

    private JsonObject onClient(Function<MinecraftClient, JsonObject> fn) throws Exception {
        return client.submit(() -> fn.apply(client)).get(15, TimeUnit.SECONDS);
    }

    private Task onClientTask(Function<MinecraftClient, Task> fn) throws Exception {
        return client.submit(() -> fn.apply(client)).get(15, TimeUnit.SECONDS);
    }

    private List<Task> onClientTasks(Function<MinecraftClient, List<Task>> fn) throws Exception {
        return client.submit(() -> fn.apply(client)).get(15, TimeUnit.SECONDS);
    }

    private static void awaitQuietly(Task task, long seconds) throws InterruptedException, ExecutionException {
        if (seconds <= 0) return;
        try {
            task.done.get(seconds, TimeUnit.SECONDS);
        } catch (TimeoutException ignored) {
            // Still running; the caller polls again.
        }
    }

    private JsonObject status() {
        JsonObject o = new JsonObject();
        o.addProperty("mod", Anaka.MOD_ID);
        o.addProperty("version", FabricLoader.getInstance().getModContainer(Anaka.MOD_ID)
            .map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("unknown"));
        o.addProperty("inWorld", client.player != null);
        o.addProperty("singleplayer", client.isInSingleplayer());
        o.addProperty("allowed", Anaka.automationAllowed(client));
        o.addProperty("controlling", Agent.get().isControlling());
        o.addProperty("paused", Agent.get().isPaused());
        return o;
    }

    private static BlockPos parsePos(String s) {
        if (s == null) throw new IllegalArgumentException("from=x,y,z and to=x,y,z are required");
        int[] v = Arrays.stream(s.split(",")).map(String::trim).mapToInt(Integer::parseInt).toArray();
        if (v.length != 3) throw new IllegalArgumentException("positions are x,y,z");
        return new BlockPos(v[0], v[1], v[2]);
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String pair : raw.split("&")) {
            int i = pair.indexOf('=');
            String k = URLDecoder.decode(i < 0 ? pair : pair.substring(0, i), StandardCharsets.UTF_8);
            String v = i < 0 ? "" : URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8);
            out.put(k, v);
        }
        return out;
    }

    private static JsonObject ok() {
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        return o;
    }

    private static JsonObject error(String msg) {
        JsonObject o = new JsonObject();
        o.addProperty("error", msg);
        return o;
    }

    private static void sendQuietly(HttpExchange exchange, int status, JsonObject body) {
        try {
            send(exchange, status, body);
        } catch (IOException ignored) {
            // Client went away.
        }
    }

    private static void send(HttpExchange exchange, int status, JsonObject body) throws IOException {
        byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}

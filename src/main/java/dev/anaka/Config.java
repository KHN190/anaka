package dev.anaka;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;

public final class Config {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public int port = 27599;
    /** Clients must send this in the Authorization: Bearer header. Generated on first launch. */
    public String token = "";
    /** Many servers forbid automation. Off by default; singleplayer always works. */
    public boolean allowMultiplayer = false;
    public boolean showHud = true;

    public static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("anaka.json");
    }

    public static Config load() {
        Path file = path();
        Config cfg = null;
        if (Files.exists(file)) {
            try {
                cfg = GSON.fromJson(Files.readString(file), Config.class);
            } catch (IOException | RuntimeException e) {
                Anaka.LOG.warn("Unreadable {}, regenerating", file, e);
            }
        }
        if (cfg == null) cfg = new Config();
        if (cfg.token == null || cfg.token.length() < 16) {
            byte[] bytes = new byte[24];
            new SecureRandom().nextBytes(bytes);
            cfg.token = HexFormat.of().formatHex(bytes);
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(cfg));
        } catch (IOException e) {
            Anaka.LOG.error("Could not write {}", file, e);
        }
        return cfg;
    }
}

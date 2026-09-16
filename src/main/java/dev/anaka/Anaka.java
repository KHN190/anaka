package dev.anaka;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Anaka implements ClientModInitializer {
    public static final String MOD_ID = "anaka";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    private static Config config;
    private static KeyBinding releaseKey;
    private static HttpApi api;

    @Override
    public void onInitializeClient() {
        config = Config.load();

        KeyBinding.Category category = KeyBinding.Category.create(Identifier.of(MOD_ID, "main"));
        releaseKey = KeyBindingHelper.registerKeyBinding(
            new KeyBinding("key.anaka.release", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_K, category));

        ClientTickEvents.START_CLIENT_TICK.register(client -> Agent.get().onStartTick(client));
        ClientTickEvents.END_CLIENT_TICK.register(client -> Agent.get().onEndTick(client));
        // Recorded on the server tick, not the client tick: /tick sprint runs the server flat out while the client
        // renders at its own pace, and a client-side recorder would drop most of a sprinted fight. Recording runs
        // whether or not the agent is driving — a fight is worth studying either way.
        ServerTickEvents.END_WORLD_TICK.register(CombatRecorder::tick);
        ServerLivingEntityEvents.AFTER_DAMAGE.register(
            (entity, source, base, taken, blocked) -> CombatRecorder.damaged(entity, source, taken));

        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            api = new HttpApi(client, config);
            api.start();
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            if (api != null) api.stop();
        });
    }

    public static Config config() {
        return config;
    }

    public static int releaseKeyCode() {
        return KeyBindingHelper.getBoundKeyOf(releaseKey).getCode();
    }

    public static Text releaseKeyName() {
        return KeyBindingHelper.getBoundKeyOf(releaseKey).getLocalizedText();
    }

    /** Automation is allowed in singleplayer, or on servers only when the user opted in. */
    public static boolean automationAllowed(MinecraftClient client) {
        return config.allowMultiplayer || client.isInSingleplayer();
    }
}

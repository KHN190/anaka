package dev.anaka.mixin;

import dev.anaka.Agent;
import dev.anaka.Anaka;
import net.minecraft.client.Keyboard;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.KeyInput;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Keyboard.class)
public abstract class KeyboardMixin {
    @Inject(method = "onKey", at = @At("HEAD"), cancellable = true)
    private void agentBridge$lockKeys(long window, int action, KeyInput input, CallbackInfo ci) {
        Agent agent = Agent.get();
        MinecraftClient client = MinecraftClient.getInstance();
        boolean toggleKey = input.key() == Anaka.releaseKeyCode();
        if (agent.isControlling()) {
            if (toggleKey && action == GLFW.GLFW_PRESS) agent.toggle(client);
            ci.cancel();
        } else if (agent.isPaused() && toggleKey && action == GLFW.GLFW_PRESS && client.currentScreen == null) {
            // Only outside screens, so typing the letter in chat doesn't hand control back.
            agent.toggle(client);
            ci.cancel();
        }
    }
}

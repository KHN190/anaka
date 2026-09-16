package dev.anaka.mixin;

import dev.anaka.Agent;
import net.minecraft.client.Mouse;
import net.minecraft.client.input.MouseInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mouse.class)
public abstract class MouseMixin {
    @Shadow
    private double cursorDeltaX;
    @Shadow
    private double cursorDeltaY;

    @Inject(method = "updateMouse", at = @At("HEAD"), cancellable = true)
    private void agentBridge$lockLook(double timeDelta, CallbackInfo ci) {
        if (Agent.get().isControlling()) {
            // Drop accumulated movement so the camera doesn't jump when control is returned.
            cursorDeltaX = 0;
            cursorDeltaY = 0;
            ci.cancel();
        }
    }

    @Inject(method = "onMouseButton", at = @At("HEAD"), cancellable = true)
    private void agentBridge$lockButtons(long window, MouseInput input, int action, CallbackInfo ci) {
        if (Agent.get().isControlling()) ci.cancel();
    }

    @Inject(method = "onMouseScroll", at = @At("HEAD"), cancellable = true)
    private void agentBridge$lockScroll(long window, double horizontal, double vertical, CallbackInfo ci) {
        if (Agent.get().isControlling()) ci.cancel();
    }
}

package dev.smartwhale.bridge.mixin;

import dev.smartwhale.bridge.Headless;
import com.mojang.blaze3d.platform.Window;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Window.class)
public abstract class WindowMixin {
    @Shadow @Final private long window;

    // Only effective when FML's early window is disabled; otherwise FML hands over an already-created window.
    @Inject(method = "<init>", at = @At(value = "INVOKE",
            target = "Lnet/neoforged/fml/loading/ImmediateWindowHandler;setupMinecraftWindow(Ljava/util/function/IntSupplier;Ljava/util/function/IntSupplier;Ljava/util/function/Supplier;Ljava/util/function/LongSupplier;)J",
            remap = false), require = 0)
    private void smartwhale$invisibleHint(CallbackInfo ci) {
        if (Headless.ENABLED) {
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_FOCUS_ON_SHOW, GLFW.GLFW_FALSE);
        }
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void smartwhale$hide(CallbackInfo ci) {
        if (Headless.ENABLED) GLFW.glfwHideWindow(window);
    }

    @Inject(method = "updateDisplay", at = @At("TAIL"))
    private void smartwhale$keepHidden(CallbackInfo ci) {
        if (Headless.ENABLED && GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_VISIBLE) == GLFW.GLFW_TRUE) {
            GLFW.glfwHideWindow(window);
        }
    }
}

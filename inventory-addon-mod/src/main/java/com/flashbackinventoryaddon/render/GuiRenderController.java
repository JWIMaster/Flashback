package com.flashbackinventoryaddon.render;

import com.flashbackinventoryaddon.FlashbackInventoryAddon;
import com.flashbackinventoryaddon.config.ModConfig;
import com.flashbackinventoryaddon.flashback.FlashbackCompat;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.VertexSorter;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.BufferBuilderStorage;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.util.Window;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

/**
 * Renders GUI layers directly into the main framebuffer while Flashback is exporting a replay.
 */
public final class GuiRenderController {

    private static ModConfig config;
    private static boolean forcingGui;

    private GuiRenderController() {
    }

    public static void reloadConfig(ModConfig newConfig) {
        config = newConfig;
    }

    public static boolean isForcingGui() {
        return forcingGui;
    }

    public static void onFrameStart() {
        // No-op placeholder for future per-frame bookkeeping; keeps mixin lean.
    }

    public static void renderIfNeeded(RenderTickCounter renderTickCounter) {
        if (!shouldRenderGui()) {
            return;
        }

        forcingGui = true;
        try {
            renderGui(renderTickCounter);
        } finally {
            forcingGui = false;
        }
    }

    private static boolean shouldRenderGui() {
        if (config == null || !config.enableGuiInFlashbackReplays) {
            return false;
        }
        if (!FlashbackCompat.isFlashbackPresent()) {
            return false;
        }
        if (!FlashbackCompat.isExporting()) {
            return false;
        }

        // Keep the feature scoped to actual replay rendering sessions.
        if (!FlashbackCompat.isInReplay()) {
            return false;
        }

        // Prefer to only kick in during first-person spectate, but allow fallback to general replay state.
        AbstractClientPlayerEntity spectating = FlashbackCompat.getSpectatingPlayer();
        return spectating != null || MinecraftClient.getInstance().cameraEntity instanceof AbstractClientPlayerEntity;
    }

    private static void renderGui(RenderTickCounter renderTickCounter) {
        MinecraftClient minecraft = MinecraftClient.getInstance();
        if (minecraft.world == null) {
            return;
        }

        Window window = minecraft.getWindow();
        Framebuffer framebuffer = minecraft.getFramebuffer();
        framebuffer.beginWrite(true);

        int framebufferWidth = window.getFramebufferWidth();
        int framebufferHeight = window.getFramebufferHeight();
        if (framebufferWidth <= 0 || framebufferHeight <= 0) {
            return;
        }

        int scaledWidth = window.getScaledWidth();
        int scaledHeight = window.getScaledHeight();

        float tickDelta = renderTickCounter.getTickDelta(true);

        // Prepare orthographic projection for 2D rendering
        Matrix4f projection = new Matrix4f().setOrtho(0.0F, (float) scaledWidth, (float) scaledHeight, 0.0F, 1000.0F, 3000.0F);
        Matrix4f previousProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorter previousSorter = RenderSystem.getVertexSorting();
        RenderSystem.setProjectionMatrix(projection, VertexSorter.BY_Z);

        Matrix4fStack modelViewStack = RenderSystem.getModelViewStack();
        modelViewStack.pushMatrix();
        modelViewStack.identity();
        RenderSystem.applyModelViewMatrix();

        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);

        BufferBuilderStorage renderBuffers = minecraft.getBufferBuilders();
        DrawContext drawContext = new DrawContext(minecraft, renderBuffers.getEntityVertexConsumers());

        // HUD first so screens render over it.
        minecraft.inGameHud.render(drawContext, renderTickCounter);

        Screen screen = minecraft.currentScreen;
        Mouse mouse = minecraft.mouse;
        boolean showCursor = screen != null || !mouse.isCursorLocked();

        int mouseX = (int) (mouse.getX() * (double) scaledWidth / (double) framebufferWidth);
        int mouseY = (int) (mouse.getY() * (double) scaledHeight / (double) framebufferHeight);

        if (screen != null) {
            screen.render(drawContext, mouseX, mouseY, tickDelta);
        }

        if (showCursor) {
            CursorRenderer.render(drawContext, mouseX, mouseY);
        }

        drawContext.draw();

        RenderSystem.disableBlend();
        RenderSystem.enableDepthTest();

        modelViewStack.popMatrix();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setProjectionMatrix(previousProjection, previousSorter);
    }
}

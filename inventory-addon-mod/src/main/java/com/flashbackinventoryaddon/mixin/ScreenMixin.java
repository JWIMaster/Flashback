package com.flashbackinventoryaddon.mixin;

import com.flashbackinventoryaddon.render.GuiRenderController;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Screen.class)
public abstract class ScreenMixin {

    @Inject(method = "render", at = @At("HEAD"))
    private void flashbackaddon$prepScreen(DrawContext drawContext, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (GuiRenderController.isForcingGui()) {
            RenderSystem.disableDepthTest();
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
        }
    }
}

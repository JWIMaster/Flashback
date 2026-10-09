package com.flashbackinventoryaddon.mixin;

import com.flashbackinventoryaddon.render.GuiRenderController;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InGameHud.class)
public class GuiMixin {

    @Inject(method = "render", at = @At("HEAD"))
    private void flashbackaddon$prepHud(DrawContext drawContext, RenderTickCounter renderTickCounter, CallbackInfo ci) {
        if (GuiRenderController.isForcingGui()) {
            RenderSystem.disableDepthTest();
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
        }
    }
}

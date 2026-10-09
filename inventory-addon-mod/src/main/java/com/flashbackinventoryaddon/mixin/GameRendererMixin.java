package com.flashbackinventoryaddon.mixin;

import com.flashbackinventoryaddon.render.GuiRenderController;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class GameRendererMixin {

    @Inject(method = "render", at = @At("TAIL"))
    private void flashbackaddon$renderGuiLayer(RenderTickCounter renderTickCounter, boolean tick, CallbackInfo ci) {
        GuiRenderController.renderIfNeeded(renderTickCounter);
    }
}

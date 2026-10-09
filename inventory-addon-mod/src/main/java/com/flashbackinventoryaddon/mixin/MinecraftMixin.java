package com.flashbackinventoryaddon.mixin;

import com.flashbackinventoryaddon.render.GuiRenderController;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public class MinecraftMixin {

    @Inject(method = "render", at = @At("HEAD"))
    private void flashbackaddon$onRender(boolean tick, CallbackInfo ci) {
        GuiRenderController.onFrameStart();
    }
}

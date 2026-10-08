package com.moulberry.flashback.mixin.gui;

import com.moulberry.flashback.gui.GuiRecorder;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sees every screen the player opens and closes.
 *
 * <p>Both targets take no arguments, so this depends on two method names and on nothing else: no
 * parameter type can have changed underneath it, and a name that changes is a skipped injection
 * rather than a game that will not start.
 */
@Mixin(Screen.class)
public class MixinScreenGuiRecorder {

    @Inject(method = "init()V", at = @At("TAIL"), require = 0)
    public void onInit(CallbackInfo ci) {
        GuiRecorder.screenOpened((Screen) (Object) this);
    }

    @Inject(method = "removed()V", at = @At("HEAD"), require = 0)
    public void onRemoved(CallbackInfo ci) {
        GuiRecorder.screenClosed((Screen) (Object) this);
    }

}

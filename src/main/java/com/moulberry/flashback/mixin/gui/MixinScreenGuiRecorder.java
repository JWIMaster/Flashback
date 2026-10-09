package com.moulberry.flashback.mixin.gui;

import com.moulberry.flashback.gui.GuiRecorder;
import com.moulberry.flashback.gui.GuiRecording;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sees every screen the player opens and closes.
 *
 * <p>Two things want to know. One records the interfaces a player uses into a readable log; the
 * other writes the ones the server never hears about into the replay, so that watching a replay
 * shows the inventory the recorded player was looking at rather than a player standing still.
 *
 * <p>The recording uses {@code added} rather than {@code init} on purpose. A container screen
 * overrides {@code init} and {@code removed}, and an injection into a method that a subclass
 * overrides never runs for that subclass, so anything hooked there is blind to exactly the screens
 * this is about. Container screens have their own hooks in {@link MixinAbstractContainerScreen}.
 *
 * <p>Every target here takes no arguments, so this depends on method names and on nothing else: no
 * parameter type can have changed underneath it, and a name that changes is a skipped injection
 * rather than a game that will not start.
 */
@Mixin(Screen.class)
public class MixinScreenGuiRecorder {

    @Inject(method = "added()V", at = @At("TAIL"), require = 0)
    public void onAdded(CallbackInfo ci) {
        GuiRecording.screenShown((Screen) (Object) this);
    }

    @Inject(method = "init()V", at = @At("TAIL"), require = 0)
    public void onInit(CallbackInfo ci) {
        GuiRecorder.screenOpened((Screen) (Object) this);
    }

    @Inject(method = "removed()V", at = @At("HEAD"), require = 0)
    public void onRemoved(CallbackInfo ci) {
        GuiRecorder.screenClosed((Screen) (Object) this);
    }

}

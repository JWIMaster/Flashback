package com.moulberry.flashback.mixin.gui;

import com.moulberry.flashback.gui.GuiRecorder;
import com.moulberry.flashback.gui.GuiRecording;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sees container screens open and close, and notes when the player clicks in one.
 *
 * <p>These have to be hooked on this class rather than on {@code Screen}: a container screen
 * overrides both {@code init} and {@code removed}, and an injection into a method that a subclass
 * overrides never runs for that subclass. That is how a chest could be opened, used and closed in a
 * recording with nothing recording it.
 *
 * <p>The handlers deliberately declare no target arguments. The real signature of {@code slotClicked}
 * differs between builds of the game - it took an {@code int} button in one and a mouse event object
 * in another - and naming the wrong one stops the game from starting. Taking nothing means there is
 * nothing to get wrong.
 */
@Mixin(AbstractContainerScreen.class)
public class MixinAbstractContainerScreen {

    @Inject(method = "init()V", at = @At("TAIL"), require = 0)
    public void onInit(CallbackInfo ci) {
        GuiRecorder.screenOpened((AbstractContainerScreen<?>) (Object) this);
    }

    @Inject(method = "removed()V", at = @At("HEAD"), require = 0)
    public void onRemoved(CallbackInfo ci) {
        AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) (Object) this;
        GuiRecorder.screenClosed(screen);
        GuiRecording.screenClosed(screen);
    }

    @Inject(method = "tick()V", at = @At("TAIL"), require = 0)
    public void onTick(CallbackInfo ci) {
        // Once a tick, while a container is on screen: writes the changes the client made and the
        // server never repeated, which is the only place they can be caught.
        GuiRecording.tick();
    }

    @Inject(method = "slotClicked", at = @At("HEAD"), require = 0)
    public void onSlotClicked(CallbackInfo ci) {
        GuiRecorder.slotClicked((AbstractContainerScreen<?>) (Object) this);
    }

}

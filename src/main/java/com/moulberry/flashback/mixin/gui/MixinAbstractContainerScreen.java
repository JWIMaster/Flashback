package com.moulberry.flashback.mixin.gui;

import com.moulberry.flashback.gui.GuiRecorder;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Notes that the player clicked in a container.
 *
 * <p>The handler deliberately declares no target arguments. The real signature of this method differs
 * between builds of the game - it took an {@code int} button in one and a mouse event object in
 * another - and naming the wrong one stops the game from starting. Taking nothing means there is
 * nothing to get wrong; the slot is looked up separately, and the item movements are worked out by
 * comparing the container with itself either way.
 */
@Mixin(AbstractContainerScreen.class)
public class MixinAbstractContainerScreen {

    @Inject(method = "slotClicked", at = @At("HEAD"), require = 0)
    public void onSlotClicked(CallbackInfo ci) {
        GuiRecorder.slotClicked((AbstractContainerScreen<?>) (Object) this);
    }

}

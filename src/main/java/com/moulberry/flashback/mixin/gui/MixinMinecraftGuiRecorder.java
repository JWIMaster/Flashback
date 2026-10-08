package com.moulberry.flashback.mixin.gui;

import com.moulberry.flashback.gui.GuiRecorder;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Checks the open container once a tick, which is when its contents have settled.
 *
 * <p>No target arguments, for the same reason as the other hooks: the fewer things this depends on,
 * the less there is that a game update can break.
 */
@Mixin(Minecraft.class)
public class MixinMinecraftGuiRecorder {

    @Inject(method = "tick()V", at = @At("TAIL"), require = 0)
    public void onTick(CallbackInfo ci) {
        GuiRecorder.tick();
    }

}

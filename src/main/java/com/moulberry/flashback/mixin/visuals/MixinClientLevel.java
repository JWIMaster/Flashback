package com.moulberry.flashback.mixin.visuals;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.ext.ClientLevelExt;
import com.moulberry.flashback.playback.WorldAnimationClock;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
public class MixinClientLevel implements ClientLevelExt {

    @Unique
    private final WorldAnimationClock flashback$animationClock = new WorldAnimationClock();

    @Inject(method = "tickTime", at = @At("HEAD"))
    private void flashback$advanceAnimationClock(CallbackInfo ci) {
        // This method only runs when the world advances: pause/freeze stops this clock too.
        // The same clock drives preview and export so both look identical.
        if (Flashback.isInReplay()) {
            this.flashback$animationClock.tick(((ClientLevel) (Object) this).getGameTime());
        } else {
            this.flashback$animationClock.reset();
        }
    }

    @Override
    public long flashback$getAnimationGameTime() {
        return this.flashback$animationClock.sample(((ClientLevel) (Object) this).getGameTime());
    }


//    @Inject(method = "getSkyColor", at = @At("HEAD"), cancellable = true)
//    public void getSkyColor(Vec3 vec3, float f, CallbackInfoReturnable<Integer> cir) {
//        EditorState editorState = EditorStateManager.getCurrent();
//        if (editorState != null) {
//            ReplayVisuals visuals = editorState.replayVisuals;
//
//            if (!visuals.renderSky) {
//                if (Flashback.isExporting() && Flashback.EXPORT_JOB.getSettings().transparent()) {
//                    cir.setReturnValue(0);
//                } else {
//                    float[] skyColour = visuals.skyColour;
//                    int r = (int)(skyColour[0] * 255);
//                    int g = (int)(skyColour[1] * 255);
//                    int b = (int)(skyColour[2] * 255);
//                    cir.setReturnValue(0xFF000000 | (r << 16) | (g << 8) | b);
//                }
//            }
//        }
//    }

    /*
     * Some entities have weird behaviour when rotating in a replay
     * This code here ensures that the interpolation of yRotO -> yRot will always be the shortest path
     */



    @Inject(method = "tickNonPassenger", at = @At("RETURN"), require = 0)
    public void tickNonPassengerEnd(Entity entity, CallbackInfo ci) {
        if (Flashback.isInReplay()) {
            entity.xRotO = Mth.wrapDegrees(entity.xRotO - entity.getXRot()) + entity.getXRot();
            entity.yRotO = Mth.wrapDegrees(entity.yRotO - entity.getYRot()) + entity.getYRot();
        }
    }

    @Inject(method = "tickPassenger", at = @At("RETURN"), require = 0)
    public void tickPassengerEnd(Entity vehicle, Entity entity, CallbackInfo ci) {
        if (Flashback.isInReplay()) {
            entity.xRotO = Mth.wrapDegrees(entity.xRotO - entity.getXRot()) + entity.getXRot();
            entity.yRotO = Mth.wrapDegrees(entity.yRotO - entity.getYRot()) + entity.getYRot();
        }
    }

}

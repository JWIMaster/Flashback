package com.moulberry.flashback.mixin.playback;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.playback.ReplayServer;
import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.state.EditorStateManager;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PositionPath;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

@Mixin(Entity.class)
public abstract class MixinEntity {

    @Shadow
    public abstract boolean isInvisible();

    @Shadow
    private Level level;

    @Shadow
    public abstract boolean isInvisibleTo(Player player);

    @Shadow
    public abstract UUID getUUID();

    // Force entities to be able to ride players on servers
    @WrapOperation(method = "startRiding(Lnet/minecraft/world/entity/Entity;ZZ)Z", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;isClientSide()Z"))
    public boolean startRiding_isClientSide(Level instance, Operation<Boolean> original) {
        if (Flashback.isInReplay()) {
            return true; // Always pretend we're clientside so mounting players is allowed
        }
        return original.call(instance);
    }

    /**
     * Aims an entity at where it is now, rather than walking it through where it has been.
     *
     * <p>A replay sends a position update as a "you are here" sync carrying the path the entity
     * covered to reach that point. That path starts at ticks the client has already drawn, so
     * interpolating along it moves the entity back over ground it has already covered - which is the
     * character suddenly stepping backwards during playback. Interpolating straight to the end of the
     * path is the same movement the client makes for an ordinary position update, so it stays smooth
     * and can only ever go forwards.
     */
    @Inject(method = "moveOrInterpolateTo(Lnet/minecraft/world/entity/PositionPath;FF)V", at = @At("HEAD"), cancellable = true)
    public void moveOrInterpolateTo_endOfPath(PositionPath path, float yRot, float xRot, CallbackInfo ci) {
        if (Flashback.isInReplay() && Flashback.getConfig().advanced.interpolateToLatestPosition) {
            ((Entity) (Object) this).moveOrInterpolateTo(path.endPosition(), yRot, xRot);
            ci.cancel();
        }
    }

    @Inject(method = "isInvisibleTo", at = @At("HEAD"), cancellable = true)
    public void isInvisibleTo(Player player, CallbackInfoReturnable<Boolean> cir) {
        ReplayServer replayServer = Flashback.getReplayServer();
        if (replayServer != null && player == Minecraft.getInstance().player) {
            EditorState editorState = EditorStateManager.getCurrent();
            if (editorState != null && editorState.isEntityHidden((Entity) (Object) this)) {
                cir.setReturnValue(true);
            }

            int localId = replayServer.getLocalPlayerId();
            if (this.level.getEntity(localId) instanceof Player localPlayer) {
                cir.setReturnValue(this.isInvisibleTo(localPlayer));
            } else {
                cir.setReturnValue(this.isInvisible());
            }
        }
    }


}

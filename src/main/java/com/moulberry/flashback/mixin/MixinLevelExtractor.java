package com.moulberry.flashback.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.ext.RemotePlayerExt;
import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.state.EditorStateManager;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.ParticlesRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.ARGB;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelExtractor.class)
public abstract class MixinLevelExtractor {

    @Shadow
    private @Nullable ClientLevel level;

    @Shadow
    protected abstract EntityRenderState extractEntity(Entity entity, float partialTickTime);

    @Shadow
    @Final
    private Minecraft minecraft;

    @Inject(method = "extractBlockDestroyAnimation", at = @At("HEAD"), cancellable = true, require = 0)
    public void renderBlockDestroyAnimation(CallbackInfo ci) {
        EditorState editorState = EditorStateManager.getCurrent();
        if (editorState != null) {
            if (!editorState.replayVisuals.renderBlocks) {
                ci.cancel();
            }
        }
    }

    @Inject(method = "extractPlayerState", at = @At("HEAD"), cancellable = true)
    public void extractPlayerState(Camera camera, DeltaTracker deltaTracker, float worldPartialTicks, PlayerRenderState state, CallbackInfo ci) {
        AbstractClientPlayer player = Flashback.getSpectatingPlayer();
        if (player == null) {
            // The camera is still attached to a player who is not the viewer, so the first-person
            // hand for this frame is theirs - but the replay is midway through replacing that
            // entity and no live instance of them exists yet. Letting this fall through to the
            // game's own extraction fills the hand in from the viewer instead, who is a different
            // person holding different things, which is what made the hand jump about during a
            // shot. Drawing no hand for the frame is the honest answer until they are back.
            if (this.level != null && Minecraft.getInstance().getCameraEntity() instanceof AbstractClientPlayer cameraPlayer
                && cameraPlayer != Minecraft.getInstance().player) {
                ci.cancel();
                state.reset();
                state.hasPlayer = false;
            }
            return;
        }
        if (this.level == null) {
            return;
        }

        ci.cancel();

        state.reset();

        state.hasPlayer = true;
        float playerPartialTick = deltaTracker.getGameTimeDeltaPartialTick(!this.level.tickRateManager().isEntityFrozen(player));
        EntityRenderState entityRenderState = this.extractEntity(player, playerPartialTick);
        if (entityRenderState instanceof AvatarRenderState) {
            AvatarRenderState avatarRenderState = (AvatarRenderState)entityRenderState;
            state.avatarRenderState = avatarRenderState;
            ((RemotePlayerExt)player).flashback$extractFirstPersonHandsAndItems(playerPartialTick, state.firstPersonHandsAndItems);
            state.portalEffectIntensity = 0.0f; // Mth.lerp(worldPartialTicks, player.oPortalEffectIntensity, player.portalEffectIntensity);
            state.nauseaEffectIntensity = player.getEffectBlendFactor(MobEffects.NAUSEA, worldPartialTicks);
            state.spinningEffectAngle = 0.0f; //player.getSpinningEffectAngle(worldPartialTicks);
            state.isUnderWater = player.isUnderWater();
            state.eyePositionY = player.getEyePosition(worldPartialTicks).y;

//            if (player.itemActivation().isActive()) {
//                ItemActivation activation = player.itemActivation();
//                PlayerRenderState.ItemActivationRenderState activationState = new PlayerRenderState.ItemActivationRenderState(activation.item().copy(), activation.ticks(), activation.offX(), activation.offY());
//                this.minecraft.getItemModelResolver().updateForTopItem(activationState.itemState, activationState.item, ItemDisplayContext.FIXED, this.level, (ItemOwner)null, 0);
//                state.itemActivation = activationState;
//            }

            if (camera.entity() instanceof LivingEntity livingEntity && livingEntity.isSleeping()) {
                return;
            }

            BlockState viewBlockingState = flashbackGetViewBlockingState(player, camera.getCullFrustum());
            if (viewBlockingState != null) {
                TextureAtlasSprite sprite = this.minecraft.getModelManager().getBlockStateModelSet().getParticleMaterial(viewBlockingState).sprite();
                state.blockOverlay = new PlayerRenderState.BlockOverlay(sprite.atlasLocation(), sprite.getU0(), sprite.getV0(), sprite.getU1(), sprite.getV1());
            }

            if (this.minecraft.options.getCameraType().isFirstPerson()) {
                state.isEyeInWater = player.isEyeInFluid(FluidTags.WATER);
                state.isOnFire = player.isOnFire();
                if (state.isEyeInWater) {
                    BlockPos eyePos = BlockPos.containing(player.getEyePosition());
                    float brightness = Lightmap.getBrightness(player.level().dimensionType(), player.level().getMaxLocalRawBrightness(eyePos));
                    state.waterOverlay = new PlayerRenderState.WaterOverlay(ARGB.colorFromFloat(0.1F, brightness, brightness, brightness), -player.getYRot() / 64.0F, player.getXRot() / 64.0F);
                }

            }
        } else {
            // A replay replaces its entities as it ticks, so the player resolved at the start of this
            // frame can be gone by the time it is extracted, and a removed entity does not extract
            // into an avatar. Render the frame without a spectated first-person player rather than
            // taking the game down; report it once so a genuine mod conflict - a renderer returning
            // something other than an avatar for a live player - is still visible rather than hidden.
            if (warnedAboutUnexpectedAvatarState.compareAndSet(false, true)) {
                Flashback.LOGGER.error("Spectated player {} did not extract into an AvatarRenderState (got {}). " +
                        "Falling back to the replay's own viewpoint for that frame; further occurrences are not logged.",
                    player.getUUID(), entityRenderState == null ? "null" : entityRenderState.getClass().getName());
            }
            state.hasPlayer = false;
        }
    }

    @Unique
    private static final java.util.concurrent.atomic.AtomicBoolean warnedAboutUnexpectedAvatarState =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    @Unique
    private static @Nullable BlockState flashbackGetViewBlockingState(final AbstractClientPlayer player, final Frustum frustum) {
        if (player.noPhysics) {
            return null;
        } else {
            AABB nearPlaneBB = frustum.getNearPlaneBounds().move(player.getEyePosition());
            BlockPos.MutableBlockPos testPos = new BlockPos.MutableBlockPos();

            for(int i = 0; i < 8; ++i) {
                testPos.set(player.getX() + (double)(((float)((i >> 0) % 2) - 0.5F) * player.getBbWidth() * 0.8F), player.getEyeY() + (double)(((float)((i >> 1) % 2) - 0.5F) * 0.1F * player.getScale()), player.getZ() + (double)(((float)((i >> 2) % 2) - 0.5F) * player.getBbWidth() * 0.8F));
                BlockState blockState = player.level().getBlockState(testPos);
                if (blockState.getRenderShape() != RenderShape.INVISIBLE && blockState.isViewBlocking(player.level(), testPos, nearPlaneBB)) {
                    return blockState;
                }
            }

            return null;
        }
    }

    @WrapWithCondition(method = "extract", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/particle/ParticleEngine;extract(Lnet/minecraft/client/renderer/state/level/ParticlesRenderState;Lnet/minecraft/client/renderer/culling/Frustum;Lnet/minecraft/client/Camera;F)V"))
    public boolean extractLevel_particleEngine_extract(ParticleEngine instance, ParticlesRenderState renderState, Frustum frustum, Camera camera, float partialTickTime) {
        EditorState editorState = EditorStateManager.getCurrent();
        if (editorState != null && !editorState.replayVisuals.renderParticles) {
            renderState.reset();
            return false;
        }
        return true;
    }

}

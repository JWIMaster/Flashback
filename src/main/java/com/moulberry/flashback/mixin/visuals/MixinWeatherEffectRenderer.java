package com.moulberry.flashback.mixin.visuals;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.ext.ClientLevelExt;
import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.state.EditorStateManager;
import com.moulberry.flashback.visuals.WeatherColumnOrientation;
import com.moulberry.flashback.visuals.WeatherTextureCoordinates;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


@Mixin(WeatherEffectRenderer.class)
public class MixinWeatherEffectRenderer {

    @Shadow @Final private float[] columnSizeX;
    @Shadow @Final private float[] columnSizeZ;

    /**
     * The snow/rain curtains are billboards that face the camera, but vanilla derives each
     * column's orientation from <em>integer</em> block offsets from {@code Mth.floor(camera)}.
     * The orientation is therefore piecewise constant and snaps to the next lattice direction
     * every time the camera crosses a block boundary, which sweeps the texture across the quad -
     * the snow visibly "flows, jumps to a different bit, flows, jumps again" while the camera
     * moves. Rebuilding the table from the exact fractional camera offset makes the orientation
     * vary continuously instead.
     */
    @Inject(method = "prepareInstances", at = @At("HEAD"))
    private void flashback$smoothColumnOrientation(CallbackInfo ci, @Local(argsOnly = true) Vec3 camera) {
        if (!Flashback.isInReplay()) {
            return;
        }

        float fractionX = (float) (camera.x - Mth.floor(camera.x));
        float fractionZ = (float) (camera.z - Mth.floor(camera.z));
        if (this.flashback$orientationFractionX == fractionX && this.flashback$orientationFractionZ == fractionZ) {
            return;
        }
        this.flashback$orientationFractionX = fractionX;
        this.flashback$orientationFractionZ = fractionZ;
        WeatherColumnOrientation.fill(this.columnSizeX, this.columnSizeZ, fractionX, fractionZ);
    }

    /**
     * The drawn columns are the square around {@code Mth.floor(camera)}, so crossing a block
     * boundary makes one ring of columns leave the grid and another arrive, and vanilla renders
     * that ring at its maximum alpha (0.8 for snow). Each column's texture phase is its own, so
     * the swap replaces the whole far field with a different-looking one - the snow "flows, jumps
     * to a different bit, flows, jumps again", but only while the camera moves. Fading the
     * outermost band out first means the ring arrives and leaves at zero opacity.
     */
    @Inject(method = "prepareInstances", at = @At("HEAD"))
    private void flashback$beginColumnFade(CallbackInfo ci, @Local(argsOnly = true) int radius) {
        this.flashback$gridRadius = radius;
    }

    @WrapOperation(method = "prepareInstances", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/VertexConsumer;setUv(FF)Lcom/mojang/blaze3d/vertex/VertexConsumer;"))
    public VertexConsumer flashback$anchorWeatherTexture(VertexConsumer instance, float u, float v, Operation<VertexConsumer> original,
                                           @Local WeatherEffectRenderer.ColumnInstance column) {
        if (Flashback.isInReplay()) {
            v = WeatherTextureCoordinates.anchor(v, column.bottomY(), column.topY());
        }
        return original.call(instance, u, v);
    }

    // Note: method targets are written owner;name(args)ret - the owner;name:desc colon form is
    // only for fields.
    @WrapOperation(method = "prepareInstances", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;lengthSquared(FF)F"))
    public float flashback$captureColumnEdge(float dx, float dz, Operation<Float> original) {
        this.flashback$columnEdge = Math.max(Math.abs(dx), Math.abs(dz));
        return original.call(dx, dz);
    }

    @WrapOperation(method = "prepareInstances", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;lerp(FFF)F"))
    public float flashback$fadeColumnAtGridEdge(float delta, float start, float end, Operation<Float> original) {
        float alpha = original.call(delta, start, end);
        if (!Flashback.isInReplay()) {
            return alpha;
        }
        return alpha * WeatherColumnOrientation.edgeFade(this.flashback$gridRadius, this.flashback$columnEdge);
    }

    @Unique private float flashback$gridRadius;
    @Unique private float flashback$columnEdge;

    @Unique
    private float flashback$orientationFractionX = Float.NaN;
    @Unique
    private float flashback$orientationFractionZ = Float.NaN;

    @WrapOperation(method = "extractRenderState", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;getGameTime()J"))
    public long flashback$weatherAnimationTime(ClientLevel instance, Operation<Long> original) {
        long rawGameTime = original.call(instance);
        if (Flashback.isInReplay()) {
            // The columns are a pure function of tick + partial, so both must come from the same
            // client tick boundary. Recorded SetTime packets rewrite gameTime between boundaries,
            // which would make tick + partial jump. This clock only advances when the world
            // actually ticks, so freezing still stops the snow and exports stay reproducible.
            long animationTick = ((ClientLevelExt) instance).flashback$getAnimationGameTime();
            return animationTick;
        }
        return rawGameTime;
    }

    @WrapOperation(method = "extractRenderState", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;getPrecipitationAt(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/biome/Biome$Precipitation;"))
    public Biome.Precipitation getPrecipitationAt(ClientLevel instance, BlockPos pos, Operation<Biome.Precipitation> original) {
        EditorState editorState = EditorStateManager.getCurrent();
        if (editorState != null) {
            switch (editorState.replayVisuals.overrideWeatherMode) {
                case CLEAR, OVERCAST -> {
                    return Biome.Precipitation.NONE;
                }
                case RAINING, THUNDERING -> {
                    return Biome.Precipitation.RAIN;
                }
                case SNOWING -> {
                    return Biome.Precipitation.SNOW;
                }
            }
        }
        return original.call(instance, pos);
    }

}

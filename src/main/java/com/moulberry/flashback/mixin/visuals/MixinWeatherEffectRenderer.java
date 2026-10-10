package com.moulberry.flashback.mixin.visuals;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.ext.ClientLevelExt;
import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.state.EditorStateManager;
import com.moulberry.flashback.visuals.WeatherTextureCoordinates;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(WeatherEffectRenderer.class)
public class MixinWeatherEffectRenderer {

    @WrapOperation(method = "prepareInstances", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/VertexConsumer;setUv(FF)Lcom/mojang/blaze3d/vertex/VertexConsumer;"))
    public VertexConsumer flashback$anchorWeatherTexture(VertexConsumer instance, float u, float v,
                                                         Operation<VertexConsumer> original,
                                                         @Local WeatherEffectRenderer.ColumnInstance column) {
        if (Flashback.isInReplay()) {
            v = WeatherTextureCoordinates.anchor(v, column.bottomY(), column.topY());
        }
        return original.call(instance, u, v);
    }

    @WrapOperation(method = "extractRenderState", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;getGameTime()J"))
    public long flashback$weatherAnimationTime(ClientLevel instance, Operation<Long> original) {
        if (Flashback.isInReplay()) {
            // Recorded SetTime packets can rewrite gameTime between client tick boundaries.
            // Pair the render partial tick with a clock advanced only by actual world ticks.
            return ((ClientLevelExt) instance).flashback$getAnimationGameTime();
        }
        return original.call(instance);
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

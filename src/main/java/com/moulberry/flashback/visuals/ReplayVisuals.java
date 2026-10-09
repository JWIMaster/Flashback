package com.moulberry.flashback.visuals;

import com.moulberry.flashback.combo_options.AspectRatio;
import com.moulberry.flashback.combo_options.Sizing;
import com.moulberry.flashback.combo_options.WeatherOverride;
import net.minecraft.client.Minecraft;

public class ReplayVisuals {

    public boolean showChat = false;
    public boolean showBossBar = false;
    public boolean showTitleText = false;
    public boolean showScoreboard = false;
    public boolean showActionBar = false;
    public boolean showHotbar = true;

    public boolean renderBlocks = true;
    public boolean renderEntities = true;
    public boolean renderPlayers = true;
    public boolean renderParticles = true;
    public boolean renderSky = true;
    public float[] skyColour = new float[]{0f, 1f, 0f};
    public boolean renderNametags = true;
    public boolean renderBeaconBeams = true;

    public boolean overrideFog = false;
    public float overrideFogStart = 0.0f;
    public float overrideFogEnd = 256.0f;

    public boolean overrideFogColour = false;
    public float[] fogColour = new float[]{0f, 1f, 0f};

    public boolean overrideFov = false;
    public float overrideFovAmount = -1f;

    public boolean overrideCameraShake = false;
    public boolean cameraShakeSplitParams = false;
    public float cameraShakeYFrequency = 1.0f;
    public float cameraShakeYAmplitude = 1.0f;
    public float cameraShakeXFrequency = 1.0f;
    public float cameraShakeXAmplitude = 1.0f;

    public boolean overrideRoll = false;
    public float overrideRollAmount = 0.0f;

    public WeatherOverride overrideWeatherMode = WeatherOverride.NONE;

    public long overrideTimeOfDay = -1;

    public boolean overrideNightVision = false;

    public boolean ruleOfThirdsGuide = false;
    public boolean centerGuide = false;
    public boolean cameraPath = true;
    public Sizing sizing = Sizing.KEEP_ASPECT_RATIO;
    public AspectRatio changeAspectRatio = AspectRatio.ASPECT_16_9;

    public boolean disableServerResourcePack = false;

    /** Camera animation is a render result, never a replacement for persisted scene defaults. */
    private transient ReplayVisuals evaluatedCamera;

    private transient boolean cameraFovResolved, cameraRollResolved, cameraShakeResolved;

    public ReplayVisuals cameraVisuals() {
        ReplayVisuals frame = this.evaluatedCamera;
        if (frame == null) return this;
        // Unanimated properties keep following live scene settings, including edits while paused.
        if (!frame.cameraFovResolved) {
            frame.overrideFov = this.overrideFov;
            frame.overrideFovAmount = this.overrideFov ? saneFov(this.overrideFovAmount) : this.overrideFovAmount;
        }
        if (!frame.cameraRollResolved) {
            frame.overrideRoll = this.overrideRoll;
            frame.overrideRollAmount = this.overrideRollAmount;
        }
        if (!frame.cameraShakeResolved) {
            frame.overrideCameraShake = this.overrideCameraShake;
            frame.cameraShakeSplitParams = this.cameraShakeSplitParams;
            frame.cameraShakeXFrequency = this.cameraShakeXFrequency;
            frame.cameraShakeXAmplitude = this.cameraShakeXAmplitude;
            frame.cameraShakeYFrequency = this.cameraShakeYFrequency;
            frame.cameraShakeYAmplitude = this.cameraShakeYAmplitude;
        }
        return frame;
    }

    public void beginCameraFrame() {
        this.evaluatedCamera = new ReplayVisuals();
    }

    public static float saneFov(float fov) {
        if (Float.isFinite(fov) && fov >= 1.0f && fov < 180.0f) return fov;
        float fallback = com.moulberry.flashback.Flashback.getConfig().internal.defaultOverrideFov;
        return Float.isFinite(fallback) && fallback >= 1.0f && fallback < 180.0f ? fallback : 70.0f;
    }

    public void setFov(float fov) {
        cameraFovResolved = true;
        overrideFov = true;
        overrideFovAmount = saneFov(fov);
    }

    public void setRoll(double roll) {
        cameraRollResolved = true;
        overrideRoll = roll <= -0.01 || roll >= 0.01;
        overrideRollAmount = overrideRoll ? (float) roll : 0.0f;
    }

    public void setCameraShake(float frequencyX, float amplitudeX, float frequencyY, float amplitudeY) {
        cameraShakeResolved = true;
        overrideCameraShake = true;
        cameraShakeSplitParams = frequencyX != frequencyY || amplitudeX != amplitudeY;
        cameraShakeXFrequency = frequencyX;
        cameraShakeXAmplitude = amplitudeX;
        cameraShakeYFrequency = frequencyY;
        cameraShakeYAmplitude = amplitudeY;
    }

}

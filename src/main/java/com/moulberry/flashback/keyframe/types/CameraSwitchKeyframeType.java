package com.moulberry.flashback.keyframe.types;

import com.moulberry.flashback.keyframe.Keyframe;
import com.moulberry.flashback.keyframe.KeyframeType;
import com.moulberry.flashback.keyframe.change.KeyframeChange;
import com.moulberry.flashback.keyframe.change.KeyframeChangeCameraSwitch;
import com.moulberry.flashback.keyframe.impl.CameraSwitchKeyframe;
import org.jetbrains.annotations.Nullable;

/**
 * The camera lane: keyframes here decide which camera is output.
 *
 * <p>It is purely a selector - it never moves the camera or animates anything itself. A keyframe
 * names a camera, and the editor resolves the lane to "the most recent cut at or before this tick"
 * and then applies that camera's own tracks. Nothing here is interpolated, which is what makes a
 * cut a cut.
 */
public class CameraSwitchKeyframeType implements KeyframeType<CameraSwitchKeyframe> {

    public static final CameraSwitchKeyframeType INSTANCE = new CameraSwitchKeyframeType();

    private CameraSwitchKeyframeType() {
    }

    @Override
    public Class<? extends KeyframeChange> keyframeChangeType() {
        return KeyframeChangeCameraSwitch.class;
    }

    @Override
    public @Nullable String icon() {
        return "\ue04b";
    }

    @Override
    public String name() {
        return net.minecraft.client.resources.language.I18n.get("flashback.keyframe.camera_switch");
    }

    @Override
    public String id() {
        return "CAMERA_SWITCH";
    }

    /** A cut is discrete, so there is nothing to interpolate. */
    @Override
    public boolean allowChangingInterpolationType() {
        return false;
    }

    /** The lane is always available; a switch is created by choosing a camera, not typed in. */
    @Override
    public boolean canBeCreatedNormally() {
        return false;
    }

    @Override
    public @Nullable CameraSwitchKeyframe createDirect() {
        return null;
    }

    @Override
    public KeyframeCreatePopup<CameraSwitchKeyframe> createPopup() {
        return null;
    }

}

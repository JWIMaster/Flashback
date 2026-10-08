package com.moulberry.flashback.keyframe;

import java.util.UUID;

/**
 * Identifies which timeline source is being output.
 *
 * <p>A source is one of: a camera (the set of camera-scoped tracks sharing an id), or a spectate
 * object (a SPECTATE track, whose keyframes say which player is watched). The switch keyframe only
 * names the source; the editor decides what that means, so the switch stays a pure selector.
 */
public record CameraSource(UUID sourceId) {

    public static CameraSource of(UUID sourceId) {
        return new CameraSource(sourceId);
    }

}

package com.moulberry.flashback.editor.ui.timeline;

import com.moulberry.flashback.state.EditorCamera;
import com.moulberry.flashback.state.KeyframeTrack;
import org.jetbrains.annotations.Nullable;

/**
 * One row of the timeline's left-hand list.
 *
 * <p>The timeline is a list of rows, but the scene's data is not: cameras own tracks, and the switch
 * lane is not a viewpoint at all. Modelling the rows explicitly - rather than repeatedly working out
 * "which track is this row" at each use - is what keeps drawing, hit-testing and dragging agreeing
 * about what the user is pointing at.
 */
public sealed interface TimelineRow {

    /** A non-interactive heading, e.g. "Cameras" or "Scene". */
    record Section(Kind kind) implements TimelineRow {
        public enum Kind { CAMERAS, SCENE }
    }

    /** A camera's own row: the header its tracks are grouped under. */
    record CameraGroup(EditorCamera camera, boolean collapsed) implements TimelineRow {}

    /**
     * A track's row.
     *
     * @param owner the camera this track belongs to, or null for a scene-wide track
     */
    record Track(KeyframeTrack track, @Nullable EditorCamera owner) implements TimelineRow {}

    /** The track this row shows, or null for rows that are not tracks. */
    @Nullable
    default KeyframeTrack trackOrNull() {
        return this instanceof Track track ? track.track() : null;
    }

    /** Whether this row is a track owned by a camera, which is what the indent means. */
    default boolean isCameraChild() {
        return this instanceof Track track && track.owner() != null;
    }

    default boolean isInteractive() {
        return !(this instanceof Section);
    }

}

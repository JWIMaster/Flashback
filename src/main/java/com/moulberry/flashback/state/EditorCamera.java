package com.moulberry.flashback.state;

import com.google.gson.annotations.SerializedName;
import com.moulberry.flashback.keyframe.KeyframeType;
import com.moulberry.flashback.keyframe.types.CameraKeyframeType;
import com.moulberry.flashback.keyframe.types.CameraOrbitKeyframeType;
import com.moulberry.flashback.keyframe.types.SpectateKeyframeType;
import com.moulberry.flashback.keyframe.types.TrackEntityKeyframeType;

import java.util.List;
import java.util.UUID;

/**
 * A camera: one viewpoint that can be cut to on the timeline.
 *
 * <p>A camera is a timeline source. It has a stable identity ({@link #id}) so camera-switch keyframes
 * keep pointing at the same camera across renames and reordering, and a display {@link #name}. Its
 * animated behaviour lives on {@link KeyframeTrack}s owned by the camera (see
 * {@link KeyframeTrack#cameraId}), not in a container of its own, so the timeline stays one ordered
 * list of rows and the camera is genuinely part of it rather than a parallel structure.
 *
 * <p>{@link #collapsed} is purely a view preference, persisted only so the user does not have to
 * collapse the same camera every session.
 */
public final class EditorCamera {

    /** What kind of viewpoint a camera is. */
    public enum Kind {
        /** A camera positioned by its own keyframes. */
        FREE,
        /** A camera that follows a player, whose player is chosen by its spectate keyframes. */
        SPECTATE;

        /** The track types this kind of camera can own. */
        public List<KeyframeType<?>> trackTypes() {
            return switch (this) {
                case FREE -> List.of(CameraKeyframeType.INSTANCE, CameraOrbitKeyframeType.INSTANCE, TrackEntityKeyframeType.INSTANCE);
                case SPECTATE -> List.of(SpectateKeyframeType.INSTANCE);
            };
        }

        /** Whether a track of this type can be added to this kind of camera. */
        public boolean allows(KeyframeType<?> type) {
            return this.trackTypes().contains(type);
        }
    }

    /** Stable identity, referenced by camera-switch keyframes. */
    @SerializedName("id")
    public UUID id = UUID.randomUUID();

    /**
     * The name the user gave this camera.
     *
     * <p>Null means unnamed: the timeline then shows a generated, localised name. Keeping the
     * unnamed state as null rather than writing a default name means renaming behaviour is simple
     * (a name is either the user's or generated) and migration never has to invent localised text.
     */
    @SerializedName("name")
    public String name;

    @SerializedName("collapsed")
    public boolean collapsed = false;

    /**
     * What kind of viewpoint this is. Absent in projects written before spectate cameras existed,
     * where Gson leaves the field at its initialiser - so {@link Kind#FREE} is the default.
     */
    @SerializedName("kind")
    public Kind kind = Kind.FREE;

    public EditorCamera(String name) {
        this.name = name;
    }

    public EditorCamera(String name, Kind kind) {
        this.name = name;
        this.kind = kind;
    }

    /**
     * Used by Gson when reading a saved camera.
     *
     * <p>Without a no-argument constructor Gson allocates the camera without running initialisers,
     * so a field missing from an older project's JSON - {@link #kind}, absent before spectate cameras
     * existed - would come back null rather than at its initial value.
     */
    private EditorCamera() {
        this(null, Kind.FREE);
    }

    public boolean hasName() {
        return this.name != null && !this.name.isBlank();
    }

    public EditorCamera copy() {
        EditorCamera copy = new EditorCamera(this.name, this.kind);
        copy.id = this.id;
        copy.collapsed = this.collapsed;
        return copy;
    }

    /** A camera owns scene-scoped tracks by tagging them, which is not possible for a switch lane. */
    public boolean canOwn(KeyframeType<?> type) {
        return this.kind.allows(type);
    }

}

package com.moulberry.flashback.state;

import com.google.gson.annotations.SerializedName;
import com.moulberry.flashback.keyframe.KeyframeType;
import com.moulberry.flashback.keyframe.types.CameraFovKeyframeType;
import com.moulberry.flashback.keyframe.types.CameraKeyframeType;
import com.moulberry.flashback.keyframe.types.CameraOrbitKeyframeType;
import com.moulberry.flashback.keyframe.types.CameraPositionKeyframeType;
import com.moulberry.flashback.keyframe.types.CameraRotationKeyframeType;
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
 * <p>A camera is also a <em>thing in its own right</em>: it holds its own position, rotation, fov and
 * shake (below), independent of any keyframe. When nothing animates a property, the camera's stored
 * value is what the view shows - see {@link EditorState#applyKeyframes}. That is what makes a camera
 * with no keyframes at all still behave like a camera, and what makes the single "camera" keyframe
 * unnecessary: position, rotation and fov can each be animated by their own track, so sliding a
 * position track never turns the camera and turning a rotation track never moves it.
 *
 * <p>{@link #collapsed} is purely a view preference, persisted only so the user does not have to
 * collapse the same camera every session.
 */
public final class EditorCamera {

    /** What kind of viewpoint a camera is. */
    public enum Kind {
        /** A camera positioned by its own keyframes. */
        FREE,
        /** A camera moved around a point, positioned by its orbit keyframes. */
        ORBIT,
        /** A camera that follows a player, whose player is chosen by its spectate keyframes. */
        SPECTATE;

        /** The track types this kind of camera can own. */
        public List<KeyframeType<?>> trackTypes() {
            return switch (this) {
                case FREE -> List.of(CameraKeyframeType.INSTANCE, CameraOrbitKeyframeType.INSTANCE, TrackEntityKeyframeType.INSTANCE,
                    CameraPositionKeyframeType.INSTANCE, CameraRotationKeyframeType.INSTANCE, CameraFovKeyframeType.INSTANCE);
                case ORBIT -> List.of(CameraOrbitKeyframeType.INSTANCE, TrackEntityKeyframeType.INSTANCE);
                case SPECTATE -> List.of(SpectateKeyframeType.INSTANCE);
            };
        }

        /** Whether a track of this type can be added to this kind of camera. */
        public boolean allows(KeyframeType<?> type) {
            return this.trackTypes().contains(type);
        }
    }

    /**
     * A viewpoint: a position and an orientation.
     *
     * <p>A camera is created at the pose the editor was already showing - see
     * {@link #startAt(Pose)} - so cutting to a new camera leaves the view where the user was looking
     * instead of at the origin. Deliberately not persisted: it describes where a camera starts, not
     * what it is.
     */
    public record Pose(double x, double y, double z, float yaw, float pitch, float roll) {

        /** The fallback for when there is no view to copy, such as outside a replay. */
        public static final Pose ORIGIN = new Pose(0, 0, 0, 0, 0, 0);
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

    /**
     * The camera's own position, independent of any keyframe.
     *
     * <p>Applied each frame only while the active camera is a {@link Kind#FREE} one and no enabled
     * track that applies to it animates position, so a position track always wins. Absent from
     * projects written before cameras held their own values, where these come back as zero; the
     * schema upgrade in {@link EditorState#migrateSchema()} seeds them from the camera's own
     * keyframes so an existing project does not jump on load.
     */
    @SerializedName("x")
    public double x;
    @SerializedName("y")
    public double y;
    @SerializedName("z")
    public double z;

    /** The camera's own orientation, independent of any keyframe. Applied like {@link #x}. */
    @SerializedName("yaw")
    public float yaw;
    @SerializedName("pitch")
    public float pitch;
    @SerializedName("roll")
    public float roll;

    /**
     * The camera's own field of view, or -1 for "unset" - meaning the project's FOV override, or the
     * game's own fov, is left alone.
     *
     * <p>A negative value is the sentinel rather than a plausible FOV, so an old project - which has
     * no fov field at all - does not silently force every camera to some arbitrary value.
     */
    @SerializedName("fov")
    public float fov = -1.0f;

    /** Whether this camera's stored shake should be applied when nothing animates shake. */
    @SerializedName("overrideCameraShake")
    public boolean overrideCameraShake = false;

    /** The camera's own camera-shake parameters, used only while {@link #overrideCameraShake}. */
    @SerializedName("cameraShakeXFrequency")
    public float cameraShakeXFrequency = 1.0f;
    @SerializedName("cameraShakeXAmplitude")
    public float cameraShakeXAmplitude = 0.0f;
    @SerializedName("cameraShakeYFrequency")
    public float cameraShakeYFrequency = 1.0f;
    @SerializedName("cameraShakeYAmplitude")
    public float cameraShakeYAmplitude = 0.0f;

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
     * existed - would come back null rather than at its initial value. The same rule is what gives
     * the camera's own values their defaults: {@link #fov} comes back as -1 rather than 0, and the
     * shake frequencies as 1 rather than 0, for a project written before they existed.
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
        copy.x = this.x;
        copy.y = this.y;
        copy.z = this.z;
        copy.yaw = this.yaw;
        copy.pitch = this.pitch;
        copy.roll = this.roll;
        copy.fov = this.fov;
        copy.overrideCameraShake = this.overrideCameraShake;
        copy.cameraShakeXFrequency = this.cameraShakeXFrequency;
        copy.cameraShakeXAmplitude = this.cameraShakeXAmplitude;
        copy.cameraShakeYFrequency = this.cameraShakeYFrequency;
        copy.cameraShakeYAmplitude = this.cameraShakeYAmplitude;
        return copy;
    }

    /**
     * Starts a camera at {@code pose}, so a new camera is where the editor's view already is.
     *
     * <p>Only the pose is taken: fov stays unset ({@link #fov} keeps its -1 sentinel) and shake stays
     * off, because those are the camera's own choices rather than part of where it is. A camera that
     * was never started at a pose - one read from a project, or built by a check - keeps whatever it
     * was given.
     */
    public void startAt(Pose pose) {
        this.x = pose.x();
        this.y = pose.y();
        this.z = pose.z();
        this.yaw = pose.yaw();
        this.pitch = pose.pitch();
        this.roll = pose.roll();
    }

    /**
     * Whether anything has ever been stored in this camera's own fields.
     *
     * <p>Used only by the schema upgrade, to decide whether an old project's camera needs seeding
     * from its keyframes. A camera whose values are all still at their defaults is one that was
     * written before cameras carried values, because the editor always initialises a new camera from
     * where the view already is.
     */
    boolean hasStaticValues() {
        return this.x != 0 || this.y != 0 || this.z != 0
            || this.yaw != 0 || this.pitch != 0 || this.roll != 0
            || this.fov >= 0 || this.overrideCameraShake;
    }

    /** A camera owns scene-scoped tracks by tagging them, which is not possible for a switch lane. */
    public boolean canOwn(KeyframeType<?> type) {
        return this.kind.allows(type);
    }

}

package com.moulberry.flashback.state;

import com.google.gson.JsonObject;
import com.moulberry.flashback.FlashbackGson;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A named camera: one viewpoint with its own animated tracks.
 *
 * <p>Cameras exist so that several viewpoints can be prepared independently and then cut between on
 * a timeline, the way video tracks work in an editor. Previously a scene had a single set of tracks,
 * so "camera A then camera B" was not expressible.
 *
 * <p>Camera-scoped tracks live here; scene-scoped global tracks (time of day, weather, tick rate,
 * freeze) stay on {@link EditorScene}, because those are properties of the world rather than of a
 * particular viewpoint and must not change when the camera cuts.
 */
public class NamedCamera {

    /** Stable identity, so switch keyframes survive renames and reordering. */
    public UUID id = UUID.randomUUID();
    public String name;

    /** Tracks belonging to this camera: position, orbit, FOV, roll, shake. */
    public final List<KeyframeTrack> tracks = new ArrayList<>();

    public NamedCamera(String name) {
        this.name = name;
    }

    public NamedCamera() {
        this.name = "Camera";
    }

    /** Whether a track of this type belongs to the camera rather than the scene. */
    public static boolean isCameraScoped(KeyframeTrack track) {
        return isCameraScopedId(track.keyframeType.id());
    }

    /**
     * Camera-scoped track types. Everything else (time of day, weather, tick rate, freeze, block
     * overrides, audio) is a property of the scene and stays there.
     */
    public static boolean isCameraScopedId(String id) {
        return switch (id) {
            case "CAMERA", "CAMERA_ORBIT", "FOV", "CAMERA_SHAKE", "TRACK_ENTITY" -> true;
            default -> false;
        };
    }

    @Nullable
    public KeyframeTrack findTrack(String typeId) {
        for (KeyframeTrack track : this.tracks) {
            if (track.keyframeType.id().equals(typeId)) {
                return track;
            }
        }
        return null;
    }

    public NamedCamera copy() {
        NamedCamera copy = new NamedCamera(this.name);
        copy.id = this.id;
        for (KeyframeTrack track : this.tracks) {
            copy.tracks.add(track.copy());
        }
        return copy;
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("id", this.id.toString());
        json.addProperty("name", this.name);
        json.add("tracks", FlashbackGson.COMPRESSED.toJsonTree(this.tracks));
        return json;
    }

}

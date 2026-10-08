package com.moulberry.flashback.state;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.moulberry.flashback.keyframe.Keyframe;
import com.moulberry.flashback.keyframe.KeyframeType;

import java.lang.reflect.Type;
import java.util.List;
import com.moulberry.flashback.keyframe.impl.CameraSwitchKeyframe;

public interface EditorSceneHistoryAction {

    void apply(EditorScene editorScene);

    record SetKeyframe(KeyframeType<?> type, int trackIndex, int tick, Keyframe keyframe) implements EditorSceneHistoryAction {
        @Override
        public void apply(EditorScene editorScene) {
            if (this.trackIndex < editorScene.keyframeTracks.size()) {
                KeyframeTrack track = editorScene.keyframeTracks.get(this.trackIndex);
                if (track.keyframeType == this.type) {
                    track.keyframesByTick.put(this.tick, this.keyframe.copy());
                }
            }
        }

        public static class TypeAdapter implements JsonSerializer<SetKeyframe>, JsonDeserializer<SetKeyframe> {
            @Override
            public SetKeyframe deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
                JsonObject jsonObject = json.getAsJsonObject();
                KeyframeType<?> type = context.deserialize(jsonObject.get("keyframe_type"), KeyframeType.class);
                int trackIndex = jsonObject.get("trackIndex").getAsInt();
                int tick = jsonObject.get("tick").getAsInt();
                Keyframe keyframe = context.deserialize(jsonObject.get("keyframe"), Keyframe.class);
                return new SetKeyframe(type, trackIndex, tick, keyframe);
            }

            @Override
            public JsonElement serialize(SetKeyframe src, Type typeOfSrc, JsonSerializationContext context) {
                JsonObject jsonObject = new JsonObject();
                jsonObject.addProperty("action_type", "set_keyframe");
                jsonObject.add("keyframe_type", context.serialize(src.type));
                jsonObject.addProperty("trackIndex", src.trackIndex);
                jsonObject.addProperty("tick", src.tick);
                jsonObject.add("keyframe", context.serialize(src.keyframe));
                return jsonObject;
            }
        }
    }

    record RemoveKeyframe(KeyframeType<?> type, int trackIndex, int tick) implements EditorSceneHistoryAction {
        @Override
        public void apply(EditorScene editorScene) {
            if (this.trackIndex < editorScene.keyframeTracks.size()) {
                KeyframeTrack track = editorScene.keyframeTracks.get(this.trackIndex);
                if (track.keyframeType == this.type) {
                    track.keyframesByTick.remove(this.tick);
                }
            }
        }

        public static class TypeAdapter implements JsonSerializer<RemoveKeyframe>, JsonDeserializer<RemoveKeyframe> {
            @Override
            public RemoveKeyframe deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
                JsonObject jsonObject = json.getAsJsonObject();
                KeyframeType<?> type = context.deserialize(jsonObject.get("keyframe_type"), KeyframeType.class);
                int trackIndex = jsonObject.get("trackIndex").getAsInt();
                int tick = jsonObject.get("tick").getAsInt();
                return new RemoveKeyframe(type, trackIndex, tick);
            }

            @Override
            public JsonElement serialize(RemoveKeyframe src, Type typeOfSrc, JsonSerializationContext context) {
                JsonObject jsonObject = new JsonObject();
                jsonObject.addProperty("action_type", "remove_keyframe");
                jsonObject.add("keyframe_type", context.serialize(src.type));
                jsonObject.addProperty("trackIndex", src.trackIndex);
                jsonObject.addProperty("tick", src.tick);
                return jsonObject;
            }
        }
    }

    /**
     * Re-inserts a camera that was deleted, along with the switches that named it.
     *
     * <p>The camera's rows are restored by the ordinary track actions that accompany this one; this
     * puts the camera's identity and the cuts to it back. Undo and redo are separate actions because
     * the order between them and the track actions matters: undo must add the camera before its rows
     * so those rows have an owner, redo must remove it after its rows are gone.
     */
    record AddCamera(EditorCamera camera, int cameraIndex, List<CameraSwitchEdit> switches) implements EditorSceneHistoryAction {

        /** A switch that was retargeted when its camera was deleted. */
        public record CameraSwitchEdit(int trackIndex, int tick, java.util.UUID cameraId) {}

        @Override
        public void apply(EditorScene editorScene) {
            if (editorScene.cameraById(this.camera.id) == null) {
                int index = Math.max(0, Math.min(this.cameraIndex, editorScene.cameras.size()));
                editorScene.cameras.add(index, this.camera);
            }
            for (CameraSwitchEdit edit : this.switches) {
                restoreSwitch(editorScene, edit, this.camera.id);
            }
        }

        private static void restoreSwitch(EditorScene editorScene, CameraSwitchEdit edit, java.util.UUID cameraId) {
            if (edit.trackIndex < 0 || edit.trackIndex >= editorScene.keyframeTracks.size()) {
                return;
            }
            KeyframeTrack track = editorScene.keyframeTracks.get(edit.trackIndex);
            if (!KeyframeTrack.isCameraSwitch(track)) {
                return;
            }
            track.keyframesByTick.put(edit.tick, new CameraSwitchKeyframe(cameraId));
        }

        public static class TypeAdapter implements JsonSerializer<AddCamera>, JsonDeserializer<AddCamera> {
            @Override
            public AddCamera deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
                JsonObject jsonObject = json.getAsJsonObject();
                EditorCamera camera = context.deserialize(jsonObject.get("camera"), EditorCamera.class);
                int cameraIndex = jsonObject.has("camera_index") ? jsonObject.get("camera_index").getAsInt() : 0;

                List<CameraSwitchEdit> switches = new java.util.ArrayList<>();
                if (jsonObject.has("switches")) {
                    for (JsonElement element : jsonObject.getAsJsonArray("switches")) {
                        JsonObject entry = element.getAsJsonObject();
                        switches.add(new CameraSwitchEdit(
                            entry.get("track_index").getAsInt(),
                            entry.get("tick").getAsInt(),
                            java.util.UUID.fromString(entry.get("camera_id").getAsString())));
                    }
                }
                return new AddCamera(camera, cameraIndex, switches);
            }

            @Override
            public JsonElement serialize(AddCamera src, Type typeOfSrc, JsonSerializationContext context) {
                JsonObject jsonObject = new JsonObject();
                jsonObject.addProperty("action_type", "add_camera");
                jsonObject.add("camera", context.serialize(src.camera));
                jsonObject.addProperty("camera_index", src.cameraIndex);

                com.google.gson.JsonArray switches = new com.google.gson.JsonArray();
                for (CameraSwitchEdit edit : src.switches) {
                    JsonObject entry = new JsonObject();
                    entry.addProperty("track_index", edit.trackIndex());
                    entry.addProperty("tick", edit.tick());
                    entry.addProperty("camera_id", edit.cameraId().toString());
                    switches.add(entry);
                }
                jsonObject.add("switches", switches);
                return jsonObject;
            }
        }
    }

    /** Removes a camera without removing its rows; used as the redo of a camera deletion. */
    record RemoveCamera(EditorCamera camera) implements EditorSceneHistoryAction {
        @Override
        public void apply(EditorScene editorScene) {
            editorScene.cameras.removeIf(camera -> camera.id.equals(this.camera.id));
            editorScene.retargetOrphanedSwitches();
        }

        public static class TypeAdapter implements JsonSerializer<RemoveCamera>, JsonDeserializer<RemoveCamera> {
            @Override
            public RemoveCamera deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
                return new RemoveCamera(context.deserialize(json.getAsJsonObject().get("camera"), EditorCamera.class));
            }

            @Override
            public JsonElement serialize(RemoveCamera src, Type typeOfSrc, JsonSerializationContext context) {
                JsonObject jsonObject = new JsonObject();
                jsonObject.addProperty("action_type", "remove_camera");
                jsonObject.add("camera", context.serialize(src.camera));
                return jsonObject;
            }
        }
    }

    /**
     * Adds a track.
     *
     * <p>{@code cameraId} is the camera the track belongs to, or null for a scene-wide track. It is
     * part of the action rather than something assigned afterwards so that undoing an edit restores
     * the camera's ownership of its rows, not only their contents.
     */
    record AddTrack(KeyframeType<?> type, int trackIndex, java.util.UUID cameraId) implements EditorSceneHistoryAction {

        public AddTrack(KeyframeType<?> type, int trackIndex) {
            this(type, trackIndex, null);
        }

        @Override
        public void apply(EditorScene editorScene) {
            if (this.trackIndex <= editorScene.keyframeTracks.size()) {
                KeyframeTrack track = new KeyframeTrack(this.type);
                track.cameraId = this.cameraId;
                editorScene.keyframeTracks.add(this.trackIndex, track);
            }
        }

        public static class TypeAdapter implements JsonSerializer<AddTrack>, JsonDeserializer<AddTrack> {
            @Override
            public AddTrack deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
                JsonObject jsonObject = json.getAsJsonObject();
                KeyframeType<?> type = context.deserialize(jsonObject.get("keyframe_type"), KeyframeType.class);
                int trackIndex = jsonObject.get("trackIndex").getAsInt();
                java.util.UUID cameraId = null;
                if (jsonObject.has("camera_id") && !jsonObject.get("camera_id").isJsonNull()) {
                    cameraId = java.util.UUID.fromString(jsonObject.get("camera_id").getAsString());
                }
                return new AddTrack(type, trackIndex, cameraId);
            }

            @Override
            public JsonElement serialize(AddTrack src, Type typeOfSrc, JsonSerializationContext context) {
                JsonObject jsonObject = new JsonObject();
                jsonObject.addProperty("action_type", "add_track");
                jsonObject.add("keyframe_type", context.serialize(src.type));
                jsonObject.addProperty("trackIndex", src.trackIndex);
                if (src.cameraId != null) {
                    jsonObject.addProperty("camera_id", src.cameraId.toString());
                }
                return jsonObject;
            }
        }
    }

    record RemoveTrack(KeyframeType<?> type, int trackIndex) implements EditorSceneHistoryAction {
        @Override
        public void apply(EditorScene editorScene) {
            if (this.trackIndex < editorScene.keyframeTracks.size()) {
                KeyframeTrack keyframeTrack = editorScene.keyframeTracks.get(this.trackIndex);
                if (keyframeTrack.keyframeType == type) {
                    editorScene.keyframeTracks.remove(this.trackIndex);
                }
            }
        }

        public static class TypeAdapter implements JsonSerializer<RemoveTrack>, JsonDeserializer<RemoveTrack> {
            @Override
            public RemoveTrack deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
                JsonObject jsonObject = json.getAsJsonObject();
                KeyframeType<?> type = context.deserialize(jsonObject.get("keyframe_type"), KeyframeType.class);
                int trackIndex = jsonObject.get("trackIndex").getAsInt();
                return new RemoveTrack(type, trackIndex);
            }

            @Override
            public JsonElement serialize(RemoveTrack src, Type typeOfSrc, JsonSerializationContext context) {
                JsonObject jsonObject = new JsonObject();
                jsonObject.addProperty("action_type", "remove_track");
                jsonObject.add("keyframe_type", context.serialize(src.type));
                jsonObject.addProperty("trackIndex", src.trackIndex);
                return jsonObject;
            }
        }
    }

    class TypeAdapter implements JsonSerializer<EditorSceneHistoryAction>, JsonDeserializer<EditorSceneHistoryAction> {
        @Override
        public EditorSceneHistoryAction deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            JsonObject jsonObject = json.getAsJsonObject();
            String type = jsonObject.get("action_type").getAsString();
            return switch (type) {
                case "set_keyframe" -> context.deserialize(json, SetKeyframe.class);
                case "remove_keyframe" -> context.deserialize(json, RemoveKeyframe.class);
                case "add_track" -> context.deserialize(json, AddTrack.class);
                case "remove_track" -> context.deserialize(json, RemoveTrack.class);
                case "add_camera" -> context.deserialize(json, AddCamera.class);
                case "remove_camera" -> context.deserialize(json, RemoveCamera.class);
                default -> throw new IllegalStateException("Unknown action type: " + type);
            };
        }

        @Override
        public JsonElement serialize(EditorSceneHistoryAction src, Type typeOfSrc, JsonSerializationContext context) {
            JsonObject jsonObject;
            switch (src) {
                case SetKeyframe setKeyframe -> {
                    jsonObject = (JsonObject) context.serialize(setKeyframe);
                    jsonObject.addProperty("action_type", "set_keyframe");
                }
                case RemoveKeyframe removeKeyframe -> {
                    jsonObject = (JsonObject) context.serialize(removeKeyframe);
                    jsonObject.addProperty("action_type", "remove_keyframe");
                }
                case AddTrack addTrack -> {
                    jsonObject = (JsonObject) context.serialize(addTrack);
                    jsonObject.addProperty("action_type", "add_track");
                }
                case RemoveTrack removeTrack -> {
                    jsonObject = (JsonObject) context.serialize(removeTrack);
                    jsonObject.addProperty("action_type", "remove_track");
                }
                case AddCamera addCamera -> {
                    jsonObject = (JsonObject) context.serialize(addCamera);
                    jsonObject.addProperty("action_type", "add_camera");
                }
                case RemoveCamera removeCamera -> {
                    jsonObject = (JsonObject) context.serialize(removeCamera);
                    jsonObject.addProperty("action_type", "remove_camera");
                }
                default -> throw new IllegalStateException("Unknown action type: " + src.getClass());
            }
            return jsonObject;
        }
    }

}

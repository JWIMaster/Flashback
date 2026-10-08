package com.moulberry.flashback.state;

import com.moulberry.flashback.keyframe.Keyframe;
import com.moulberry.flashback.keyframe.KeyframeType;
import com.moulberry.flashback.keyframe.types.CameraKeyframeType;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import com.moulberry.flashback.keyframe.types.TrackEntityKeyframeType;
import com.moulberry.flashback.keyframe.types.SpectateKeyframeType;
import com.moulberry.flashback.keyframe.types.CameraSwitchKeyframeType;
import com.moulberry.flashback.keyframe.types.CameraOrbitKeyframeType;
import com.moulberry.flashback.keyframe.impl.CameraSwitchKeyframe;

public class EditorScene {

    public String name;

    /**
     * The scene's tracks, camera-owned ones included, in timeline order.
     *
     * <p>Not final: Gson can create a scene without running field initialisers, so
     * {@link EditorState} repairs missing collections after loading. See {@link #EditorScene()}.
     */
    public List<KeyframeTrack> keyframeTracks = new ArrayList<>();

    /**
     * The cameras that can be cut to in this scene.
     *
     * <p>This is the authoritative list of cameras: a camera exists when it is in here, not when a
     * track happens to mention its id. A camera's own tracks live in {@link #keyframeTracks} tagged
     * with {@link KeyframeTrack#cameraId}, so the timeline stays one ordered list of rows and the
     * existing selection, undo and copy code keeps working on track indices.
     */
    public List<EditorCamera> cameras = new ArrayList<>();

    public int exportStartTicks = -1;
    public int exportEndTicks = -1;

    private final EditorSceneHistory history = new EditorSceneHistory();

    public EditorScene(String name) {
        this.name = name;
    }

    /**
     * Used by Gson when reading a saved scene.
     *
     * <p>A no-argument constructor matters here: without one Gson creates the object with
     * {@code Unsafe.allocateInstance}, which runs no field initialisers at all, so every field
     * missing from an older project's JSON comes back null instead of at its initial value. With it,
     * an absent field keeps its initialiser - which is how {@link EditorState} already behaves.
     */
    private EditorScene() {
        this.name = "";
    }

    /**
     * The scene's cameras, created if this scene was built by reflection without initialisers.
     *
     * <p>{@link EditorState} repairs loaded state, but this is the read path playback goes through,
     * and a null list here used to take the whole server down mid-tick - so it is made total rather
     * than merely assumed.
     */
    private List<EditorCamera> cameraList() {
        if (this.cameras == null) {
            this.cameras = new ArrayList<>();
        }
        return this.cameras;
    }

    /** The scene's tracks, created on the same basis as {@link #cameraList()}. */
    private List<KeyframeTrack> trackList() {
        if (this.keyframeTracks == null) {
            this.keyframeTracks = new ArrayList<>();
        }
        return this.keyframeTracks;
    }

    /**
     * The lane whose keyframes decide which camera is output. Created lazily, because a scene with
     * no cameras has nothing to switch between and should not show an empty lane.
     */
    @Nullable
    public KeyframeTrack cameraSwitchTrack() {
        for (KeyframeTrack track : this.trackList()) {
            if (KeyframeTrack.isCameraSwitch(track)) {
                return track;
            }
        }
        return null;
    }

    /**
     * Finds the camera-switch lane, creating it at the top of the timeline if it does not exist.
     *
     * <p>There is exactly one per scene. Keeping it first means the row that decides what is being
     * output always reads before the tracks it selects between.
     */
    public KeyframeTrack findOrCreateCameraSwitchTrack() {
        KeyframeTrack existing = this.cameraSwitchTrack();
        if (existing != null) {
            return existing;
        }

        KeyframeTrack track = new KeyframeTrack(CameraSwitchKeyframeType.INSTANCE);
        this.trackList().add(0, track);
        return track;
    }

    @Nullable
    public EditorCamera cameraById(@Nullable UUID id) {
        if (id == null) {
            return null;
        }
        for (EditorCamera camera : this.cameraList()) {
            if (camera.id.equals(id)) {
                return camera;
            }
        }
        return null;
    }

    /**
     * The camera a switch keyframe with this id should really refer to.
     *
     * <p>Falls back to the first camera so that a switch pointing at a camera that has since been
     * deleted still resolves to something usable instead of leaving the viewpoint undefined.
     */
    @Nullable
    public EditorCamera resolveCamera(@Nullable UUID id) {
        EditorCamera camera = this.cameraById(id);
        if (camera != null) {
            return camera;
        }
        return this.cameraList().isEmpty() ? null : this.cameraList().get(0);
    }

    /**
     * Whether a switch has actually been recorded. Used to highlight the output camera: with no
     * switches yet, the first camera is output but nothing has been cut to, so highlighting it would
     * imply a decision the user has not made.
     */
    public boolean hasCameraSwitches() {
        KeyframeTrack switchTrack = this.cameraSwitchTrack();
        return switchTrack != null && switchTrack.enabled && !switchTrack.keyframesByTick.isEmpty();
    }

    /**
     * The camera output at {@code tick}: the target of the most recent switch keyframe at or before
     * it.
     *
     * <p>A switch is a hard cut, so nothing is interpolated here. Before the first switch of an
     * enabled lane the first camera is output, which is the camera a scene starts on.
     *
     * <p>Returns null only when the scene is not using switches at all - no switch lane, or a turned
     * off one. That is the case where playback must keep evaluating every camera exactly as it did
     * before cameras were split apart, so a project saved by an older version still behaves
     * identically.
     */
    @Nullable
    public EditorCamera resolveCameraAt(float tick) {
        KeyframeTrack switchTrack = this.cameraSwitchTrack();
        if (switchTrack == null || !switchTrack.enabled) {
            return null;
        }

        var entry = switchTrack.keyframesByTick.floorEntry((int) tick);
        if (entry != null && entry.getValue() instanceof CameraSwitchKeyframe switchKeyframe) {
            return this.resolveCamera(switchKeyframe.cameraId);
        }

        return this.cameraList().isEmpty() ? null : this.cameraList().get(0);
    }

    /** The tracks belonging to a camera, in timeline order. */
    public List<KeyframeTrack> tracksOfCamera(EditorCamera camera) {
        List<KeyframeTrack> tracks = new ArrayList<>();
        for (KeyframeTrack track : this.trackList()) {
            if (camera.id.equals(track.cameraId)) {
                tracks.add(track);
            }
        }
        return tracks;
    }

    /** Index of a track in the scene's list, or -1. */
    public int trackIndexOf(KeyframeTrack track) {
        return this.trackList().indexOf(track);
    }

    /** Index of a camera in the scene's registry, or -1. */
    public int cameraIndexOf(EditorCamera camera) {
        return this.cameraList().indexOf(camera);
    }

    /** Moves a track within the scene's list. */
    public void moveTrack(int fromIndex, int toIndex) {
        List<KeyframeTrack> tracks = this.trackList();
        if (fromIndex < 0 || fromIndex >= tracks.size()) {
            return;
        }
        KeyframeTrack track = tracks.remove(fromIndex);
        tracks.add(Math.max(0, Math.min(toIndex, tracks.size())), track);
    }

    /**
     * Moves a camera to a different position among the cameras.
     *
     * <p>Camera order is the registry's, and it decides the order of the camera groups on the
     * timeline. A camera's tracks stay where they are in the track list: they are grouped by their
     * owner rather than by position, so the group simply appears at its new place.
     */
    public void moveCamera(EditorCamera camera, int toIndex) {
        List<EditorCamera> cameras = this.cameraList();
        int from = cameras.indexOf(camera);
        if (from < 0) {
            return;
        }
        cameras.remove(from);
        cameras.add(Math.max(0, Math.min(toIndex, cameras.size())), camera);
    }

    public boolean hasTrackOfType(EditorCamera camera, KeyframeType<?> type) {        for (KeyframeTrack track : this.trackList()) {
            if (camera.id.equals(track.cameraId) && track.keyframeType == type) {
                return true;
            }
        }
        return false;
    }

    /**
     * Where a new track owned by {@code camera} should be inserted: after the camera's existing
     * tracks. A camera with no tracks yet is placed after the previous camera's block, so each
     * camera owns one contiguous run of rows and its children can never be interleaved with another
     * camera's.
     *
     * <p>The camera does not have to be registered yet, which is what lets a camera and its first
     * track be added in a single undoable step.
     */
    public int insertionIndexForTrackOf(EditorCamera camera) {
        int cameraIndex = this.cameraList().indexOf(camera);
        if (cameraIndex < 0) {
            cameraIndex = this.cameraList().size();
        }

        // Walk backwards over the cameras that sit before this one: the new row belongs directly
        // after the last track any of them owns.
        for (int i = cameraIndex - 1; i >= 0; i--) {
            int last = this.lastTrackIndexOwnedBy(this.cameraList().get(i));
            if (last >= 0) {
                return last + 1;
            }
        }

        // No earlier camera has a block, so this camera's rows go before the scene-level tracks -
        // but never before the switch lane, which belongs at the top of the timeline.
        int start = this.sceneTrackStart();
        List<KeyframeTrack> tracks = this.trackList();
        if (!tracks.isEmpty() && KeyframeTrack.isCameraSwitch(tracks.get(0))) {
            start = Math.max(start, 1);
        }
        return start;
    }

    private int lastTrackIndexOwnedBy(EditorCamera camera) {
        for (int i = this.trackList().size() - 1; i >= 0; i--) {
            if (camera.id.equals(this.trackList().get(i).cameraId)) {
                return i;
            }
        }
        return -1;
    }

    /** Index of the first track in the scene that is not camera-owned (the switch lane included). */
    public int sceneTrackStart() {
        for (int i = 0; i < this.trackList().size(); i++) {
            if (this.trackList().get(i).cameraId == null) {
                return i;
            }
        }
        return this.trackList().size();
    }

    /**
     * The rows a camera occupies: its own row, plus one row per track it owns.
     *
     * <p>The order is the scene registry's, not the track list's, so a camera whose rows somehow end
     * up out of order still groups correctly.
     */
    public record CameraBlock(EditorCamera camera, List<KeyframeTrack> tracks) {}

    /** Every camera in registry order, each with the rows it owns. */
    public List<CameraBlock> cameraBlocks() {
        List<CameraBlock> blocks = new ArrayList<>();
        for (EditorCamera camera : this.cameraList()) {
            blocks.add(new CameraBlock(camera, this.tracksOfCamera(camera)));
        }
        return blocks;
    }

    /**
     * Points any switch that names a camera that no longer exists at the first remaining camera.
     *
     * <p>Without this, deleting a camera would leave switches naming nothing, and the timeline would
     * show a cut to a camera that cannot be chosen again.
     */
    /** Retimes a cut for preview purposes without touching the scene. */
    public interface CutRetime {
        int retime(int tick);
    }

    /**
     * One camera shot: the stretch of the timeline where a single camera is the output.
     *
     * <p>Shots tile the whole timeline with no gaps, because a cut is a boundary rather than an
     * object sitting between two shots. That is what makes "this camera is live from here to here"
     * always true, and what makes dragging a boundary a well-defined edit.
     *
     * @param cutTick the cut that starts this shot, or -1 when the shot runs from the start of the
     *                replay with no cut of its own
     */
    public record Shot(EditorCamera camera, int startTick, int endTick, int cutTick) {
        public int duration() {
            return this.endTick - this.startTick;
        }
    }

    /**
     * The shots that make up the timeline, in order.
     *
     * <p>{@code retime} lets a caller ask what the shots would look like if a boundary moved, which is
     * how a drag previews itself without editing the scene.
     */
    public List<Shot> shots(int totalTicks) {
        return this.shots(totalTicks, null);
    }

    public List<Shot> shots(int totalTicks, @Nullable CutRetime retime) {
        List<Shot> shots = new ArrayList<>();
        if (this.cameraList().isEmpty() || totalTicks <= 0) {
            return shots;
        }

        // Every cut that resolves to a camera, ordered by where it will appear (a previewed drag
        // may have moved it), remembering the tick the keyframe actually sits on.
        record Cut(int atTick, int keyframeTick, EditorCamera camera) {}
        List<Cut> cuts = new ArrayList<>();
        KeyframeTrack switchTrack = this.cameraSwitchTrack();
        if (switchTrack != null) {
            for (Map.Entry<Integer, Keyframe> entry : switchTrack.keyframesByTick.entrySet()) {
                if (!(entry.getValue() instanceof CameraSwitchKeyframe cut)) {
                    continue;
                }
                EditorCamera camera = this.resolveCamera(cut.cameraId);
                if (camera == null) {
                    continue;
                }
                int tick = retime == null ? entry.getKey() : retime.retime(entry.getKey());
                cuts.add(new Cut(Math.max(0, Math.min(totalTicks, tick)), entry.getKey(), camera));
            }
            cuts.sort(Comparator.comparingInt(Cut::atTick));
        }

        int start = 0;
        int startCutTick = -1;
        for (Cut cut : cuts) {
            if (cut.atTick() > start) {
                EditorCamera camera = this.resolveCameraAt(start);
                if (camera != null) {
                    shots.add(new Shot(camera, start, cut.atTick(), startCutTick));
                }
                start = cut.atTick();
                startCutTick = cut.keyframeTick();
            } else if (cut.atTick() == 0) {
                // A cut on the very first tick starts the first shot rather than making an empty one.
                startCutTick = cut.keyframeTick();
            }
        }
        if (start < totalTicks) {
            EditorCamera camera = this.resolveCameraAt(start);
            if (camera == null && !cuts.isEmpty()) {
                camera = cuts.get(cuts.size() - 1).camera();
            }
            if (camera != null) {
                shots.add(new Shot(camera, start, totalTicks, startCutTick));
            }
        }
        return shots;
    }

    /** The shot covering a tick, or null when there are no shots. */
    @Nullable
    public Shot shotAt(int tick, int totalTicks) {
        for (Shot shot : this.shots(totalTicks)) {
            if (tick >= shot.startTick() && tick < shot.endTick()) {
                return shot;
            }
        }
        return null;
    }

    public void retargetOrphanedSwitches() {
        UUID fallback = this.cameraList().isEmpty() ? null : this.cameraList().get(0).id;
        for (KeyframeTrack track : this.trackList()) {
            if (!KeyframeTrack.isCameraSwitch(track)) {
                continue;
            }
            // Walk the cuts in time order: a cut whose camera has gone takes over from whatever was
            // live before it, so removing a camera extends its predecessor's span instead of jumping
            // to an unrelated viewpoint.
            UUID previous = null;
            for (Map.Entry<Integer, Keyframe> entry : track.keyframesByTick.entrySet()) {
                if (!(entry.getValue() instanceof CameraSwitchKeyframe cut)) {
                    continue;
                }
                if (cut.cameraId != null && this.cameraById(cut.cameraId) != null) {
                    previous = cut.cameraId;
                    continue;
                }
                UUID replacement = previous != null ? previous : fallback;
                entry.setValue(new CameraSwitchKeyframe(replacement));
                if (replacement != null) {
                    previous = replacement;
                }
            }
        }
    }

    public void setKeyframe(int trackIndex, int tick, Keyframe keyframe) {
        if (trackIndex < 0 || trackIndex >= this.trackList().size()) {
            return;
        }

        List<EditorSceneHistoryAction> undo = new ArrayList<>();
        List<EditorSceneHistoryAction> redo = new ArrayList<>();

        String description;

        KeyframeTrack track = this.trackList().get(trackIndex);
        Keyframe old = track.keyframesByTick.get(tick);
        if (old != null) {
            undo.add(new EditorSceneHistoryAction.SetKeyframe(track.keyframeType, trackIndex, tick, old.copy()));
            description = "Replaced " + track.keyframeType.name() + " keyframe";
        } else {
            undo.add(new EditorSceneHistoryAction.RemoveKeyframe(track.keyframeType, trackIndex, tick));
            description = "Added " + track.keyframeType.name() + " keyframe";
        }
        redo.add(new EditorSceneHistoryAction.SetKeyframe(track.keyframeType, trackIndex, tick, keyframe.copy()));

        this.push(new EditorSceneHistoryEntry(undo, redo, description));
    }

    public void push(EditorSceneHistoryEntry entry) {
        if (entry.undo().isEmpty() && entry.redo().isEmpty()) {
            return;
        }

        this.history.push(this, entry);
    }

    public void undo(Consumer<String> descriptionConsumer) {
        this.history.undo(this, descriptionConsumer);
    }

    public void redo(Consumer<String> descriptionConsumer) {
        this.history.redo(this, descriptionConsumer);
    }

    public void setExportTicks(int start, int end, int totalTicks) {
        if (this.exportEndTicks < 0) {
            this.exportEndTicks = totalTicks;
        }
        this.exportStartTicks = Math.max(0, Math.min(totalTicks, this.exportStartTicks));
        this.exportEndTicks = Math.max(0, Math.min(totalTicks, this.exportEndTicks));

        if (start >= 0) {
            this.exportStartTicks = start;
            if (this.exportEndTicks < start) {
                this.exportEndTicks = start;
            }
        }

        if (end >= 0) {
            this.exportEndTicks = end;
            if (this.exportStartTicks > end) {
                this.exportStartTicks = end;
            }
        }

        if (this.exportStartTicks <= 0 && this.exportEndTicks >= totalTicks) {
            this.exportStartTicks = -1;
            this.exportEndTicks = -1;
        }
    }

    /**
     * The name to show for a camera: an explicit name typed on one of its tracks, then the camera's
     * own name, then a generated one based on its position in the scene.
     *
     * <p>Generating the fallback name here rather than storing one keeps the model free of default
     * text and puts the localisation in the display layer where it belongs.
     */
    public String displayNameOf(EditorCamera camera) {
        for (KeyframeTrack track : this.trackList()) {
            if (camera.id.equals(track.cameraId) && track.customName != null && !track.customName.isBlank()) {
                return track.customName;
            }
        }
        if (camera.hasName()) {
            return camera.name;
        }
        return net.minecraft.client.resources.language.I18n.get("flashback.camera_n", this.cameraList().indexOf(camera) + 1);
    }

    /**
     * Whether this keyframe type describes the viewpoint of a camera rather than the scene.
     *
     * <p>Camera-scoped tracks are only evaluated while their camera is the one being output. Scene
     * tracks (time of day, weather, tick rate, freeze, audio, block overrides) always apply, because
     * they describe the world and must not change when the camera cuts.
     */
    public static boolean isCameraScoped(KeyframeType<?> type) {
        return type == CameraKeyframeType.INSTANCE
            || type == CameraOrbitKeyframeType.INSTANCE
            || type == TrackEntityKeyframeType.INSTANCE
            || type == SpectateKeyframeType.INSTANCE;
    }

}

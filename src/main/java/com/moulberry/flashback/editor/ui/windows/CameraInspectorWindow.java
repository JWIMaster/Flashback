package com.moulberry.flashback.editor.ui.windows;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.editor.ui.ImGuiHelper;
import com.moulberry.flashback.editor.ui.ReplayUI;
import com.moulberry.flashback.editor.ui.timeline.TimelineColours;
import com.moulberry.flashback.editor.ui.timeline.TimelineEdits;
import com.moulberry.flashback.keyframe.Keyframe;
import com.moulberry.flashback.keyframe.KeyframeType;
import com.moulberry.flashback.keyframe.impl.CameraFovKeyframe;
import com.moulberry.flashback.keyframe.impl.CameraKeyframe;
import com.moulberry.flashback.keyframe.impl.CameraPositionKeyframe;
import com.moulberry.flashback.keyframe.impl.CameraRotationKeyframe;
import com.moulberry.flashback.keyframe.impl.CameraShakeKeyframe;
import com.moulberry.flashback.keyframe.impl.FOVKeyframe;
import com.moulberry.flashback.keyframe.types.FOVKeyframeType;
import com.moulberry.flashback.keyframe.interpolation.InterpolationType;
import com.moulberry.flashback.keyframe.types.CameraKeyframeType;
import com.moulberry.flashback.keyframe.types.CameraFovKeyframeType;
import com.moulberry.flashback.keyframe.types.CameraPositionKeyframeType;
import com.moulberry.flashback.keyframe.types.CameraRotationKeyframeType;
import com.moulberry.flashback.keyframe.types.CameraShakeKeyframeType;
import com.moulberry.flashback.playback.ReplayServer;
import com.moulberry.flashback.record.FlashbackMeta;
import com.moulberry.flashback.state.EditorCamera;
import com.moulberry.flashback.state.EditorScene;
import com.moulberry.flashback.state.EditorSceneHistoryAction;
import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.state.EditorStateManager;
import com.moulberry.flashback.state.KeyframeTrack;
import imgui.moulberry90.ImGui;
import imgui.moulberry90.flag.ImGuiCond;
import imgui.moulberry90.type.ImBoolean;
import net.minecraft.client.resources.language.I18n;
import org.joml.Vector3d;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Inspects the selected camera's evaluated values at the playhead, independently of timeline selection.
 * Drawing holds no scene stamp. Mutations take short write stamps and preview only after releasing them.
 * Animated edits write existing keys or auto-key on actual change; undriven edits write static values.
 * A scalar widget owns its gesture and commits one undo entry when it deactivates.
 */
public class CameraInspectorWindow {

    /** The selected camera object, or null when no camera is selected. */
    @Nullable
    private static UUID selectedCameraId = null;

    private static boolean open = false;
    private static boolean openNow = false;

    private static final ImBoolean windowOpen = new ImBoolean();

    /**
     * The drag in progress.
     *
     * <p>Immediate mode means the widget is called again every frame, so what has to survive between
     * frames is the pre-drag value and where the drag is writing. Holding the target here is what
     * makes a drag one undo step rather than one per frame: the target is mutated live with no
     * history, and a single entry is pushed from this record's {@code before} snapshot and the final
     * value when the drag ends.
     */
    @Nullable
    private static Drag drag;

    private static final class Drag {
        final UUID cameraId;
        final String widgetId;
        final int from;
        @Nullable KeyframeTrack overrideTrack;
        @Nullable Keyframe overrideBase;
        @Nullable Keyframe overrideBeforeForHistory;
        @Nullable final KeyframeTrack track;
        /** Existing key, or an evaluated seed for an auto-key; activation alone never inserts it. */
        @Nullable final Keyframe base;
        final EditorState.CameraProperty property;
        /** The tick the gesture started on; a drag never writes to a different tick. */
        final int tick;
        /**
         * The keyframe that was at this tick when the drag started, or null when there was none.
         *
         * <p>Each live frame replaces the map entry with a keyframe built from {@link #base} and
         * the drag's numbers, preserving properties the scalar does not own.
         */
        @Nullable
        final Keyframe before;
        /** A copy of {@link #before}, because the live keyframe is replaced rather than edited. */
        @Nullable
        final Keyframe beforeForHistory;
        /** The three numbers of a dragged transform, or the one value at [0] for the scalar ones. */
        final float[] values;
        /** Whether the drag has actually moved a value, so a click alone records nothing. */
        boolean changed;

        Drag(UUID cameraId, String widgetId, int from, EditorState.CameraProperty property, int tick,
             @Nullable KeyframeTrack track, @Nullable Keyframe base,
             @Nullable Keyframe before, @Nullable Keyframe beforeForHistory, float[] values) {
            this.cameraId = cameraId;
            this.widgetId = widgetId;
            this.from = from;
            this.track = track;
            this.base = base;
            this.property = property;
            this.tick = tick;
            this.before = before;
            this.beforeForHistory = beforeForHistory;
            this.values = values;
        }
    }

    /** Everything the drawing pass needs, copied under one short read stamp. */
    private record Snapshot(EditorScene scene, EditorCamera camera, EditorState.CameraEvaluation evaluated,
                            boolean visible, int playhead,
                            java.util.Map<EditorState.CameraProperty, PropertyState> properties) {
        @Nullable
        PropertyState stateOf(EditorState.CameraProperty property) {
            return this.properties.get(property);
        }
    }

    private static java.util.Map<EditorState.CameraProperty, PropertyState> snapshotProperties(
            EditorState state, EditorScene scene, EditorCamera camera, int tick) {
        var result = new java.util.EnumMap<EditorState.CameraProperty, PropertyState>(EditorState.CameraProperty.class);
        for (EditorState.CameraProperty property : EditorState.CameraProperty.values()) {
            KeyframeTrack winner = state.evaluatedCameraTrack(scene, camera, tick, property);
            KeyframeTrack track = winner == null ? compatibleTrack(scene, camera, property) : winner;
            if (track != null) {
                Keyframe key = track.keyframesByTick.get(tick);
                result.put(property, new PropertyState(track, scene.trackIndexOf(track), key == null ? null : key.copy()));
            }
        }
        return result;
    }

    /** One property's current track facts. */
    private record PropertyState(KeyframeTrack track, int trackIndex, @Nullable Keyframe keyframeAtPlayhead) {}

    public static void render() {
        ReplayServer replayServer = Flashback.getReplayServer();
        if (replayServer == null || !open) {
            return;
        }

        FlashbackMeta metadata = replayServer.getMetadata();
        EditorState editorState = EditorStateManager.get(metadata.replayIdentifier);
        int playhead = TimelineWindow.getCursorTick();

        // -- Read: copy everything this frame needs, then let go of the lock before drawing anything.
        Snapshot snapshot;
        long stamp = editorState.acquireRead();
        try {
            EditorScene scene = editorState.getCurrentScene(stamp);
            EditorCamera camera = scene == null ? null : scene.cameraById(selectedCameraId);
            if (camera == null) {
                // The camera was deleted, or the project was reloaded out from under the selection.
                selectedCameraId = null;
                open = false;
                drag = null;
                return;
            }
            snapshot = new Snapshot(scene, camera, editorState.evaluateCameraAt(scene, camera, playhead),
                previewIsVisible(scene, camera, playhead), playhead, snapshotProperties(editorState, scene, camera, playhead));
        } finally {
            editorState.release(stamp);
        }

        if (openNow) {
            // Opening as a tab beside the visuals panel is where this belongs: it is the properties
            // panel for whatever the timeline selected, not another window to arrange. Appearing
            // means the placement happens once, so the tab can still be dragged elsewhere.
            openNow = false;
            int visualsDock = VisualsWindow.dockNodeId();
            if (visualsDock != 0) {
                ImGui.setNextWindowDockID(visualsDock, ImGuiCond.Appearing);
            } else {
                // Nothing to dock to - the visuals panel is floating - so appear near the middle.
                ImGui.setNextWindowPos(ImGui.getMainViewport().getCenter().x + ReplayUI.scaleUi(150),
                    ImGui.getMainViewport().getCenter().y - ReplayUI.scaleUi(150), ImGuiCond.Appearing, 0.5f, 0.5f);
                ImGui.setNextWindowSize(ReplayUI.scaleUi(340), ReplayUI.scaleUi(420), ImGuiCond.Appearing);
            }
        }

        windowOpen.set(true);
        String title = I18n.get("flashback.camera_inspector") + "###CameraInspector";
        // Deliberately no NoFocusOnAppearing: a window that appears in a dock node without focus stays
        // behind whichever tab it docked beside, so the panel would never actually show. Focus is only
        // taken on the frame the window appears, and the timeline has already handled the click that
        // opened it.
        if (ImGui.begin(title, windowOpen)) {
            // No lock is held from here on. Every mutation below takes its own short write stamp.
            drawContents(editorState, replayServer, snapshot, playhead);
        }
        ImGui.end();

        if (!windowOpen.get()) {
            open = false;
        }
    }

    /**
     * Runs one mutation, holding the write stamp only for that mutation.
     *
     * <p>The scene and the camera are re-resolved under the lock by id, so nothing is carried into
     * the callback from an earlier unlocked read. The lock is released in a finally, so a callback
     * that throws cannot leave it held.
     */
    private static void mutate(EditorState editorState, java.util.function.Consumer<MutationContext> action) {
        EditorCamera preview = null;
        long stamp = editorState.acquireWrite();
        try {
            EditorScene scene = editorState.getCurrentScene(stamp);
            EditorCamera camera = scene == null ? null : scene.cameraById(selectedCameraId);
            MutationContext context = new MutationContext(editorState, scene, camera);
            action.accept(context);
            editorState.markDirty();
            if (scene != null && camera != null && scene.cameraById(camera.id) != null) preview = camera;
        } finally {
            editorState.release(stamp);
        }
        // previewCamera takes its own stamp; never call it inside the write critical section.
        if (preview != null) editorState.previewCamera(preview, TimelineWindow.getCursorTick());
    }

    /** Runs a mutation that produces the new selection, such as a camera being duplicated. */
    @Nullable
    private static EditorCamera mutateForCamera(EditorState editorState,
                                                java.util.function.Function<MutationContext, EditorCamera> action) {
        long stamp = editorState.acquireWrite();
        try {
            EditorScene scene = editorState.getCurrentScene(stamp);
            EditorCamera camera = scene == null ? null : scene.cameraById(selectedCameraId);
            EditorCamera result = action.apply(new MutationContext(editorState, scene, camera));
            editorState.markDirty();
            return result;
        } finally {
            editorState.release(stamp);
        }
    }

    private record MutationContext(EditorState state, @Nullable EditorScene scene, @Nullable EditorCamera camera) {}

    // -- Selection -------------------------------------------------------------------------------

    /** Whether this camera is the selected object. Cheap enough to ask per row per frame. */
    public static boolean isSelected(@Nullable EditorCamera camera) {
        return camera != null && camera.id.equals(selectedCameraId);
    }

    /** The selected camera's id, or null. The inspector is the only writer; the timeline reads it. */
    @Nullable
    public static UUID selectedCameraId() {
        return selectedCameraId;
    }

    /** Makes a camera the selected object and shows the inspector for it. */
    public static void select(@Nullable EditorCamera camera) {
        if (camera == null) {
            return;
        }
        selectedCameraId = camera.id;
        open = true;
        openNow = true;
    }

    public static void clear() {
        selectedCameraId = null;
        open = false;
        drag = null;
    }

    // -- Contents --------------------------------------------------------------------------------

    private static void drawContents(EditorState editorState, ReplayServer replayServer, Snapshot snapshot, int playhead) {
        EditorCamera camera = snapshot.camera();
        EditorScene scene = snapshot.scene();
        EditorState.CameraEvaluation evaluated = snapshot.evaluated();

        // A drag may be writing one property while the evaluation still describes the pre-drag state
        // for that frame, so the dragged numbers come from the drag itself.
        float[] position = valuesFor(EditorState.CameraProperty.POSITION, evaluated);
        float[] rotation = valuesFor(EditorState.CameraProperty.ROTATION, evaluated);
        float[] fov = valuesFor(EditorState.CameraProperty.FOV, evaluated);
        float[] shake = valuesFor(EditorState.CameraProperty.SHAKE, evaluated);

        // The header is the camera's identity, in its own timeline colour, with the playhead named
        // once so every keyframe control below is unambiguous about where it would write.
        int accent = TimelineColours.cameraAccent(scene.cameraIndexOf(camera));
        ImGui.textColored(accent, scene.displayNameOf(camera));
        ImGui.sameLine();
        ImGui.textDisabled(I18n.get("flashback.camera_inspector.at_playhead", TimelineWindow.formatTick(playhead)));

        if (ImGui.smallButton(I18n.get("flashback.rename") + "##cameraRename")) {
            // The existing rename flow: it only records state for the timeline's popup, it takes no lock.
            TimelineWindow.renameCamera(scene, camera);
        }
        ImGui.sameLine();
        if (ImGui.smallButton(I18n.get("flashback.duplicate") + "##cameraDuplicate")) {
            EditorCamera copy = mutateForCamera(editorState, ctx -> ctx.camera() == null || ctx.scene() == null
                ? null : TimelineWindow.duplicateCameraInScene(ctx.scene(), ctx.state(), ctx.camera()));
            if (copy != null) {
                select(copy);
            }
        }
        ImGuiHelper.tooltip(I18n.get("flashback.camera_inspector.duplicate_tooltip"));
        ImGui.sameLine();
        if (ImGui.smallButton(I18n.get("flashback.delete") + "##cameraDelete")) {
            ImGui.openPopup("##DeleteCamera");
        }
        if (ImGuiHelper.beginPopup("##DeleteCamera")) {
            ImGui.textDisabled(I18n.get("flashback.camera_inspector.delete_confirm", scene.displayNameOf(camera)));
            if (ImGui.button(I18n.get("flashback.delete") + "##confirmDelete")) {
                mutate(editorState, ctx -> deleteCamera(ctx));
                clear();
                ImGui.closeCurrentPopup();
            }
            ImGui.sameLine();
            if (ImGui.button(I18n.get("gui.cancel") + "##cancelDelete")) {
                ImGui.closeCurrentPopup();
            }
            ImGui.endPopup();
        }

        ImGui.separator();

        if (camera.kind != EditorCamera.Kind.FREE) {
            // An orbit or spectate camera is moved by its own kind's track, not by these fields, so
            // saying which one is the honest thing - the fields below are still its static pose.
            ImGui.textWrapped(I18n.get("flashback.camera_inspector.kind_note",
                I18n.get("flashback.camera_kind." + camera.kind.name().toLowerCase(Locale.ROOT))));
        }

        // Editing an inactive camera previews the selection without changing the switch lane.
        if (!snapshot.visible()) {
            ImGui.textWrapped(I18n.get("flashback.camera_inspector.not_live_note"));
        }

        if (ImGui.collapsingHeader(I18n.get("flashback.camera_inspector.transform") + "##cameraTransform")) {
            ImGui.textDisabled(I18n.get("flashback.camera_inspector.position"));
            dragField(editorState, replayServer, snapshot, playhead, EditorState.CameraProperty.POSITION,
                I18n.get("flashback.camera_inspector.x") + "##cameraX", position, 0, 1, POSITION_DRAG_SPEED,
                UNBOUNDED_MIN, UNBOUNDED_MAX, "%.3f");
            dragField(editorState, replayServer, snapshot, playhead, EditorState.CameraProperty.POSITION,
                I18n.get("flashback.camera_inspector.y") + "##cameraY", position, 1, 1, POSITION_DRAG_SPEED,
                UNBOUNDED_MIN, UNBOUNDED_MAX, "%.3f");
            dragField(editorState, replayServer, snapshot, playhead, EditorState.CameraProperty.POSITION,
                I18n.get("flashback.camera_inspector.z") + "##cameraZ", position, 2, 1, POSITION_DRAG_SPEED,
                UNBOUNDED_MIN, UNBOUNDED_MAX, "%.3f");
            animationRow(editorState, snapshot, playhead, EditorState.CameraProperty.POSITION);

            ImGui.textDisabled(I18n.get("flashback.camera_inspector.rotation"));
            dragField(editorState, replayServer, snapshot, playhead, EditorState.CameraProperty.ROTATION,
                I18n.get("flashback.pitch") + "##cameraPitch", rotation, 1, 1, ROTATION_DRAG_SPEED,
                UNBOUNDED_MIN, UNBOUNDED_MAX, "%.2f");
            dragField(editorState, replayServer, snapshot, playhead, EditorState.CameraProperty.ROTATION,
                I18n.get("flashback.yaw") + "##cameraYaw", rotation, 0, 1, ROTATION_DRAG_SPEED,
                UNBOUNDED_MIN, UNBOUNDED_MAX, "%.2f");
            dragField(editorState, replayServer, snapshot, playhead, EditorState.CameraProperty.ROTATION,
                I18n.get("flashback.roll") + "##cameraRoll", rotation, 2, 1, ROTATION_DRAG_SPEED,
                UNBOUNDED_MIN, UNBOUNDED_MAX, "%.2f");
            animationRow(editorState, snapshot, playhead, EditorState.CameraProperty.ROTATION);
        }

        if (ImGui.collapsingHeader(I18n.get("flashback.camera_inspector.lens") + "##cameraLens")) {
            // -1 is the camera's "not overridden" sentinel, not an angle. A drag has to start from the
            // project's default field of view instead of from -1: a clamping widget would otherwise
            // pin the sentinel to its lower bound, and a field of view of about zero renders nothing.
            if (fov[0] < 0 && drag == null) {
                fov[0] = Flashback.getConfig().internal.defaultOverrideFov;
            }
            dragField(editorState, replayServer, snapshot, playhead, EditorState.CameraProperty.FOV,
                I18n.get("flashback.fov") + "##cameraFov", fov, 0, 1, FOV_DRAG_SPEED,
                CAMERA_FOV_MIN, CAMERA_FOV_MAX, "%.1f");
            if (camera.fov < 0) {
                ImGui.sameLine();
                ImGui.textDisabled(I18n.get("flashback.camera_inspector.fov_not_overridden"));
            }
            animationRow(editorState, snapshot, playhead, EditorState.CameraProperty.FOV);
        }

        if (ImGui.collapsingHeader(I18n.get("flashback.camera_inspector.effects") + "##cameraEffects")) {
            if (ImGui.checkbox(I18n.get("flashback.camera_inspector.override_camera_shake") + "##cameraShakeEnabled",
                    camera.overrideCameraShake)) {
                mutate(editorState, CameraInspectorWindow::toggleCameraShake);
            }

            ImGui.beginDisabled(!camera.overrideCameraShake);
            dragField(editorState, replayServer, snapshot, playhead, EditorState.CameraProperty.SHAKE,
                I18n.get("flashback.frequency_x") + "##cameraShakeXFrequency", shake, 0, 1,
                SHAKE_FREQUENCY_DRAG_SPEED, SHAKE_MIN, SHAKE_MAX, "%.3f");
            dragField(editorState, replayServer, snapshot, playhead, EditorState.CameraProperty.SHAKE,
                I18n.get("flashback.amplitude_x") + "##cameraShakeXAmplitude", shake, 1, 1,
                SHAKE_AMPLITUDE_DRAG_SPEED, SHAKE_MIN, SHAKE_MAX, "%.3f");
            dragField(editorState, replayServer, snapshot, playhead, EditorState.CameraProperty.SHAKE,
                I18n.get("flashback.frequency_y") + "##cameraShakeYFrequency", shake, 2, 1,
                SHAKE_FREQUENCY_DRAG_SPEED, SHAKE_MIN, SHAKE_MAX, "%.3f");
            dragField(editorState, replayServer, snapshot, playhead, EditorState.CameraProperty.SHAKE,
                I18n.get("flashback.amplitude_y") + "##cameraShakeYAmplitude", shake, 3, 1,
                SHAKE_AMPLITUDE_DRAG_SPEED, SHAKE_MIN, SHAKE_MAX, "%.3f");
            ImGui.endDisabled();

            animationRow(editorState, snapshot, playhead, EditorState.CameraProperty.SHAKE);
        }

        if (ImGui.collapsingHeader(I18n.get("flashback.camera_inspector.animation") + "##cameraAnimation")) {
            ImGui.textWrapped(I18n.get("flashback.camera_inspector.animation_hint"));
        }
    }

    /**
     * The numbers a property is showing: the drag's own values while its gesture is in progress, and
     * otherwise what the camera evaluates to.
     *
     * <p>This is the "displayed" value. It is never written back anywhere - the drag session and the
     * evaluation are its only sources.
     */
    private static float[] valuesFor(EditorState.CameraProperty property, EditorState.CameraEvaluation evaluated) {
        if (drag != null && drag.cameraId.equals(selectedCameraId) && drag.property == property) {
            return drag.values;
        }
        return evaluatedValues(property, evaluated);
    }

    private static float[] evaluatedValues(EditorState.CameraProperty property, EditorState.CameraEvaluation evaluated) {
        return switch (property) {
            case POSITION -> new float[]{(float) evaluated.position().x, (float) evaluated.position().y,
                (float) evaluated.position().z};
            case ROTATION -> new float[]{(float) evaluated.yaw(), (float) evaluated.pitch(), (float) evaluated.roll()};
            case FOV -> new float[]{evaluated.fov()};
            case SHAKE -> new float[]{evaluated.shakeXFrequency(), evaluated.shakeXAmplitude(),
                evaluated.shakeYFrequency(), evaluated.shakeYAmplitude()};
        };
    }

    // -- Dragging --------------------------------------------------------------------------------

    /** Position moves in blocks per pixel; three decimals of a block is the useful resolution. */
    private static final float POSITION_DRAG_SPEED = 0.02f;
    /** Rotation moves in degrees per pixel. */
    private static final float ROTATION_DRAG_SPEED = 0.25f;
    /** FOV moves in degrees per pixel. */
    private static final float FOV_DRAG_SPEED = 0.1f;
    /** Shake frequency and amplitude move in their own units per pixel. */
    private static final float SHAKE_FREQUENCY_DRAG_SPEED = 0.01f;
    private static final float SHAKE_AMPLITUDE_DRAG_SPEED = 0.005f;

    /**
     * The range a field of view is held in.
     *
     * <p>One degree is the point below which the projection is degenerate and nothing in the world
     * draws; 110 is the widest the editor's own fov controls allow, so the two agree.
     */
    private static final float CAMERA_FOV_MIN = 1.0f;
    private static final float CAMERA_FOV_MAX = 110.0f;

    /** Position and rotation are genuinely unbounded, but the lower bound must still be real. */
    private static final float UNBOUNDED_MIN = -Float.MAX_VALUE;
    private static final float UNBOUNDED_MAX = Float.MAX_VALUE;

    /** Shake is a positive amount; a negative frequency or amplitude can only be meaningless. */
    private static final float SHAKE_MIN = 0.0f;
    private static final float SHAKE_MAX = 10.0f;

    /**
     * One draggable camera field.
     *
     * <p>The array is kept in the drag session rather than re-read from the store every frame:
     * imgui's drag widget accumulates the mouse delta onto the value it is handed, so re-reading a
     * keyframe that is only re-evaluated once per frame would make the drag snap back. Only the
     * window named by {@code values}/{@code from}/{@code count} belongs to this widget, so the
     * sibling field in the same row is left exactly as the user left it.
     *
     * <p>No lock is held while the widget runs; the mutation it produces takes the write stamp in
     * {@link #performDrag} and {@link #finishDrag}.
     */
    private static void dragField(EditorState editorState, ReplayServer replayServer, Snapshot snapshot, int playhead,
                                  EditorState.CameraProperty property, String label, float[] values,
                                  int from, int count, float speed, float min, float max, String format) {
        if (count != 1) throw new IllegalArgumentException("A camera field is scalar");
        Drag session = drag != null && drag.cameraId.equals(snapshot.camera().id)
            && drag.property == property && drag.widgetId.equals(label) ? drag : null;
        float[] initial = values.clone();
        float[] scalar = {session == null ? values[from] : session.values[from]};
        PropertyState state = snapshot.stateOf(property);
        boolean editable = !snapshot.evaluated().isDriven(property)
            || (state != null && compatibleType(state.track(), property));
        // Orbit/entity-driven transforms belong to their own track editors, not scalar pose keys.
        ImGui.beginDisabled(!editable);
        boolean changed = ImGuiHelper.dragFloat(label, scalar, speed, min, max, format);
        boolean activated = ImGui.isItemActivated();
        boolean deactivated = ImGui.isItemDeactivated();
        ImGui.endDisabled();
        if (!editable) return;
        if (session == null && (activated || changed)) {
            if (drag != null) finishDrag(editorState, snapshot, drag);
            session = beginDrag(snapshot, property, playhead, initial, label, from);
            drag = session;
        }
        if (session == null) return;
        if (changed && Float.compare(scalar[0], session.values[from]) != 0) {
            session.values[from] = scalar[0];
            values[from] = scalar[0];
            session.changed = true;
            performDrag(editorState, replayServer, snapshot, session);
        }
        if (deactivated) {
            finishDrag(editorState, snapshot, session);
            drag = null;
        }
    }

    /**
     * Starts a drag and records everything it needs to end as one undo step.
     *
     * <p>The initial displayed values were copied before submitting the scalar. Keyframes are copied
     * here and replaced, rather than edited, during each live write.
     */
    private static Drag beginDrag(Snapshot snapshot, EditorState.CameraProperty property, int playhead,
                                  float[] initial, String widgetId, int from) {
        PropertyState state = snapshot.stateOf(property);
        boolean keyed = state != null && state.track().enabled && state.keyframeAtPlayhead() != null;
        Keyframe before = keyed ? state.keyframeAtPlayhead().copy() : null;
        KeyframeTrack track = state != null && state.track().enabled
            && compatibleType(state.track(), property)
            && (keyed || snapshot.evaluated().isDriven(property)) ? state.track() : null;
        Keyframe base = track == null ? null : before;
        if (base == null && track != null && !track.keyframesByTick.isEmpty()) {
            var entry = track.keyframesByTick.floorEntry(playhead);
            if (entry == null) entry = track.keyframesByTick.firstEntry();
            base = evaluatedKeyframe(entry.getValue(), snapshot.evaluated());
        }
        return new Drag(snapshot.camera().id, widgetId, from, property, playhead, track, base,
            before, before == null ? null : before.copy(), initial.clone());
    }

    /** Seed a new key from the evaluated pose, retaining the driving track's type and interpolation. */
    private static Keyframe evaluatedKeyframe(Keyframe template, EditorState.CameraEvaluation evaluated) {
        Keyframe result = template.copy();
        for (EditorState.CameraProperty property : EditorState.CameraProperty.values()) {
            if ((template instanceof CameraKeyframe && (property == EditorState.CameraProperty.POSITION
                    || property == EditorState.CameraProperty.ROTATION))
                || template.keyframeType() == typeOf(property)
                || (property == EditorState.CameraProperty.FOV && template instanceof FOVKeyframe)) {
                result = keyframeWithDraggedValues(result, property, evaluatedValues(property, evaluated));
            }
        }
        if (result instanceof CameraKeyframe whole) whole.position.set(evaluated.position());
        else if (result instanceof CameraPositionKeyframe position) position.position.set(evaluated.position());
        return result;
    }

    private static Keyframe keyframeForDrag(Drag session) {
        return keyframeForDrag(session, session.base);
    }

    private static Keyframe keyframeForDrag(Drag session, Keyframe base) {
        Keyframe result = keyframeWithDraggedValues(base, session.property, session.values);
        if (session.property == EditorState.CameraProperty.POSITION) {
            Vector3d position = result instanceof CameraKeyframe whole ? whole.position
                : result instanceof CameraPositionKeyframe split ? split.position : null;
            Vector3d before = base instanceof CameraKeyframe whole ? whole.position
                : base instanceof CameraPositionKeyframe split ? split.position : null;
            if (position != null && before != null) {
                position.set(before);
                position.setComponent(session.from, session.values[session.from]);
            }
        }
        return result;
    }

    /**
     * Writes one frame of a drag, live and with no history.
     *
     * <p>Keyed and driven properties write only their track. An animated property's first changed
     * frame inserts its key at the playhead. Undriven properties write only the static fallback.
     */
    private static void performDrag(EditorState editorState, ReplayServer replayServer, Snapshot snapshot, Drag session) {
        mutate(editorState, ctx -> performDragFrame(ctx, session));

        // Whether the edit can reach the view was decided under the read stamp; the flag itself is
        // atomic, so setting it needs no lock. The same route the timeline uses while paused: the
        // apply pass only re-runs when the replay ticks, so a paused preview would not move. Setting
        // it every frame matters because the pass consumes it once per tick.
        if (snapshot.visible()) {
            replayServer.forceApplyKeyframes.set(true);
        }
    }

    /**
     * Ends a drag with exactly one undo step.
     *
     * <p>A keyed drag goes through {@link TimelineEdits#push} with the pre-drag keyframe and the final
     * one, so undo restores the old value and redo the new - one entry for the whole gesture rather
     * than one per frame. The final keyframe is built from the drag's own numbers, not from the
     * camera's stored fields (a keyed drag never writes those). An unkeyed drag has no history to
     * record - a camera's stored fields are not on the undo stack, the same as renaming one - so it
     * only marks the project dirty, which the mutation already did.
     */
    private static void finishDrag(EditorState editorState, Snapshot snapshot, Drag session) {
        if (!session.changed) {
            return;
        }
        if (session.base != null) {
            Keyframe after = keyframeForDrag(session);
            if (after != null) {
                mutate(editorState, ctx -> commitDrag(ctx, session, after));
            }
        }
    }

    /**
     * A keyframe carrying the drag's numbers, keeping whatever the dragged property does not own.
     *
     * <p>The keyframe's own type decides what it carries, not the property being dragged: a
     * whole-camera keyframe holds position and rotation together, so dragging its position keeps its
     * type - and therefore its rotation, at the value it already had - rather than replacing it with a
     * position-only keyframe and quietly dropping where the camera was looking. The interpolation
     * type is carried over for the same reason: it is not something the drag is editing.
     *
     * <p>The properties the drag does not own come from {@code base}, the keyframe the drag started
     * from, so a drag is strictly additive and cannot pick up anything that changed in between.
     */
    @Nullable
    private static Keyframe keyframeWithDraggedValues(Keyframe base, EditorState.CameraProperty property, float[] values) {
        InterpolationType interpolation = base.interpolationType();
        return switch (base.keyframeType().id()) {
            case "CAMERA" -> {
                boolean position = property == EditorState.CameraProperty.POSITION;
                boolean rotation = property == EditorState.CameraProperty.ROTATION;
                Vector3d basePosition = base instanceof CameraKeyframe whole ? new Vector3d(whole.position) : new Vector3d();
                yield new CameraKeyframe(
                    position ? new Vector3d(values[0], values[1], values[2]) : basePosition,
                    rotation ? values[0] : (base instanceof CameraKeyframe w2 ? w2.yaw : 0),
                    rotation ? values[1] : (base instanceof CameraKeyframe w3 ? w3.pitch : 0),
                    rotation ? values[2] : (base instanceof CameraKeyframe w4 ? w4.roll : 0),
                    interpolation);
            }
            case "CAMERA_POSITION" -> new CameraPositionKeyframe(new Vector3d(values[0], values[1], values[2]), interpolation);
            case "CAMERA_ROTATION" -> new CameraRotationKeyframe(values[0], values[1], values[2], interpolation);
            case "CAMERA_FOV" -> new CameraFovKeyframe(Math.min(CAMERA_FOV_MAX, Math.max(CAMERA_FOV_MIN, values[0])),
                interpolation);
            case "FOV" -> new FOVKeyframe(Math.min(CAMERA_FOV_MAX, Math.max(CAMERA_FOV_MIN, values[0])), interpolation);
            case "CAMERA_SHAKE" -> new CameraShakeKeyframe(values[0], values[1], values[2], values[3], true, interpolation);
            default -> null;
        };
    }

    /** Clamps a stored FOV to a usable angle. */
    private static float saneCameraFov(EditorCamera camera) {
        return Math.min(CAMERA_FOV_MAX, Math.max(CAMERA_FOV_MIN, camera.fov));
    }

    /** Copies a drag's numbers into the camera fields the property owns, and only those fields. */
    private static void applyValues(EditorCamera camera, EditorState.CameraProperty property, float[] values) {
        switch (property) {
            case POSITION -> {
                camera.x = values[0];
                camera.y = values[1];
                camera.z = values[2];
            }
            case ROTATION -> {
                camera.yaw = values[0];
                camera.pitch = values[1];
                camera.roll = values[2];
            }
            // Belt and braces behind the widget's bounds: nothing on this path may store an angle that
            // would render nothing.
            case FOV -> camera.fov = Math.min(CAMERA_FOV_MAX, Math.max(CAMERA_FOV_MIN, values[0]));
            case SHAKE -> {
                camera.cameraShakeXFrequency = values[0];
                camera.cameraShakeXAmplitude = values[1];
                // The Y pair is carried in the same session so a Y drag leaves X alone and vice versa.
                if (values.length > 3) {
                    camera.cameraShakeYFrequency = values[2];
                    camera.cameraShakeYAmplitude = values[3];
                }
            }
        }
    }

    // -- Mutation bodies -------------------------------------------------------------------------
    // Each of these runs under the write stamp taken by mutate(); none of them takes a lock itself.

    private static void deleteCamera(MutationContext ctx) {
        if (ctx.scene() != null && ctx.camera() != null) {
            TimelineEdits.deleteCamera(ctx.scene(), ctx.state(), ctx.camera());
        }
    }

    private static void toggleCameraShake(MutationContext ctx) {
        if (ctx.camera() != null) {
            ctx.camera().overrideCameraShake = !ctx.camera().overrideCameraShake;
        }
    }

    private static void performDragFrame(MutationContext ctx, Drag session) {
        if (ctx.scene() == null || ctx.camera() == null || !ctx.camera().id.equals(session.cameraId)) return;
        if (session.base != null) {
            KeyframeTrack track = session.track;
            if (track != null && track.enabled && ctx.scene().trackIndexOf(track) >= 0) {
                EditorState.CameraEvaluation initial = session.overrideTrack == null
                    ? ctx.state().evaluateCameraAt(ctx.scene(), ctx.camera(), session.tick) : null;
                Keyframe replacement = keyframeForDrag(session);
                if (replacement != null) track.keyframesByTick.put(session.tick, replacement);
                // Inserting a held key can move it before another active property write. Keep the
                // original track's key, and merge the final winner into this same undo gesture.
                KeyframeTrack winner = ctx.state().evaluatedCameraTrack(ctx.scene(), ctx.camera(), session.tick, session.property);
                if (session.overrideTrack == null && winner != null && winner != track && compatibleType(winner, session.property)) {
                    Keyframe existing = winner.keyframesByTick.get(session.tick);
                    var entry = winner.keyframesByTick.floorEntry(session.tick);
                    if (entry == null) entry = winner.keyframesByTick.firstEntry();
                    if (entry != null) {
                        session.overrideTrack = winner;
                        session.overrideBeforeForHistory = existing == null ? null : existing.copy();
                        session.overrideBase = evaluatedKeyframe(entry.getValue(), initial);
                    }
                }
                if (session.overrideTrack != null && ctx.scene().trackIndexOf(session.overrideTrack) >= 0) {
                    Keyframe override = keyframeForDrag(session, session.overrideBase);
                    if (override != null) session.overrideTrack.keyframesByTick.put(session.tick, override);
                }
            }
        } else {
            EditorCamera camera = ctx.camera();
            double x = camera.x, y = camera.y, z = camera.z;
            applyValues(camera, session.property, session.values);
            if (session.property == EditorState.CameraProperty.POSITION) {
                if (session.from != 0) camera.x = x;
                if (session.from != 1) camera.y = y;
                if (session.from != 2) camera.z = z;
            }
        }
    }

    private static void commitDrag(MutationContext ctx, Drag session, Keyframe after) {
        if (ctx.scene() == null || ctx.camera() == null || !ctx.camera().id.equals(session.cameraId)) return;
        KeyframeTrack track = session.track;
        int index = track == null ? -1 : ctx.scene().trackIndexOf(track);
        if (track == null || index < 0) return;
        EditorSceneHistoryAction undo = session.beforeForHistory == null
            ? new EditorSceneHistoryAction.RemoveKeyframe(track.keyframeType, index, session.tick)
            : new EditorSceneHistoryAction.SetKeyframe(track.keyframeType, index, session.tick, session.beforeForHistory);
        var undoActions = new java.util.ArrayList<EditorSceneHistoryAction>();
        var redoActions = new java.util.ArrayList<EditorSceneHistoryAction>();
        undoActions.add(undo);
        redoActions.add(new EditorSceneHistoryAction.SetKeyframe(track.keyframeType, index, session.tick, after));
        if (session.overrideTrack != null) {
            int overrideIndex = ctx.scene().trackIndexOf(session.overrideTrack);
            if (overrideIndex >= 0) {
                KeyframeType<?> overrideType = session.overrideTrack.keyframeType;
                undoActions.add(session.overrideBeforeForHistory == null
                    ? new EditorSceneHistoryAction.RemoveKeyframe(overrideType, overrideIndex, session.tick)
                    : new EditorSceneHistoryAction.SetKeyframe(overrideType, overrideIndex, session.tick, session.overrideBeforeForHistory));
                redoActions.add(new EditorSceneHistoryAction.SetKeyframe(overrideType, overrideIndex, session.tick,
                    keyframeForDrag(session, session.overrideBase)));
            }
        }
        TimelineEdits.push(ctx.scene(), ctx.state(), undoActions, redoActions,
            I18n.get("flashback.camera_inspector.dragged_property", I18n.get(labelOf(session.property))));
    }

    // -- Animation rows --------------------------------------------------------------------------

    /**
     * Shows a property's animation state and its key control.
     *
     * <p>The three states are drawn differently: a property no track drives reads as plain text, one
     * that is animated but has no key here reads in the warning colour used for "something is driving
     * this", and one with a key at the playhead is called out in white, the same colour the timeline
     * uses for selection. "Animated" is the evaluation's answer, not merely the presence of a track.
     *
     * <p>Both buttons mutate under their own short write stamp - a track and its first keyframe are
     * added in one undoable step, and the keyframe already at this tick decides the shape of what is
     * added so a whole-camera track keeps whole-camera keyframes.
     */
    private static void animationRow(EditorState editorState, Snapshot snapshot, int playhead,
                                     EditorState.CameraProperty property) {
        ImGui.pushID(property.name());
        EditorCamera camera = snapshot.camera();
        PropertyState state = snapshot.stateOf(property);
        boolean keyedHere = state != null && state.track().enabled && state.keyframeAtPlayhead() != null;
        boolean animated = snapshot.evaluated().isDriven(property);

        ImGui.textDisabled(I18n.get(labelOf(property)) + ":");
        ImGui.sameLine();
        if (keyedHere) {
            ImGui.textColored(0xFFFFFFFF, I18n.get("flashback.camera_inspector.state_keyed_here"));
        } else if (animated) {
            ImGui.textColored(0xFFDD6000, I18n.get("flashback.camera_inspector.state_animated"));
        } else {
            ImGui.textDisabled(I18n.get("flashback.camera_inspector.state_static"));
        }

        ImGui.sameLine();
        boolean canKey = camera.canOwn(typeOf(property));
        ImGui.beginDisabled(!canKey);
        if (keyedHere) {
            if (ImGui.smallButton(I18n.get("flashback.camera_inspector.remove_key") + "###RemoveKey")) {
                mutate(editorState, ctx -> removeKeyframe(ctx, property, playhead));
            }
            ImGuiHelper.tooltip(I18n.get("flashback.camera_inspector.remove_key_tooltip"));
        } else {
            if (ImGui.smallButton(I18n.get("flashback.camera_inspector.add_key") + "###AddKey")) {
                mutate(editorState, ctx -> putKeyframe(ctx, property, playhead));
            }
            ImGuiHelper.tooltip(I18n.get("flashback.camera_inspector.add_key_tooltip"));
        }
        if (!canKey) {
            // Outside the disabled block so the explanation is not greyed out with the button.
            ImGuiHelper.tooltip(I18n.get("flashback.camera_inspector.no_track_for_kind",
                I18n.get("flashback.camera_kind." + camera.kind.name().toLowerCase(Locale.ROOT))));
        }
        ImGui.endDisabled();

        if (animated && !keyedHere) {
            ImGui.textWrapped(I18n.get("flashback.camera_inspector.animated_not_keyed_here"));
        }
        ImGui.popID();
    }

    /**
     * Adds a keyframe for a property at a tick, creating its track if this is the first key of it.
     *
     * <p>Track and keyframe are one undoable step, so undo cannot leave an empty row behind. A
     * keyframe already at the tick decides the shape of what is added, so a whole-camera track keeps
     * whole-camera keyframes. All of this runs under the caller's write stamp.
     */
    private static void putKeyframe(MutationContext ctx, EditorState.CameraProperty property, int tick) {
        if (ctx.scene() == null || ctx.camera() == null) {
            return;
        }
        KeyframeType<?> type = typeOf(property);
        KeyframeTrack track = propertyTrack(ctx.state(), ctx.scene(), ctx.camera(), property, tick);
        Keyframe existing = track == null ? null : track.keyframesByTick.get(tick);
        EditorState.CameraEvaluation evaluated = ctx.state().evaluateCameraAt(ctx.scene(), ctx.camera(), tick);
        Keyframe template = existing;
        if (template == null && track != null && !track.keyframesByTick.isEmpty()) {
            var entry = track.keyframesByTick.floorEntry(tick);
            template = (entry == null ? track.keyframesByTick.firstEntry() : entry).getValue();
        }
        if (template == null) template = TimelineWindow.readPropertyKeyframe(ctx.camera(), type);
        Keyframe keyframe = template == null ? null : evaluatedKeyframe(template, evaluated);
        if (keyframe == null) {
            return;
        }

        if (track != null) {
            TimelineEdits.setKeyframe(ctx.scene(), ctx.state(), track, tick, keyframe);
            return;
        }
        if (!ctx.camera().canOwn(type)) {
            ReplayUI.setInfoOverlay(I18n.get("flashback.camera_inspector.no_track_for_kind",
                I18n.get("flashback.camera_kind." + ctx.camera().kind.name().toLowerCase(Locale.ROOT))));
            return;
        }
        int index = ctx.scene().insertionIndexForTrackOf(ctx.camera());
        TimelineEdits.push(ctx.scene(), ctx.state(),
            List.of(new EditorSceneHistoryAction.RemoveTrack(type, index)),
            List.of(new EditorSceneHistoryAction.AddTrack(type, index, ctx.camera().id),
                new EditorSceneHistoryAction.SetKeyframe(type, index, tick, keyframe)),
            I18n.get("flashback.added_named_keyframe", type.name()));
    }

    /** Removes a property's keyframe at a tick, if it has one. Runs under the caller's write stamp. */
    private static void removeKeyframe(MutationContext ctx, EditorState.CameraProperty property, int tick) {
        if (ctx.scene() == null || ctx.camera() == null) {
            return;
        }
        KeyframeType<?> type = typeOf(property);
        KeyframeTrack track = propertyTrack(ctx.state(), ctx.scene(), ctx.camera(), property, tick);
        if (track == null) {
            return;
        }
        Keyframe existing = track.keyframesByTick.get(tick);
        int index = ctx.scene().trackIndexOf(track);
        if (existing == null || index < 0) {
            return;
        }
        TimelineEdits.push(ctx.scene(), ctx.state(),
            List.of(new EditorSceneHistoryAction.SetKeyframe(track.keyframeType, index, tick, existing.copy())),
            List.of(new EditorSceneHistoryAction.RemoveKeyframe(track.keyframeType, index, tick)),
            I18n.get("flashback.removed_named_keyframe", type.name()));
    }

    /** The camera's stored numbers for a property, used only to shape a keyframe being added. */
    private static float[] cameraValues(EditorCamera camera, EditorState.CameraProperty property) {
        return switch (property) {
            case POSITION -> new float[]{(float) camera.x, (float) camera.y, (float) camera.z};
            case ROTATION -> new float[]{camera.yaw, camera.pitch, camera.roll};
            case FOV -> new float[]{saneCameraFov(camera)};
            case SHAKE -> new float[]{camera.cameraShakeXFrequency, camera.cameraShakeXAmplitude,
                camera.cameraShakeYFrequency, camera.cameraShakeYAmplitude};
        };
    }

    /** Whether normal playback will also show this edit; other selections preview explicitly. */
    private static boolean previewIsVisible(EditorScene scene, EditorCamera camera, int tick) {
        if (camera.kind != EditorCamera.Kind.FREE) {
            return false;
        }
        EditorCamera active = scene.resolveCameraAt(tick);
        return active == null || active.id.equals(camera.id);
    }

    @Nullable
    private static KeyframeTrack propertyTrack(EditorState state, EditorScene scene, EditorCamera camera,
                                               EditorState.CameraProperty property, int tick) {
        KeyframeTrack winner = state.evaluatedCameraTrack(scene, camera, tick, property);
        return winner == null ? compatibleTrack(scene, camera, property) : winner;
    }

    /** Empty/before-first lanes are reusable, but are not claimed as animated winning sources. */
    @Nullable
    private static KeyframeTrack compatibleTrack(EditorScene scene, EditorCamera camera,
                                                 EditorState.CameraProperty property) {
        for (KeyframeTrack track : scene.keyframeTracks) {
            if (track != null && track.enabled && camera.id.equals(track.cameraId)
                && compatibleType(track, property)) return track;
        }
        return null;
    }

    private static boolean compatibleType(KeyframeTrack track, EditorState.CameraProperty property) {
        return track.keyframeType == typeOf(property)
            || (property == EditorState.CameraProperty.FOV && track.keyframeType == FOVKeyframeType.INSTANCE)
            || ((property == EditorState.CameraProperty.POSITION || property == EditorState.CameraProperty.ROTATION)
                && track.keyframeType == CameraKeyframeType.INSTANCE);
    }

    /** The track type a camera property lives on. */
    private static KeyframeType<?> typeOf(EditorState.CameraProperty property) {
        return switch (property) {
            case POSITION -> CameraPositionKeyframeType.INSTANCE;
            case ROTATION -> CameraRotationKeyframeType.INSTANCE;
            case FOV -> CameraFovKeyframeType.INSTANCE;
            case SHAKE -> CameraShakeKeyframeType.INSTANCE;
        };
    }

    /** The name a property is shown under, for labels and for describing a drag in the history. */
    private static String labelOf(EditorState.CameraProperty property) {
        return switch (property) {
            case POSITION -> "flashback.camera_inspector.position";
            case ROTATION -> "flashback.camera_inspector.rotation";
            case FOV -> "flashback.fov";
            case SHAKE -> "flashback.visuals.overrides.camera_shake";
        };
    }

}

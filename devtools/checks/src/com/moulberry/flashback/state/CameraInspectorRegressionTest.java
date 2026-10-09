package com.moulberry.flashback.state;

import com.moulberry.flashback.keyframe.Keyframe;
import com.moulberry.flashback.keyframe.impl.CameraKeyframe;
import com.moulberry.flashback.keyframe.impl.CameraPositionKeyframe;
import com.moulberry.flashback.keyframe.interpolation.InterpolationType;
import com.moulberry.flashback.keyframe.types.CameraKeyframeType;
import com.moulberry.flashback.keyframe.types.CameraPositionKeyframeType;
import org.joml.Vector3d;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Source regressions for native ImGui submission and preview wiring. This deliberately requires no
 * ImGui context: it checks identity, scalar slices, item lifecycle and lock order, not mouse rendering.
 * Run from the repository root after compilation, alongside the functional camera-object checks.
 */
public final class CameraInspectorRegressionTest {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        String inspector = Files.readString(Path.of(
            "src/main/java/com/moulberry/flashback/editor/ui/windows/CameraInspectorWindow.java"));
        String helper = Files.readString(Path.of(
            "src/main/java/com/moulberry/flashback/editor/ui/ImGuiHelper.java"));
        String field = method(inspector, "private static void dragField(");
        check("one scalar submission per field per frame", count(field, "ImGuiHelper.dragFloat(") == 1);
        check("scalar uses selected axis", field.contains("float[] scalar = {session == null ? values[from] : session.values[from]};"));
        check("initial copied before native mutation", field.indexOf("float[] initial = values.clone();")
            < field.indexOf("ImGuiHelper.dragFloat("));
        check("gesture belongs to camera/property/widget", field.contains("drag.cameraId.equals(snapshot.camera().id)")
            && field.contains("drag.property == property") && field.contains("drag.widgetId.equals(label)"));
        check("zero-change clicks end gesture", field.contains("ImGui.isItemDeactivated()")
            && field.contains("if (deactivated)"));
        check("only actual changed scalars write", field.contains("changed && Float.compare(scalar[0], session.values[from]) != 0")
            && field.indexOf("performDrag(") > field.indexOf("if (changed &&"));
        axis(inspector, "cameraX", "position", 0);
        axis(inspector, "cameraY", "position", 1);
        axis(inspector, "cameraZ", "position", 2);
        axis(inspector, "cameraPitch", "rotation", 1);
        axis(inspector, "cameraYaw", "rotation", 0);
        axis(inspector, "cameraRoll", "rotation", 2);
        axis(inspector, "cameraShakeXFrequency", "shake", 0);
        axis(inspector, "cameraShakeXAmplitude", "shake", 1);
        axis(inspector, "cameraShakeYFrequency", "shake", 2);
        axis(inspector, "cameraShakeYAmplitude", "shake", 3);

        String scalar = method(helper, "public static boolean dragFloat(String label, float[] value, float speed, float min");
        check("unique scalar label scope", scalar.indexOf("ImGui.pushID(label)") < scalar.indexOf("ImGui.dragFloat(\"##value\"")
            && scalar.contains("ImGui.popID()"));
        check("native fine adjustment is not cancelled", !scalar.contains("speed *") && !scalar.contains("effectiveSpeed"));
        check("group forwards item lifecycle after label", scalar.indexOf("ImGui.beginGroup()") < scalar.indexOf("ImGui.dragFloat(")
            && scalar.indexOf("ImGui.endGroup()") > scalar.indexOf("ImGui.textUnformatted(renderedText)"));

        String mutate = method(inspector, "private static void mutate(");
        check("selected camera reaches actual rendering preview", mutate.contains("editorState.previewCamera(preview, TimelineWindow.getCursorTick())"));
        check("preview runs after write stamp released", mutate.indexOf("editorState.previewCamera(")
            > mutate.indexOf("editorState.release(stamp)") && mutate.contains("if (preview != null)"));
        String begin = method(inspector, "private static Drag beginDrag(");
        check("activation alone never auto-keys", !begin.contains("keyframesByTick.put(") && !begin.contains("applyValues("));
        check("driven gestures retain target track", begin.contains("snapshot.evaluated().isDriven(property)")
            && begin.contains("evaluatedKeyframe(entry.getValue(), snapshot.evaluated())"));
        String live = method(inspector, "private static void performDragFrame(");
        check("live keyed writes use gesture numbers", live.contains("keyframeForDrag(session)")
            && live.contains("keyframesByTick.put(session.tick, replacement)"));
        String merged = method(inspector, "private static Keyframe keyframeForDrag(Drag session, Keyframe base)");
        check("position siblings retain double precision", merged.contains("position.set(before)")
            && merged.contains("position.setComponent(session.from, session.values[session.from])"));
        check("key merge reads gesture values", merged.contains("keyframeWithDraggedValues(base, session.property, session.values)"));
        String finish = method(inspector, "private static void finishDrag(");
        check("release uses gesture values, not static fallback", finish.contains("keyframeForDrag(session)")
            && !finish.contains("cameraValues("));
        String commit = method(inspector, "private static void commitDrag(");
        check("undo removes auto-key when originally absent", commit.contains("session.beforeForHistory == null")
            && commit.contains("new EditorSceneHistoryAction.RemoveKeyframe("));
        check("one history merge at gesture end", count(commit, "TimelineEdits.push(") == 1);
        check("snapshot uses authoritative winning provenance", method(inspector, "private static java.util.Map<EditorState.CameraProperty, PropertyState> snapshotProperties(")
            .contains("state.evaluatedCameraTrack(scene, camera, tick, property)"));
        check("inspector does not duplicate active/held resolution", !inspector.contains("createKeyframeChange(tick, null)"));
        autoKeyPrecedence(false);
        autoKeyPrecedence(true);
        System.out.println("All " + assertions + " inspector regression assertions passed (native rendering not exercised)");
    }

    /** Calls the actual private gesture mutation/commit path, not a reproduction of its algorithm. */
    private static void autoKeyPrecedence(boolean existingOverride) throws Exception {
        EditorState state = new EditorState();
        long stamp = state.acquireWrite();
        try {
            EditorScene scene = state.getCurrentScene(stamp);
            EditorCamera camera = new EditorCamera("precedence", EditorCamera.Kind.FREE);
            scene.cameras.add(camera);
            KeyframeTrack whole = new KeyframeTrack(CameraKeyframeType.INSTANCE);
            whole.cameraId = camera.id;
            whole.keyframesByTick.put(0, new CameraKeyframe(new Vector3d(1.123456789, 2, 3), 11, 22, 33, InterpolationType.LINEAR));
            KeyframeTrack split = new KeyframeTrack(CameraPositionKeyframeType.INSTANCE);
            split.cameraId = camera.id;
            split.keyframesByTick.put(0, new CameraPositionKeyframe(new Vector3d(100, 200, 300), InterpolationType.LINEAR));
            split.keyframesByTick.put(20, new CameraPositionKeyframe(new Vector3d(200, 300, 400), InterpolationType.LINEAR));
            if (existingOverride) split.keyframesByTick.put(10,
                new CameraPositionKeyframe(new Vector3d(999, 888, 777), InterpolationType.EASE_IN_OUT));
            scene.keyframeTracks.add(whole);
            scene.keyframeTracks.add(split);
            EditorState.CameraEvaluation evaluated = state.evaluateCameraAt(scene, camera, 10);
            check("held whole wins before auto-key", state.evaluatedCameraTrack(scene, camera, 10, EditorState.CameraProperty.POSITION) == whole);

            Class<?> inspector = Class.forName("com.moulberry.flashback.editor.ui.windows.CameraInspectorWindow");
            Class<?> snapshotType = Class.forName(inspector.getName() + "$Snapshot");
            Method properties = inspector.getDeclaredMethod("snapshotProperties", EditorState.class, EditorScene.class, EditorCamera.class, int.class);
            properties.setAccessible(true);
            Constructor<?> snapshotConstructor = snapshotType.getDeclaredConstructors()[0];
            snapshotConstructor.setAccessible(true);
            Object snapshot = snapshotConstructor.newInstance(scene, camera, evaluated, false, 10,
                properties.invoke(null, state, scene, camera, 10));
            Method begin = inspector.getDeclaredMethod("beginDrag", snapshotType, EditorState.CameraProperty.class,
                int.class, float[].class, String.class, int.class);
            begin.setAccessible(true);
            Object gesture = begin.invoke(null, snapshot, EditorState.CameraProperty.POSITION, 10,
                new float[]{(float) evaluated.position().x, (float) evaluated.position().y, (float) evaluated.position().z}, "test##cameraY", 1);
            check("activation creates no key", !whole.keyframesByTick.containsKey(10));
            var valuesField = gesture.getClass().getDeclaredField("values");
            valuesField.setAccessible(true);
            ((float[]) valuesField.get(gesture))[1] = 42;
            Class<?> contextType = Class.forName(inspector.getName() + "$MutationContext");
            Constructor<?> contextConstructor = contextType.getDeclaredConstructors()[0];
            contextConstructor.setAccessible(true);
            Object context = contextConstructor.newInstance(state, scene, camera);
            Method perform = inspector.getDeclaredMethod("performDragFrame", contextType, gesture.getClass());
            perform.setAccessible(true);
            perform.invoke(null, context, gesture);
            perform.invoke(null, context, gesture);
            EditorState.CameraEvaluation after = state.evaluateCameraAt(scene, camera, 10);
            check("auto-key precedence displays edited scalar", after.position().y == 42);
            check("auto-key precedence keeps evaluated sibling precision", after.position().x == evaluated.position().x
                && after.position().z == evaluated.position().z && after.yaw() == evaluated.yaw());
            check("auto-key does not duplicate ticks", whole.keyframesByTick.size() == 2 && split.keyframesByTick.size() == 3);
            Method key = inspector.getDeclaredMethod("keyframeForDrag", gesture.getClass());
            key.setAccessible(true);
            Method commit = inspector.getDeclaredMethod("commitDrag", contextType, gesture.getClass(), Keyframe.class);
            commit.setAccessible(true);
            commit.invoke(null, context, gesture, key.invoke(null, gesture));
            scene.undo(state, ignored -> {});
            check("undo removes original auto-key", !whole.keyframesByTick.containsKey(10));
            check("undo restores override absence or existing key", existingOverride
                ? ((CameraPositionKeyframe) split.keyframesByTick.get(10)).position.y == 888
                    && split.keyframesByTick.get(10).interpolationType() == InterpolationType.EASE_IN_OUT
                : !split.keyframesByTick.containsKey(10));
            check("undo restores original evaluated pose", state.evaluateCameraAt(scene, camera, 10).position().equals(evaluated.position()));
            scene.redo(state, ignored -> {});
            check("redo restores gesture evaluation", state.evaluateCameraAt(scene, camera, 10).position().y == 42);
        } finally {
            state.release(stamp);
        }
    }

    private static void axis(String source, String widget, String array, int index) {
        check(widget + " edits only axis " + index,
            source.contains("\"##" + widget + "\", " + array + ", " + index + ", 1,"));
    }

    private static String method(String source, String signature) {
        int start = source.indexOf(signature);
        if (start < 0) throw new AssertionError("Missing method: " + signature);
        int brace = source.indexOf('{', start);
        int depth = 1;
        int end = brace + 1;
        while (depth > 0 && end < source.length()) {
            char c = source.charAt(end++);
            if (c == '{') depth++;
            else if (c == '}') depth--;
        }
        return source.substring(start, end);
    }

    private static int count(String source, String needle) {
        return (source.length() - source.replace(needle, "").length()) / needle.length();
    }

    private static void check(String name, boolean passes) {
        assertions++;
        if (!passes) throw new AssertionError(name);
    }
}

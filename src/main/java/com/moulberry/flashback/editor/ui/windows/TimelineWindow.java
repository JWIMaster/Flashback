package com.moulberry.flashback.editor.ui.windows;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.FlashbackGson;
import com.moulberry.flashback.Utils;
import com.moulberry.flashback.editor.CopiedKeyframes;
import com.moulberry.flashback.editor.SavedTrack;
import com.moulberry.flashback.editor.keybinds.Keybinds;
import com.moulberry.flashback.editor.ui.ImGuiHelper;
import com.moulberry.flashback.editor.ui.KeyframeRelativeOffsets;
import com.moulberry.flashback.editor.ui.ReplayUI;
import com.moulberry.flashback.editor.ui.timeline.TimelineColours;
import com.moulberry.flashback.editor.ui.timeline.TimelineEdits;
import com.moulberry.flashback.editor.ui.timeline.TimelineLayout;
import com.moulberry.flashback.editor.ui.timeline.TimelineRow;
import com.moulberry.flashback.editor.ui.timeline.TimelineSelection;
import com.moulberry.flashback.keyframe.Keyframe;
import com.moulberry.flashback.keyframe.KeyframeRegistry;
import com.moulberry.flashback.keyframe.KeyframeType;
import com.moulberry.flashback.keyframe.handler.MinecraftKeyframeHandler;
import com.moulberry.flashback.keyframe.impl.CameraKeyframe;
import com.moulberry.flashback.keyframe.impl.CameraOrbitKeyframe;
import com.moulberry.flashback.keyframe.impl.CameraSwitchKeyframe;
import com.moulberry.flashback.keyframe.impl.TimelapseKeyframe;
import com.moulberry.flashback.keyframe.interpolation.InterpolationType;
import com.moulberry.flashback.keyframe.types.CameraKeyframeType;
import com.moulberry.flashback.keyframe.types.TimelapseKeyframeType;
import com.moulberry.flashback.playback.ReplayServer;
import com.moulberry.flashback.record.FlashbackMeta;
import com.moulberry.flashback.record.ReplayMarker;
import com.moulberry.flashback.state.EditorCamera;
import com.moulberry.flashback.state.EditorScene;
import com.moulberry.flashback.state.EditorSceneHistoryAction;
import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.state.EditorStateManager;
import com.moulberry.flashback.state.KeyframeTrack;
import com.moulberry.flashback.utils.InputHelper;
import imgui.moulberry90.ImDrawList;
import imgui.moulberry90.ImGui;
import imgui.moulberry90.ImVec4;
import imgui.moulberry90.flag.ImGuiCol;
import imgui.moulberry90.flag.ImGuiComboFlags;
import imgui.moulberry90.flag.ImGuiInputTextFlags;
import imgui.moulberry90.flag.ImGuiKey;
import imgui.moulberry90.flag.ImGuiMouseButton;
import imgui.moulberry90.flag.ImGuiMouseCursor;
import imgui.moulberry90.flag.ImGuiPopupFlags;
import imgui.moulberry90.flag.ImGuiStyleVar;
import imgui.moulberry90.flag.ImGuiWindowFlags;
import imgui.moulberry90.type.ImString;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The timeline window.
 *
 * <p>The design is in three parts, and keeping them apart is what makes the window predictable:
 *
 * <ul>
 *   <li>{@link Frame} is everything the window knows for one frame - the panels' rectangles, the
 *       tick-to-pixel mapping, the rows, the mouse position. It is built once and passed to every
 *       method, so no part of drawing or hit-testing can disagree with another about where something
 *       is.</li>
 *   <li>{@link Drag} is the single gesture in progress. There is only ever one, so no combination of
 *       flags can be active at once and finishing a gesture is one method.</li>
 *   <li>Input raises an <em>intent</em> (select this, open that menu, start this drag); drawing and
 *       {@link TimelineEdits} carry it out. Nothing mutates the scene from inside a hit test.</li>
 * </ul>
 *
 * <p>Rows come from {@link TimelineLayout}, the selection from {@link TimelineSelection}, and every
 * change to the project goes through {@link TimelineEdits}, so each edit is undoable by construction.
 */
public class TimelineWindow {

    // -- Metrics and colours ---------------------------------------------------------------------

    private static final int ROW_HOVER_TINT = 0x14FFFFFF;
    private static final int ROW_DIVIDER = 0x18FFFFFF;
    private static final int SELECTED_OUTLINE = 0xFFFFFFFF;

    private static final float[] REPLAY_TICK_SPEEDS = {1.0f, 2.0f, 4.0f, 10.0f, 20.0f, 40.0f, 100.0f, 200.0f, 400.0f};

    // -- Live state ------------------------------------------------------------------------------

    private static final TimelineSelection SELECTION = new TimelineSelection();

    private static EditorState editorState;
    private static EditorScene scene;
    private static long sceneStamp;
    private static boolean sceneStampIsWrite;

    private static int cursorTicks;
    private static int pendingStepBackwardsTicks;
    /** Whether the pointer was inside this window last frame, used to stop the window dragging. */
    private static boolean windowHovered;

    /** The one gesture in progress, or null. */
    @Nullable
    private static Drag drag;

    /** Intents raised by input this frame and carried out while drawing. */
    @Nullable
    private static TimelineSelection.Ref pendingInspector;
    private static boolean inspectorOpen;
    private static boolean openInspectorNow;
    private static boolean openTypePopupNow;
    @Nullable
    private static KeyframeTrack pendingCutMenuTrack;
    private static int pendingCutMenuTick;
    private static boolean openCutMenuNow;
    private static int pendingCreateRow = -1;
    private static int pendingCreateTick;
    @Nullable
    private static KeyframeType.KeyframeCreatePopup<?> pendingTypePopup;
    @Nullable
    private static KeyframeTrack pendingTypePopupTrack;
    private static int pendingTypePopupTick;

    private static ImString sceneNameString;
    private static ImString cameraNameString;
    @Nullable
    private static EditorCamera pendingRenameCamera;
    private static boolean openRenameCameraPopup;

    private static boolean copyRelativeToPosition;
    private static boolean copyRelativeToYaw;
    private static boolean copyRelativeToPitch;

    /** Painting the enable toggle by dragging across rows. */
    private static boolean enablePaintActive;
    private static boolean enablePaintValue;

    public static int getCursorTick() {
        return cursorTicks;
    }

    // -- Frame -----------------------------------------------------------------------------------

    /**
     * How a replay tick maps to a screen X and back, for the current zoom.
     *
     * <p>Kept separate from the frame because the frame's own construction needs it: working out
     * which cuts to draw while one of them is being dragged happens before there is a frame to ask.
     */
    private record TickScale(float x, float timelineLeftOffset, float timelineWidth,
                             float availableTicks, int minTicks, int totalTicks) {

        float xOfTick(float tick) {
            return this.x + this.timelineLeftOffset + (tick - this.minTicks) / this.availableTicks * this.timelineWidth;
        }

        int tickAtX(float screenX) {
            float relative = screenX - (this.x + this.timelineLeftOffset);
            float amount = Math.max(0f, Math.min(1f, relative / this.timelineWidth));
            int tick = this.minTicks + Math.round(amount * this.availableTicks);
            return Math.max(0, Math.min(this.totalTicks, tick));
        }

        float pixelsPerTick() {
            return this.timelineWidth / this.availableTicks;
        }
    }

    /**
     * Everything about the window for one frame.
     *
     * <p>Every position is derived here once, rather than recomputed at each use, so a hit test can
     * only ever agree with what was drawn.
     */
    private static final class Frame {
        final ReplayServer replayServer;
        final FlashbackMeta metadata;
        final EditorState state;
        final EditorScene scene;
        final TimelineLayout layout;

        final float x, y, width, height;
        final float leftWidth, rulerHeight;
        final float minorSeparatorHeight, timestampHeight;
        final float uiScale;
        final float keyframeSize, rowHeight, sectionHeight, cutsLaneHeight, buttonSize, indentWidth;
        final float contentY;
        final boolean showScrollbar;

        final float mouseX, mouseY;
        final boolean mouseInLeft, mouseInTimeline, mouseInRows, mouseInRuler;

        final TickScale scale;
        final int cursorTicks, currentReplayTick, totalTicks;
        final int minTicks;
        final float availableTicks, timelineWidth, timelineLeftOffset;
        final int minorsPerMajor, ticksPerMinor;
        final boolean showSubSeconds;
        final float minorSeparatorWidth;
        final int errorOffset;

        final float zoomBarHeight, zoomBarMin, zoomBarMax;
        final boolean zoomBarExpanded;
        final float controlSize, controlsY;
        final float skipBackwardsX, slowDownX, pauseX, fastForwardsX, skipForwardsX;

        final List<SwitchSegment> switchSegments;
        final List<SwitchCut> switchCuts;
        final boolean cutsLaneEnabled;

        @SuppressWarnings("checkstyle:ParameterNumber")
        Frame(ReplayServer replayServer, FlashbackMeta metadata, EditorState state, EditorScene scene,
              TimelineLayout layout, float x, float y, float width, float height, float leftWidth,
              float rulerHeight, float minorSeparatorHeight, float timestampHeight,
              float uiScale, float keyframeSize, float rowHeight, float sectionHeight, float cutsLaneHeight,
              float buttonSize, float indentWidth, float contentY,
              boolean showScrollbar, float mouseX, float mouseY, TickScale scale, int cursorTicks, int currentReplayTick,
              int totalTicks, int minTicks, float availableTicks, float timelineWidth, float timelineLeftOffset,
              int minorsPerMajor, int ticksPerMinor, boolean showSubSeconds, float minorSeparatorWidth, int errorOffset,
              float zoomBarHeight, float zoomBarMin, float zoomBarMax, boolean zoomBarExpanded,
              float controlSize, float controlsY, float skipBackwardsX, float slowDownX, float pauseX,
              float fastForwardsX, float skipForwardsX,
              List<SwitchSegment> switchSegments, List<SwitchCut> switchCuts, boolean cutsLaneEnabled) {
            this.replayServer = replayServer;
            this.metadata = metadata;
            this.state = state;
            this.scene = scene;
            this.layout = layout;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.leftWidth = leftWidth;
            this.rulerHeight = rulerHeight;
            this.minorSeparatorHeight = minorSeparatorHeight;
            this.timestampHeight = timestampHeight;
            this.uiScale = uiScale;
            this.keyframeSize = keyframeSize;
            this.rowHeight = rowHeight;
            this.sectionHeight = sectionHeight;
            this.cutsLaneHeight = cutsLaneHeight;
            this.buttonSize = buttonSize;
            this.indentWidth = indentWidth;
            this.contentY = contentY;
            this.showScrollbar = showScrollbar;
            this.scale = scale;
            this.mouseX = mouseX;
            this.mouseY = mouseY;
            this.cursorTicks = cursorTicks;
            this.currentReplayTick = currentReplayTick;
            this.totalTicks = totalTicks;
            this.minTicks = minTicks;
            this.availableTicks = availableTicks;
            this.timelineWidth = timelineWidth;
            this.timelineLeftOffset = timelineLeftOffset;
            this.minorsPerMajor = minorsPerMajor;
            this.ticksPerMinor = ticksPerMinor;
            this.showSubSeconds = showSubSeconds;
            this.minorSeparatorWidth = minorSeparatorWidth;
            this.errorOffset = errorOffset;
            this.zoomBarHeight = zoomBarHeight;
            this.zoomBarMin = zoomBarMin;
            this.zoomBarMax = zoomBarMax;
            this.zoomBarExpanded = zoomBarExpanded;
            this.controlSize = controlSize;
            this.controlsY = controlsY;
            this.skipBackwardsX = skipBackwardsX;
            this.slowDownX = slowDownX;
            this.pauseX = pauseX;
            this.fastForwardsX = fastForwardsX;
            this.skipForwardsX = skipForwardsX;
            this.switchSegments = switchSegments;
            this.switchCuts = switchCuts;
            this.cutsLaneEnabled = cutsLaneEnabled;

            this.mouseInLeft = mouseX >= x && mouseX < x + leftWidth;
            this.mouseInTimeline = mouseX >= x + leftWidth && mouseX <= x + width;
            this.mouseInRows = mouseY > y + rulerHeight && mouseY < y + height;
            this.mouseInRuler = mouseY >= y && mouseY <= y + rulerHeight && mouseX >= x + leftWidth;
        }

        float xOfTick(float tick) {
            return this.scale.xOfTick(tick);
        }

        int tickAtX(float screenX) {
            return this.scale.tickAtX(screenX);
        }

        float pixelsPerTick() {
            return this.scale.pixelsPerTick();
        }

        float rowTop(int rowIndex) {
            return this.layout.rowTop(this.contentY, rowIndex);
        }

        float rowBottom(int rowIndex) {
            return this.layout.rowBottom(this.contentY, rowIndex);
        }

        float rowHeight(int rowIndex) {
            return this.layout.rowHeight(rowIndex);
        }

        int rowAt(float screenY) {
            return this.layout.rowAt(screenY, this.contentY);
        }

        float timelineLeft() {
            return this.x + this.leftWidth;
        }

        float timelineRight() {
            return this.x + this.width;
        }

        float zoomBarTop() {
            return this.y + this.height - this.zoomBarHeight;
        }

        /** Whether the pointer is over the cut lane specifically, which has its own gestures. */
        boolean mouseOverCutsLane() {
            if (!this.mouseInTimeline || !this.mouseInRows) {
                return false;
            }
            KeyframeTrack cutsLane = this.scene.cameraSwitchTrack();
            return cutsLane != null && this.layout.trackAt(this.rowAt(this.mouseY)) == cutsLane;
        }
    }

    /** One camera's span in the switch lane. */
    private record SwitchSegment(int fromTick, int toTick, EditorCamera camera, int cameraIndex) {}

    /** A cut: the tick at which the output camera changes. */
    private record SwitchCut(int tick, EditorCamera camera, int cameraIndex) {}

    // -- Drag ------------------------------------------------------------------------------------

    /** The one gesture in progress. */
    private sealed interface Drag {
        /** The mouse button this gesture follows until it is released. */
        default int button() {
            return ImGuiMouseButton.Left;
        }

        /** Scrubbing the playback head. */
        final class Head implements Drag {}

        /** Moving keyframes: one grabbed keyframe carries the whole selection. */
        final class Keys implements Drag {
            final KeyframeTrack track;
            final int tick;
            final float anchorX, anchorY;
            boolean moved;

            Keys(KeyframeTrack track, int tick, float anchorX, float anchorY) {
                this.track = track;
                this.tick = tick;
                this.anchorX = anchorX;
                this.anchorY = anchorY;
            }
        }

        /** Reordering a row. */
        final class Row implements Drag {
            final int rowIndex;
            int slot = -1;

            Row(int rowIndex) {
                this.rowIndex = rowIndex;
            }
        }

        /** Dragging one end of the export range. */
        final class ExportEdge implements Drag {
            final boolean start;

            ExportEdge(boolean start) {
                this.start = start;
            }
        }

        /** Moving or resizing the zoom bar. */
        final class Zoom implements Drag {
            enum Part { MOVE, LEFT, RIGHT }

            final Part part;
            final int button;
            final float anchorX;
            final double minBefore, maxBefore;

            Zoom(Part part, int button, float anchorX, double minBefore, double maxBefore) {
                this.part = part;
                this.button = button;
                this.anchorX = anchorX;
                this.minBefore = minBefore;
                this.maxBefore = maxBefore;
            }

            @Override
            public int button() {
                return this.button;
            }
        }

        /** Resizing the row list. */
        final class Splitter implements Drag {}

        /** Rubber-band selecting keyframes. */
        final class Marquee implements Drag {
            final float anchorX, anchorY;
            final TimelineSelection before = new TimelineSelection();
            final boolean additive;

            Marquee(float anchorX, float anchorY, boolean additive) {
                this.anchorX = anchorX;
                this.anchorY = anchorY;
                this.additive = additive;
            }
        }
    }

    /** What a row asked for, carried out once every row has been drawn. */
    private sealed interface RowAction {
        record DeleteTrack(KeyframeTrack track) implements RowAction {}
        record ClearTrack(KeyframeTrack track) implements RowAction {}
        record DeleteCamera(EditorCamera camera) implements RowAction {}
        record RenameCamera(EditorCamera camera) implements RowAction {}
        record AddTrack(EditorCamera camera, KeyframeType<?> type) implements RowAction {}
        record ApplyKeyframe(KeyframeTrack track, int tick, Keyframe keyframe) implements RowAction {}
        record MoveCamera(EditorCamera camera, int delta) implements RowAction {}
        record CreateKeyframe(KeyframeTrack track, int tick) implements RowAction {}
    }

    // -- Entry point -----------------------------------------------------------------------------

    public static void render() {
        ReplayServer replayServer = Flashback.getReplayServer();
        if (replayServer == null) {
            return;
        }

        FlashbackMeta metadata = replayServer.getMetadata();
        editorState = EditorStateManager.get(metadata.replayIdentifier);

        String title = I18n.get("flashback.timeline_window", ticksToTimestamp(cursorTicks), cursorTicks);
        ImGuiHelper.pushStyleVar(ImGuiStyleVar.WindowPadding, 0, 0);
        int flags = ImGuiWindowFlags.NoScrollWithMouse | ImGuiWindowFlags.NoScrollbar;
        if (windowHovered) {
            flags |= ImGuiWindowFlags.NoMove;
        }
        boolean visible = ImGui.begin(title + "###Timeline", flags);
        ImGuiHelper.popStyleVar();

        if (visible) {
            sceneStamp = editorState.acquireRead();
            sceneStampIsWrite = false;
            try {
                scene = editorState.getCurrentScene(sceneStamp);
                Frame frame = buildFrame(replayServer, metadata);
                if (frame != null) {
                    windowHovered = frame.mouseX >= frame.x && frame.mouseX < frame.x + frame.width
                        && frame.mouseY >= frame.y && frame.mouseY < frame.y + frame.height;
                    SELECTION.prune(scene);
                    handleScroll(frame);
                    handleKeyPresses(frame);
                    handleMouse(frame);
                    draw(frame);
                }
            } finally {
                editorState.release(sceneStamp);
                sceneStamp = 0L;
                sceneStampIsWrite = false;
                scene = null;
            }
        }
        ImGui.end();
    }

    // -- Frame construction ----------------------------------------------------------------------

    @Nullable
    private static Frame buildFrame(ReplayServer replayServer, FlashbackMeta metadata) {
        float minX = ImGui.getWindowContentRegionMinX();
        float minY = ImGui.getWindowContentRegionMinY();
        float width = ImGui.getWindowContentRegionMaxX() - minX;
        float height = ImGui.getWindowContentRegionMaxY() - minY;
        if (width < 1 || height < 1) {
            return null;
        }
        float x = ImGui.getWindowPosX() + minX;
        float y = ImGui.getWindowPosY() + minY;

        float uiScale = Math.max(1, ReplayUI.scaleUi(1));
        float minorSeparatorHeight = ReplayUI.scaleUi(10);
        float majorSeparatorHeight = minorSeparatorHeight * 2;
        float timestampHeight = ReplayUI.scaleUi(20);
        float rulerHeight = timestampHeight + majorSeparatorHeight;
        float rowHeight = Math.max(ImGui.getTextLineHeight() + ReplayUI.scaleUi(7), ReplayUI.scaleUi(23));
        float sectionHeight = Math.max(ImGui.getTextLineHeight(), ReplayUI.scaleUi(17));
        float cutsLaneHeight = Math.max(rowHeight + ReplayUI.scaleUi(9), ReplayUI.scaleUi(32));
        float keyframeSize = ReplayUI.scaleUi(9);
        float buttonSize = ImGui.getTextLineHeight();
        float indentWidth = ReplayUI.scaleUi(14);

        // The row list is as wide as the user last made it, within limits that keep the timeline
        // usable on any window: never less than a readable column, never more than most of the view.
        float leftWidth = TimelineLayout.panelWidth(editorState.timelinePanelWidth, width, uiScale,
            ReplayUI.scaleUi(140));

        TimelineLayout layout = TimelineLayout.build(scene, new TimelineLayout.Metrics(
            rowHeight, sectionHeight, cutsLaneHeight, ReplayUI.scaleUi(6)));
        float scrollbar = ImGui.getStyle().getScrollbarSize() - 1;
        boolean showScrollbar = layout.bottom(0) + rulerHeight > height;
        if (showScrollbar) {
            width -= scrollbar;
        }

        float mouseX = ReplayUI.getIO().getMousePosX();
        float mouseY = ReplayUI.getIO().getMousePosY();

        int currentReplayTick = replayServer.getReplayTick();
        int totalTicks = replayServer.getTotalReplayTicks();

        // -- Tick scale: how many ticks a separator covers at the current zoom --
        float timelineWidth = width - leftWidth;
        float shownTicks = Math.max(1, Math.round((editorState.zoomMax - editorState.zoomMin) * totalTicks));

        int targetMajorWidth;
        if (currentReplayTick + (int) shownTicks > 20 * 60 * 60) {
            int hours = (currentReplayTick + (int) shownTicks) / (20 * 60 * 60);
            targetMajorWidth = (int) ImGuiHelper.calcTextWidth("9".repeat((int) Math.log10(hours) + 1) + ":99:99") + 20;
        } else {
            targetMajorWidth = (int) ImGuiHelper.calcTextWidth("99:99") + 20;
        }

        float targetTicksPerMajor = 1f / (timelineWidth / shownTicks / targetMajorWidth);
        int minorsPerMajor;
        int ticksPerMinor;
        boolean showSubSeconds;
        if (targetTicksPerMajor < 5) {
            minorsPerMajor = 5;
            ticksPerMinor = 1;
            showSubSeconds = true;
        } else if (targetTicksPerMajor < 8) {
            minorsPerMajor = 5;
            ticksPerMinor = 2;
            showSubSeconds = true;
        } else {
            minorsPerMajor = 4;
            ticksPerMinor = (int) Math.ceil(targetTicksPerMajor / 20) * 20 / minorsPerMajor;
            showSubSeconds = false;
        }

        int majorSnap = Math.max(1, ticksPerMinor * minorsPerMajor);
        int minTicks = (int) Math.round(editorState.zoomMin * totalTicks / majorSnap) * majorSnap;
        float minorSeparatorWidth = (timelineWidth / shownTicks) * ticksPerMinor;
        float availableTicks = timelineWidth / minorSeparatorWidth * ticksPerMinor;
        double errorTicks = editorState.zoomMin * totalTicks - minTicks;
        int errorOffset = (int) (-errorTicks / ticksPerMinor * minorSeparatorWidth);
        float timelineLeftOffset = leftWidth + errorOffset;

        TickScale scale = new TickScale(x, timelineLeftOffset, timelineWidth, availableTicks, minTicks, totalTicks);

        // -- Cursor: the replay tick, unless it is being scrubbed or stepped --
        cursorTicks = currentReplayTick;
        if (drag instanceof Drag.Head) {
            cursorTicks = scale.tickAtX(mouseX);
        } else if (replayServer.jumpToTick >= 0) {
            cursorTicks = replayServer.jumpToTick;
        } else if (pendingStepBackwardsTicks > 0) {
            cursorTicks = Math.max(0, cursorTicks - pendingStepBackwardsTicks);
        }

        // -- Transport controls --
        float controlSize = ReplayUI.scaleUi(24);
        float controlsY = y + rulerHeight / 2 - controlSize / 2;
        float skipBackwardsX = x + leftWidth / 6 - controlSize / 2;
        float slowDownX = x + leftWidth * 2 / 6 - controlSize / 2;
        float pauseX = x + leftWidth / 2 - controlSize / 2;
        float fastForwardsX = x + leftWidth * 4 / 6 - controlSize / 2;
        float skipForwardsX = x + leftWidth * 5 / 6 - controlSize / 2;

        // -- Zoom bar --
        float zoomBarWidth = width - (leftWidth + 1);
        float zoomBarMin = x + leftWidth + 1 + (float) (editorState.zoomMin * zoomBarWidth);
        float zoomBarMax = x + leftWidth + 1 + (float) (editorState.zoomMax * zoomBarWidth);
        float zoomBarHeight = 6;
        boolean zoomBarExpanded = false;
        if (mouseY >= y + height - zoomBarHeight * 2 && mouseY <= y + height || drag instanceof Drag.Zoom) {
            zoomBarHeight *= 2;
            zoomBarExpanded = true;
        }

        float contentY = y + rulerHeight - ImGui.getScrollY();

        // -- The switch lane's spans: computed once so drawing and hit-testing share them --
        List<SwitchSegment> segments = new ArrayList<>();
        List<SwitchCut> cuts = new ArrayList<>();
        KeyframeTrack switchTrack = scene.cameraSwitchTrack();
        boolean switchLaneEnabled = switchTrack != null && switchTrack.enabled;
        // While a cut is being dragged it is drawn at the tick it would land on, so the band follows
        // the pointer instead of snapping into place only on release.
        Drag.Keys draggedKeys = drag instanceof Drag.Keys keys && KeyframeTrack.isCameraSwitch(keys.track) ? keys : null;
        int draggedCutTick = draggedKeys == null ? -1
            : Math.max(0, Math.min(totalTicks, draggedKeys.tick + snappedDelta(scale, mouseX, draggedKeys.tick, draggedKeys.track)));
        if (switchTrack != null) {
            for (Map.Entry<Integer, Keyframe> entry : switchTrack.keyframesByTick.entrySet()) {
                if (entry.getValue() instanceof CameraSwitchKeyframe cut) {
                    EditorCamera camera = scene.resolveCamera(cut.cameraId);
                    if (camera != null) {
                        int cutTick = draggedKeys != null && entry.getKey() == draggedKeys.tick ? draggedCutTick : entry.getKey();
                        cuts.add(new SwitchCut(cutTick, camera, scene.cameraIndexOf(camera)));
                    }
                }
            }
            if (switchLaneEnabled && !scene.cameras.isEmpty()) {
                int from = 0;
                for (SwitchCut cut : cuts) {
                    if (cut.tick() > from) {
                        EditorCamera camera = scene.resolveCameraAt(from);
                        if (camera != null) {
                            segments.add(new SwitchSegment(from, cut.tick(), camera, scene.cameraIndexOf(camera)));
                        }
                    }
                    from = Math.max(from, cut.tick());
                }
                if (from < totalTicks) {
                    EditorCamera camera = cuts.isEmpty() ? scene.cameras.get(0) : cuts.get(cuts.size() - 1).camera();
                    segments.add(new SwitchSegment(from, totalTicks, camera, scene.cameraIndexOf(camera)));
                }
            }
        }

        return new Frame(replayServer, metadata, editorState, scene, layout, x, y, width, height, leftWidth,
            rulerHeight, minorSeparatorHeight, timestampHeight, uiScale, keyframeSize, rowHeight,
            sectionHeight, cutsLaneHeight,
            buttonSize, indentWidth, contentY, showScrollbar, mouseX, mouseY, scale, cursorTicks, currentReplayTick,
            totalTicks, minTicks, availableTicks, timelineWidth, timelineLeftOffset, minorsPerMajor, ticksPerMinor,
            showSubSeconds, minorSeparatorWidth, errorOffset, zoomBarHeight, zoomBarMin, zoomBarMax, zoomBarExpanded,
            controlSize, controlsY, skipBackwardsX, slowDownX, pauseX, fastForwardsX, skipForwardsX,
            segments, cuts, switchLaneEnabled);
    }

    // -- Drawing ---------------------------------------------------------------------------------

    private static void draw(Frame f) {
        ImDrawList drawList = ImGui.getWindowDrawList();

        drawList.addLine(f.timelineLeft(), f.y + f.timestampHeight, f.timelineLeft(), f.y + f.height, 0x40FFFFFF);
        drawList.addLine(f.x, f.y + f.rulerHeight, f.x + f.width - 2, f.y + f.rulerHeight, 0x40FFFFFF);

        ImGui.dummy(0, f.rulerHeight - 1);

        drawScrollableArea(f);
        drawRuler(f);
        drawZoomBar(f);
        drawTransport(f);
        drawPopups(f);
    }

    private static void drawScrollableArea(Frame f) {
        int flags = ImGuiWindowFlags.NoScrollWithMouse;
        flags |= f.showScrollbar ? ImGuiWindowFlags.AlwaysVerticalScrollbar : ImGuiWindowFlags.NoScrollbar;

        ImGui.beginChild("##TimelineRows", 0, 0, false, flags);
        try {
            ImDrawList drawList = ImGui.getWindowDrawList();
            drawLeftPanel(f, drawList);

            // Drawn before the canvas clip so the marker is visible in both panels.
            drawInsertionIndicator(f, drawList);
            drawEmptyState(f, drawList);

            drawList.pushClipRect(f.timelineLeft() + 1, f.y + f.rulerHeight, f.timelineRight(), f.y + f.height, true);
            drawSwitchBand(f, drawList);
            drawKeyframeRows(f, drawList);
            drawMarquee(f, drawList);
            drawList.popClipRect();
        } finally {
            ImGui.endChild();
        }
    }

    /** A hint in the canvas when there is nothing on the timeline yet. */
    private static void drawEmptyState(Frame f, ImDrawList drawList) {
        if (f.layout.size() > 0) {
            return;
        }
        String hint = I18n.get("flashback.timeline.empty");
        float textWidth = ImGuiHelper.calcTextWidth(hint);
        drawList.addText(f.timelineLeft() + (f.timelineRight() - f.timelineLeft() - textWidth) / 2,
            f.y + f.rulerHeight + f.rowHeight * 2, 0x60FFFFFF, hint);
    }

    // -- Left panel ------------------------------------------------------------------------------

    /** The right-hand edge of the row buttons inside the panel: keeps a margin off the divider. */
    private static final float ROW_BUTTON_MARGIN = 8f;

    private static void drawLeftPanel(Frame f, ImDrawList drawList) {
        int hoveredRow = f.mouseInLeft && f.mouseInRows ? f.rowAt(f.mouseY) : -1;
        RowAction action = null;

        for (int rowIndex = 0; rowIndex < f.layout.size(); rowIndex++) {
            TimelineRow row = f.layout.row(rowIndex);
            float bottom = f.rowBottom(rowIndex);
            boolean hovered = rowIndex == hoveredRow;

            drawRowBackground(f, drawList, rowIndex, row, hovered);

            ImGui.pushID(rowIndex);
            RowAction rowAction = switch (row) {
                case TimelineRow.Section section -> drawSectionRow(f, rowIndex, section);
                case TimelineRow.CameraGroup camera -> drawCameraRow(f, rowIndex, camera);
                case TimelineRow.Track track -> drawTrackRow(f, rowIndex, track);
            };
            if (rowAction != null) {
                action = rowAction;
            }

            // A right-click anywhere on an interactive row opens that row's menu, so the menu is
            // always available rather than only on one small control.
            if (hovered && row.isInteractive() && ImGui.isMouseClicked(ImGuiMouseButton.Right)) {
                ImGui.openPopup("##RowMenu");
            }
            if (ImGuiHelper.beginPopup("##RowMenu")) {
                RowAction menuAction = drawRowMenu(row);
                if (menuAction != null) {
                    action = menuAction;
                }
                ImGui.endPopup();
            }

            ImGui.popID();

            // A hairline between rows, inset so it reads as a separator rather than a border.
            drawList.addLine(f.x + 8, bottom - 1, f.x + f.leftWidth - 8, bottom - 1, TimelineColours.ROW_DIVIDER);
        }

        applyEnablePaint(f);
        applyRowAction(f, action);
        drawSplitterHandle(f, drawList);
        drawFooter(f);
    }

    private static void drawRowBackground(Frame f, ImDrawList drawList, int rowIndex, TimelineRow row, boolean hovered) {
        float top = f.rowTop(rowIndex);
        float bottom = f.rowBottom(rowIndex);
        float left = f.x;
        float right = f.x + f.width;

        if (row instanceof TimelineRow.Section) {
            return;
        }

        if (row instanceof TimelineRow.CameraGroup group) {
            int accent = TimelineColours.cameraAccent(f.scene.cameraIndexOf(group.camera()));
            boolean live = f.cutsLaneEnabled && group.camera() == f.scene.resolveCameraAt(f.cursorTicks);
            drawList.addRectFilled(left, top, right, bottom, TimelineColours.alpha(accent, live ? 0x2E : 0x16));
            // A solid strip in the camera's colour is the camera's identity for the whole row.
            drawList.addRectFilled(left, top, left + 3, bottom, accent);
            if (hovered) {
                drawList.addRectFilled(left, top, right, bottom, TimelineColours.ROW_HOVER);
            }
            return;
        }

        if (row instanceof TimelineRow.Track trackRow) {
            if (KeyframeTrack.isCameraSwitch(trackRow.track())) {
                // The programme lane is a different kind of thing, so it gets a different surface.
                drawList.addRectFilled(left, top, right, bottom, TimelineColours.CUTS_LANE_BACKGROUND);
            } else if (trackRow.owner() != null) {
                int accent = TimelineColours.cameraAccent(f.scene.cameraIndexOf(trackRow.owner()));
                drawList.addRectFilled(left, top, right, bottom, TimelineColours.alpha(accent, 0x0C));
            }
            if (trackRow.track().customColour != 0) {
                drawList.addRectFilled(left, top, left + 3, bottom, trackRow.track().customColour);
            }
            if (hovered) {
                drawList.addRectFilled(left, top, right, bottom, TimelineColours.ROW_HOVER);
            }
            if (SELECTION.countOf(trackRow.track()) > 0) {
                // A marker on the divider, so the lane being edited is findable from the canvas.
                drawList.addRectFilled(f.x + f.leftWidth - 3, top, f.x + f.leftWidth, bottom,
                    TimelineColours.SELECTED);
            }
        }
    }

    @Nullable
    private static RowAction drawSectionRow(Frame f, int rowIndex, TimelineRow.Section section) {
        String label = switch (section.kind()) {
            case CAMERAS -> I18n.get("flashback.timeline.section_cameras");
            case SCENE -> I18n.get("flashback.timeline.section_scene");
        };
        float top = f.rowTop(rowIndex);
        float height = f.layout.rowHeight(rowIndex);
        ImGui.setCursorScreenPos(f.x + 10, top + height / 2 - ImGui.getTextLineHeight() / 2f);
        ImGui.textColored(TimelineColours.SECTION_TEXT, label);
        return null;
    }

    @Nullable
    private static RowAction drawCameraRow(Frame f, int rowIndex, TimelineRow.CameraGroup group) {
        EditorCamera camera = group.camera();
        String name = f.scene.displayNameOf(camera);
        int accent = TimelineColours.cameraAccent(f.scene.cameraIndexOf(camera));
        float top = f.rowTop(rowIndex);
        float bottom = f.rowBottom(rowIndex);
        float buttonY = top + f.layout.rowHeight(rowIndex) / 2 - f.buttonSize / 2;
        RowAction action = null;

        float cursorX = f.x + 8;
        ImGui.setCursorScreenPos(cursorX, buttonY);
        dragHandle(f, rowIndex);
        cursorX += f.buttonSize + 2;

        // The chevron folds the camera's tracks away, which is how a long list stays manageable.
        ImGui.setCursorScreenPos(cursorX, buttonY);
        ImGui.invisibleButton("##collapse", f.buttonSize, f.buttonSize);
        boolean collapseClicked = ImGui.isItemClicked(ImGuiMouseButton.Left);
        ImGui.getWindowDrawList().addText(ImGui.getItemRectMinX() + 3, ImGui.getItemRectMinY(), 0xFFDDDDDD,
            camera.collapsed ? "\ue315" : "\ue313");
        ImGuiHelper.tooltip(I18n.get(camera.collapsed ? "flashback.timeline.expand" : "flashback.timeline.collapse"));
        cursorX += f.buttonSize;

        // The name is clipped so a long one cannot run underneath the buttons.
        float buttonsX = f.x + f.leftWidth - ROW_BUTTON_MARGIN - rowButtonsWidth(f, 1);
        float nameRight = buttonsX - 6;
        String kindIcon = camera.kind == EditorCamera.Kind.SPECTATE ? "\ue7fd " : "\ue04b ";
        ImGui.setCursorScreenPos(cursorX, top + f.layout.rowHeight(rowIndex) / 2 - ImGui.getTextLineHeight() / 2f);
        ImDrawList drawList = ImGui.getWindowDrawList();
        drawList.pushClipRect(cursorX - 2, top, Math.max(cursorX, nameRight), bottom, true);
        ImGui.textColored(accent, kindIcon + name);
        drawList.popClipRect();
        if (ImGui.isItemClicked(ImGuiMouseButton.Left) && ImGui.isMouseDoubleClicked(ImGuiMouseButton.Left)) {
            action = new RowAction.RenameCamera(camera);
        }
        ImGuiHelper.tooltip(I18n.get("flashback.camera_kind." + camera.kind.name().toLowerCase(Locale.ROOT)));

        if (collapseClicked) {
            camera.collapsed = !camera.collapsed;
            if (camera.collapsed) {
                SELECTION.clear();
                inspectorOpen = false;
            }
            editorState.markDirty();
        }

        ImGui.setCursorScreenPos(buttonsX, buttonY);
        if (ImGui.invisibleButton("##cameraMenu", f.buttonSize, f.buttonSize)) {
            ImGui.openPopup("##CameraMenu");
        }
        drawList.addText(ImGui.getItemRectMinX() + 3, ImGui.getItemRectMinY(), TimelineColours.TEXT_DIM, "\ue5d2");
        ImGuiHelper.tooltip(I18n.get("flashback.open_camera_options"));

        if (ImGuiHelper.beginPopup("##CameraMenu")) {
            int cameraIndex = f.scene.cameraIndexOf(camera);
            ImGui.textDisabled(f.scene.displayNameOf(camera));
            ImGui.separator();
            if (ImGui.menuItem("\ue5d8 " + I18n.get("flashback.timeline.move_up") + "##cameraUp",
                    null, false, cameraIndex > 0)) {
                action = new RowAction.MoveCamera(camera, -1);
            }
            if (ImGui.menuItem("\ue5db " + I18n.get("flashback.timeline.move_down") + "##cameraDown",
                    null, false, cameraIndex >= 0 && cameraIndex < f.scene.cameras.size() - 1)) {
                action = new RowAction.MoveCamera(camera, 1);
            }
            ImGui.separator();
            for (KeyframeType<?> type : camera.kind.trackTypes()) {
                if (!f.scene.hasTrackOfType(camera, type)
                        && ImGui.menuItem("\ue148 " + I18n.get("flashback.add_named_track", type.name()) + "##addCameraTrack")) {
                    action = new RowAction.AddTrack(camera, type);
                }
            }
            ImGui.separator();
            if (ImGui.menuItem("\ue3c9 " + I18n.get("flashback.rename") + "##renameCamera")) {
                action = new RowAction.RenameCamera(camera);
            }
            if (ImGui.menuItem("\ue872 " + I18n.get("flashback.delete_camera") + "##deleteCamera")) {
                action = new RowAction.DeleteCamera(camera);
            }
            ImGui.endPopup();
        }

        return action;
    }

    @Nullable
    private static RowAction drawTrackRow(Frame f, int rowIndex, TimelineRow.Track row) {
        KeyframeTrack track = row.track();
        boolean cutsLane = KeyframeTrack.isCameraSwitch(track);
        float top = f.rowTop(rowIndex);
        float bottom = f.rowBottom(rowIndex);
        float height = f.layout.rowHeight(rowIndex);
        float buttonY = top + height / 2 - f.buttonSize / 2;
        RowAction action = null;

        float cursorX = f.x + 8;
        if (cutsLane) {
            // The cut lane is not reorderable and owns nothing, so it has no grip and no indent.
            cursorX += 2;
        } else {
            ImGui.setCursorScreenPos(cursorX, buttonY);
            dragHandle(f, rowIndex);
            cursorX += f.buttonSize + 2;
            if (row.isCameraChild()) {
                cursorX += f.indentWidth;
            }
        }

        String icon = track.keyframeType.icon();
        String name = track.customName != null ? track.customName : displayName(track);

        if (track.nameEditField != null) {
            ImGui.setCursorScreenPos(cursorX, top + height / 2 - ImGui.getTextLineHeight() / 2f);
            ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 0, 0);
            if (icon != null) {
                ImGui.textUnformatted(icon);
                ImGui.sameLine(0, 3);
            }
            ImGui.setNextItemWidth(140);
            if (track.forceFocusTrack) {
                track.forceFocusTrack = false;
                ImGui.setKeyboardFocusHere();
            }
            boolean entered = ImGui.inputText("##TrackName", track.nameEditField,
                ImGuiInputTextFlags.EnterReturnsTrue | ImGuiInputTextFlags.AutoSelectAll);
            if (entered || ImGui.isItemDeactivated()) {
                String enteredName = ImGuiHelper.getString(track.nameEditField).trim();
                track.customName = enteredName.isEmpty() || enteredName.equals(displayName(track)) ? null : enteredName;
                track.nameEditField = null;
                editorState.markDirty();
            }
            ImGui.popStyleVar();
        } else {
            float nameRight = f.x + f.leftWidth - ROW_BUTTON_MARGIN - rowButtonsWidth(f, cutsLane ? 2 : 3) - 6;
            String label = (icon != null ? icon + " " : "") + name;
            ImGui.setCursorScreenPos(cursorX, top + height / 2 - ImGui.getTextLineHeight() / 2f);
            ImDrawList drawList = ImGui.getWindowDrawList();
            drawList.pushClipRect(cursorX - 2, top, Math.max(cursorX, nameRight), bottom, true);
            if (!track.enabled) {
                ImGui.textDisabled(label);
            } else if (track.customColour != 0) {
                ImGui.textColored(track.customColour, label);
            } else {
                ImGui.textUnformatted(label);
            }
            drawList.popClipRect();
            if (ImGui.isItemClicked(ImGuiMouseButton.Left) && ImGui.isMouseDoubleClicked(ImGuiMouseButton.Left)) {
                track.nameEditField = ImGuiHelper.createResizableImString(name);
                track.forceFocusTrack = true;
            }
            ImGuiHelper.tooltip(I18n.get(cutsLane ? "flashback.timeline.switch_hint" : "flashback.timeline.rename_hint"));
        }

        float buttonsX = f.x + f.leftWidth - ROW_BUTTON_MARGIN - rowButtonsWidth(f, cutsLane ? 2 : 3);
        ImGui.setCursorScreenPos(buttonsX, buttonY);

        // One control means one thing everywhere: this inserts a keyframe, at the playhead, on this
        // lane - and it is drawn as a keyframe so it matches the thing it makes.
        if (!cutsLane) {
            if (ImGui.invisibleButton("##insertKey", f.buttonSize, f.buttonSize)) {
                action = new RowAction.CreateKeyframe(track, f.cursorTicks);
            }
            drawKeyframeButton(ImGui.getWindowDrawList(), ImGui.getItemRectMinX(), ImGui.getItemRectMinY(),
                f.buttonSize, track.enabled ? TimelineColours.TEXT : TimelineColours.TEXT_DIM);
            ImGuiHelper.tooltip(I18n.get("flashback.timeline.insert_keyframe_at",
                ticksToTimestamp(f.cursorTicks), f.cursorTicks));
            ImGui.sameLine();
        }

        if (ImGui.invisibleButton("##enable", f.buttonSize, f.buttonSize)) {
            track.enabled = !track.enabled;
            editorState.markDirty();
            enablePaintActive = true;
            enablePaintValue = track.enabled;
        }
        ImGui.getWindowDrawList().addText(ImGui.getItemRectMinX() + 3, ImGui.getItemRectMinY(),
            track.enabled ? TimelineColours.TEXT : TimelineColours.TEXT_DIM,
            track.enabled ? "\ue8f4" : "\ue8f5");
        ImGuiHelper.tooltip(I18n.get(track.enabled ? "flashback.disable_keyframe_track" : "flashback.enable_keyframe_track"));
        ImGui.sameLine();

        if (ImGui.invisibleButton("##trackMenu", f.buttonSize, f.buttonSize)) {
            ImGui.openPopup("##RowMenu");
        }
        ImGui.getWindowDrawList().addText(ImGui.getItemRectMinX() + 3, ImGui.getItemRectMinY(), TimelineColours.TEXT_DIM, "\ue5d2");
        ImGuiHelper.tooltip(I18n.get("flashback.open_track_options"));

        return drawTypePopup(track, action);
    }

    /**
     * A small square of camera colour, drawn rather than text so it needs no glyph in the font and
     * stays crisp at any GUI scale.
     */
    private static void drawColourChip(int colour) {
        float size = Math.max(6f, ImGui.getTextLineHeight() * 0.55f);
        ImGui.sameLine();
        float startX = ImGui.getCursorScreenPosX() + ImGui.getStyle().getItemSpacingX();
        ImGui.dummy(size, size);
        float x = Math.max(startX, ImGui.getItemRectMinX());
        float y = ImGui.getItemRectMinY() + (ImGui.getTextLineHeight() - size) / 2f;
        ImGui.getWindowDrawList().addRectFilled(x, y, x + size, y + size, colour, 2f);
    }

    /** Draws the insert-keyframe affordance as a small diamond, matching the canvas. */
    private static void drawKeyframeButton(ImDrawList drawList, float x, float y, float size, int colour) {
        float cx = x + size / 2;
        float cy = y + size / 2;
        float r = Math.max(4f, size * 0.30f);
        drawList.addQuadFilled(cx, cy - r, cx + r, cy, cx, cy + r, cx - r, cy, colour);
    }

    /** The width of {@code count} row buttons, including the spacing between them. */
    private static float rowButtonsWidth(Frame f, int count) {
        float spacing = ImGui.getStyle().getItemSpacingX();
        return count * f.buttonSize + (count - 1) * spacing;
    }



    /**
     * Draws the small per-type form a lane needs before it can create its keyframe - a spectate
     * target, an orbit, a timelapse length.
     *
     * <p>The popup is opened and begun inside this row's ID scope, in the same frame, so it belongs
     * to the lane that asked for it. Its answer is applied directly: re-entering the create path
     * would open the form again instead of making a keyframe.
     */
    @Nullable
    private static RowAction drawTypePopup(KeyframeTrack track, @Nullable RowAction existing) {
        if (pendingTypePopup == null || pendingTypePopupTrack != track) {
            return existing;
        }
        KeyframeType.KeyframeCreatePopup<?> popup = pendingTypePopup;
        int tick = pendingTypePopupTick;

        if (openTypePopupNow) {
            ImGui.openPopup("##CreateKeyframe");
            openTypePopupNow = false;
        }

        Keyframe created = null;
        if (ImGuiHelper.beginPopup("##CreateKeyframe")) {
            created = popup.render();
            if (created != null) {
                pendingTypePopup = null;
                pendingTypePopupTrack = null;
                ImGui.closeCurrentPopup();
            }
            // Always paired: returning while the popup is open would leave its ID on ImGui's stack
            // and corrupt every later push/pop in the frame.
            ImGui.endPopup();
        } else {
            pendingTypePopup = null;
            pendingTypePopupTrack = null;
        }
        return created == null ? existing : new RowAction.ApplyKeyframe(track, tick, created);
    }

    /** Starts a row drag when the grip is grabbed. */
    private static void dragHandle(Frame f, int rowIndex) {
        ImGui.invisibleButton("##handle", f.buttonSize, f.buttonSize);
        boolean hovered = ImGui.isItemHovered();
        boolean active = drag instanceof Drag.Row row && row.rowIndex == rowIndex;
        ImGui.getWindowDrawList().addText(ImGui.getItemRectMinX() + 2, ImGui.getItemRectMinY(),
            hovered || active ? 0xFFDDDDDD : 0x60FFFFFF, "\ue945");
        if (hovered || active) {
            ImGui.setMouseCursor(ImGuiMouseCursor.Hand);
            ImGuiHelper.tooltip(I18n.get("flashback.timeline.drag_to_reorder"));
        }
        if (ImGui.isItemActivated()) {
            drag = new Drag.Row(rowIndex);
        }
    }

    private static String displayName(KeyframeTrack track) {
        if (KeyframeTrack.isCameraSwitch(track)) {
            return I18n.get("flashback.timeline.camera_cuts");
        }
        return track.keyframeType.name();
    }

    @Nullable
    private static RowAction drawRowMenu(TimelineRow row) {
        if (row instanceof TimelineRow.CameraGroup group) {
            if (ImGui.menuItem("\ue3c9 " + I18n.get("flashback.rename") + "##rowRename")) {
                return new RowAction.RenameCamera(group.camera());
            }
            return null;
        }
        if (!(row instanceof TimelineRow.Track trackRow)) {
            return null;
        }

        KeyframeTrack track = trackRow.track();
        RowAction action = null;
        if (ImGui.menuItem("\ue3c9 " + I18n.get("flashback.rename") + "##rowRename")) {
            track.nameEditField = ImGuiHelper.createResizableImString(
                track.customName != null ? track.customName : displayName(track));
            track.forceFocusTrack = true;
        }
        if (ImGui.menuItem("\ue40a " + I18n.get("flashback.set_colour") + "##rowColour")) {
            ImGui.openPopup("##TrackColour");
        }
        if (ImGui.menuItem("\ue14a " + I18n.get("flashback.clear_keyframes") + "##rowClear")) {
            action = new RowAction.ClearTrack(track);
        }
        if (!KeyframeTrack.isCameraSwitch(track)
                && ImGui.menuItem("\ue872 " + I18n.get("flashback.delete_track") + "##rowDelete")) {
            action = new RowAction.DeleteTrack(track);
        }

        if (ImGuiHelper.beginPopup("##TrackColour")) {
            if (ImGui.button(I18n.get("flashback.reset_to_default") + "##resetColour")) {
                track.customColour = 0;
                editorState.markDirty();
                ImGui.closeCurrentPopup();
            } else {
                int colour = track.customColour != 0 ? track.customColour : ImGui.getColorU32(ImGuiCol.Text);
                ImVec4 vec = new ImVec4();
                ImGui.colorConvertU32ToFloat4(vec, colour);
                float[] rgb = {vec.x, vec.y, vec.z};
                if (ImGui.colorPicker3(I18n.get("flashback.track_colour"), rgb)) {
                    track.customColour = ImGui.colorConvertFloat4ToU32(rgb[0], rgb[1], rgb[2], 1.0f);
                    editorState.markDirty();
                }
            }
            ImGui.endPopup();
        }
        return action;
    }

    private static void applyEnablePaint(Frame f) {
        if (!enablePaintActive) {
            return;
        }
        if (!ImGui.isMouseDown(ImGuiMouseButton.Left)) {
            enablePaintActive = false;
            return;
        }
        int rowIndex = f.mouseInLeft ? f.rowAt(f.mouseY) : -1;
        if (rowIndex < 0 || rowIndex >= f.layout.size()) {
            return;
        }
        KeyframeTrack track = f.layout.trackAt(rowIndex);
        if (track != null && track.enabled != enablePaintValue) {
            track.enabled = enablePaintValue;
            editorState.markDirty();
        }
    }

    private static void applyRowAction(Frame f, @Nullable RowAction action) {
        if (action == null) {
            return;
        }
        upgradeToWrite();
        switch (action) {
            case RowAction.DeleteTrack delete -> TimelineEdits.deleteTrack(scene, editorState, delete.track());
            case RowAction.ClearTrack clear -> TimelineEdits.clearTrack(scene, editorState, clear.track());
            case RowAction.DeleteCamera delete -> TimelineEdits.deleteCamera(scene, editorState, delete.camera());
            case RowAction.RenameCamera rename -> {
                pendingRenameCamera = rename.camera();
                cameraNameString = ImGuiHelper.createResizableImString(scene.displayNameOf(rename.camera()));
                openRenameCameraPopup = true;
            }
            case RowAction.AddTrack add -> TimelineEdits.addTrackToCamera(scene, editorState, add.camera(), add.type());
            case RowAction.ApplyKeyframe apply -> TimelineEdits.setKeyframe(scene, editorState, apply.track(), apply.tick(), apply.keyframe());
            case RowAction.MoveCamera move -> {
                int from = scene.cameraIndexOf(move.camera());
                int to = from + move.delta();
                if (from >= 0 && to >= 0 && to < scene.cameras.size()) {
                    List<EditorCamera> order = new ArrayList<>(scene.cameras);
                    order.remove(from);
                    order.add(to, move.camera());
                    TimelineEdits.reorderCamera(scene, editorState, order);
                }
            }
            case RowAction.CreateKeyframe create -> createKeyframe(create.track(), create.tick());
        }
    }

    private static void drawFooter(Frame f) {
        // Rows position themselves explicitly, so the cursor is not automatically past the last one.
        ImGui.setCursorScreenPos(f.x + 8, f.layout.bottom(f.contentY) + 2);
        if (ImGui.smallButton(I18n.get("flashback.add_element") + "##AddElement")) {
            ImGui.openPopup("##AddElement");
        }
        ImGui.sameLine();
        drawSceneSwitcher(f);

        if (ImGuiHelper.beginPopup("##AddElement")) {
            if (ImGui.menuItem("\ue04b " + I18n.get("flashback.new_camera") + "##addCamera")) {
                upgradeToWrite();
                TimelineEdits.addCamera(scene, editorState, EditorCamera.Kind.FREE, f.cursorTicks);
                ImGui.closeCurrentPopup();
            }
            if (ImGui.menuItem("\ue8f4 " + I18n.get("flashback.new_spectate_camera") + "##addSpectateCamera")) {
                upgradeToWrite();
                TimelineEdits.addCamera(scene, editorState, EditorCamera.Kind.SPECTATE, f.cursorTicks);
                ImGui.closeCurrentPopup();
            }
            ImGui.separator();
            for (KeyframeType<?> type : KeyframeRegistry.getTypes()) {
                if (!type.canBeCreatedNormally() || EditorScene.isCameraScoped(type)) {
                    // A camera-owned type belongs to a camera, so it is added from the camera's menu.
                    continue;
                }
                if (ImGui.selectable(type.name() + "##addSceneTrack")) {
                    upgradeToWrite();
                    int index = scene.keyframeTracks.size();
                    TimelineEdits.push(scene, editorState,
                        List.of(new EditorSceneHistoryAction.RemoveTrack(type, index)),
                        List.of(new EditorSceneHistoryAction.AddTrack(type, index)),
                        I18n.get("flashback.create_named_track", type.name()));
                    ImGui.closeCurrentPopup();
                }
            }
            ImGui.endPopup();
        }
    }

    private static void drawSceneSwitcher(Frame f) {
        List<EditorScene> scenes = editorState.getScenes(sceneStamp);

        ImGui.setNextItemWidth(Math.max(80, f.leftWidth - ImGui.getCursorPosX() - 16));
        ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, 4, 0);
        boolean openNew = false;
        boolean openRename = false;
        boolean openDelete = false;
        if (ImGui.beginCombo("##SceneSwitcher", scene.name, ImGuiComboFlags.HeightLargest)) {
            if (ImGui.menuItem("\ue148 " + I18n.get("flashback.new_scene"))) {
                openNew = true;
            }
            if (ImGui.menuItem("\ue3c9 " + I18n.get("flashback.rename"))) {
                openRename = true;
            }
            if (scenes.size() > 1 && ImGui.menuItem("\ue92b " + I18n.get("flashback.delete_forever"))) {
                openDelete = true;
            }
            ImGui.separator();
            for (int i = 0; i < scenes.size(); i++) {
                ImGui.pushID(i);
                boolean selected = i == editorState.getSceneIndex();
                if (ImGui.selectable(scenes.get(i).name, selected) && !selected) {
                    upgradeToWrite();
                    editorState.setSceneIndex(i, sceneStamp);
                }
                ImGui.popID();
            }
            ImGui.endCombo();
        }
        ImGui.popStyleVar();

        if (openNew) {
            sceneNameString = ImGuiHelper.createResizableImString(I18n.get("flashback.default_scene_name", scenes.size() + 1));
            ImGui.openPopup("##NewScene");
        } else if (openRename) {
            sceneNameString = ImGuiHelper.createResizableImString(scene.name);
            ImGui.openPopup("##RenameScene");
        } else if (openDelete) {
            ImGui.openPopup("##DeleteScene");
        }

        if (ImGuiHelper.beginPopup("##NewScene")) {
            ImGui.setKeyboardFocusHere();
            ImGui.inputText(I18n.get("flashback.name"), sceneNameString);
            if (ImGui.button(I18n.get("flashback.create")) || ReplayUI.consumeConfirm()) {
                String name = ImGuiHelper.getString(sceneNameString).trim();
                if (!name.isEmpty()) {
                    upgradeToWrite();
                    scenes.add(new EditorScene(name));
                    editorState.setSceneIndex(scenes.size() - 1, sceneStamp);
                    editorState.markDirty();
                    ImGui.closeCurrentPopup();
                }
            }
            ImGui.sameLine();
            if (ImGui.button(I18n.get("gui.cancel")) || ReplayUI.consumeCancel()) {
                ImGui.closeCurrentPopup();
            }
            ImGui.endPopup();
        }

        if (ImGuiHelper.beginPopup("##RenameScene")) {
            ImGui.setKeyboardFocusHere();
            ImGui.inputText(I18n.get("flashback.name"), sceneNameString);
            if (ImGui.button(I18n.get("flashback.rename")) || ReplayUI.consumeConfirm()) {
                String name = ImGuiHelper.getString(sceneNameString).trim();
                if (!name.isEmpty()) {
                    upgradeToWrite();
                    scene.name = name;
                    editorState.markDirty();
                    ImGui.closeCurrentPopup();
                }
            }
            ImGui.sameLine();
            if (ImGui.button(I18n.get("gui.cancel")) || ReplayUI.consumeCancel()) {
                ImGui.closeCurrentPopup();
            }
            ImGui.endPopup();
        }

        if (ImGuiHelper.beginPopup("##DeleteScene")) {
            if (scenes.size() > 1) {
                ImGui.textUnformatted(I18n.get("flashback.delete_scene_confirm1"));
                ImGui.textUnformatted(I18n.get("flashback.delete_scene_confirm2"));
                if (ImGui.button(I18n.get("flashback.delete_forever"))) {
                    upgradeToWrite();
                    int index = editorState.getSceneIndex();
                    scenes.remove(index);
                    if (index >= scenes.size()) {
                        editorState.setSceneIndex(scenes.size() - 1, sceneStamp);
                    }
                    editorState.markDirty();
                    ImGui.closeCurrentPopup();
                }
                ImGui.sameLine();
                if (ImGui.button(I18n.get("gui.cancel")) || ReplayUI.consumeCancel()) {
                    ImGui.closeCurrentPopup();
                }
            } else {
                ImGui.closeCurrentPopup();
            }
            ImGui.endPopup();
        }
    }

    /** The divider between the rows and the canvas doubles as the resize handle. */
    private static void drawSplitterHandle(Frame f, ImDrawList drawList) {
        boolean active = drag instanceof Drag.Splitter;
        boolean hovered = !active && Math.abs(f.mouseX - f.timelineLeft()) <= 3
            && f.mouseY > f.y + f.rulerHeight && f.mouseY < f.y + f.height;
        if (active || hovered) {
            drawList.addRectFilled(f.timelineLeft() - 1, f.y + f.rulerHeight, f.timelineLeft() + 1, f.y + f.height,
                active ? TimelineColours.TEXT : TimelineColours.TEXT_DIM);
            if (hovered) {
                ImGui.setMouseCursor(ImGuiMouseCursor.ResizeEW);
            }
        }
    }

    /** Starts a splitter drag when the divider is grabbed. @return true when the click was used */
    private static boolean handleSplitterClick(Frame f) {
        if (Math.abs(f.mouseX - f.timelineLeft()) > 3 || f.mouseY <= f.y + f.rulerHeight || f.mouseY >= f.y + f.height) {
            return false;
        }
        drag = new Drag.Splitter();
        return true;
    }

    // -- Canvas ----------------------------------------------------------------------------------

    /**
     * The cut lane: a band showing which camera is live when, with a flag at each cut.
     *
     * <p>This lane is where the camera is decided. Clicking the band where there is no cut opens the
     * camera menu for that moment, clicking a flag selects the cut, and dragging a flag retimes it -
     * one place, one set of gestures, rather than a button somewhere else that does the same job.
     */
    private static void drawSwitchBand(Frame f, ImDrawList drawList) {
        KeyframeTrack cutsLane = f.scene.cameraSwitchTrack();
        if (cutsLane == null) {
            return;
        }
        int rowIndex = f.layout.rowOfTrack(cutsLane);
        if (rowIndex < 0) {
            return;
        }
        float top = f.rowTop(rowIndex);
        float bottom = f.rowBottom(rowIndex);
        float midY = (top + bottom) / 2;
        float left = f.timelineLeft() + 1;
        float right = f.timelineRight();

        SwitchSegment hoveredSegment = f.mouseOverCutsLane() ? segmentAt(f, f.tickAtX(f.mouseX)) : null;
        int hoveredCut = f.mouseOverCutsLane() ? cutAt(f, cutsLane) : -1;

        for (SwitchSegment segment : f.switchSegments) {
            float from = Math.max(left, f.xOfTick(segment.fromTick()));
            float to = Math.min(right, f.xOfTick(segment.toTick()));
            if (to <= from) {
                continue;
            }
            int accent = TimelineColours.cameraAccent(segment.cameraIndex());
            boolean hovered = segment == hoveredSegment && hoveredCut < 0;
            int fill = TimelineColours.alpha(accent, hovered ? 0x66 : 0x3A);
            drawList.addRectFilled(from, top + 3, to, bottom - 3, fill);
            drawList.addRectFilled(from, top + 3, to, top + 6, TimelineColours.alpha(accent, 0xE0));
            if (hovered) {
                drawList.addRect(from, top + 3, to, bottom - 3, TimelineColours.alpha(accent, 0xFF));
            }

            String name = f.scene.displayNameOf(segment.camera());
            float textWidth = ImGuiHelper.calcTextWidth(name);
            if (to - from > textWidth + 12) {
                // With a single span the name moves left, leaving the right-hand end free for the
                // hint that teaches what the band does.
                boolean lonely = f.switchCuts.isEmpty() && f.switchSegments.size() == 1;
                float nameX = lonely ? from + 8 : from + (to - from - textWidth) / 2;
                drawList.pushClipRect(from + 4, top, to - 4, bottom, true);
                drawList.addText(nameX, midY - ImGui.getTextLineHeight() / 2f,
                    TimelineColours.textOn(accent), name);
                drawList.popClipRect();
            }
        }

        if (hoveredSegment != null && hoveredCut < 0) {
            ImGuiHelper.drawTooltip(I18n.get("flashback.timeline.segment_info",
                    f.scene.displayNameOf(hoveredSegment.camera()),
                    ticksToTimestamp(hoveredSegment.fromTick()), ticksToTimestamp(hoveredSegment.toTick()))
                + "\n" + I18n.get("flashback.timeline.segment_hint"));
        }

        // Cuts: a bright tick with a flag, so where the camera changes reads at a glance.
        for (SwitchCut cut : f.switchCuts) {
            float cutX = f.xOfTick(cut.tick());
            if (cutX < left - 10 || cutX > right + 10) {
                continue;
            }
            int accent = TimelineColours.cameraAccent(cut.cameraIndex());
            boolean selected = SELECTION.contains(cutsLane, cut.tick());
            float flagHeight = cut.tick() == hoveredCut ? 13 : 10;
            drawList.addLine(cutX, top + 2, cutX, bottom - 2, TimelineColours.alpha(accent, 0xFF), selected ? 3f : 2f);
            drawList.addTriangleFilled(cutX - 5, top + 2, cutX + 5, top + 2, cutX, top + 2 + flagHeight, accent);
            if (selected) {
                drawList.addRect(cutX - 7, top + 1, cutX + 7, bottom - 1, TimelineColours.SELECTED);
            }
        }

        if (hoveredCut >= 0) {
            EditorCamera camera = f.scene.resolveCameraAt(hoveredCut);
            ImGuiHelper.drawTooltip(I18n.get("flashback.timeline.cut_tooltip",
                    camera == null ? "" : f.scene.displayNameOf(camera), ticksToTimestamp(hoveredCut))
                + "\n" + I18n.get("flashback.timeline.cut_tooltip_hint"));
        }

        // Until the band has been used, it has to say what it is for: the band is the control.
        if (f.switchCuts.isEmpty() && hoveredCut < 0 && hoveredSegment == null) {
            String hint = I18n.get("flashback.timeline.segment_hint");
            float textWidth = ImGuiHelper.calcTextWidth(hint);
            float hintX = right - textWidth - ReplayUI.scaleUi(10);
            if (hintX > left + 8) {
                drawList.addText(hintX, midY - ImGui.getTextLineHeight() / 2f, TimelineColours.TEXT_DIM, hint);
            }
        }
    }

    /** The span of the band that contains a tick, or null in a gap. */
    @Nullable
    private static SwitchSegment segmentAt(Frame f, int tick) {
        for (SwitchSegment segment : f.switchSegments) {
            if (tick >= segment.fromTick() && tick < segment.toTick()) {
                return segment;
            }
        }
        return null;
    }

    private static void drawKeyframeRows(Frame f, ImDrawList drawList) {
        for (int rowIndex = 0; rowIndex < f.layout.size(); rowIndex++) {
            if (!(f.layout.row(rowIndex) instanceof TimelineRow.Track trackRow)) {
                continue;
            }
            KeyframeTrack track = trackRow.track();
            if (KeyframeTrack.isCameraSwitch(track)) {
                continue;
            }

            float midY = (f.rowTop(rowIndex) + f.rowBottom(rowIndex)) / 2;
            drawList.addLine(f.timelineLeft() + 1, midY, f.timelineRight(), midY,
                track.enabled ? TimelineColours.ROW_RAIL : TimelineColours.ROW_RAIL_DISABLED);

            TreeMap<Integer, Keyframe> keyframes = track.keyframesByTick;
            int minTick = track.keyframeType.cullKeyframesInTimelineToTheLeft() ? f.minTicks - 10 : Integer.MIN_VALUE;
            int maxTick = f.minTicks + (int) f.availableTicks + 10;

            for (int tick = minTick; tick <= maxTick; tick++) {
                Map.Entry<Integer, Keyframe> entry = keyframes.ceilingEntry(tick);
                if (entry == null || entry.getKey() > maxTick) {
                    break;
                }
                tick = entry.getKey();
                Keyframe keyframe = entry.getValue();

                boolean selected = SELECTION.contains(track, tick);
                int drawnTick = selected ? grabbedTickFor(f, tick) : tick;
                float midX = f.xOfTick(drawnTick);

                boolean hovered = !selected && Math.abs(f.mouseX - midX) <= f.keyframeSize
                    && Math.abs(f.mouseY - midY) <= f.keyframeSize;
                int colour = keyframeColour(track, keyframe);
                if (!track.enabled) {
                    colour = TimelineColours.alpha(colour & 0x00FFFFFF, 0x70);
                }
                if (selected) {
                    drawList.addRectFilled(midX - f.keyframeSize - 3, midY - f.keyframeSize - 3,
                        midX + f.keyframeSize + 3, midY + f.keyframeSize + 3, TimelineColours.SELECTION_GLOW);
                    drawList.addRect(midX - f.keyframeSize - 2, midY - f.keyframeSize - 2,
                        midX + f.keyframeSize + 2, midY + f.keyframeSize + 2, TimelineColours.SELECTED);
                } else if (hovered) {
                    drawList.addRect(midX - f.keyframeSize - 2, midY - f.keyframeSize - 2,
                        midX + f.keyframeSize + 2, midY + f.keyframeSize + 2, TimelineColours.alpha(0xFFFFFFFF, 0x90));
                }
                keyframe.drawOnTimeline(drawList, (int) f.keyframeSize, midX, midY, colour,
                    f.pixelsPerTick(), f.timelineLeft(), f.timelineRight(), tick, keyframes);

                if (hovered) {
                    // Name the lane too, so hovering says which track this keyframe belongs to.
                    ImGuiHelper.drawTooltip(displayName(track) + " - " + I18n.get("flashback.tick_label", tick)
                        + "\n" + I18n.get("flashback.timeline.keyframe_hint"));
                }

                drawTimelapseSpan(f, drawList, track, keyframes, entry, midY);
            }
        }
    }

    private static int keyframeColour(KeyframeTrack track, Keyframe keyframe) {
        if (track.keyframeType == TimelapseKeyframeType.INSTANCE && track.keyframesByTick.size() == 1) {
            return 0xFF155FFF;
        }
        return track.customColour != 0 ? track.customColour : -1;
    }

    private static void drawTimelapseSpan(Frame f, ImDrawList drawList, KeyframeTrack track,
                                          TreeMap<Integer, Keyframe> keyframes, Map.Entry<Integer, Keyframe> entry,
                                          float midY) {
        if (track.keyframeType != TimelapseKeyframeType.INSTANCE) {
            return;
        }
        Map.Entry<Integer, Keyframe> floorEntry = keyframes.floorEntry(entry.getKey() - 1);
        if (floorEntry == null || !(floorEntry.getValue() instanceof TimelapseKeyframe left)
                || !(entry.getValue() instanceof TimelapseKeyframe right)) {
            return;
        }

        int tickDelta = right.ticks - left.ticks;
        String message = tickDelta <= 0
            ? I18n.get("flashback.invalid").toUpperCase(Locale.ROOT)
            : Utils.timeInTicksToString(tickDelta);
        int textColour = tickDelta <= 0 ? 0xFF155FFF : 0xFFFFFFFF;

        float leftX = f.xOfTick(floorEntry.getKey());
        float rightX = f.xOfTick(entry.getKey());
        float midX = (leftX + rightX) / 2;
        float textY = midY - f.rowHeight * 0.3f;

        String text = I18n.get("flashback.select_replay.duration", message);
        float textWidth = ImGuiHelper.calcTextWidth(text);
        if (textWidth > rightX - leftX) {
            text = message;
            textWidth = ImGuiHelper.calcTextWidth(text);
        }
        if (textWidth <= rightX - leftX) {
            drawList.addText(midX - textWidth / 2, textY, textColour, text);
        }

        float startLine1 = leftX + f.keyframeSize;
        float endLine1 = midX - textWidth / 2 - 5;
        float startLine2 = midX + textWidth / 2 + 5;
        float endLine2 = rightX - f.keyframeSize;
        if (startLine1 < endLine1) {
            drawList.addLine(startLine1, midY, endLine1, midY, 0x80FFFFFF);
        }
        if (startLine2 < endLine2) {
            drawList.addLine(startLine2, midY, endLine2, midY, 0x80FFFFFF);
        }
    }

    private static void drawMarquee(Frame f, ImDrawList drawList) {
        if (!(drag instanceof Drag.Marquee marquee)) {
            return;
        }
        float minX = Math.min(f.mouseX, marquee.anchorX);
        float minY = Math.min(f.mouseY, marquee.anchorY);
        float maxX = Math.max(f.mouseX, marquee.anchorX);
        float maxY = Math.max(f.mouseY, marquee.anchorY);
        drawList.addRectFilled(minX, minY, maxX, maxY, 0x30DD6000);
        drawList.addRect(minX, minY, maxX, maxY, 0xFFDD6000);
        drawList.addRect(minX + 1, minY + 1, maxX - 1, maxY - 1, 0x60FFDD60);
    }

    private static void drawInsertionIndicator(Frame f, ImDrawList drawList) {
        if (!(drag instanceof Drag.Row row) || row.slot < 0 || !f.layout.wouldMove(row.rowIndex, row.slot)) {
            return;
        }
        float y = f.layout.slotY(row.rowIndex, row.slot, f.contentY);
        drawList.addLine(f.x + 4, y, f.timelineRight() - 2, y, TimelineColours.DROP_INDICATOR, 2f);
        drawList.addTriangleFilled(f.x + 4, y - 4, f.x + 4, y + 4, f.x + 10, y, TimelineColours.DROP_INDICATOR);
    }

    private static void drawRuler(Frame f) {
        ImDrawList drawList = ImGui.getWindowDrawList();
        drawList.pushClipRect(f.timelineLeft(), f.y, f.timelineRight(), f.y + f.height, true);

        if (f.scene.exportStartTicks >= 0 && f.scene.exportEndTicks >= 0) {
            float startX = f.xOfTick(f.scene.exportStartTicks);
            float endX = f.xOfTick(f.scene.exportEndTicks);
            drawList.addRectFilled(startX, f.y + f.timestampHeight, endX, f.y + f.rulerHeight, 0x60FFAA00);
            drawList.addLine(startX, f.y + f.timestampHeight, startX, f.y + f.rulerHeight, 0xFFFFAA00, 4f);
            drawList.addLine(endX, f.y + f.timestampHeight, endX, f.y + f.rulerHeight, 0xFFFFAA00, 4f);
            if (f.mouseInRuler && (Math.abs(f.mouseX - startX) <= 5 || Math.abs(f.mouseX - endX) <= 5)) {
                ImGui.setMouseCursor(ImGuiMouseCursor.ResizeEW);
            }
        }

        int minor = -f.minorsPerMajor;
        while (true) {
            float hi = f.timelineLeft() + f.minorSeparatorWidth * minor + f.errorOffset;
            if (hi >= f.timelineRight() - 1) {
                break;
            }
            if (minor % f.minorsPerMajor == 0) {
                drawList.addLine(hi, f.y + f.timestampHeight, hi, f.y + f.rulerHeight, 0x40FFFFFF);
                int ticks = f.minTicks + minor * f.ticksPerMinor;
                String timestamp = ticksToTimestamp(ticks);
                drawList.addText(hi + 2, f.y + 2, 0xFFB0B0B0, timestamp);
                if (f.showSubSeconds) {
                    float width = ImGuiHelper.calcTextWidth(timestamp);
                    drawList.addText(hi + 2 + width, f.y + 2, 0xFF707070, "/" + (ticks % 20));
                }
            } else {
                drawList.addLine(hi, f.y + f.rulerHeight - f.minorSeparatorHeight, hi, f.y + f.rulerHeight, 0x30FFFFFF);
            }
            minor += 1;
        }

        float markerY = f.y + f.rulerHeight;
        for (int tick = f.minTicks - 10; tick <= f.minTicks + f.availableTicks + 10; tick++) {
            Map.Entry<Integer, ReplayMarker> entry = f.metadata.replayMarkers.ceilingEntry(tick);
            if (entry == null || entry.getKey() > f.minTicks + f.availableTicks + 10) {
                break;
            }
            int markerTick = entry.getKey();
            ReplayMarker marker = entry.getValue();
            float markerX = f.xOfTick(markerTick);
            int colour = marker.colour();
            colour = ((colour >> 16) & 0xFF) | (colour & 0xFF00) | ((colour << 16) & 0xFF0000) | 0xFF000000;
            drawList.addCircleFilled(markerX, markerY, ReplayUI.scaleUi(5), colour);
            if (Math.abs(markerX - f.mouseX) <= 5 && Math.abs(markerY - f.mouseY) <= 5
                    && !ImGui.isAnyMouseDown() && marker.description() != null) {
                ImGuiHelper.drawTooltip(marker.description());
            }
            tick = markerTick + 1;
        }

        // The end of the replay, so it is obvious where the timeline stops.
        if (editorState.zoomMax >= 1.0) {
            drawList.addLine(f.timelineRight() - 2, f.y + f.timestampHeight, f.timelineRight() - 2,
                f.y + f.height - f.zoomBarHeight, 0x60FFFFFF);
        }

        // The playhead is neutral white on purpose: it has to stay legible over every camera colour,
        // and it is the one thing on the timeline that must always be findable at a glance.
        float cursorX = f.xOfTick(f.cursorTicks);
        int cursorColour = f.cursorTicks < f.currentReplayTick ? 0xA0FFFFFF : TimelineColours.PLAYHEAD;
        float size = ReplayUI.scaleUi(5);
        drawList.addRectFilled(cursorX - 2, f.y + f.rulerHeight, cursorX + 2,
            f.y + f.height - f.zoomBarHeight, TimelineColours.PLAYHEAD_SHADOW);
        drawList.addRectFilled(cursorX - 0.5f, f.y + f.rulerHeight, cursorX + 1.5f,
            f.y + f.height - f.zoomBarHeight, cursorColour);
        drawList.addTriangleFilled(cursorX, f.y + f.rulerHeight + size,
            cursorX - size * 2, f.y + f.timestampHeight + size,
            cursorX + size * 2, f.y + f.timestampHeight + size, TimelineColours.PLAYHEAD_SHADOW);
        drawList.addTriangleFilled(cursorX, f.y + f.rulerHeight + size - 2,
            cursorX - size * 2 + 2, f.y + f.timestampHeight + size,
            cursorX + size * 2 - 2, f.y + f.timestampHeight + size, cursorColour);

        drawList.popClipRect();
    }

    private static void drawZoomBar(Frame f) {
        ImDrawList drawList = ImGui.getWindowDrawList();
        float top = f.zoomBarTop();
        if (f.zoomBarExpanded) {
            drawList.addRectFilled(f.timelineLeft() + 1, top, f.timelineRight(), f.y + f.height, 0xFF404040, f.zoomBarHeight);
        }
        boolean active = drag instanceof Drag.Zoom;
        boolean hovered = f.mouseY >= top && f.mouseX >= f.zoomBarMin && f.mouseX <= f.zoomBarMax;
        if (active || hovered) {
            drawList.addRectFilled(f.zoomBarMin + f.zoomBarHeight / 2, top, f.zoomBarMax - f.zoomBarHeight / 2,
                f.y + f.height, 0xFFFFFFFF, f.zoomBarHeight);
            drawList.addCircleFilled(f.zoomBarMin + f.zoomBarHeight / 2, top + f.zoomBarHeight / 2,
                f.zoomBarHeight / 2, 0xFFAAAA00);
            drawList.addCircleFilled(f.zoomBarMax - f.zoomBarHeight / 2, top + f.zoomBarHeight / 2,
                f.zoomBarHeight / 2, 0xFFAAAA00);
        } else {
            drawList.addRectFilled(f.zoomBarMin, top, f.zoomBarMax, f.y + f.height, 0xFFFFFFFF, f.zoomBarHeight);
        }
    }

    private static void drawTransport(Frame f) {
        ImDrawList drawList = ImGui.getWindowDrawList();
        float y = f.controlsY;
        float size = f.controlSize;
        float rate = f.replayServer.getDesiredTickRate(true);

        drawList.addTriangleFilled(f.skipBackwardsX + size / 3f, y + size / 2, f.skipBackwardsX + size, y,
            f.skipBackwardsX + size, y + size, 0xFFFFFFFF);
        drawList.addRectFilled(f.skipBackwardsX, y, f.skipBackwardsX + size / 3f, y + size, 0xFFFFFFFF);

        int slowColour = rate < 20 ? 0xFF8080FF : 0xFFFFFFFF;
        drawList.addTriangleFilled(f.slowDownX, y + size / 2, f.slowDownX + size / 2, y, f.slowDownX + size / 2, y + size, slowColour);
        drawList.addTriangleFilled(f.slowDownX + size / 2, y + size / 2, f.slowDownX + size, y,
            f.slowDownX + size, y + size, slowColour);

        if (f.replayServer.replayPaused) {
            drawList.addTriangleFilled(f.pauseX + size / 12f, y, f.pauseX + size, y + size / 2,
                f.pauseX + size / 12f, y + size, 0xFFFFFFFF);
        } else {
            drawList.addRectFilled(f.pauseX, y, f.pauseX + size / 3f, y + size, 0xFFFFFFFF);
            drawList.addRectFilled(f.pauseX + size * 2 / 3f, y, f.pauseX + size, y + size, 0xFFFFFFFF);
        }

        int fastColour = rate > 20 ? 0xFF80FF80 : 0xFFFFFFFF;
        drawList.addTriangleFilled(f.fastForwardsX, y, f.fastForwardsX + size / 2, y + size / 2,
            f.fastForwardsX, y + size, fastColour);
        drawList.addTriangleFilled(f.fastForwardsX + size / 2, y, f.fastForwardsX + size, y + size / 2,
            f.fastForwardsX + size / 2, y + size, fastColour);

        drawList.addTriangleFilled(f.skipForwardsX, y, f.skipForwardsX + size * 2 / 3f, y + size / 2,
            f.skipForwardsX, y + size, 0xFFFFFFFF);
        drawList.addRectFilled(f.skipForwardsX + size * 2 / 3f, y, f.skipForwardsX + size, y + size, 0xFFFFFFFF);

        if (ImGui.isAnyMouseDown()) {
            return;
        }
        if (inControl(f, f.skipBackwardsX)) {
            ImGuiHelper.drawTooltip(I18n.get("flashback.skip_backwards"));
        } else if (inControl(f, f.slowDownX)) {
            ImGuiHelper.drawTooltip(I18n.get("flashback.slow_down_with_current_speed", rate / 20f));
        } else if (inControl(f, f.pauseX)) {
            ImGuiHelper.drawTooltip(I18n.get(f.replayServer.replayPaused ? "flashback.start_replay" : "flashback.pause_replay"));
        } else if (inControl(f, f.fastForwardsX)) {
            ImGuiHelper.drawTooltip(I18n.get("flashback.fast_forwards_with_current_speed", rate / 20f));
        } else if (inControl(f, f.skipForwardsX)) {
            ImGuiHelper.drawTooltip(I18n.get("flashback.skip_forwards"));
        }
    }

    private static boolean inControl(Frame f, float left) {
        return f.mouseX >= left && f.mouseX <= left + f.controlSize
            && f.mouseY >= f.controlsY && f.mouseY <= f.controlsY + f.controlSize;
    }

    // -- Input -----------------------------------------------------------------------------------

    private static void handleMouse(Frame f) {
        if (ImGui.isPopupOpen("", ImGuiPopupFlags.AnyPopup) || ReplayUI.getIO().getWantTextInput()) {
            if (drag != null) {
                finishDrag(f);
            }
            return;
        }

        if (drag != null) {
            updateDrag(f);
            if (!ImGui.isMouseDown(dragButton())) {
                finishDrag(f);
            }
            return;
        }

        boolean left = ImGui.isMouseClicked(ImGuiMouseButton.Left);
        boolean right = ImGui.isMouseClicked(ImGuiMouseButton.Right);
        boolean middle = ImGui.isMouseClicked(ImGuiMouseButton.Middle);
        if (!left && !right && !middle) {
            return;
        }

        if (left && handleTransportClick(f)) {
            return;
        }

        if (left && Math.abs(f.mouseY - (f.y + f.rulerHeight)) <= 6 && f.mouseInTimeline) {
            Integer markerTick = nearestMarker(f);
            if (markerTick != null) {
                jumpToMarker(f, markerTick);
                return;
            }
        }

        if (left && handleZoomBarClick(f, ImGuiMouseButton.Left)) {
            return;
        }
        if (middle && handleZoomBarClick(f, ImGuiMouseButton.Middle)) {
            return;
        }

        if (left && f.mouseInRuler) {
            handleRulerClick(f);
            return;
        }

        if (left && handleSplitterClick(f)) {
            return;
        }

        if (f.mouseInLeft && f.mouseInRows) {
            // Rows handle their own clicks through their widgets.
            return;
        }

        if (f.mouseInTimeline && f.mouseInRows) {
            handleCanvasClick(f, left, right);
        }
    }

    private static boolean handleTransportClick(Frame f) {
        if (inControl(f, f.skipBackwardsX)) {
            Integer previousMarker = f.metadata.replayMarkers.floorKey(f.cursorTicks - 1);
            f.replayServer.goToReplayTick(previousMarker != null ? previousMarker : 0);
            return true;
        }
        if (inControl(f, f.slowDownX)) {
            float current = f.replayServer.getDesiredTickRate(true);
            float highest = REPLAY_TICK_SPEEDS[0];
            for (float speed : REPLAY_TICK_SPEEDS) {
                if (speed >= current) {
                    break;
                }
                highest = speed;
            }
            f.replayServer.setDesiredTickRate(highest, true);
            return true;
        }
        if (inControl(f, f.pauseX)) {
            togglePaused(f.replayServer);
            return true;
        }
        if (inControl(f, f.fastForwardsX)) {
            float current = f.replayServer.getDesiredTickRate(true);
            float lowest = REPLAY_TICK_SPEEDS[REPLAY_TICK_SPEEDS.length - 1];
            for (int i = REPLAY_TICK_SPEEDS.length - 1; i >= 0; i--) {
                if (REPLAY_TICK_SPEEDS[i] <= current) {
                    break;
                }
                lowest = REPLAY_TICK_SPEEDS[i];
            }
            f.replayServer.setDesiredTickRate(lowest, true);
            return true;
        }
        if (inControl(f, f.skipForwardsX)) {
            Integer nextMarker = f.metadata.replayMarkers.ceilingKey(f.cursorTicks + 1);
            f.replayServer.goToReplayTick(nextMarker != null ? nextMarker : f.totalTicks);
            return true;
        }
        return false;
    }

    /** @return true when the click was consumed by the zoom bar (or by recentring on it) */
    private static boolean handleZoomBarClick(Frame f, int button) {
        float zoomTop = f.zoomBarTop();
        if (f.mouseY >= zoomTop && f.mouseY <= f.y + f.height
                && f.mouseX >= f.zoomBarMin - f.zoomBarHeight && f.mouseX <= f.zoomBarMax + f.zoomBarHeight) {
            Drag.Zoom.Part part = f.mouseX <= f.zoomBarMin + f.zoomBarHeight ? Drag.Zoom.Part.LEFT
                : f.mouseX >= f.zoomBarMax - f.zoomBarHeight ? Drag.Zoom.Part.RIGHT : Drag.Zoom.Part.MOVE;
            drag = new Drag.Zoom(part, button, f.mouseX, editorState.zoomMin, editorState.zoomMax);
            return true;
        }
        if (button == ImGuiMouseButton.Left && f.zoomBarExpanded && f.mouseY >= zoomTop && f.mouseInTimeline) {
            double size = editorState.zoomMax - editorState.zoomMin;
            double target = (f.mouseX - f.timelineLeft()) / Math.max(1, f.timelineWidth);
            editorState.zoomMin = Math.max(0, Math.min(1 - size, target - size / 2));
            editorState.zoomMax = editorState.zoomMin + size;
            editorState.markDirty();
            return true;
        }
        return false;
    }

    private static void handleRulerClick(Frame f) {
        if (f.scene.exportStartTicks >= 0 && f.scene.exportEndTicks >= 0) {
            if (Math.abs(f.mouseX - f.xOfTick(f.scene.exportStartTicks)) <= 5) {
                drag = new Drag.ExportEdge(true);
                return;
            }
            if (Math.abs(f.mouseX - f.xOfTick(f.scene.exportEndTicks)) <= 5) {
                drag = new Drag.ExportEdge(false);
                return;
            }
        }
        f.replayServer.replayPaused = true;
        f.replayServer.goToReplayTick(f.tickAtX(f.mouseX));
        drag = new Drag.Head();
    }

    private static void handleCanvasClick(Frame f, boolean left, boolean right) {
        int rowIndex = f.rowAt(f.mouseY);
        KeyframeTrack track = f.layout.trackAt(rowIndex);
        if (track == null) {
            if (left) {
                beginMarquee(f);
            }
            return;
        }

        if (KeyframeTrack.isCameraSwitch(track)) {
            handleCutsLaneClick(f, track, left, right);
            return;
        }

        int grabbed = keyframeAt(f, track);

        if (right) {
            if (grabbed >= 0) {
                if (!SELECTION.contains(track, grabbed)) {
                    SELECTION.replace(track, grabbed);
                }
                pendingInspector = new TimelineSelection.Ref(track, grabbed);
            } else if (track.keyframeType.canBeCreatedNormally()) {
                pendingCreateRow = rowIndex;
                pendingCreateTick = f.tickAtX(f.mouseX);
            }
            return;
        }

        if (!left) {
            return;
        }

        if (grabbed >= 0) {
            if (ReplayUI.isCtrlOrCmdDown()) {
                SELECTION.toggle(track, grabbed);
            } else if (!SELECTION.contains(track, grabbed)) {
                SELECTION.replace(track, grabbed);
            }
            if (ImGui.isMouseDoubleClicked(ImGuiMouseButton.Left)) {
                applyKeyframe(track, grabbed);
                return;
            }
            drag = new Drag.Keys(track, grabbed, f.mouseX, f.mouseY);
            return;
        }

        beginMarquee(f);
    }

    /**
     * The cut lane's gestures, which are the whole story for choosing cameras.
     *
     * <p>On a flag: select it, drag it to retime, or right-click to edit it - exactly like a keyframe
     * anywhere else. On the band between flags: ask which camera should be live from here, which is
     * the only way a cut is created.
     */
    private static void handleCutsLaneClick(Frame f, KeyframeTrack cutsLane, boolean left, boolean right) {
        int cut = cutAt(f, cutsLane);

        if (cut >= 0) {
            if (right) {
                if (!SELECTION.contains(cutsLane, cut)) {
                    SELECTION.replace(cutsLane, cut);
                }
                pendingInspector = new TimelineSelection.Ref(cutsLane, cut);
                return;
            }
            if (!left) {
                return;
            }
            if (ReplayUI.isCtrlOrCmdDown()) {
                SELECTION.toggle(cutsLane, cut);
            } else if (!SELECTION.contains(cutsLane, cut)) {
                SELECTION.replace(cutsLane, cut);
            }
            if (ImGui.isMouseDoubleClicked(ImGuiMouseButton.Left)) {
                // Double-clicking a cut is the quickest way to preview it.
                EditorCamera camera = f.scene.resolveCameraAt(cut);
                if (camera != null) {
                    editorState.previewCamera(camera, f.cursorTicks);
                }
                return;
            }
            drag = new Drag.Keys(cutsLane, cut, f.mouseX, f.mouseY);
            return;
        }

        if (left || right) {
            pendingCutMenuTrack = cutsLane;
            pendingCutMenuTick = f.tickAtX(f.mouseX);
            openCutMenuNow = true;
        }
    }

    private static void beginMarquee(Frame f) {
        drag = new Drag.Marquee(f.mouseX, f.mouseY, ReplayUI.isCtrlOrCmdDown());
        if (drag instanceof Drag.Marquee marquee) {
            marquee.before.addAll(SELECTION);
        }
        if (!ReplayUI.isCtrlOrCmdDown()) {
            SELECTION.clear();
        }
    }

    private static int dragButton() {
        return drag == null ? ImGuiMouseButton.Left : drag.button();
    }

    private static int keyframeAt(Frame f, KeyframeTrack track) {
        int tick = f.tickAtX(f.mouseX);
        Map.Entry<Integer, Keyframe> floor = track.keyframesByTick.floorEntry(tick);
        if (floor != null) {
            float customWidth = floor.getValue().getCustomWidthInTicks();
            if (customWidth > 0) {
                if (tick <= floor.getKey() + Math.ceil(customWidth)) {
                    return floor.getKey();
                }
            } else if (Math.abs(f.xOfTick(floor.getKey()) - f.mouseX) <= f.keyframeSize) {
                return floor.getKey();
            }
        }
        Map.Entry<Integer, Keyframe> ceiling = track.keyframesByTick.ceilingEntry(tick);
        if (ceiling != null && Math.abs(f.xOfTick(ceiling.getKey()) - f.mouseX) <= f.keyframeSize) {
            return ceiling.getKey();
        }
        if (floor != null && ceiling != null) {
            float floorDistance = Math.abs(f.xOfTick(floor.getKey()) - f.mouseX);
            float ceilDistance = Math.abs(f.xOfTick(ceiling.getKey()) - f.mouseX);
            if (Math.min(floorDistance, ceilDistance) <= f.keyframeSize) {
                return floorDistance <= ceilDistance ? floor.getKey() : ceiling.getKey();
            }
        }
        return -1;
    }

    private static int cutAt(Frame f, KeyframeTrack switchTrack) {
        // Cuts are drawn as flags, so they get a slightly wider grab area than a keyframe.
        for (int tick : switchTrack.keyframesByTick.keySet()) {
            if (Math.abs(f.xOfTick(tick) - f.mouseX) <= f.keyframeSize + 3) {
                return tick;
            }
        }
        return -1;
    }

    private static void updateDrag(Frame f) {
        switch (drag) {
            case Drag.Head ignored -> {
                f.replayServer.replayPaused = true;
                f.replayServer.goToReplayTick(snapTick(f, f.tickAtX(f.mouseX)));
            }
            case Drag.Keys keys -> {
                if (!keys.moved && (Math.abs(f.mouseX - keys.anchorX) > 2 || Math.abs(f.mouseY - keys.anchorY) > 2)) {
                    keys.moved = true;
                }
                if (keys.moved) {
                    String tooltip = moveTooltip(f, keys);
                    if (!InputHelper.isShiftDownRaw() && scene.keyframeTracks.size() > 1) {
                        tooltip += "\n" + I18n.get("flashback.hold_shift_to_snap");
                    }
                    ImGuiHelper.drawTooltip(tooltip);
                }
            }
            case Drag.Row row -> row.slot = f.layout.insertionSlot(row.rowIndex, f.mouseY, f.contentY);
            case Drag.Splitter ignored -> {
                ImGui.setMouseCursor(ImGuiMouseCursor.ResizeEW);
                double units = (f.mouseX - f.x) / Math.max(1f, f.uiScale);
                editorState.timelinePanelWidth = Math.max(170, Math.min(420, units));
                editorState.markDirty();
            }
            case Drag.ExportEdge edge -> {
                int tick = snapTick(f, f.tickAtX(f.mouseX));
                upgradeToWrite();
                if (edge.start) {
                    scene.setExportTicks(tick, -1, f.totalTicks);
                } else {
                    scene.setExportTicks(-1, tick, f.totalTicks);
                }
                editorState.markDirty();
            }
            case Drag.Zoom zoom -> {
                float factor = (f.mouseX - zoom.anchorX) / Math.max(1, f.timelineRight() - f.timelineLeft());
                if (zoom.part == Drag.Zoom.Part.LEFT) {
                    ImGui.setMouseCursor(ImGuiMouseCursor.ResizeEW);
                    editorState.zoomMin = Math.max(0, Math.min(editorState.zoomMax - 0.01, zoom.minBefore + factor));
                } else if (zoom.part == Drag.Zoom.Part.RIGHT) {
                    ImGui.setMouseCursor(ImGuiMouseCursor.ResizeEW);
                    editorState.zoomMax = Math.max(editorState.zoomMin + 0.01, Math.min(1, zoom.maxBefore + factor));
                } else {
                    ImGui.setMouseCursor(ImGuiMouseCursor.Hand);
                    double size = zoom.maxBefore - zoom.minBefore;
                    if (factor < 0) {
                        editorState.zoomMin = Math.max(0, zoom.minBefore + factor);
                        editorState.zoomMax = editorState.zoomMin + size;
                    } else if (factor > 0) {
                        editorState.zoomMax = Math.min(1, zoom.maxBefore + factor);
                        editorState.zoomMin = editorState.zoomMax - size;
                    }
                }
                editorState.markDirty();
            }
            case Drag.Marquee marquee -> {
                SELECTION.clear();
                if (marquee.additive) {
                    SELECTION.addAll(marquee.before);
                }
                selectWithin(f, marquee);
            }
        }
    }

    private static void finishDrag(Frame f) {
        Drag finished = drag;
        drag = null;

        if (finished instanceof Drag.Keys keys) {
            if (keys.moved) {
                applyKeyframeMove(f, keys);
            } else {
                SELECTION.setPrimary(new TimelineSelection.Ref(keys.track, keys.tick));
            }
        } else if (finished instanceof Drag.Row row) {
            applyRowReorder(f, row);
        } else if (finished instanceof Drag.Head) {
            f.replayServer.replayPaused = true;
        }
    }

    private static void applyRowReorder(Frame f, Drag.Row row) {
        if (row.slot < 0 || !f.layout.wouldMove(row.rowIndex, row.slot)) {
            return;
        }
        upgradeToWrite();
        if (f.layout.row(row.rowIndex) instanceof TimelineRow.CameraGroup) {
            List<EditorCamera> order = f.layout.cameraOrderAfterMove(row.rowIndex, row.slot);
            if (!order.isEmpty()) {
                TimelineEdits.reorderCamera(scene, editorState, order);
            }
            return;
        }
        List<KeyframeTrack> order = f.layout.trackOrderAfterMove(row.rowIndex, row.slot);
        if (!order.isEmpty()) {
            TimelineEdits.reorderTrackGroup(scene, editorState, f.layout.trackSlotsOf(row.rowIndex), order);
            // A reordered row is re-created from a snapshot, so references to the old track object -
            // including the selection - no longer point at anything. Dropping them here is honest;
            // the alternative would be leaving a selection that silently matches nothing.
            SELECTION.clear();
        }
    }

    private static void applyKeyframeMove(Frame f, Drag.Keys keys) {
        int delta = grabbedDelta(f, keys);
        if (delta == 0) {
            return;
        }
        upgradeToWrite();

        List<EditorSceneHistoryAction> undo = new ArrayList<>();
        List<EditorSceneHistoryAction> redo = new ArrayList<>();
        int moved = 0;

        for (TimelineSelection.Ref ref : SELECTION.refs()) {
            KeyframeTrack track = ref.track();
            int trackIndex = scene.trackIndexOf(track);
            Keyframe keyframe = track.keyframesByTick.get(ref.tick());
            if (trackIndex < 0 || keyframe == null) {
                continue;
            }
            int newTick = Math.max(0, Math.min(f.totalTicks, ref.tick() + delta));
            if (newTick == ref.tick()) {
                continue;
            }
            Keyframe overwritten = track.keyframesByTick.get(newTick);
            redo.add(new EditorSceneHistoryAction.RemoveKeyframe(track.keyframeType, trackIndex, ref.tick()));
            redo.add(new EditorSceneHistoryAction.SetKeyframe(track.keyframeType, trackIndex, newTick, keyframe));
            undo.add(new EditorSceneHistoryAction.RemoveKeyframe(track.keyframeType, trackIndex, newTick));
            undo.add(new EditorSceneHistoryAction.SetKeyframe(track.keyframeType, trackIndex, ref.tick(), keyframe));
            if (overwritten != null) {
                // The keyframe the move landed on comes back on undo.
                undo.add(new EditorSceneHistoryAction.SetKeyframe(track.keyframeType, trackIndex, newTick, overwritten));
            }
            moved += 1;
        }

        if (moved == 0) {
            return;
        }

        // Follow the keyframes to their new ticks, so the selection survives the move.
        List<TimelineSelection.Ref> movedRefs = new ArrayList<>();
        for (TimelineSelection.Ref ref : SELECTION.refs()) {
            int newTick = Math.max(0, Math.min(f.totalTicks, ref.tick() + delta));
            movedRefs.add(new TimelineSelection.Ref(ref.track(), newTick));
        }

        TimelineEdits.push(scene, editorState, undo, redo, I18n.get("flashback.moved_n_keyframes", moved));

        if (!movedRefs.isEmpty()) {
            SELECTION.replace(movedRefs.get(0).track(), movedRefs.get(0).tick());
            for (int i = 1; i < movedRefs.size(); i++) {
                SELECTION.add(movedRefs.get(i).track(), movedRefs.get(i).tick());
            }
        }
        SELECTION.prune(scene);
    }

    private static int grabbedDelta(Frame f, Drag.Keys keys) {
        return snappedDelta(f.scale, f.mouseX, keys.tick, keys.track);
    }

    /** How far the grabbed keyframe has been moved, with shift snapping applied. */
    private static int snappedDelta(TickScale scale, float mouseX, int grabbedTick, @Nullable KeyframeTrack dragged) {
        int delta = scale.tickAtX(mouseX) - grabbedTick;
        if (InputHelper.isShiftDownRaw()) {
            Integer snapped = nearestKeyframeTick(scale, mouseX, grabbedTick, dragged);
            if (snapped != null) {
                delta = snapped - grabbedTick;
            }
        }
        return delta;
    }

    /** The tick a grabbed keyframe is drawn at, including the current drag offset. */
    private static int grabbedTickFor(Frame f, int tick) {
        if (drag instanceof Drag.Keys keys) {
            return Math.max(0, Math.min(f.totalTicks, tick + grabbedDelta(f, keys)));
        }
        return tick;
    }

    private static String moveTooltip(Frame f, Drag.Keys keys) {
        int delta = grabbedDelta(f, keys);
        return I18n.get("flashback.tick_label", keys.tick + delta)
            + I18n.get("flashback.tick_offset", (delta >= 0 ? "+" : "") + delta);
    }

    private static int snapTick(Frame f, int tick) {
        if (!InputHelper.isShiftDownRaw()) {
            return tick;
        }
        Integer closest = nearestKeyframeTick(f.scale, f.mouseX, tick, null);
        return closest != null ? closest : tick;
    }

    /** The keyframe nearest the pointer, skipping the track being dragged. */
    @Nullable
    private static Integer nearestKeyframeTick(TickScale scale, float mouseX, int tick, @Nullable KeyframeTrack exclude) {
        Integer closest = null;
        float closestDistance = scale.pixelsPerTick() * 40;
        for (KeyframeTrack track : scene.keyframeTracks) {
            if (track == exclude) {
                continue;
            }
            Integer floor = track.keyframesByTick.floorKey(tick);
            Integer ceiling = track.keyframesByTick.ceilingKey(tick);
            for (Integer option : new Integer[]{floor, ceiling}) {
                if (option == null) {
                    continue;
                }
                float distance = Math.abs(scale.xOfTick(option) - mouseX);
                if (distance < closestDistance) {
                    closestDistance = distance;
                    closest = option;
                }
            }
        }
        return closest;
    }

    private static void selectWithin(Frame f, Drag.Marquee marquee) {
        float minX = Math.min(f.mouseX, marquee.anchorX) - f.keyframeSize;
        float maxX = Math.max(f.mouseX, marquee.anchorX) + f.keyframeSize;
        float minY = Math.min(f.mouseY, marquee.anchorY);
        float maxY = Math.max(f.mouseY, marquee.anchorY);

        for (int rowIndex = 0; rowIndex < f.layout.size(); rowIndex++) {
            if (!(f.layout.row(rowIndex) instanceof TimelineRow.Track trackRow)) {
                continue;
            }
            if (f.rowBottom(rowIndex) < minY || f.rowTop(rowIndex) > maxY) {
                continue;
            }
            KeyframeTrack track = trackRow.track();
            for (int tick : track.keyframesByTick.keySet()) {
                float x = f.xOfTick(tick);
                if (x >= minX && x <= maxX) {
                    SELECTION.add(track, tick);
                }
            }
        }
    }

    private static void jumpToMarker(Frame f, int markerTick) {
        f.replayServer.goToReplayTick(markerTick);
        ReplayMarker marker = f.metadata.replayMarkers.get(markerTick);
        if (marker == null || marker.position() == null) {
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || Minecraft.getInstance().level == null) {
            return;
        }
        ReplayMarker.MarkerPosition position = marker.position();
        if (!Minecraft.getInstance().level.dimension().toString().equals(position.dimension())) {
            return;
        }
        Vec3 target = new Vec3(position.position());
        Vec3 eyes = player.getEyePosition();
        Vec3 delta = eyes.subtract(target);
        if (delta.length() > 3) {
            player.snapTo(delta.normalize().scale(3).add(target).subtract(0, player.getEyeHeight(), 0));
        }
        player.lookAt(EntityAnchorArgument.Anchor.EYES, target);
        player.setDeltaMovement(Vec3.ZERO);
    }

    @Nullable
    private static Integer nearestMarker(Frame f) {
        Integer best = null;
        for (int tick = f.minTicks - 10; tick <= f.minTicks + f.availableTicks + 10; tick++) {
            Map.Entry<Integer, ReplayMarker> entry = f.metadata.replayMarkers.ceilingEntry(tick);
            if (entry == null || entry.getKey() > f.minTicks + f.availableTicks + 10) {
                break;
            }
            tick = entry.getKey();
            if (Math.abs(f.xOfTick(tick) - f.mouseX) <= 5) {
                if (best == null || Math.abs(tick - f.cursorTicks) < Math.abs(best - f.cursorTicks)) {
                    best = tick;
                }
            }
        }
        return best;
    }

    // -- Scrolling and keys ----------------------------------------------------------------------

    private static void handleScroll(Frame f) {
        int scroll = (int) Math.signum(ReplayUI.getIO().getMouseWheel());
        if (scroll == 0 || !f.mouseInTimeline || f.mouseY < f.y || f.mouseY > f.y + f.height) {
            return;
        }

        double zoomMin = editorState.zoomMin;
        double zoomMax = editorState.zoomMax;

        if (Keybinds.TIMELINE_ZOOM_SCROLL.areAllModifiersDown()) {
            double mousePercentage = (f.mouseX - f.timelineLeft()) / Math.max(1, f.timelineWidth);
            double zoomDelta = zoomMax - zoomMin;
            if (zoomDelta <= 0.001) {
                return;
            }
            if (scroll > 0) {
                editorState.zoomMin += zoomDelta * 0.05 * mousePercentage;
                editorState.zoomMax -= zoomDelta * 0.05 * (1 - mousePercentage);
            } else {
                editorState.zoomMin = Math.max(0, zoomMin - zoomDelta * 0.05 / 0.9 * mousePercentage);
                editorState.zoomMax = Math.min(1, zoomMax + zoomDelta * 0.05 / 0.9 * (1 - mousePercentage));
            }
        } else if (Keybinds.TIMELINE_MOVE_SCROLL.areAllModifiersDown()) {
            if (scroll > 0) {
                if (zoomMax >= 0.99) {
                    editorState.zoomMin += 1.0 - zoomMax;
                    editorState.zoomMax = 1.0;
                } else {
                    editorState.zoomMin += 0.01;
                    editorState.zoomMax += 0.01;
                }
            } else if (zoomMin <= 0.01) {
                editorState.zoomMax -= zoomMin;
                editorState.zoomMin = 0.0;
            } else {
                editorState.zoomMin -= 0.01;
                editorState.zoomMax -= 0.01;
            }
        } else {
            return;
        }
        editorState.markDirty();
    }

    private static void handleKeyPresses(Frame f) {
        if (Keybinds.PAUSE.isPressed(false)) {
            togglePaused(f.replayServer);
        }
        if (ImGui.isKeyPressed(ImGuiKey.LeftArrow, false)) {
            pendingStepBackwardsTicks += ReplayUI.isCtrlOrCmdDown() ? 5 : 1;
        } else if (pendingStepBackwardsTicks > 0 && !ImGui.isKeyDown(ImGuiKey.LeftArrow)) {
            f.replayServer.goToReplayTick(Math.max(0, f.replayServer.getReplayTick() - pendingStepBackwardsTicks));
            f.replayServer.forceApplyKeyframes.set(true);
            pendingStepBackwardsTicks = 0;
        }
        if (ImGui.isKeyPressed(ImGuiKey.RightArrow, false)) {
            f.replayServer.goToReplayTick(Math.min(f.totalTicks, f.cursorTicks + (ReplayUI.isCtrlOrCmdDown() ? 5 : 1)));
            f.replayServer.forceApplyKeyframes.set(true);
        }
        if (ImGui.isKeyPressed(ImGuiKey.UpArrow, false)) {
            jumpToNeighbouringKeyframe(f, 1);
        }
        if (ImGui.isKeyPressed(ImGuiKey.DownArrow, false)) {
            jumpToNeighbouringKeyframe(f, -1);
        }

        boolean delete = ImGui.isKeyPressed(ImGuiKey.Delete, false) || ImGui.isKeyPressed(ImGuiKey.Backspace, false);
        if (delete && !SELECTION.isEmpty()) {
            deleteSelection();
        }

        if (Keybinds.UNDO.isPressed(false)) {
            upgradeToWrite();
            scene.undo(ReplayUI::setInfoOverlayShort);
            editorState.markDirty();
        }
        if (Keybinds.REDO.isPressed(false)) {
            upgradeToWrite();
            scene.redo(ReplayUI::setInfoOverlayShort);
            editorState.markDirty();
        }
        if (Keybinds.COPY.isPressed(false) && !SELECTION.isEmpty()) {
            copySelection(false, false, false);
        }
        if (Keybinds.PASTE.isPressed(false)) {
            pasteKeyframes(f);
        }

        handleMarkInOut(f);

        if (Keybinds.ZOOM_IN.isPressed(true)) {
            double delta = editorState.zoomMax - editorState.zoomMin;
            if (delta > 0.001) {
                editorState.zoomMin += delta * 0.025;
                editorState.zoomMax -= delta * 0.025;
                editorState.markDirty();
            }
        }
        if (Keybinds.ZOOM_OUT.isPressed(true)) {
            double delta = editorState.zoomMax - editorState.zoomMin;
            if (delta > 0.001 && delta < 1.0) {
                editorState.zoomMin = Math.max(0, editorState.zoomMin - delta * 0.025);
                editorState.zoomMax = Math.min(1, editorState.zoomMax + delta * 0.025);
                editorState.markDirty();
            }
        }

        float rollCw = Keybinds.ROLL_CW.isPressed(true) ? (Keybinds.ROLL_CW.isPressed(false) ? 1.0f : 3.0f) : 0.0f;
        float rollCcw = Keybinds.ROLL_CCW.isPressed(true) ? (Keybinds.ROLL_CCW.isPressed(false) ? 1.0f : 3.0f) : 0.0f;
        if (rollCw != rollCcw) {
            editorState.replayVisuals.overrideRoll = true;
            editorState.replayVisuals.overrideRollAmount += rollCw - rollCcw;
            if (editorState.replayVisuals.overrideRollAmount < -180.0f) editorState.replayVisuals.overrideRollAmount += 360.0f;
            if (editorState.replayVisuals.overrideRollAmount > 180.0f) editorState.replayVisuals.overrideRollAmount -= 360.0f;
            editorState.markDirty();
        }

        if (Keybinds.ADD_CAMERA.isPressed(false)) {
            addCameraKeyframeAtCursor(f);
        }

        // Ctrl while scrubbing applies the keyframes live, which is how a shot is previewed.
        if (drag instanceof Drag.Head && !scene.keyframeTracks.isEmpty()) {
            if (InputHelper.isCtrlDownRaw()) {
                editorState.applyKeyframes(new MinecraftKeyframeHandler(Minecraft.getInstance()), f.cursorTicks, sceneStamp);
            } else if (!InputHelper.isShiftDownRaw()) {
                // One tooltip with both hints: two tooltips would draw on top of each other.
                ImGuiHelper.drawTooltip(I18n.get("flashback.hold_ctrl_to_apply_keyframes")
                    + "\n" + I18n.get("flashback.hold_shift_to_snap_to_keyframes"));
            }
        }
    }

    private static void addCameraKeyframeAtCursor(Frame f) {
        EditorCamera camera = TimelineEdits.cameraForEditing(scene, f.cursorTicks);
        if (camera == null) {
            upgradeToWrite();
            TimelineEdits.addCamera(scene, editorState, EditorCamera.Kind.FREE, f.cursorTicks);
            camera = TimelineEdits.cameraForEditing(scene, f.cursorTicks);
        }
        if (camera == null) {
            return;
        }
        upgradeToWrite();
        if (Minecraft.getInstance().player != Minecraft.getInstance().getCameraEntity()) {
            // Same reasoning as adding the keyframe from the timeline: a camera keyframe while
            // spectating a player would record that player's position into a camera nobody is
            // looking through.
            ReplayUI.setInfoOverlay(I18n.get("flashback.camera_keyframes_not_needed"));
            new MinecraftKeyframeHandler(Minecraft.getInstance()).stopSpectating();
        }
        TimelineEdits.addCameraPositionKeyframe(scene, editorState, camera, f.cursorTicks);
        ReplayUI.setInfoOverlayShort(I18n.get("flashback.added_named_keyframe", CameraKeyframeType.INSTANCE.name()));
    }

    private static void jumpToNeighbouringKeyframe(Frame f, int direction) {
        int target = direction > 0 ? f.totalTicks : 0;
        if (direction > 0) {
            if (scene.exportStartTicks > f.cursorTicks) {
                target = Math.min(target, scene.exportStartTicks);
            }
            if (scene.exportEndTicks > f.cursorTicks) {
                target = Math.min(target, scene.exportEndTicks);
            }
            Integer marker = f.metadata.replayMarkers.ceilingKey(f.cursorTicks + 1);
            if (marker != null) {
                target = Math.min(target, marker);
            }
            for (KeyframeTrack track : scene.keyframeTracks) {
                Integer next = track.keyframesByTick.ceilingKey(f.cursorTicks + 1);
                if (next != null) {
                    target = Math.min(target, next);
                }
            }
        } else {
            if (scene.exportEndTicks >= 0 && scene.exportEndTicks < f.cursorTicks) {
                target = Math.max(target, scene.exportEndTicks);
            }
            if (scene.exportStartTicks >= 0 && scene.exportStartTicks < f.cursorTicks) {
                target = Math.max(target, scene.exportStartTicks);
            }
            Integer marker = f.metadata.replayMarkers.floorKey(f.cursorTicks - 1);
            if (marker != null) {
                target = Math.max(target, marker);
            }
            for (KeyframeTrack track : scene.keyframeTracks) {
                Integer previous = track.keyframesByTick.floorKey(f.cursorTicks - 1);
                if (previous != null) {
                    target = Math.max(target, previous);
                }
            }
        }
        f.replayServer.goToReplayTick(target);
        f.replayServer.forceApplyKeyframes.set(true);
    }

    private static void handleMarkInOut(Frame f) {
        if (Keybinds.MARK_IN.isPressed(false)) {
            upgradeToWrite();
            scene.setExportTicks(f.cursorTicks, -1, f.totalTicks);
            editorState.markDirty();
            ReplayUI.setInfoOverlayShort(I18n.get("flashback.timeline.marked_in", f.cursorTicks));
        }
        if (Keybinds.MARK_OUT.isPressed(false)) {
            upgradeToWrite();
            scene.setExportTicks(-1, f.cursorTicks, f.totalTicks);
            editorState.markDirty();
            ReplayUI.setInfoOverlayShort(I18n.get("flashback.timeline.marked_out", f.cursorTicks));
        }
        if (Keybinds.CLEAR_IN.isPressed(false)) {
            upgradeToWrite();
            scene.setExportTicks(0, -1, f.totalTicks);
            editorState.markDirty();
            ReplayUI.setInfoOverlayShort(I18n.get("flashback.timeline.marked_cleared_in"));
        }
        if (Keybinds.CLEAR_OUT.isPressed(false)) {
            upgradeToWrite();
            scene.setExportTicks(-1, f.totalTicks, f.totalTicks);
            editorState.markDirty();
            ReplayUI.setInfoOverlayShort(I18n.get("flashback.timeline.marked_cleared_out"));
        }
    }

    private static void togglePaused(ReplayServer replayServer) {
        if (replayServer.getReplayTick() >= replayServer.getTotalReplayTicks()) {
            replayServer.jumpToTick = 0;
        }
        replayServer.replayPaused = !replayServer.replayPaused;
        if (!replayServer.replayPaused) {
            Screen screen = Minecraft.getInstance().gui.screen();
            if (screen != null && screen.isPauseScreen()) {
                Minecraft.getInstance().gui.setScreen(null);
            }
        }
    }

    // -- Operations ------------------------------------------------------------------------------

    private static void createKeyframe(KeyframeTrack track, int tick) {
        upgradeToWrite();
        KeyframeType<?> type = track.keyframeType;
        Keyframe direct = type.createDirect();
        if (direct != null) {
            if (type == CameraKeyframeType.INSTANCE
                    && Minecraft.getInstance().player != Minecraft.getInstance().getCameraEntity()) {
                // A camera keyframe while spectating a player would animate the wrong thing.
                ReplayUI.setInfoOverlay(I18n.get("flashback.camera_keyframes_not_needed"));
                new MinecraftKeyframeHandler(Minecraft.getInstance()).stopSpectating();
            }
            TimelineEdits.setKeyframe(scene, editorState, track, tick, direct);
            return;
        }

        if (type == TimelapseKeyframeType.INSTANCE && track.keyframesByTick.isEmpty()) {
            TimelineEdits.setKeyframe(scene, editorState, track, tick, new TimelapseKeyframe(0));
            return;
        }

        KeyframeType.KeyframeCreatePopup<?> popup = type.createPopup();
        if (popup != null) {
            pendingTypePopup = popup;
            pendingTypePopupTrack = track;
            pendingTypePopupTick = tick;
            // Opened by the lane's own draw pass, which is where the popup's ID lives.
            openTypePopupNow = true;
        }
    }

    private static void applyKeyframe(KeyframeTrack track, int tick) {
        Keyframe keyframe = track.keyframesByTick.get(tick);
        if (keyframe == null) {
            return;
        }
        MinecraftKeyframeHandler handler = new MinecraftKeyframeHandler(Minecraft.getInstance());
        if (!keyframe.keyframeType().supportsHandler(handler)) {
            return;
        }
        var change = keyframe.createChange();
        if (change != null) {
            change.apply(handler);
        }
    }

    private static void deleteSelection() {
        upgradeToWrite();
        List<EditorSceneHistoryAction> undo = new ArrayList<>();
        List<EditorSceneHistoryAction> redo = new ArrayList<>();

        for (TimelineSelection.Ref ref : SELECTION.refs()) {
            int trackIndex = scene.trackIndexOf(ref.track());
            Keyframe keyframe = ref.track().keyframesByTick.get(ref.tick());
            if (trackIndex < 0 || keyframe == null) {
                continue;
            }
            undo.add(new EditorSceneHistoryAction.SetKeyframe(ref.track().keyframeType, trackIndex, ref.tick(), keyframe.copy()));
            redo.add(new EditorSceneHistoryAction.RemoveKeyframe(ref.track().keyframeType, trackIndex, ref.tick()));
        }
        if (undo.isEmpty()) {
            return;
        }
        int count = undo.size();
        SELECTION.clear();
        inspectorOpen = false;
        TimelineEdits.push(scene, editorState, undo, redo, I18n.get("flashback.deleted_n_keyframes", count));
    }

    private static void copySelection(boolean relativePosition, boolean relativeYaw, boolean relativePitch) {
        int minTick = Integer.MAX_VALUE;
        for (TimelineSelection.Ref ref : SELECTION.refs()) {
            minTick = Math.min(minTick, ref.tick());
        }

        Map<KeyframeTrack, TreeMap<Integer, Keyframe>> byTrack = new LinkedHashMap<>();
        for (TimelineSelection.Ref ref : SELECTION.refs()) {
            Keyframe keyframe = ref.track().keyframesByTick.get(ref.tick());
            if (keyframe != null) {
                byTrack.computeIfAbsent(ref.track(), t -> new TreeMap<>()).put(ref.tick() - minTick, keyframe);
            }
        }

        List<SavedTrack> tracks = new ArrayList<>();
        for (Map.Entry<KeyframeTrack, TreeMap<Integer, Keyframe>> entry : byTrack.entrySet()) {
            KeyframeTrack track = entry.getKey();
            tracks.add(new SavedTrack(track.keyframeType, scene.trackIndexOf(track), !track.enabled, track.cameraId, entry.getValue()));
        }

        LocalPlayer player = Minecraft.getInstance().player;
        CopiedKeyframes copied = new CopiedKeyframes();
        if (player != null) {
            copied.relativePosition = relativePosition ? new Vector3d(player.getX(), player.getY(), player.getZ()) : null;
            copied.relativeYaw = relativeYaw ? player.getViewYRot(1.0f) : null;
            copied.relativePitch = relativePitch ? player.getViewXRot(1.0f) : null;
        }
        copied.savedTracks = tracks;

        Minecraft.getInstance().keyboardHandler.setClipboard(FlashbackGson.COMPRESSED.toJson(copied));
        ReplayUI.setInfoOverlay(I18n.get("flashback.copied_n_keyframes_to_clipboard", SELECTION.count()));
    }

    private static void pasteKeyframes(Frame f) {
        String clipboard = Minecraft.getInstance().keyboardHandler.getClipboard().trim();
        if (!clipboard.startsWith("{") || !clipboard.endsWith("}")) {
            return;
        }
        try {
            CopiedKeyframes copied = FlashbackGson.COMPRESSED.fromJson(clipboard, CopiedKeyframes.class);
            if (copied == null || copied.savedTracks == null) {
                return;
            }

            KeyframeRelativeOffsets offsets = new KeyframeRelativeOffsets();
            LocalPlayer player = Minecraft.getInstance().player;
            if (player != null) {
                if (copied.relativePosition != null) {
                    offsets.oldOrigin = copied.relativePosition;
                    offsets.newOrigin = new Vector3d(player.getX(), player.getY(), player.getZ());
                }
                if (copied.relativeYaw != null) {
                    offsets.oldYaw = copied.relativeYaw;
                    offsets.newYaw = player.getViewYRot(1.0f);
                }
                if (copied.relativePitch != null) {
                    offsets.oldPitch = copied.relativePitch;
                    offsets.newPitch = player.getViewXRot(1.0f);
                }
            }

            upgradeToWrite();
            int count = 0;
            for (SavedTrack savedTrack : copied.savedTracks) {
                count += savedTrack.applyToScene(scene, f.cursorTicks, f.totalTicks, offsets);
            }
            if (count > 0) {
                ReplayUI.setInfoOverlay(I18n.get("flashback.pasted_n_keyframes_from_clipboard", count));
                editorState.markDirty();
            }
        } catch (Exception ignored) {
            // A clipboard that is not one of ours is simply not pasted.
        }
    }

    // -- Popups ----------------------------------------------------------------------------------

    private static void drawPopups(Frame f) {
        drawInspector(f);
        drawCutMenu(f);
        drawCreateAtTickPopup(f);
        drawRenameCameraPopup();
    }

    private static void drawInspector(Frame f) {
        if (pendingInspector != null) {
            SELECTION.setPrimary(pendingInspector);
            pendingInspector = null;
            inspectorOpen = true;
            openInspectorNow = true;
        }
        if (!inspectorOpen) {
            return;
        }

        TimelineSelection.Ref ref = SELECTION.primary();
        if (ref == null) {
            inspectorOpen = false;
            return;
        }

        ImGui.pushID(System.identityHashCode(ref.track()));
        if (openInspectorNow) {
            // Opened and begun in the same ID scope, so the popup belongs to the keyframe it edits.
            ImGui.openPopup("##KeyframeInspector");
            openInspectorNow = false;
        }
        if (ImGuiHelper.beginPopup("##KeyframeInspector")) {
            drawInspectorContents(f, ref);
            ImGui.endPopup();
        } else {
            inspectorOpen = false;
        }
        ImGui.popID();
    }

    private static void drawInspectorContents(Frame f, TimelineSelection.Ref ref) {
        KeyframeTrack track = ref.track();
        Keyframe keyframe = track.keyframesByTick.get(ref.tick());
        if (keyframe == null) {
            ImGui.closeCurrentPopup();
            inspectorOpen = false;
            return;
        }

        ImGui.textDisabled(displayName(track));
        ImGui.separator();

        keyframe.renderEditKeyframe(update -> {
            upgradeToWrite();
            List<EditorSceneHistoryAction> undo = new ArrayList<>();
            List<EditorSceneHistoryAction> redo = new ArrayList<>();
            int modified = 0;
            for (TimelineSelection.Ref other : SELECTION.refs()) {
                int index = scene.trackIndexOf(other.track());
                Keyframe otherKeyframe = other.track().keyframesByTick.get(other.tick());
                if (index < 0 || otherKeyframe == null || otherKeyframe.getClass() != keyframe.getClass()) {
                    continue;
                }
                modified += 1;
                undo.add(new EditorSceneHistoryAction.SetKeyframe(other.track().keyframeType, index, other.tick(), otherKeyframe.copy()));
                update.accept(otherKeyframe);
                redo.add(new EditorSceneHistoryAction.SetKeyframe(other.track().keyframeType, index, other.tick(), otherKeyframe.copy()));
            }
            if (modified > 0) {
                TimelineEdits.push(scene, editorState, undo, redo, I18n.get("flashback.modified_n_keyframes", modified));
            }
        });

        if (keyframe.keyframeType().allowChangingInterpolationType()) {
            int[] type = {keyframe.interpolationType().ordinal()};
            ImGui.setNextItemWidth(160);
            if (ImGuiHelper.combo(I18n.get("flashback.type"), type, InterpolationType.getNames())) {
                InterpolationType chosen = InterpolationType.INTERPOLATION_TYPES[type[0]];
                applyToSelection(keyframe.getClass(), k -> k.interpolationType(chosen),
                    I18n.get("flashback.changed_interpolation_type_to", chosen.text()));
            }
        }

        if (keyframe.keyframeType().allowChangingTimelineTick()) {
            int[] holder = {ref.tick()};
            ImGui.setNextItemWidth(160);
            ImGuiHelper.inputInt(I18n.get("flashback.tick"), holder);
            if (ImGui.isItemDeactivatedAfterEdit() && holder[0] != ref.tick()) {
                moveSelectedTick(f, ref, holder[0]);
            }
        }

        boolean multiple = SELECTION.count() > 1;

        if (!multiple && keyframe instanceof CameraSwitchKeyframe cut) {
            // Retargeting an existing cut belongs with the cut, so changing your mind does not mean
            // deleting and remaking it.
            EditorCamera live = f.scene.resolveCameraAt(ref.tick());
            ImGui.separator();
            ImGui.textDisabled(I18n.get("flashback.timeline.cut_to"));
            for (EditorCamera camera : f.scene.cameras) {
                int accent = TimelineColours.cameraAccent(f.scene.cameraIndexOf(camera));
                boolean isTarget = cut.cameraId != null && cut.cameraId.equals(camera.id);
                ImGui.pushID("cut" + camera.id);
                if (ImGui.radioButton(f.scene.displayNameOf(camera), isTarget)) {
                    retargetCut(ref, camera);
                }
                ImGui.popID();
                // The same colour the camera's span uses in the lane.
                drawColourChip(accent);
                if (camera == live) {
                    ImGui.sameLine();
                    ImGui.textDisabled(I18n.get("flashback.timeline.live"));
                }
            }
        }
        if (ImGui.button((multiple ? I18n.get("flashback.remove_all") : I18n.get("flashback.remove")) + "##Remove")) {
            ImGui.closeCurrentPopup();
            inspectorOpen = false;
            deleteSelection();
            return;
        }

        if (!multiple && keyframe instanceof CameraKeyframe) {
            ImGui.sameLine();
            if (ImGui.button(I18n.get("flashback.apply") + "##Apply")) {
                applyKeyframe(track, ref.tick());
            }
        }
        if (keyframe instanceof CameraKeyframe || keyframe instanceof CameraOrbitKeyframe) {
            if (ImGui.button(I18n.get("flashback.copy_relative") + "##CopyRelative")) {
                ImGui.openPopup("##CopyRelative");
            }
            if (ImGuiHelper.beginPopup("##CopyRelative")) {
                if (ImGui.checkbox(I18n.get("flashback.copy_relative_to_position") + "##Pos", copyRelativeToPosition)) {
                    copyRelativeToPosition = !copyRelativeToPosition;
                }
                if (ImGui.checkbox(I18n.get("flashback.copy_relative_to_yaw") + "##Yaw", copyRelativeToYaw)) {
                    copyRelativeToYaw = !copyRelativeToYaw;
                }
                if (ImGui.checkbox(I18n.get("flashback.copy_relative_to_pitch") + "##Pitch", copyRelativeToPitch)) {
                    copyRelativeToPitch = !copyRelativeToPitch;
                }
                if (ImGui.button(I18n.get("flashback.do_copy_relative") + "##DoCopy")) {
                    copySelection(copyRelativeToPosition, copyRelativeToYaw, copyRelativeToPitch);
                    ImGui.closeCurrentPopup();
                }
                ImGui.endPopup();
            }
        }
    }

    /**
     * "Which camera should be live from here?" - the one way a cut is made.
     *
     * <p>The camera already live at that moment is marked, so it is obvious what the choice changes.
     */
    private static void drawCutMenu(Frame f) {
        if (pendingCutMenuTrack == null) {
            return;
        }
        KeyframeTrack cutsLane = pendingCutMenuTrack;
        int tick = pendingCutMenuTick;
        pendingCutMenuTrack = null;

        ImGui.pushID("##CutMenuScope");
        if (openCutMenuNow) {
            ImGui.openPopup("##CutMenu");
            openCutMenuNow = false;
        }
        if (ImGuiHelper.beginPopup("##CutMenu")) {
            ImGui.textDisabled(I18n.get("flashback.timeline.cut_at", ticksToTimestamp(tick), tick));
            ImGui.separator();

            if (f.scene.cameras.isEmpty()) {
                ImGui.textDisabled(I18n.get("flashback.timeline.no_cameras"));
            } else {
                EditorCamera live = f.scene.resolveCameraAt(tick);
                for (EditorCamera camera : f.scene.cameras) {
                    int accent = TimelineColours.cameraAccent(f.scene.cameraIndexOf(camera));
                    boolean isLive = camera == live;
                    // The camera already being output is the current state, not a choice: it is shown
                    // ticked and cannot be picked, so a cut can never mean "stay as you are".
                    if (ImGui.menuItem("\ue04b " + f.scene.displayNameOf(camera) + "##cut_" + camera.id,
                            null, isLive, !isLive)) {
                        upgradeToWrite();
                        TimelineEdits.cutToCamera(scene, editorState, camera, tick);
                        ImGui.closeCurrentPopup();
                        inspectorOpen = false;
                    }
                    // The same colour the camera's span uses in the lane.
                    drawColourChip(accent);
                    if (isLive) {
                        ImGui.sameLine();
                        ImGui.textDisabled(I18n.get("flashback.timeline.live"));
                    }
                }
            }
            ImGui.endPopup();
        }
        ImGui.popID();
    }

    private static void drawCreateAtTickPopup(Frame f) {
        if (pendingCreateRow < 0 || pendingCreateRow >= f.layout.size()) {
            return;
        }
        int rowIndex = pendingCreateRow;
        int tick = pendingCreateTick;
        pendingCreateRow = -1;

        KeyframeTrack track = f.layout.trackAt(rowIndex);
        if (track == null) {
            return;
        }
        ImGui.pushID(rowIndex + 60000);
        ImGui.openPopup("##CreateAtTick");
        if (ImGuiHelper.beginPopup("##CreateAtTick")) {
            if (ImGui.menuItem(I18n.get("flashback.create_keyframe_at_n", tick) + "##createAtTick")) {
                createKeyframe(track, tick);
                ImGui.closeCurrentPopup();
            }
            ImGui.endPopup();
        }
        ImGui.popID();
    }

    private static void drawRenameCameraPopup() {
        if (pendingRenameCamera != null && openRenameCameraPopup) {
            ImGui.openPopup("##RenameCamera");
            openRenameCameraPopup = false;
        }
        if (ImGuiHelper.beginPopup("##RenameCamera")) {
            EditorCamera camera = pendingRenameCamera;
            if (camera != null) {
                ImGui.setKeyboardFocusHere();
                ImGui.inputText(I18n.get("flashback.name"), cameraNameString);
                if (ImGui.button(I18n.get("flashback.rename")) || ReplayUI.consumeConfirm()) {
                    String name = ImGuiHelper.getString(cameraNameString).trim();
                    if (!name.isEmpty()) {
                        upgradeToWrite();
                        camera.name = name;
                        for (KeyframeTrack track : scene.tracksOfCamera(camera)) {
                            track.customName = null;
                        }
                        editorState.markDirty();
                    }
                    pendingRenameCamera = null;
                    ImGui.closeCurrentPopup();
                }
                ImGui.sameLine();
                if (ImGui.button(I18n.get("gui.cancel")) || ReplayUI.consumeCancel()) {
                    pendingRenameCamera = null;
                    ImGui.closeCurrentPopup();
                }
            } else {
                ImGui.closeCurrentPopup();
            }
            ImGui.endPopup();
        } else {
            pendingRenameCamera = null;
        }
    }

    // -- Shared plumbing -------------------------------------------------------------------------

    private interface KeyframeEdit {
        void apply(Keyframe keyframe);
    }

    private static void applyToSelection(Class<?> keyframeClass, KeyframeEdit edit, String description) {
        upgradeToWrite();
        List<EditorSceneHistoryAction> undo = new ArrayList<>();
        List<EditorSceneHistoryAction> redo = new ArrayList<>();
        int modified = 0;
        for (TimelineSelection.Ref ref : SELECTION.refs()) {
            int index = scene.trackIndexOf(ref.track());
            Keyframe keyframe = ref.track().keyframesByTick.get(ref.tick());
            if (index < 0 || keyframe == null || keyframe.getClass() != keyframeClass) {
                continue;
            }
            modified += 1;
            undo.add(new EditorSceneHistoryAction.SetKeyframe(ref.track().keyframeType, index, ref.tick(), keyframe.copy()));
            edit.apply(keyframe);
            redo.add(new EditorSceneHistoryAction.SetKeyframe(ref.track().keyframeType, index, ref.tick(), keyframe.copy()));
        }
        if (modified > 0) {
            TimelineEdits.push(scene, editorState, undo, redo, description);
        }
    }

    /** Points an existing cut at a different camera, as one undoable step. */
    private static void retargetCut(TimelineSelection.Ref ref, EditorCamera camera) {
        upgradeToWrite();
        int index = scene.trackIndexOf(ref.track());
        Keyframe existing = ref.track().keyframesByTick.get(ref.tick());
        if (index < 0 || !(existing instanceof CameraSwitchKeyframe cut)) {
            return;
        }
        CameraSwitchKeyframe retargeted = new CameraSwitchKeyframe(camera.id, existing.interpolationType());
        TimelineEdits.push(scene, editorState,
            List.of(new EditorSceneHistoryAction.SetKeyframe(ref.track().keyframeType, index, ref.tick(), cut.copy())),
            List.of(new EditorSceneHistoryAction.SetKeyframe(ref.track().keyframeType, index, ref.tick(), retargeted)),
            I18n.get("flashback.timeline.cut_to"));
    }

    private static void moveSelectedTick(Frame f, TimelineSelection.Ref anchor, int newTick) {
        upgradeToWrite();
        int index = scene.trackIndexOf(anchor.track());
        Keyframe keyframe = anchor.track().keyframesByTick.get(anchor.tick());
        if (index < 0 || keyframe == null) {
            return;
        }
        int clamped = Math.max(0, Math.min(f.totalTicks, newTick));
        TimelineEdits.push(scene, editorState,
            List.of(new EditorSceneHistoryAction.RemoveKeyframe(anchor.track().keyframeType, index, clamped),
                    new EditorSceneHistoryAction.SetKeyframe(anchor.track().keyframeType, index, anchor.tick(), keyframe.copy())),
            List.of(new EditorSceneHistoryAction.RemoveKeyframe(anchor.track().keyframeType, index, anchor.tick()),
                    new EditorSceneHistoryAction.SetKeyframe(anchor.track().keyframeType, index, clamped, keyframe.copy())),
            I18n.get("flashback.moved_n_keyframes", 1));
        SELECTION.replace(anchor.track(), clamped);
    }

    /** Takes the write lock for the scene, releasing the read lock held for this frame. */
    private static void upgradeToWrite() {
        if (!sceneStampIsWrite) {
            editorState.release(sceneStamp);
            sceneStamp = editorState.acquireWrite();
            sceneStampIsWrite = true;
        }
    }

    private static String ticksToTimestamp(int ticks) {
        int seconds = ticks / 20;
        int minutes = seconds / 60;
        int hours = minutes / 60;
        if (hours == 0) {
            return String.format("%02d:%02d", minutes, seconds % 60);
        }
        return String.format("%02d:%02d:%02d", hours, minutes % 60, seconds % 60);
    }

}

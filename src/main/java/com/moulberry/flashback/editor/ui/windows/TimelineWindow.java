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
import com.moulberry.flashback.keyframe.impl.CameraFovKeyframe;
import com.moulberry.flashback.keyframe.impl.CameraKeyframe;
import com.moulberry.flashback.keyframe.impl.CameraOrbitKeyframe;
import com.moulberry.flashback.keyframe.impl.CameraPositionKeyframe;
import com.moulberry.flashback.keyframe.impl.CameraRotationKeyframe;
import com.moulberry.flashback.keyframe.impl.CameraShakeKeyframe;
import com.moulberry.flashback.keyframe.impl.CameraSwitchKeyframe;
import com.moulberry.flashback.keyframe.impl.TimelapseKeyframe;
import com.moulberry.flashback.keyframe.interpolation.InterpolationType;
import com.moulberry.flashback.keyframe.types.CameraFovKeyframeType;
import com.moulberry.flashback.keyframe.types.CameraKeyframeType;
import com.moulberry.flashback.keyframe.types.CameraPositionKeyframeType;
import com.moulberry.flashback.keyframe.types.CameraRotationKeyframeType;
import com.moulberry.flashback.keyframe.types.CameraShakeKeyframeType;
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
import com.moulberry.flashback.state.TimelineCut;
import com.moulberry.flashback.utils.InputHelper;
import imgui.moulberry90.ImDrawList;
import imgui.moulberry90.ImGui;
import imgui.moulberry90.ImGuiIO;
import imgui.moulberry90.ImVec4;
import imgui.moulberry90.flag.ImGuiCol;
import imgui.moulberry90.flag.ImGuiComboFlags;
import imgui.moulberry90.flag.ImGuiHoveredFlags;
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
import java.util.UUID;

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

    /**
     * A stretch cut out of the edit: darkened, hatched and edged so it reads as removed rather than
     * as another selection, and dark enough to stay legible over every camera colour underneath.
     */
    private static final int CUT_FILL = 0x48000000;
    private static final int CUT_HATCH = 0x55FF6060;
    private static final int CUT_EDGE = 0xB0FF7070;

    /** The tick range being marked, in the same amber the keyframe marquee uses. */
    private static final int RANGE_FILL = 0x28DD6000;
    private static final int RANGE_EDGE = 0xFFDD6000;

    private static final float[] REPLAY_TICK_SPEEDS = {1.0f, 2.0f, 4.0f, 10.0f, 20.0f, 40.0f, 100.0f, 200.0f, 400.0f};

    // -- Live state ------------------------------------------------------------------------------

    private static final TimelineSelection SELECTION = new TimelineSelection();

    /**
     * The two ends of the tick range marked on the canvas, or -1 when nothing is marked.
     *
     * <p>Kept apart from {@link #SELECTION}, which is about keyframes: a cut is about a stretch of
     * time, so it has to survive whatever happens to the keys inside it.
     */
    private static int rangeStartTick = -1;
    private static int rangeEndTick = -1;

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
    private static int editingTotalTicks;
    private static int currentCursorTick;
    private static int exportStartTick = -1;
    private static int exportEndTick = -1;
    private static int selectedShotStart = -1;
    private static EditorScene.Shot pendingShotMenu;
    /**
     * The shot the menu is about, kept for as long as the menu is open.
     *
     * <p>ImGui closes a popup that is not begun in a frame, so the menu has to be begun every frame
     * it is up - remembering what it refers to is what makes that possible.
     */
    private static EditorScene.Shot menuShot;
    private static int menuTick;
    /** Whether the shot menu was open last frame, so a tooltip cannot cover it. */
    private static boolean shotMenuShowing;
    /** The tick a cut was requested for, or -1 when the menu is editing an existing shot. */
    private static int menuStartTick = -1;
    /** A removed stretch whose menu was asked for this frame, waiting to be opened while drawing. */
    @Nullable
    private static TimelineCut pendingRegionMenu;
    /**
     * The removed stretch the region menu is about, kept for as long as the menu is open.
     *
     * <p>Like the shot menu, it has to be remembered because ImGui closes a popup that is not begun
     * in a frame, and the edit it offers needs the stretch it was opened on.
     */
    @Nullable
    private static TimelineCut menuRegion;
    private static boolean openRegionMenuNow;
    /**
     * How far the row list is scrolled.
     *
     * <p>Read back from the list itself each frame and used for the layout, because the offset has to
     * be known before the list is begun - hit-testing happens first, and a click has to land on the
     * row that is drawn under it.
     */
    private static double rowScroll;
    /** The scroll range this frame, filled in when the frame is built. */
    private static double rowScrollMax;
    /** Where the scrollbar thumb was grabbed, so dragging it does not make it jump. */
    private static float rowScrollbarGrab;
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
    /** The camera a row menu asked to delete, waiting for the confirmation popup. */
    @Nullable
    private static EditorCamera pendingDeleteCamera;
    private static boolean openDeleteCameraPopup;

    private static boolean copyRelativeToPosition;
    private static boolean copyRelativeToYaw;
    private static boolean copyRelativeToPitch;

    /** Painting the enable toggle by dragging across rows. */
    private static boolean enablePaintActive;
    private static boolean enablePaintValue;

    /**
     * The playhead in replay ticks.
     *
     * <p>The camera inspector keys at this tick, so there is exactly one answer to "where is the
     * playhead" rather than the inspector guessing from the replay server, which during a scrub is a
     * tick behind what is drawn.
     */
    public static int getCursorTick() {
        return cursorTicks;
    }

    /** A tick as the timeline labels it, for the inspector to name the playhead with. */
    public static String formatTick(int tick) {
        return ticksToTimestamp(tick);
    }

    /**
     * Takes the write lock for the scene, releasing the read lock held for this frame.
     *
     * <p>Shared with the camera inspector, which edits the same scene in the same frame: the stamp
     * is passed in and back so each window owns exactly one stamp, and no window can release another
     * one's lock.
     */
    public static long upgradeToWrite(EditorState state, long stamp, boolean alreadyWrite) {
        if (alreadyWrite) {
            return stamp;
        }
        state.release(stamp);
        long writeStamp = state.acquireWrite();
        sceneStamp = writeStamp;
        sceneStampIsWrite = true;
        return writeStamp;
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
        final float uiScale, footerHeight;
        final float keyframeSize, rowHeight, sectionHeight, cutsLaneHeight, buttonSize, indentWidth;
        final float contentY;
        final boolean showScrollbar;

        final float mouseX, mouseY;
        final boolean mouseInLeft, mouseInTimeline, mouseInRows, mouseInRuler, mouseOverTimeline,
            mouseOverRows, mouseOverWindow;

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
              float uiScale, float footerHeight, float keyframeSize, float rowHeight, float sectionHeight, float cutsLaneHeight,
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
            this.footerHeight = footerHeight;
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
            this.mouseOverTimeline = mouseX >= x && mouseX <= x + width
                && mouseY >= y + rulerHeight && mouseY <= y + height - zoomBarHeight;
            this.mouseOverRows = mouseX >= x && mouseX <= x + width
                && mouseY >= y + rulerHeight && mouseY < y + height - footerHeight;
            this.mouseOverWindow = mouseX >= x && mouseX <= x + width
                && mouseY >= y && mouseY <= y + height;
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
            /** Where this cut would land this frame, filled in by the frame builder. */
            int previewTick;

            Keys(KeyframeTrack track, int tick, float anchorX, float anchorY) {
                this.track = track;
                this.tick = tick;
                this.anchorX = anchorX;
                this.anchorY = anchorY;
                this.previewTick = tick;
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

        /** Moving one boundary of a shot, which retimes where the cut happens. */
        static final class CutEdge implements Drag {
            final int cutTick;
            final int grabOffset;
            int target = -1;

            CutEdge(int cutTick, int grabOffset) {
                this.cutTick = cutTick;
                this.grabOffset = grabOffset;
            }
        }

        /** Sliding a whole shot: both of its boundaries move together, its neighbours stretch. */
        static final class ShotBody implements Drag {
            final int startTick;
            final int endTick;
            final int anchorTick;
            /** How far the pointer was from the boundary it grabbed, so the shot does not jump. */
            final int grabOffset;
            int delta = 0;

            ShotBody(int startTick, int endTick, int anchorTick, int grabOffset) {
                this.startTick = startTick;
                this.endTick = endTick;
                this.anchorTick = anchorTick;
                this.grabOffset = grabOffset;
            }
        }

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

        /** Marking a stretch of ticks to cut out, dragged across the empty canvas. */
        final class Range implements Drag {
            final int anchorTick;
            boolean moved;

            Range(int anchorTick) {
                this.anchorTick = anchorTick;
            }
        }

        /**
         * Moving one boundary of a removed stretch.
         *
         * <p>Named apart from the shot lane's {@link CutEdge}, which retimes where the output camera
         * changes: this one changes which ticks are removed from the edit. The whole cut list is
         * carried along because the live result is rebuilt from it each frame, so no other cut moves
         * and none is merged while the boundary is being dragged.
         */
        final class RemovedEdge implements Drag {
            final List<TimelineCut> before;
            final int index;
            final boolean startEdge;
            /** How far the pointer was from the boundary it grabbed, so the boundary does not jump. */
            final int grabOffset;

            RemovedEdge(List<TimelineCut> before, int index, boolean startEdge, int grabOffset) {
                this.before = before;
                this.index = index;
                this.startEdge = startEdge;
                this.grabOffset = grabOffset;
            }
        }
    }

    /** Which boundary of which removed stretch the pointer is on. */
    private record CutEdgeHit(int index, boolean startEdge) {}

    /** What a row asked for, carried out once every row has been drawn. */
    private sealed interface RowAction {
        record DeleteTrack(KeyframeTrack track) implements RowAction {}
        record ClearTrack(KeyframeTrack track) implements RowAction {}
        record DeleteCamera(EditorCamera camera) implements RowAction {}
        record DuplicateCamera(EditorCamera camera) implements RowAction {}
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
        float indentWidth = ReplayUI.scaleUi(20);

        // The row list is as wide as the user last made it, within limits that keep the timeline
        // usable on any window: never less than a readable column, never more than most of the view.
        float leftWidth = TimelineLayout.panelWidth(editorState.timelinePanelWidth, width, uiScale,
            ReplayUI.scaleUi(140));

        TimelineLayout layout = TimelineLayout.build(scene, new TimelineLayout.Metrics(
            rowHeight, sectionHeight, cutsLaneHeight, ReplayUI.scaleUi(6)));

        float footerHeight = ImGui.getFrameHeight() + ReplayUI.scaleUi(8);
        float scrollbar = ImGui.getStyle().getScrollbarSize() - 1;
        float rowsHeight = Math.max(40, height - rulerHeight - footerHeight);
        float contentHeight = layout.bottom(0) + ReplayUI.scaleUi(6);
        boolean showScrollbar = contentHeight > rowsHeight;
        float maxScroll = Math.max(0, contentHeight - rowsHeight);
        rowScroll = Math.max(0, Math.min(maxScroll, rowScroll));

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

        // The magnet and the shot hit-testing need a few facts about this frame; keeping them in one
        // place is what lets every drag snap to the same things.
        rowScrollMax = maxScroll;
        editingTotalTicks = totalTicks;
        currentCursorTick = cursorTicks;
        exportStartTick = scene.exportStartTicks;
        exportEndTick = scene.exportEndTicks;

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

        float contentY = y + rulerHeight - (float) rowScroll;

        // -- The switch lane's spans: computed once so drawing and hit-testing share them --
        // While a boundary or a whole shot is being dragged, the lane is drawn as it would look if
        // the drag were committed, so the edit is visible before it is made.
        Drag.Keys draggedKeys = drag instanceof Drag.Keys keys && KeyframeTrack.isCameraSwitch(keys.track) ? keys : null;
        Drag.CutEdge draggedEdge = drag instanceof Drag.CutEdge edge ? edge : null;
        Drag.ShotBody draggedBody = drag instanceof Drag.ShotBody body ? body : null;
        KeyframeTrack switchTrack = scene.cameraSwitchTrack();
        boolean cutsLaneEnabled = switchTrack != null && switchTrack.enabled;

        if (draggedKeys != null) {
            draggedKeys.previewTick = clampTick(draggedKeys.tick + snappedDelta(scale, mouseX, draggedKeys.tick, switchTrack));
        }
        if (draggedEdge != null) {
            int wanted = scale.tickAtX(mouseX) - draggedEdge.grabOffset;
            draggedEdge.target = snapDragTick(scale, switchTrack, wanted, draggedEdge.cutTick);
        }
        if (draggedBody != null) {
            int anchorTarget = scale.tickAtX(mouseX) - draggedBody.grabOffset;
            int want = snapDragTick(scale, switchTrack, anchorTarget, draggedBody.anchorTick) - draggedBody.anchorTick;
            // The shot may not be pushed past either end of the replay.
            int lowest = -draggedBody.startTick;
            int highest = totalTicks - draggedBody.endTick;
            if (draggedBody.startTick == 0) {
                // A shot that starts the replay has no boundary to move, so it can only grow.
                lowest = 0;
            }
            draggedBody.delta = Math.max(lowest, Math.min(highest, want));
        }

        EditorScene.CutRetime retime = null;
        if (draggedKeys != null) {
            retime = tick -> draggedKeys.tick == tick ? draggedKeys.previewTick : tick;
        } else if (draggedEdge != null) {
            retime = tick -> draggedEdge.cutTick == tick ? draggedEdge.target : tick;
        } else if (draggedBody != null) {
            retime = tick -> draggedBody.startTick == tick || draggedBody.endTick == tick
                ? tick + draggedBody.delta : tick;
        }

        List<SwitchSegment> segments = new ArrayList<>();
        List<SwitchCut> cuts = new ArrayList<>();
        if (switchTrack != null && !scene.cameras.isEmpty()) {
            for (EditorScene.Shot shot : scene.shots(totalTicks, retime)) {
                segments.add(new SwitchSegment(shot.startTick(), shot.endTick(), shot.camera(),
                    scene.cameraIndexOf(shot.camera())));
            }
            for (Map.Entry<Integer, Keyframe> entry : switchTrack.keyframesByTick.entrySet()) {
                if (!(entry.getValue() instanceof CameraSwitchKeyframe cut)) {
                    continue;
                }
                EditorCamera camera = scene.resolveCamera(cut.cameraId);
                if (camera == null) {
                    continue;
                }
                int cutTick = retime == null ? entry.getKey() : retime.retime(entry.getKey());
                cuts.add(new SwitchCut(Math.max(0, Math.min(totalTicks, cutTick)), camera,
                    scene.cameraIndexOf(camera)));
            }
            cuts.sort(java.util.Comparator.comparingInt(SwitchCut::tick));
        }

        return new Frame(replayServer, metadata, editorState, scene, layout, x, y, width, height, leftWidth,
            rulerHeight, minorSeparatorHeight, timestampHeight, uiScale, footerHeight, keyframeSize, rowHeight,
            sectionHeight, cutsLaneHeight,
            buttonSize, indentWidth, contentY, showScrollbar, mouseX, mouseY, scale, cursorTicks, currentReplayTick,
            totalTicks, minTicks, availableTicks, timelineWidth, timelineLeftOffset, minorsPerMajor, ticksPerMinor,
            showSubSeconds, minorSeparatorWidth, errorOffset, zoomBarHeight, zoomBarMin, zoomBarMax, zoomBarExpanded,
            controlSize, controlsY, skipBackwardsX, slowDownX, pauseX, fastForwardsX, skipForwardsX,
            segments, cuts, cutsLaneEnabled);
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
        // The list is positioned by our own scroll offset rather than ImGui's, because ImGui hides
        // the wheel it consumes, which would leave nothing for zooming and panning to read.
        int flags = ImGuiWindowFlags.NoScrollbar | ImGuiWindowFlags.NoScrollWithMouse;

        // The list ends above the footer rather than under it, so a row is never half covered.
        ImGui.beginChild("##TimelineRows", 0, Math.max(40, f.height - f.rulerHeight - f.footerHeight),
            false, flags);
        try {
            ImDrawList drawList = ImGui.getWindowDrawList();
            drawTreeGuides(f, drawList);
            drawLeftPanel(f, drawList);

            // Drawn before the canvas clip so the marker is visible in both panels.
            drawInsertionIndicator(f, drawList);
            drawEmptyState(f, drawList);

            drawList.pushClipRect(f.timelineLeft() + 1, f.y + f.rulerHeight, f.timelineRight(),
                f.y + f.height - f.footerHeight, true);
            drawSwitchBand(f, drawList);
            drawCutRegions(f, drawList);
            showCutEdgeCursor(f);
            drawKeyframeRows(f, drawList);
            drawRangeSelection(f, drawList);
            drawMarquee(f, drawList);
            drawList.popClipRect();

            // Room to scroll past the last row, so it never ends up half under the footer.
            ImGui.setCursorScreenPos(f.x, f.layout.bottom(f.contentY));
            ImGui.dummy(1, f.footerHeight + 4);


        } finally {
            ImGui.endChild();
        }
        // Outside the scrolling child, so the controls are always reachable however long the list is.
        drawFooter(f);
    }

    /**
     * The tree lines that show which tracks belong to which camera.
     *
     * <p>A camera and its tracks are otherwise told apart only by a tint, which is not something you
     * can read at a glance; a guide line says it plainly.
     */
    private static void drawTreeGuides(Frame f, ImDrawList drawList) {
        TimelineRow.CameraGroup open = null;
        float guideX = 0;
        for (int i = 0; i < f.layout.size(); i++) {
            TimelineRow row = f.layout.row(i);
            if (row instanceof TimelineRow.CameraGroup group) {
                if (open != null) {
                    drawGuide(drawList, f, open, i - 1, guideX);
                }
                open = group.camera().collapsed ? null : group;
                guideX = f.x + 8 + f.buttonSize / 2;
            } else if (open != null && !(row instanceof TimelineRow.Track track && track.owner() == open.camera())) {
                drawGuide(drawList, f, open, i - 1, guideX);
                open = null;
            }
        }
        if (open != null) {
            drawGuide(drawList, f, open, f.layout.size() - 1, guideX);
        }
    }

    private static void drawGuide(ImDrawList drawList, Frame f, TimelineRow.CameraGroup group, int lastRow, float x) {
        int first = f.layout.rowOfTrack(null);
        int groupRow = -1;
        for (int i = 0; i < f.layout.size(); i++) {
            if (f.layout.row(i) == group) {
                groupRow = i;
                break;
            }
        }
        if (groupRow < 0 || lastRow <= groupRow) {
            return;
        }
        int accent = TimelineColours.cameraAccent(f.scene.cameraIndexOf(group.camera()));
        drawList.addLine(x, f.rowBottom(groupRow) - 2, x, f.rowBottom(lastRow) - 4,
            TimelineColours.alpha(accent, 0x70), 1f);
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
        drawRowScrollbar(f, drawList);
        drawSplitterHandle(f, drawList);
        drawFooter(f);
    }

    /**
     * The row list's scrollbar, next to the rows it scrolls rather than at the far edge of the window.
     *
     * <p>It is drawn and dragged by hand because the list's scroll offset is ours: ImGui's own
     * scrollbar would move the list without the timeline noticing.
     */
    private static void drawRowScrollbar(Frame f, ImDrawList drawList) {
        if (!f.showScrollbar || rowScrollMax <= 0) {
            return;
        }
        float top = f.y + f.rulerHeight;
        float bottom = f.y + f.height - f.footerHeight;
        float trackHeight = bottom - top;
        if (trackHeight <= 8) {
            return;
        }

        float viewHeight = trackHeight;
        float contentHeight = (float) rowScrollMax + viewHeight;
        float thumbHeight = Math.max(ReplayUI.scaleUi(24), trackHeight * (viewHeight / contentHeight));
        float travel = Math.max(1, trackHeight - thumbHeight);
        float thumbY = top + (float) (travel * (rowScroll / rowScrollMax));
        float x0 = f.x + f.leftWidth - 5;
        float x1 = f.x + f.leftWidth - 1;

        ImGui.setCursorScreenPos(x0 - 2, top);
        ImGui.invisibleButton("##rowsScrollbar", (x1 - x0) + 3, trackHeight);
        if (ImGui.isItemActivated()) {
            // Grabbing the thumb keeps the pointer where it was; grabbing the track centres it.
            rowScrollbarGrab = f.mouseY >= thumbY && f.mouseY <= thumbY + thumbHeight
                ? f.mouseY - thumbY : thumbHeight / 2;
        }
        if (ImGui.isItemActive()) {
            double wanted = (f.mouseY - top - rowScrollbarGrab) / travel * rowScrollMax;
            rowScroll = Math.max(0, Math.min(rowScrollMax, wanted));
            ImGui.setMouseCursor(ImGuiMouseCursor.Hand);
        }

        boolean active = ImGui.isItemHovered() || ImGui.isItemActive();
        drawList.addRectFilled(x0, top, x1, bottom, TimelineColours.ROW_DIVIDER, 3f);
        drawList.addRectFilled(x0, thumbY, x1, Math.min(bottom, thumbY + thumbHeight),
            active ? 0xB0FFFFFF : 0x70FFFFFF, 3f);
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
            if (CameraInspectorWindow.isSelected(group.camera())) {
                // Object selection, drawn like every other selection on the timeline but inset so it
                // reads as "this object" rather than as a marked keyframe or a hovering pointer.
                drawList.addRect(left + 1, top + 1, right - 1, bottom - 1, SELECTED_OUTLINE);
            }
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
        float y = top + height / 2 - ImGui.getTextLineHeight() / 2f;

        if (section.kind() != TimelineRow.Section.Kind.CAMERAS || f.scene.cameras.isEmpty()) {
            ImGui.setCursorScreenPos(f.x + 10, y);
            ImGui.textColored(TimelineColours.SECTION_TEXT, label);
            return null;
        }

        // The heading doubles as collapse-all: with a lot of cameras, folding them all away is the
        // difference between seeing the whole edit and seeing three rows of it.
        boolean anyExpanded = f.scene.cameras.stream().anyMatch(camera -> !camera.collapsed);
        ImGui.setCursorScreenPos(f.x + 6, top + 1);
        if (ImGui.invisibleButton("##collapseAll", ImGui.getTextLineHeight() + 4, height - 2)) {
            for (EditorCamera camera : f.scene.cameras) {
                camera.collapsed = anyExpanded;
            }
            if (anyExpanded) {
                SELECTION.clear();
                inspectorOpen = false;
            }
            editorState.markDirty();
        }
        boolean hovered = ImGui.isItemHovered();
        ImGui.getWindowDrawList().addText(f.x + 10, y,
            hovered ? TimelineColours.TEXT : TimelineColours.SECTION_TEXT,
            (anyExpanded ? "\ue313  " : "\ue315  ") + label);
        ImGuiHelper.tooltip(I18n.get(anyExpanded ? "flashback.timeline.collapse_all"
            : "flashback.timeline.expand_all"));
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
        String kindIcon = switch (camera.kind) {
            case FREE -> "\ue04b ";
            case SPECTATE -> "\ue7fd ";
            case ORBIT -> "\ue577 ";
        };
        ImGui.setCursorScreenPos(cursorX, top + f.layout.rowHeight(rowIndex) / 2 - ImGui.getTextLineHeight() / 2f);
        ImDrawList drawList = ImGui.getWindowDrawList();
        drawList.pushClipRect(cursorX - 2, top, Math.max(cursorX, nameRight), bottom, true);
        ImGui.textColored(accent, kindIcon + name);
        drawList.popClipRect();
        // A single click on the row's name selects the camera object and opens its inspector; a
        // double click is still rename, so the two gestures do not fight over the same item.
        if (ImGui.isItemClicked(ImGuiMouseButton.Left)) {
            CameraInspectorWindow.select(camera);
            if (ImGui.isMouseDoubleClicked(ImGuiMouseButton.Left)) {
                action = new RowAction.RenameCamera(camera);
            }
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
            if (ImGui.menuItem("\ue14d " + I18n.get("flashback.duplicate") + "##duplicateCamera")) {
                action = new RowAction.DuplicateCamera(camera);
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
            float nameRight = f.x + f.leftWidth - ROW_BUTTON_MARGIN - rowButtonsWidth(f, 3) - 6;
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

        float buttonsX = f.x + f.leftWidth - ROW_BUTTON_MARGIN - rowButtonsWidth(f, 3);
        ImGui.setCursorScreenPos(buttonsX, buttonY);

        // One control in one place on every lane: it inserts this lane's item at the playhead. On a
        // camera lane that means asking which camera should be live from here, which is the reliable
        // way to cut - the band is for adjusting shots that already exist.
        if (ImGui.invisibleButton("##insertKey", f.buttonSize, f.buttonSize)) {
            if (cutsLane) {
                requestCutMenu(f.cursorTicks);
            } else {
                action = new RowAction.CreateKeyframe(track, f.cursorTicks);
            }
        }
        if (cutsLane) {
            ImGui.getWindowDrawList().addText(ImGui.getItemRectMinX() + 1, ImGui.getItemRectMinY(),
                track.enabled ? TimelineColours.TEXT : TimelineColours.TEXT_DIM, "\ue14e");
        } else {
            drawKeyframeButton(ImGui.getWindowDrawList(), ImGui.getItemRectMinX(), ImGui.getItemRectMinY(),
                f.buttonSize, track.enabled ? TimelineColours.TEXT : TimelineColours.TEXT_DIM);
        }
        ImGuiHelper.tooltip(I18n.get(cutsLane ? "flashback.timeline.cut_playhead_hint"
            : "flashback.timeline.insert_keyframe_at", ticksToTimestamp(f.cursorTicks), f.cursorTicks));
        ImGui.sameLine();

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
            // The camera's own row offers the whole object's actions, delete included: the camera and
            // its rows are one thing, so the way to remove it has to be on the row that names it.
            if (ImGui.menuItem("\ue3c9 " + I18n.get("flashback.rename") + "##rowRename")) {
                return new RowAction.RenameCamera(group.camera());
            }
            if (ImGui.menuItem("\ue14d " + I18n.get("flashback.duplicate") + "##rowDuplicate")) {
                return new RowAction.DuplicateCamera(group.camera());
            }
            ImGui.separator();
            if (ImGui.menuItem("\ue872 " + I18n.get("flashback.delete_camera") + "##rowDeleteCamera")) {
                return new RowAction.DeleteCamera(group.camera());
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
        sceneStamp = upgradeToWrite(editorState, sceneStamp, sceneStampIsWrite);
        switch (action) {
            case RowAction.DeleteTrack delete -> TimelineEdits.deleteTrack(scene, editorState, delete.track());
            case RowAction.ClearTrack clear -> TimelineEdits.clearTrack(scene, editorState, clear.track());
            case RowAction.DeleteCamera delete -> {
                // The same confirmation the inspector's header uses: deleting a camera takes all of
                // its rows and keyframes with it, so it is never one menu click away from happening.
                pendingDeleteCamera = delete.camera();
                openDeleteCameraPopup = true;
            }
            case RowAction.DuplicateCamera duplicate -> {
                EditorCamera copy = duplicateCamera(scene, editorState, duplicate.camera());
                if (copy != null) {
                    CameraInspectorWindow.select(copy);
                }
            }
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
        // Pinned to the bottom of the window rather than the end of the list, on its own bar so the
        // rows scrolled underneath it do not show through.
        float top = f.y + f.height - f.footerHeight;
        ImGui.getWindowDrawList().addRectFilled(f.x, top, f.x + f.width, f.y + f.height,
            ImGui.getColorU32(ImGuiCol.WindowBg));
        ImGui.getWindowDrawList().addLine(f.x, top, f.x + f.width, top, TimelineColours.PANEL_DIVIDER);
        ImGui.setCursorScreenPos(f.x + 8, top + 3);
        if (ImGui.smallButton(I18n.get("flashback.add_element") + "##AddElement")) {
            ImGui.openPopup("##AddElement");
        }
        ImGui.sameLine();
        drawCutButton();
        ImGui.sameLine();
        drawSceneSwitcher(f);

        if (ImGuiHelper.beginPopup("##AddElement")) {
            if (ImGui.menuItem("\ue04b " + I18n.get("flashback.new_camera") + "##addCamera")) {
                sceneStamp = upgradeToWrite(editorState, sceneStamp, sceneStampIsWrite);
                TimelineEdits.addCamera(scene, editorState, EditorCamera.Kind.FREE, f.cursorTicks);
                ImGui.closeCurrentPopup();
            }
            if (ImGui.menuItem("\ue577 " + I18n.get("flashback.new_orbit_camera") + "##addOrbitCamera")) {
                sceneStamp = upgradeToWrite(editorState, sceneStamp, sceneStampIsWrite);
                TimelineEdits.addCamera(scene, editorState, EditorCamera.Kind.ORBIT, f.cursorTicks);
                ImGui.closeCurrentPopup();
            }
            if (ImGui.menuItem("\ue7fd " + I18n.get("flashback.new_spectate_camera") + "##addSpectateCamera")) {
                sceneStamp = upgradeToWrite(editorState, sceneStamp, sceneStampIsWrite);
                TimelineEdits.addCamera(scene, editorState, EditorCamera.Kind.SPECTATE, f.cursorTicks);
                ImGui.closeCurrentPopup();
            }
            // Duplicating copies what you are working on: the selected camera if there is one, and
            // otherwise whichever camera is being output at the playhead.
            UUID selectedId = CameraInspectorWindow.selectedCameraId();
            EditorCamera duplicateSource = selectedId != null ? scene.cameraById(selectedId) : scene.resolveCameraAt(f.cursorTicks);
            if (ImGui.menuItem("\ue14d " + I18n.get("flashback.duplicate_camera") + "##duplicateCameraFromAdd",
                    null, false, duplicateSource != null)) {
                if (duplicateSource != null) {
                    EditorCamera copy = duplicateCamera(scene, editorState, duplicateSource);
                    if (copy != null) {
                        CameraInspectorWindow.select(copy);
                    }
                }
                ImGui.closeCurrentPopup();
            }
            ImGui.separator();
            for (KeyframeType<?> type : KeyframeRegistry.getTypes()) {
                if (!type.canBeCreatedNormally() || EditorScene.isCameraScoped(type)) {
                    // A camera-owned type belongs to a camera, so it is added from the camera's menu.
                    continue;
                }
                if (ImGui.selectable(type.name() + "##addSceneTrack")) {
                    sceneStamp = upgradeToWrite(editorState, sceneStamp, sceneStampIsWrite);
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

    /**
     * The one control for taking a stretch of ticks out of the edit or putting it back.
     *
     * <p>Its label follows the selection: over kept ticks it removes them, and over a stretch that is
     * already gone it restores the part it covers, so the same control undoes itself. With nothing
     * marked there is nothing to act on, so it is disabled rather than hidden - a control that comes
     * and goes is harder to find than one that is plainly unavailable - and the hint still explains
     * what it is for while it waits.
     */
    private static void drawCutButton() {
        boolean marked = hasRangeSelection();
        boolean restore = marked && cutContainingRange() != null;
        ImGui.beginDisabled(!marked);
        if (ImGui.smallButton(I18n.get(restore ? "flashback.timeline.restore_cut"
                : "flashback.timeline.cut_out") + "##CutOut")) {
            applyRangeCut();
        }
        ImGui.endDisabled();
        ImGuiHelper.tooltip(I18n.get("flashback.timeline.cut_hint"), ImGuiHoveredFlags.AllowWhenDisabled);
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
                    sceneStamp = upgradeToWrite(editorState, sceneStamp, sceneStampIsWrite);
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
                    sceneStamp = upgradeToWrite(editorState, sceneStamp, sceneStampIsWrite);
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
                    sceneStamp = upgradeToWrite(editorState, sceneStamp, sceneStampIsWrite);
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
                    sceneStamp = upgradeToWrite(editorState, sceneStamp, sceneStampIsWrite);
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
    /**
     * The programme lane, drawn as shots: contiguous blocks, each in its camera's colour, with a
     * boundary mark where one shot gives way to the next.
     *
     * <p>Everything about how it looks says what it does - a block is a stretch of time that belongs
     * to a camera, the highlighted edge is the one you can move, and the white outline is the shot
     * the inspector is describing.
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

        ShotHit hover = f.mouseOverCutsLane() && !(drag instanceof Drag.CutEdge) && !(drag instanceof Drag.ShotBody)
            ? shotHitTest(f, cutsLane) : null;
        boolean laneUsable = f.cutsLaneEnabled && !f.scene.cameras.isEmpty();

        if (f.scene.cameras.isEmpty()) {
            drawList.addRectFilled(left, top + 3, right, bottom - 3, TimelineColours.CUTS_LANE_BACKGROUND);
            String hint = I18n.get("flashback.timeline.no_cameras");
            float width = ImGuiHelper.calcTextWidth(hint);
            if (right - left > width + 16) {
                drawList.addText(left + (right - left - width) / 2, midY - ImGui.getTextLineHeight() / 2f,
                    TimelineColours.TEXT_DIM, hint);
            }
            return;
        }

        for (SwitchSegment segment : f.switchSegments) {
            float from = Math.max(left, f.xOfTick(segment.fromTick()));
            float to = Math.min(right, f.xOfTick(segment.toTick()));
            if (to <= from) {
                continue;
            }
            int accent = TimelineColours.cameraAccent(segment.cameraIndex());
            if (to - from < 4) {
                // Narrower than its own border: draw it as the boundary it is, not as a gap.
                drawList.addRectFilled(from - 1, top + 3, to + 1, bottom - 3, TimelineColours.alpha(accent, 0xFF));
                continue;
            }
            int baseAlpha = laneUsable ? 0x3A : 0x16;
            boolean hoveredShot = hover != null && hover.shot().camera() == segment.camera()
                && hover.shot().startTick() == segment.fromTick();
            boolean selectedShot = selectedShotStart == segment.fromTick();

            drawList.addRectFilled(from, top + 3, to, bottom - 3,
                TimelineColours.alpha(accent, hoveredShot || selectedShot ? 0x66 : baseAlpha));
            // The camera's colour along the top is the shot's identity at a glance.
            drawList.addRectFilled(from, top + 3, to, top + 7, TimelineColours.alpha(accent, laneUsable ? 0xE0 : 0x60));

            String name = f.scene.displayNameOf(segment.camera());
            float textWidth = ImGuiHelper.calcTextWidth(name);
            if (to - from > textWidth + 16) {
                float nameX = from + (to - from - textWidth) / 2;
                float nameY = midY - ImGui.getTextLineHeight() / 2f;
                drawList.pushClipRect(from + 4, top, to - 4, bottom, true);
                // White on every camera colour, with a shadow so it stays readable on the lighter
                // accents - the shot name is the one label that must never be ambiguous.
                drawList.addText(nameX + 1, nameY + 1, 0xA0000000, name);
                drawList.addText(nameX, nameY, TimelineColours.TEXT, name);
                drawList.popClipRect();
            }

            // The boundary that starts this shot is drawn as a hard edge: that is where the cut is.
            if (segment.fromTick() > 0 && from > left) {
                boolean edgeHovered = hover != null && hover.edgeTick() == segment.fromTick();
                float edgeWidth = edgeHovered ? 3f : 2f;
                drawList.addRectFilled(from - edgeWidth / 2, top + 1, from + edgeWidth / 2, bottom - 1,
                    edgeHovered ? TimelineColours.SELECTED : 0xFFFFFFFF);
                if (edgeHovered) {
                    // Grips on the edge, so it reads as something that can be dragged.
                    drawList.addRectFilled(from - 5, midY - 1, from + 5, midY + 1, TimelineColours.SELECTED);
                }
            }
            if (selectedShot) {
                drawList.addRect(from, top + 2, to, bottom - 2, TimelineColours.SELECTED);
            }
        }

        // ImGui draws tooltips above popups, so an open menu must suppress the hover tooltip or the
        // two cover each other at the same pointer position.
        if (hover != null && !openCutMenuNow && !shotMenuShowing) {
            EditorScene.Shot shot = hover.shot();
            String what = I18n.get("flashback.timeline.shot_range",
                ticksToTimestamp(shot.startTick()), ticksToTimestamp(shot.endTick()));
            String hint = hover.edgeTick() >= 0
                ? I18n.get("flashback.timeline.shot_hint")
                : I18n.get("flashback.timeline.shot_hint");
            ImGuiHelper.drawTooltip(f.scene.displayNameOf(shot.camera()) + "\n" + what
                + "  (" + I18n.get("flashback.timeline.shot_duration",
                    ticksToTimestamp(shot.duration())) + ")"
                + "\n" + hint);
        }

        // What the magnet lined up with, shown where it happened.
        if (snapIndicatorTick >= 0 && (drag instanceof Drag.CutEdge || drag instanceof Drag.ShotBody
                || drag instanceof Drag.Keys)) {
            float x = f.xOfTick(snapIndicatorTick);
            drawList.addLine(x, top - 6, x, bottom + 6, TimelineColours.DROP_INDICATOR, 2f);
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
                int colour = keyframeColour(f, track, keyframe);
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

    private static int keyframeColour(Frame f, KeyframeTrack track, Keyframe keyframe) {
        if (track.keyframeType == TimelapseKeyframeType.INSTANCE && track.keyframesByTick.size() == 1) {
            return 0xFF155FFF;
        }
        if (track.customColour != 0) {
            return track.customColour;
        }
        if (track.cameraId != null) {
            // A keyframe on a camera's own track belongs to that camera, so it carries its colour.
            EditorCamera owner = f.scene.resolveCamera(track.cameraId);
            if (owner != null) {
                return TimelineColours.cameraAccent(f.scene.cameraIndexOf(owner));
            }
        }
        return -1;
    }

    /**
     * A timelapse occupies a stretch of time, so it is drawn as a bar with two ends rather than as
     * two unrelated points: the length of the bar is the length of the timelapse, and its ends are
     * the keyframes you drag to change it.
     */
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
        boolean invalid = tickDelta <= 0;
        String length = invalid ? I18n.get("flashback.invalid").toUpperCase(Locale.ROOT)
            : Utils.timeInTicksToString(tickDelta);
        int colour = track.customColour != 0 ? track.customColour : 0xFF9CCC65;
        if (!track.enabled) {
            colour = TimelineColours.alpha(colour, 0x60);
        }

        float leftX = f.xOfTick(floorEntry.getKey());
        float rightX = f.xOfTick(entry.getKey());
        float barHeight = Math.max(6f, f.rowHeight * 0.42f);
        float barLeft = leftX + f.keyframeSize;
        float barRight = rightX - f.keyframeSize;
        if (barRight <= barLeft) {
            return;
        }

        drawList.addRectFilled(barLeft, midY - barHeight / 2, barRight, midY + barHeight / 2,
            TimelineColours.alpha(colour, invalid ? 0x30 : 0x4A), 3f);
        drawList.addRect(barLeft, midY - barHeight / 2, barRight, midY + barHeight / 2,
            TimelineColours.alpha(colour, invalid ? 0x80 : 0xC0), 3f);

        String text = I18n.get("flashback.select_replay.duration", length);
        float textWidth = ImGuiHelper.calcTextWidth(text);
        if (textWidth > barRight - barLeft - 6) {
            text = length;
            textWidth = ImGuiHelper.calcTextWidth(text);
        }
        if (textWidth <= barRight - barLeft - 6) {
            drawList.addText((barLeft + barRight - textWidth) / 2, midY - ImGui.getTextLineHeight() / 2f,
                invalid ? 0xFFFF8080 : TimelineColours.TEXT, text);
        }
    }

    /**
     * The stretches that have been cut out, drawn as one region across the whole canvas.
     *
     * <p>A cut is about the timeline rather than one lane, so it is filled from the ruler to the
     * footer and darkened rather than tinted: the material underneath is still there to be read,
     * which is what keeps a keyframe inside a cut findable while the diagonal hatch makes it
     * unmistakable that the stretch is removed. A plain tint would read as another selection, so the
     * hatch and the hard edges are the whole point - they say exactly which ticks are gone.
     */
    private static void drawCutRegions(Frame f, ImDrawList drawList) {
        List<TimelineCut> cuts = f.state.normalisedCuts();
        if (cuts.isEmpty()) {
            return;
        }
        float top = f.y + f.rulerHeight;
        float bottom = f.y + f.height - f.footerHeight;
        float height = bottom - top;
        // Never zero: this is a loop step, and a UI scale of zero would make the hatch loop forever.
        float hatchSpacing = Math.max(1, ReplayUI.scaleUi(7));
        for (TimelineCut cut : cuts) {
            float from = Math.max(f.timelineLeft() + 1, f.xOfTick(cut.start));
            float to = Math.min(f.timelineRight(), f.xOfTick(cut.end));
            if (to <= from) {
                // Narrower than a pixel at this zoom: there is no room to show the hatch.
                continue;
            }
            drawList.addRectFilled(from, top, to, bottom, CUT_FILL);
            // The hatch is clipped to the cut so the lines stop at its edges instead of spilling
            // into kept time, where they would imply it was removed too.
            drawList.pushClipRect(from, top, to, bottom, true);
            for (float x = from - height; x < to; x += hatchSpacing) {
                drawList.addLine(x, bottom, x + height, top, CUT_HATCH, 1f);
            }
            drawList.popClipRect();
            drawList.addLine(from, top, from, bottom, CUT_EDGE, 1f);
            drawList.addLine(to, top, to, bottom, CUT_EDGE, 1f);
        }
    }

    /**
     * The tick range marked on the canvas, in the same amber the keyframe marquee uses so a drag
     * reads the same wherever it lands.
     *
     * <p>It spans every lane for the same reason a cut does: the selection is a stretch of time
     * rather than a set of rows, and the button that acts on it says so.
     */
    private static void drawRangeSelection(Frame f, ImDrawList drawList) {
        if (!hasRangeSelection()) {
            return;
        }
        float from = f.xOfTick(rangeMinTick());
        float to = f.xOfTick(rangeMaxTick());
        float top = f.y + f.rulerHeight;
        float bottom = f.y + f.height - f.footerHeight;
        drawList.addRectFilled(from, top, to, bottom, RANGE_FILL);
        drawList.addLine(from, top, from, bottom, RANGE_EDGE, 2f);
        drawList.addLine(to, top, to, bottom, RANGE_EDGE, 2f);
    }

    /**
     * Shows that a removed stretch's boundary can be dragged, before it is actually grabbed.
     *
     * <p>The file already gives the export handles in the ruler this cue, so the same one is used
     * here. A keyframe sitting on the boundary suppresses it, because there the press would grab the
     * key instead, and the shot lane suppresses it because that lane has edges of its own.
     */
    private static void showCutEdgeCursor(Frame f) {
        if (drag != null || !f.mouseInTimeline || !f.mouseOverRows || f.mouseOverCutsLane()) {
            return;
        }
        KeyframeTrack track = f.layout.trackAt(f.rowAt(f.mouseY));
        if (track != null && !KeyframeTrack.isCameraSwitch(track) && keyframeAt(f, track) >= 0) {
            return;
        }
        if (cutEdgeAt(f) != null) {
            ImGui.setMouseCursor(ImGuiMouseCursor.ResizeEW);
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

        // A right-click on the canvas gives up the range selection. It may also open a keyframe or
        // shot menu; both mean "start again", so the range should not outlive either.
        if (right && f.mouseInTimeline) {
            clearRangeSelection();
        }

        // Ctrl/Cmd on the ruler marks a stretch of time instead of moving the playhead. The
        // timestamps are the one place time is marked: dragging the keyframes below stays the
        // keyframe box-select, so nothing in the row area can arm a cut by accident. Decided here,
        // ahead of the replay-marker and export-handle clicks a plain ruler click can mean, so the
        // modifier does the same thing wherever the pointer is on the ruler.
        //
        // The key state is read raw rather than from ImGui: ImGui only learns about a modifier when
        // a key event tells it, and holding Command and then pressing the mouse sends no such event,
        // so a Command-drag can see a stale "not held". The keybinds already read it this way, which
        // is why Ctrl+Z works where this gesture did not.
        if (left && isShortcutModifierDown() && f.mouseInRuler) {
            beginRange(f);
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
            seek(f, previousMarker != null ? previousMarker : 0);
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
            seek(f, nextMarker != null ? nextMarker : f.totalTicks);
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
        seek(f, f.tickAtX(f.mouseX));
        drag = new Drag.Head();
    }

    private static void handleCanvasClick(Frame f, boolean left, boolean right) {
        // Pressing on the timeline gives up a marked stretch, so a mark can be dismissed by clicking
        // the same place again instead of only from the keyboard. Marking is done on the ruler, so
        // nothing here can start one by accident.
        if (left && hasRangeSelection()) {
            clearRangeSelection();
        }
        selectedShotStart = -1;
        int rowIndex = f.rowAt(f.mouseY);
        KeyframeTrack track = f.layout.trackAt(rowIndex);

        if (track != null && KeyframeTrack.isCameraSwitch(track)) {
            // The shot lane owns its right-clicks too, so it is decided before the region menu.
            handleCutsLaneClick(f, track, left, right);
            return;
        }

        int grabbed = track == null ? -1 : keyframeAt(f, track);

        // A right-click on a removed stretch offers to put it back, wherever in the canvas it lands:
        // the region is drawn across every row, so a gap or a section heading has to answer for it
        // too. It comes after the keyframe grab, so a key sitting on the region still opens its own
        // menu, which is the more precise thing the pointer was aimed at.
        if (right && grabbed < 0 && requestRegionMenu(f)) {
            return;
        }

        if (track == null) {
            if (left && !beginRemovedEdgeDrag(f)) {
                beginMarquee(f);
            }
            return;
        }

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
            if (isShortcutModifierDown()) {
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

        // A boundary of a removed stretch wins over marking a new one, but only after the keyframe
        // check above: grabbing a key that happens to sit on the boundary is the more precise gesture.
        if (beginRemovedEdgeDrag(f)) {
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
    /**
     * A shot is a clip: its body selects and slides it, its edges move the cut that bounds it, and
     * right-clicking it opens the menu that changes which camera it shows.
     */
    private static void handleCutsLaneClick(Frame f, KeyframeTrack cutsLane, boolean left, boolean right) {
        ShotHit hit = shotHitTest(f, cutsLane);
        if (hit == null) {
            // No shot here - either there are no cameras yet or the lane is empty.
            if (!f.scene.cameras.isEmpty() && (left || right)) {
                requestCutMenu(f.tickAtX(f.mouseX));
            }
            return;
        }

        EditorScene.Shot shot = hit.shot();
        // Clicking a shot is a click on the camera that is showing: it selects that camera object
        // and opens its inspector, which is the same thing clicking the camera's row does.
        CameraInspectorWindow.select(shot.camera());
        if (right) {
            SELECTION.clear();
            selectedShotStart = shot.startTick();
            pendingShotMenu = shot;
            menuShot = shot;
            menuStartTick = -1;
            openCutMenuNow = true;
            return;
        }
        if (!left) {
            return;
        }

        SELECTION.clear();
        selectedShotStart = shot.startTick();
        if (hit.edgeTick() >= 0) {
            drag = new Drag.CutEdge(hit.edgeTick(), f.tickAtX(f.mouseX) - hit.edgeTick());
        } else {
            drag = new Drag.ShotBody(shot.startTick(), shot.endTick(), hit.anchorTick(),
                f.tickAtX(f.mouseX) - hit.anchorTick());
        }
    }

    private record ShotHit(EditorScene.Shot shot, int edgeTick, int anchorTick) {}

    /**
     * What part of a shot the pointer is over: one of its boundaries, or its body.
     *
     * <p>The edge zone is deliberately narrow - it is the precision target - and the body is
     * everything else, because sliding a whole shot is the coarser gesture.
     */
    @Nullable
    private static ShotHit shotHitTest(Frame f, KeyframeTrack cutsLane) {
        EditorScene.Shot shot = f.scene.shotAt(f.tickAtX(f.mouseX), f.totalTicks);
        if (shot == null) {
            return null;
        }
        float grab = ReplayUI.scaleUi(7);
        int startCut = shot.cutTick();
        int endCut = cutsLane.keyframesByTick.containsKey(shot.endTick()) ? shot.endTick() : -1;

        float startDistance = startCut >= 0 ? Math.abs(f.xOfTick(shot.startTick()) - f.mouseX) : Float.MAX_VALUE;
        float endDistance = endCut >= 0 ? Math.abs(f.xOfTick(shot.endTick()) - f.mouseX) : Float.MAX_VALUE;
        if (startDistance <= grab || endDistance <= grab) {
            int edgeTick = startDistance <= endDistance ? startCut : endCut;
            // Dragging an edge lines that boundary up with the magnet.
            return new ShotHit(shot, edgeTick, edgeTick);
        }

        // Sliding: anchor on the boundary nearest the grab, so a real boundary does the snapping.
        boolean hasStart = startCut >= 0;
        boolean hasEnd = endCut >= 0;
        int anchor;
        if (!hasStart) {
            anchor = endCut;
        } else if (!hasEnd) {
            anchor = startCut;
        } else {
            anchor = startDistance <= endDistance ? startCut : endCut;
        }
        return new ShotHit(shot, -1, anchor);
    }

    /** Opens the shot menu to create a cut at this tick. */
    private static void requestCutMenu(int tick) {
        pendingCutMenuTrack = scene.cameraSwitchTrack();
        pendingCutMenuTick = clampTick(tick);
        pendingShotMenu = null;
        menuShot = null;
        menuStartTick = pendingCutMenuTick;
        openCutMenuNow = true;
    }

    /**
     * Starts the keyframe box-select, which is what a plain drag over the rows means.
     *
     * <p>Marking a stretch of time is deliberately not this gesture: dragging across the keyframes is
     * how keys are picked out, and having that also arm the cut tool made an ordinary drag look like
     * it was cutting the replay. Marking time lives on the ruler - the one place that is about time
     * rather than about keys - so a cut is always a deliberate gesture on the timestamps.
     *
     * <p>Shift adds to the selection instead of replacing it. It means "ignore the magnet" on the
     * drags that snap, but a marquee has nothing to snap, so the key is free here.
     */
    private static void beginMarquee(Frame f) {
        boolean additive = InputHelper.isShiftDownRaw();
        drag = new Drag.Marquee(f.mouseX, f.mouseY, additive);
        if (drag instanceof Drag.Marquee marquee) {
            marquee.before.addAll(SELECTION);
        }
        if (!additive) {
            SELECTION.clear();
        }
    }

    /**
     * Marks a tick range from wherever the gesture began - the empty canvas or, with Ctrl/Cmd, the
     * ruler - so both places pick the same ticks and show the same highlight.
     *
     * <p>The anchored end goes through the same magnet as every other drag, so a cut can be lined up
     * exactly with a keyframe; Shift turns the magnet off, as it does everywhere else.
     */
    private static void beginRange(Frame f) {
        int anchor = snapTick(f, f.tickAtX(f.mouseX));
        drag = new Drag.Range(anchor);
        rangeStartTick = anchor;
        rangeEndTick = anchor;
    }

    /** Whether a stretch of ticks is currently marked, which is what the cut control acts on. */
    private static boolean hasRangeSelection() {
        return rangeStartTick >= 0 && rangeEndTick >= 0 && rangeStartTick != rangeEndTick;
    }

    private static int rangeMinTick() {
        return Math.min(rangeStartTick, rangeEndTick);
    }

    private static int rangeMaxTick() {
        return Math.max(rangeStartTick, rangeEndTick);
    }

    private static void clearRangeSelection() {
        rangeStartTick = -1;
        rangeEndTick = -1;
    }

    /**
     * The cut the whole range sits inside, or null when any of it is still kept.
     *
     * <p>Only a range one cut covers completely can be restored; a range that reaches past the cut
     * also describes kept ticks, so there the control offers to remove them instead.
     */
    @Nullable
    private static TimelineCut cutContainingRange() {
        TimelineCut cut = editorState.cutAt(rangeMinTick());
        if (cut == null || rangeMaxTick() > cut.end) {
            return null;
        }
        return cut;
    }

    /**
     * Carries out what the cut control offers: remove the marked stretch, or put back the part of a
     * cut the range sits inside, as one undoable step.
     *
     * <p>Restoring goes through {@link EditorState#restoreRange}, so putting back the middle of a
     * long cut leaves the rest of it removed rather than throwing the whole cut away. Either way the
     * list is snapshotted on both sides, because a removal can merge cuts and a restore can split
     * one, and undo has to be exact about that.
     */
    private static void applyRangeCut() {
        if (!hasRangeSelection()) {
            return;
        }
        boolean restore = cutContainingRange() != null;
        List<TimelineCut> before = snapshotCuts();
        if (restore) {
            editorState.restoreRange(rangeMinTick(), rangeMaxTick());
        } else {
            editorState.addCut(rangeMinTick(), rangeMaxTick());
        }
        List<TimelineCut> after = snapshotCuts();
        if (sameCuts(before, after)) {
            // Nothing moved, so there is nothing worth an undo step.
            return;
        }
        sceneStamp = upgradeToWrite(editorState, sceneStamp, sceneStampIsWrite);
        TimelineEdits.setCuts(scene, editorState, before, after,
            I18n.get(restore ? "flashback.timeline.restore_cut" : "flashback.timeline.cut_out"));
    }

    /**
     * Puts one whole removed stretch back, as the region menu offers.
     *
     * <p>It is the same edit a marked range inside the cut would make, reached from the region
     * itself; the range selection is deliberately left alone, because the user asked for this cut
     * rather than for whatever happened to be marked. The list is snapshotted on both sides so the
     * restore is one exact undo step, and {@link EditorState#restoreRange} covers the whole stretch.
     */
    private static void restoreRegion(TimelineCut cut) {
        List<TimelineCut> before = snapshotCuts();
        editorState.restoreRange(cut.start, cut.end);
        List<TimelineCut> after = snapshotCuts();
        if (sameCuts(before, after)) {
            return;
        }
        sceneStamp = upgradeToWrite(editorState, sceneStamp, sceneStampIsWrite);
        TimelineEdits.setCuts(scene, editorState, before, after, I18n.get("flashback.timeline.restore_cut"));
    }

    /**
     * A copy of the cut list, taken either side of an edit so undo can put the old one back.
     *
     * <p>The copies matter: the live list is replaced rather than edited in place, so a snapshot
     * that shared its entries would quietly change with it and undo would restore the new state.
     */
    private static List<TimelineCut> snapshotCuts() {
        List<TimelineCut> snapshot = new ArrayList<>();
        for (TimelineCut cut : editorState.normalisedCuts()) {
            snapshot.add(cut.copy());
        }
        return snapshot;
    }

    /** Whether two cut lists hold the same stretches, by tick rather than by identity. */
    private static boolean sameCuts(List<TimelineCut> a, List<TimelineCut> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i).start != b.get(i).start || a.get(i).end != b.get(i).end) {
                return false;
            }
        }
        return true;
    }

    /**
     * The removed-stretch boundary nearest the pointer, or null when none is close enough.
     *
     * <p>The grab area is narrow on purpose: the body of a cut is still empty canvas, where the plain
     * drag keeps its meaning of marking a new stretch, so only the edges behave differently.
     */
    @Nullable
    private static CutEdgeHit cutEdgeAt(Frame f) {
        // Never zero, so the comparison below can only match a boundary the pointer is really on.
        float bestDistance = Math.max(1, ReplayUI.scaleUi(5));
        CutEdgeHit best = null;
        List<TimelineCut> cuts = editorState.normalisedCuts();
        for (int i = 0; i < cuts.size(); i++) {
            TimelineCut cut = cuts.get(i);
            float startDistance = Math.abs(f.xOfTick(cut.start) - f.mouseX);
            if (startDistance <= bestDistance) {
                bestDistance = startDistance;
                best = new CutEdgeHit(i, true);
            }
            float endDistance = Math.abs(f.xOfTick(cut.end) - f.mouseX);
            if (endDistance < bestDistance) {
                bestDistance = endDistance;
                best = new CutEdgeHit(i, false);
            }
        }
        return best;
    }

    /**
     * Starts moving the boundary of a removed stretch under the pointer.
     *
     * <p>Returns whether one was grabbed, so the caller can fall through to marking a new range when
     * there was nothing to move.
     */
    private static boolean beginRemovedEdgeDrag(Frame f) {
        CutEdgeHit hit = cutEdgeAt(f);
        if (hit == null) {
            return false;
        }
        List<TimelineCut> before = snapshotCuts();
        TimelineCut cut = before.get(hit.index());
        int boundaryTick = hit.startEdge() ? cut.start : cut.end;
        // The pointer rarely lands exactly on the boundary, so remember where it was: without this
        // the boundary would jump to the pointer on the first frame of the drag.
        int grabOffset = f.tickAtX(f.mouseX) - boundaryTick;
        drag = new Drag.RemovedEdge(before, hit.index(), hit.startEdge(), grabOffset);
        return true;
    }

    /**
     * Asks for the menu that puts a removed stretch back, when the pointer is on one.
     *
     * <p>The stretch is found by tick rather than by pixels, so the whole hatched region answers to
     * the right-click and not only the line drawn along its edge. A copy is kept because the live
     * cut list can be replaced before the menu is answered, and the menu still has to know which
     * stretch it is offering to restore.
     *
     * @return whether a removed stretch was under the pointer, so the caller knows the click was used
     */
    private static boolean requestRegionMenu(Frame f) {
        TimelineCut cut = editorState.cutAt(f.tickAtX(f.mouseX));
        if (cut == null) {
            return false;
        }
        pendingRegionMenu = cut.copy();
        openRegionMenuNow = true;
        return true;
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
                seek(f, snapTick(f, f.tickAtX(f.mouseX)));
            }
            case Drag.Keys keys -> {
                if (!keys.moved && (Math.abs(f.mouseX - keys.anchorX) > 2 || Math.abs(f.mouseY - keys.anchorY) > 2)) {
                    keys.moved = true;
                }
                if (keys.moved) {
                    String tooltip = moveTooltip(f, keys);
                    if (!InputHelper.isShiftDownRaw()) {
                        tooltip += "\n" + I18n.get("flashback.hold_shift_for_free_positioning");
                    }
                    ImGuiHelper.drawTooltip(tooltip);
                }
            }
            case Drag.CutEdge edge -> {
                if (edge.target >= 0) {
                    ImGui.setMouseCursor(ImGuiMouseCursor.ResizeEW);
                    EditorCamera camera = f.scene.resolveCameraAt(edge.cutTick);
                    ImGuiHelper.drawTooltip(I18n.get("flashback.timeline.cut_tooltip",
                            camera == null ? "" : f.scene.displayNameOf(camera), ticksToTimestamp(edge.target))
                        + "\n" + I18n.get("flashback.timeline.cut_tooltip_hint"));
                }
            }
            case Drag.ShotBody body -> {
                if (body.delta != 0) {
                    int from = Math.max(0, body.startTick + body.delta);
                    int to = Math.min(f.totalTicks, body.endTick + body.delta);
                    ImGuiHelper.drawTooltip(I18n.get("flashback.timeline.shot_range",
                            ticksToTimestamp(from), ticksToTimestamp(to))
                        + "\n" + I18n.get("flashback.timeline.shot_duration",
                            ticksToTimestamp(to - from)));
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
                sceneStamp = upgradeToWrite(editorState, sceneStamp, sceneStampIsWrite);
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
            case Drag.RemovedEdge edge -> {
                int tick = snapTick(f, f.tickAtX(f.mouseX) - edge.grabOffset);
                List<TimelineCut> live = new ArrayList<>(edge.before.size());
                TimelineCut moved = null;
                for (int i = 0; i < edge.before.size(); i++) {
                    TimelineCut cut = edge.before.get(i).copy();
                    if (i == edge.index) {
                        moved = cut;
                    }
                    live.add(cut);
                }
                if (moved != null) {
                    if (edge.startEdge) {
                        // The start may not reach the end, and may not go before the replay: a
                        // removed stretch always keeps at least one tick.
                        moved.start = Math.max(0, Math.min(moved.end - 1, tick));
                    } else {
                        moved.end = Math.min(f.totalTicks, Math.max(moved.start + 1, tick));
                    }
                    // Live feedback only: the history entry is recorded once, on release, so the whole
                    // drag is a single undo step and no other cut is touched while it moves.
                    editorState.setCuts(live);
                    ImGui.setMouseCursor(ImGuiMouseCursor.ResizeEW);
                    ImGuiHelper.drawTooltip(I18n.get("flashback.timeline.shot_range",
                        ticksToTimestamp(moved.start), ticksToTimestamp(moved.end)));
                }
            }
            case Drag.Range range -> {
                int tick = snapTick(f, f.tickAtX(f.mouseX));
                if (tick != range.anchorTick) {
                    range.moved = true;
                }
                rangeEndTick = tick;
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
        } else if (finished instanceof Drag.CutEdge edge) {
            if (edge.target >= 0 && edge.target != edge.cutTick) {
                // Without the write stamp the edit would be made against the read snapshot and then
                // thrown away when the snapshot is released, so the drag would appear to do nothing.
                sceneStamp = upgradeToWrite(editorState, sceneStamp, sceneStampIsWrite);
                TimelineEdits.moveCut(scene, editorState, edge.cutTick, edge.target);
                selectedShotStart = edge.target;
            }
        } else if (finished instanceof Drag.RemovedEdge edge) {
            // One history entry for the whole drag, from the snapshot taken when it began. A drag
            // that put the boundary back where it found it records nothing.
            List<TimelineCut> after = snapshotCuts();
            if (!sameCuts(edge.before, after)) {
                sceneStamp = upgradeToWrite(editorState, sceneStamp, sceneStampIsWrite);
                TimelineEdits.setCuts(scene, editorState, edge.before, after,
                    I18n.get("flashback.timeline.adjusted_cut"));
            }
        } else if (finished instanceof Drag.ShotBody body) {
            if (body.delta != 0) {
                sceneStamp = upgradeToWrite(editorState, sceneStamp, sceneStampIsWrite);
                TimelineEdits.slideShot(scene, editorState, body.startTick, body.endTick, body.delta);
                selectedShotStart = Math.max(0, body.startTick + body.delta);
            }
        } else if (finished instanceof Drag.Row row) {
            applyRowReorder(f, row);
        } else if (finished instanceof Drag.Range range) {
            // A press that never dragged is how the selection is given up, and so is a drag that
            // came back to where it started: either way there is no stretch left to act on.
            if (!range.moved || !hasRangeSelection()) {
                clearRangeSelection();
            }
        } else if (finished instanceof Drag.Head) {
            f.replayServer.replayPaused = true;
        }
    }

    private static void applyRowReorder(Frame f, Drag.Row row) {
        if (row.slot < 0 || !f.layout.wouldMove(row.rowIndex, row.slot)) {
            return;
        }
        sceneStamp = upgradeToWrite(editorState, sceneStamp, sceneStampIsWrite);
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
        sceneStamp = upgradeToWrite(editorState, sceneStamp, sceneStampIsWrite);

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
    /** How far a dragged item should move, with the magnet applied to where it would land. */
    private static int snappedDelta(TickScale scale, float mouseX, int grabbedTick, @Nullable KeyframeTrack dragged) {
        int raw = scale.tickAtX(mouseX);
        return snapDragTick(scale, dragged, raw, grabbedTick) - grabbedTick;
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

    /** Where the magnet last pulled a drag to, so the frame can show what it lined up with. */
    private static int snapIndicatorTick = -1;

    /** Pixels within which a dragged tick is pulled onto a nearby tick. */
    private static final float SNAP_PIXELS = 7f;

    // How much one notch of the wheel does. Deliberately gentle: a trackpad sends many small events,
    // and overshooting the section you wanted is worse than taking one more gesture to reach it.
    /** Pixels the row list moves per notch. */
    private static final float SCROLL_PIXELS_PER_NOTCH = 26f;
    /** Fraction of the visible span the timeline moves per notch. */
    private static final double PAN_FRACTION_PER_NOTCH = 0.08;
    /** Fraction of the visible span one notch of zoom keeps. */
    private static final double ZOOM_FACTOR_PER_NOTCH = 0.90;

    /**
     * The ticks a drag lines up with: the ends of the replay and the playhead, then every cut and
     * keyframe, since those are the points an edit is meant to line up with.
     *
     * <p>Snapping is on by default and Shift turns it off, which is what editing software has trained
     * everyone to expect: the magnet helps by default, and a key gives you the raw value when wanted.
     */
    private static List<Integer> snapTargets(@Nullable KeyframeTrack draggedTrack, int excludeTick) {
        List<Integer> targets = new ArrayList<>();
        targets.add(0);
        targets.add(editingTotalTicks);
        targets.add(currentCursorTick);
        if (exportStartTick >= 0) {
            targets.add(exportStartTick);
        }
        if (exportEndTick >= 0) {
            targets.add(exportEndTick);
        }
        for (KeyframeTrack track : scene.keyframeTracks) {
            for (int tick : track.keyframesByTick.keySet()) {
                if (track == draggedTrack && tick == excludeTick) {
                    continue;
                }
                targets.add(tick);
            }
        }
        return targets;
    }

    /**
     * Pulls a tick onto the nearest snap target if one is close enough.
     *
     * @param draggedTrack the track being dragged, whose own keyframes are not targets
     * @param excludeTick  the tick being dragged, which must not snap to itself
     */
    private static int snapDragTick(TickScale scale, @Nullable KeyframeTrack draggedTrack, int tick, int excludeTick) {
        if (InputHelper.isShiftDownRaw()) {
            snapIndicatorTick = -1;
            return tick;
        }
        float tolerance = SNAP_PIXELS * ReplayUI.scaleUi(1);
        int best = tick;
        float bestDistance = tolerance;
        for (int target : snapTargets(draggedTrack, excludeTick)) {
            float distance = Math.abs(scale.xOfTick(target) - scale.xOfTick(tick));
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = target;
            }
        }
        snapIndicatorTick = best == tick ? -1 : best;
        return best;
    }

    /** Snaps the playhead while scrubbing, using the same magnet as every other drag. */
    private static int snapTick(Frame f, int tick) {
        return snapDragTick(f.scale, null, tick, tick);
    }

    private static int clampTick(int tick) {
        return Math.max(0, Math.min(editingTotalTicks, tick));
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
        seek(f, markerTick);
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

    /** The row list's scroll offset, for the offscreen harness to check. */
    public static double debugRowScroll() {
        return rowScroll;
    }

    /**
     * Moves the playhead and makes the world catch up.
     *
     * <p>Keyframes are only reapplied while the replay is playing, plus whenever a seek asks for it.
     * Scrubbing pauses the replay, so without this the camera would keep showing wherever it was
     * before - which is why scrubbing has to ask, exactly like stepping does.
     */
    private static void seek(Frame f, int tick) {
        int target = Math.max(0, Math.min(f.totalTicks, tick));
        // Re-arming the same jump every frame would keep the server from ever taking it, and the
        // server is what re-resolves who is being spectated. Only a moved playhead asks for a jump.
        if (f.replayServer.jumpToTick != target) {
            f.replayServer.goToReplayTick(target);
        }
        f.replayServer.forceApplyKeyframes.set(true);
    }

    private static void handleScroll(Frame f) {
        // A trackpad offers both axes and often only the horizontal one when scrolling a timeline, so
        // take whichever the gesture produced.
        float vertical = ReplayUI.getIO().getMouseWheel();
        float horizontal = ReplayUI.getIO().getMouseWheelH();
        boolean sideways = Math.abs(horizontal) > Math.abs(vertical);
        float wheel = sideways ? horizontal : vertical;
        // A trackpad's horizontal gesture is reported in the opposite sense to the direction the
        // fingers travel, so it is flipped to pan the way the gesture looks like it should. The
        // vertical axis is already the right way round and is left alone.
        float panWheel = sideways ? -wheel : wheel;
        // Zooming is a whole-window gesture: it should work with the pointer over the rows as well as
        // over the timeline, because the section you want to zoom into is often named in the rows.
        if (wheel == 0 || !f.mouseOverWindow) {
            return;
        }

        double zoomMin = editorState.zoomMin;
        double zoomMax = editorState.zoomMax;
        double minimumSpan = Math.min(1.0, 2.0 / Math.max(1, f.totalTicks));
        double span = zoomMax - zoomMin;

        // Both command keys zoom, whichever one the platform or the user thinks of as "command",
        // and the binding is honoured on top so a customised gesture still works.
        ImGuiIO io = ReplayUI.getIO();
        boolean zoomModifier = isShortcutModifierDown()
            || Keybinds.TIMELINE_ZOOM_SCROLL.areAllModifiersDown();
        boolean panModifier = io.getKeyShift() || Keybinds.TIMELINE_MOVE_SCROLL.areAllModifiersDown();
        if (zoomModifier) {
            // Zoom about the pointer: whatever is under the cursor stays under the cursor, which is
            // what makes zooming feel like moving a magnifier rather than jumping.
            double factor = Math.pow(ZOOM_FACTOR_PER_NOTCH, wheel);
            double newSpan = Math.max(minimumSpan, Math.min(1.0, span * factor));
            double anchor = Math.max(0, Math.min(1, (f.mouseX - f.timelineLeft()) / Math.max(1, f.timelineWidth)));
            double newMin = zoomMin + (span - newSpan) * anchor;
            newMin = Math.max(0, Math.min(1 - newSpan, newMin));
            editorState.zoomMin = newMin;
            editorState.zoomMax = newMin + newSpan;
        } else if (panModifier || sideways || !f.mouseOverRows) {
            // Move along the replay by a fraction of what is on screen, so one notch moves the same
            // amount of material whatever the zoom level. Over the ruler there is nothing to scroll
            // vertically, so the wheel moves time there too.
            double step = span * PAN_FRACTION_PER_NOTCH * panWheel;
            double newMin = Math.max(0, Math.min(1 - span, zoomMin + step));
            editorState.zoomMin = newMin;
            editorState.zoomMax = newMin + span;
        } else {
            // Nothing else claims it, so it scrolls the row list. This is the plain meaning of the
            // wheel, and it works over the rows and over the timeline alike.
            double pixelsPerNotch = SCROLL_PIXELS_PER_NOTCH * ReplayUI.getUiScale();
            rowScroll = Math.max(0, Math.min(rowScrollMax, rowScroll - wheel * pixelsPerNotch));
            return;
        }
        editorState.markDirty();
    }

    /**
     * Whether the shortcut modifier is held: Command on macOS, Ctrl everywhere else.
     *
     * <p>Read from the keyboard itself rather than from ImGui's cached modifier flags. ImGui only
     * updates those flags when a key event arrives, so a gesture that begins with the modifier
     * already down - holding Command and then pressing the mouse - can find the stale state and
     * decide the modifier is not held, which is what kept Command-drag from marking a range.
     */
    private static boolean isShortcutModifierDown() {
        // Both modifiers are accepted. Command is the Mac convention, but someone reaching for Ctrl
        // expects the gesture to answer too, and on Windows and Linux the two are the same key, so
        // the extra check only ever adds a way to do the same thing.
        return InputHelper.isCtrlOrCmdDownRaw() || InputHelper.isCtrlDownRaw();
    }

    private static void handleKeyPresses(Frame f) {
        if (Keybinds.PAUSE.isPressed(false)) {
            togglePaused(f.replayServer);
        }
        if (ImGui.isKeyPressed(ImGuiKey.LeftArrow, false)) {
            pendingStepBackwardsTicks += ReplayUI.isCtrlOrCmdDown() ? 5 : 1;
        } else if (pendingStepBackwardsTicks > 0 && !ImGui.isKeyDown(ImGuiKey.LeftArrow)) {
            seek(f, f.replayServer.getReplayTick() - pendingStepBackwardsTicks);
            pendingStepBackwardsTicks = 0;
        }
        if (ImGui.isKeyPressed(ImGuiKey.RightArrow, false)) {
            seek(f, f.cursorTicks + (ReplayUI.isCtrlOrCmdDown() ? 5 : 1));
        }
        if (ImGui.isKeyPressed(ImGuiKey.UpArrow, false)) {
            jumpToNeighbouringKeyframe(f, 1);
        }
        if (ImGui.isKeyPressed(ImGuiKey.DownArrow, false)) {
            jumpToNeighbouringKeyframe(f, -1);
        }

        // Escape gives up the range selection, the same way a right-click or a click without a drag
        // does, so there is always a way out of the gesture that does not edit anything.
        if (ImGui.isKeyPressed(ImGuiKey.Escape, false)) {
            clearRangeSelection();
        }

        boolean delete = ImGui.isKeyPressed(ImGuiKey.Delete, false) || ImGui.isKeyPressed(ImGuiKey.Backspace, false);
        if (delete && hasRangeSelection()) {
            // A marked range takes Delete, because it is the more explicit thing on screen and the
            // control it stands for does exactly this. The keyframe selection still gets the key
            // whenever no range is marked, which is where deleting keys is expected to work.
            applyRangeCut();
        } else if (delete && !SELECTION.isEmpty()) {
            deleteSelection();
        } else if (delete) {
            deleteSelectedShot();
        }

        if (Keybinds.UNDO.isPressed(false)) {
            sceneStamp = upgradeToWrite(editorState, sceneStamp, sceneStampIsWrite);
            scene.undo(editorState, ReplayUI::setInfoOverlayShort);
            editorState.markDirty();
        }
        if (Keybinds.REDO.isPressed(false)) {
            sceneStamp = upgradeToWrite(editorState, sceneStamp, sceneStampIsWrite);
            scene.redo(editorState, ReplayUI::setInfoOverlayShort);
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

        // Scrubbing already applies the keyframes through seek(), so the only hint left here is the
        // one about placing a grabbed keyframe freely.
        if (drag instanceof Drag.Head && !scene.keyframeTracks.isEmpty() && !InputHelper.isShiftDownRaw()) {
            ImGuiHelper.drawTooltip(I18n.get("flashback.hold_shift_for_free_positioning"));
        }
    }

    private static void addCameraKeyframeAtCursor(Frame f) {
        EditorCamera camera = cameraForEditing(f);
        if (camera == null) {
            sceneStamp = upgradeToWrite(editorState, sceneStamp, sceneStampIsWrite);
            TimelineEdits.addCamera(scene, editorState, EditorCamera.Kind.FREE, f.cursorTicks);
            camera = cameraForEditing(f);
        }
        if (camera == null) {
            return;
        }
        sceneStamp = upgradeToWrite(editorState, sceneStamp, sceneStampIsWrite);
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
        seek(f, target);
        f.replayServer.forceApplyKeyframes.set(true);
    }

    private static void handleMarkInOut(Frame f) {
        if (Keybinds.MARK_IN.isPressed(false)) {
            sceneStamp = upgradeToWrite(editorState, sceneStamp, sceneStampIsWrite);
            scene.setExportTicks(f.cursorTicks, -1, f.totalTicks);
            editorState.markDirty();
            ReplayUI.setInfoOverlayShort(I18n.get("flashback.timeline.marked_in", f.cursorTicks));
        }
        if (Keybinds.MARK_OUT.isPressed(false)) {
            sceneStamp = upgradeToWrite(editorState, sceneStamp, sceneStampIsWrite);
            scene.setExportTicks(-1, f.cursorTicks, f.totalTicks);
            editorState.markDirty();
            ReplayUI.setInfoOverlayShort(I18n.get("flashback.timeline.marked_out", f.cursorTicks));
        }
        if (Keybinds.CLEAR_IN.isPressed(false)) {
            sceneStamp = upgradeToWrite(editorState, sceneStamp, sceneStampIsWrite);
            scene.setExportTicks(0, -1, f.totalTicks);
            editorState.markDirty();
            ReplayUI.setInfoOverlayShort(I18n.get("flashback.timeline.marked_cleared_in"));
        }
        if (Keybinds.CLEAR_OUT.isPressed(false)) {
            sceneStamp = upgradeToWrite(editorState, sceneStamp, sceneStampIsWrite);
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
        sceneStamp = upgradeToWrite(editorState, sceneStamp, sceneStampIsWrite);
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

    /**
     * Removes the selected shot from the programme.
     *
     * <p>A shot is a stretch of the timeline, so deleting it means removing the cut that starts it:
     * the previous camera then runs on through where it used to be. The first shot has no cut of its
     * own, so there is nothing to remove and nothing happens.
     */
    private static void deleteSelectedShot() {
        if (selectedShotStart < 0) {
            return;
        }
        EditorScene.Shot shot = scene.shotAt(selectedShotStart, editingTotalTicks);
        if (shot == null || shot.cutTick() < 0) {
            return;
        }
        sceneStamp = upgradeToWrite(editorState, sceneStamp, sceneStampIsWrite);
        TimelineEdits.deleteShot(scene, editorState, shot.cutTick());
        selectedShotStart = -1;
        inspectorOpen = false;
    }

    private static void deleteSelection() {
        sceneStamp = upgradeToWrite(editorState, sceneStamp, sceneStampIsWrite);
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

            sceneStamp = upgradeToWrite(editorState, sceneStamp, sceneStampIsWrite);
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
        drawRegionMenu();
        drawCreateAtTickPopup(f);
        drawRenameCameraPopup();
        drawDeleteCameraPopup();
    }

    /**
     * Confirms deleting a camera, for the row menus that do not go through the inspector.
     *
     * <p>Same edit and same wording as the inspector's header button - {@link TimelineEdits#deleteCamera}
     * removes the camera, every track it owns and every keyframe on them, and retargets the cuts that
     * named it - so there is one deletion path and it is undoable wherever it is started from.
     */
    private static void drawDeleteCameraPopup() {
        if (pendingDeleteCamera != null && openDeleteCameraPopup) {
            ImGui.openPopup("##DeleteCamera");
            openDeleteCameraPopup = false;
        }
        if (ImGuiHelper.beginPopup("##DeleteCamera")) {
            EditorCamera camera = pendingDeleteCamera;
            if (camera != null) {
                ImGui.textDisabled(I18n.get("flashback.camera_inspector.delete_confirm", scene.displayNameOf(camera)));
                if (ImGui.button(I18n.get("flashback.delete") + "##confirmDeleteCamera") || ReplayUI.consumeConfirm()) {
                    sceneStamp = upgradeToWrite(editorState, sceneStamp, sceneStampIsWrite);
                    TimelineEdits.deleteCamera(scene, editorState, camera);
                    // A camera that no longer exists must not stay selected, or the inspector would go
                    // on showing fields for something that is not in the scene.
                    if (camera.id.equals(CameraInspectorWindow.selectedCameraId())) {
                        CameraInspectorWindow.clear();
                    }
                    pendingDeleteCamera = null;
                    ImGui.closeCurrentPopup();
                }
                ImGui.sameLine();
                if (ImGui.button(I18n.get("gui.cancel") + "##cancelDeleteCamera") || ReplayUI.consumeCancel()) {
                    pendingDeleteCamera = null;
                    ImGui.closeCurrentPopup();
                }
            } else {
                ImGui.closeCurrentPopup();
            }
            ImGui.endPopup();
        } else {
            pendingDeleteCamera = null;
        }
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
            sceneStamp = upgradeToWrite(editorState, sceneStamp, sceneStampIsWrite);
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
    /**
     * The shot menu: what this shot is, and everything that can be done to it.
     *
     * <p>It serves both ways a shot is chosen - right-clicking an existing one, and asking for a cut
     * at the playhead - because those ask the same question: which camera should be live here. The
     * camera already showing is ticked rather than disabled, so re-cutting is always possible.
     */
    private static void drawCutMenu(Frame f) {
        if (pendingCutMenuTrack != null || pendingShotMenu != null) {
            menuShot = pendingShotMenu;
            menuTick = pendingShotMenu != null ? pendingShotMenu.startTick() : pendingCutMenuTick;
            pendingCutMenuTrack = null;
            pendingShotMenu = null;
        }
        if (menuShot == null && menuStartTick < 0) {
            return;
        }
        EditorScene.Shot shot = menuShot;
        int tick = menuTick;

        ImGui.pushID("##CutMenuScope");
        if (openCutMenuNow) {
            ImGui.openPopup("##CutMenu");
            openCutMenuNow = false;
        }
        boolean begun = ImGuiHelper.beginPopup("##CutMenu");
        shotMenuShowing = begun;
        if (!begun && !openCutMenuNow) {
            // The user dismissed it, so there is nothing left to remember.
            menuShot = null;
            menuStartTick = -1;
        }
        if (begun) {
            if (shot != null) {
                // The header is the shot's identity: camera, where it runs, how long it lasts.
                int accent = TimelineColours.cameraAccent(f.scene.cameraIndexOf(shot.camera()));
                ImGui.textColored(accent, f.scene.displayNameOf(shot.camera()));
                ImGui.textDisabled(I18n.get("flashback.timeline.shot_range",
                    ticksToTimestamp(shot.startTick()), ticksToTimestamp(shot.endTick()))
                    + "   " + I18n.get("flashback.timeline.shot_duration", ticksToTimestamp(shot.duration())));
            } else {
                ImGui.textDisabled(I18n.get("flashback.timeline.cut_at", ticksToTimestamp(tick), tick));
            }
            ImGui.separator();

            if (f.scene.cameras.isEmpty()) {
                ImGui.textDisabled(I18n.get("flashback.timeline.no_cameras"));
            } else {
                EditorCamera current = shot != null ? shot.camera() : f.scene.resolveCameraAt(tick);
                for (EditorCamera camera : f.scene.cameras) {
                    int accent = TimelineColours.cameraAccent(f.scene.cameraIndexOf(camera));
                    boolean isCurrent = camera == current;
                    if (ImGui.menuItem("\ue04b " + f.scene.displayNameOf(camera) + "##cut_" + camera.id,
                            null, isCurrent, true)) {
                        sceneStamp = upgradeToWrite(editorState, sceneStamp, sceneStampIsWrite);
                        if (shot != null) {
                            TimelineEdits.cutShotToCamera(scene, editorState, shot.startTick(), camera);
                        } else {
                            TimelineEdits.cutToCamera(scene, editorState, camera, tick);
                        }
                        selectedShotStart = shot != null ? shot.startTick() : tick;
                        ImGui.closeCurrentPopup();
                        inspectorOpen = false;
                    }
                    drawColourChip(accent);
                    if (isCurrent) {
                        ImGui.sameLine();
                        ImGui.textDisabled(I18n.get("flashback.timeline.live"));
                    }
                }
            }

            if (shot != null) {
                ImGui.separator();
                boolean insideShot = f.cursorTicks > shot.startTick() && f.cursorTicks < shot.endTick();
                if (ImGui.menuItem("\ue14e " + I18n.get("flashback.timeline.split_at_playhead") + "##splitShot",
                        null, false, insideShot)) {
                    sceneStamp = upgradeToWrite(editorState, sceneStamp, sceneStampIsWrite);
                    TimelineEdits.cutToCamera(scene, editorState, shot.camera(), f.cursorTicks);
                    selectedShotStart = f.cursorTicks;
                    ImGui.closeCurrentPopup();
                }
                if (ImGui.menuItem("\ue8f4 " + I18n.get("flashback.preview") + "##previewShot")) {
                    editorState.previewCamera(shot.camera(), f.cursorTicks, sceneStamp);
                    ImGui.closeCurrentPopup();
                }
                ImGui.separator();
                if (ImGui.menuItem("\ue872 " + I18n.get("flashback.timeline.remove_shot") + "##removeShot",
                        null, false, shot.cutTick() >= 0)) {
                    sceneStamp = upgradeToWrite(editorState, sceneStamp, sceneStampIsWrite);
                    TimelineEdits.deleteShot(scene, editorState, shot.cutTick());
                    selectedShotStart = -1;
                    ImGui.closeCurrentPopup();
                }
            }
            ImGui.endPopup();
        }
        ImGui.popID();
    }

    /**
     * The menu a right-click on a removed stretch opens: one item that puts the whole stretch back.
     *
     * <p>It is kept and begun every frame like the shot menu, because ImGui closes a popup that is
     * not begun in a frame. The stretch it was opened on is remembered as a copy, so the answer is
     * about what the user right-clicked even if the live list has moved on since.
     */
    private static void drawRegionMenu() {
        if (pendingRegionMenu != null) {
            menuRegion = pendingRegionMenu;
            pendingRegionMenu = null;
        }
        if (menuRegion == null) {
            return;
        }

        ImGui.pushID("##CutRegionMenuScope");
        if (openRegionMenuNow) {
            ImGui.openPopup("##CutRegionMenu");
            openRegionMenuNow = false;
        }
        boolean begun = ImGuiHelper.beginPopup("##CutRegionMenu");
        if (!begun) {
            // Dismissed without choosing, so there is nothing left to remember.
            menuRegion = null;
        }
        if (begun) {
            if (ImGui.menuItem(I18n.get("flashback.timeline.restore_cut") + "##restoreRegion")) {
                restoreRegion(menuRegion);
                ImGui.closeCurrentPopup();
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
                        sceneStamp = upgradeToWrite(editorState, sceneStamp, sceneStampIsWrite);
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
        sceneStamp = upgradeToWrite(editorState, sceneStamp, sceneStampIsWrite);
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
        sceneStamp = upgradeToWrite(editorState, sceneStamp, sceneStampIsWrite);
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
        sceneStamp = upgradeToWrite(editorState, sceneStamp, sceneStampIsWrite);
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

    // -- Camera object operations ----------------------------------------------------------------

    /**
     * The camera's track of a type, or null when it does not have one yet.
     *
     * <p>The camera inspector asks this every frame for every property, so it is a plain scan of the
     * scene's track list rather than anything built once and cached - the list is short, and a cache
     * would have to be invalidated by every undo.
     */
    @Nullable
    public static KeyframeTrack trackOfType(EditorScene scene, EditorCamera camera, KeyframeType<?> type) {
        for (KeyframeTrack track : scene.keyframeTracks) {
            if (camera.id.equals(track.cameraId) && track.keyframeType == type) {
                return track;
            }
        }
        return null;
    }

    /**
     * A keyframe holding the camera's current value for one of its animatable properties.
     *
     * <p>The value is read from the camera - the same object the inspector's fields are bound to -
     * so the keyframe records exactly what the user sees, including an edit made a moment earlier
     * that has not been keyed yet. That is what makes "type a value, then press the key button"
     * capture the typed value.
     */
    @Nullable
    public static Keyframe readPropertyKeyframe(EditorCamera camera, KeyframeType<?> type) {
        if (type == CameraPositionKeyframeType.INSTANCE) {
            return new CameraPositionKeyframe(new Vector3d(camera.x, camera.y, camera.z));
        }
        if (type == CameraRotationKeyframeType.INSTANCE) {
            return new CameraRotationKeyframe(camera.yaw, camera.pitch, camera.roll);
        }
        if (type == CameraFovKeyframeType.INSTANCE) {
            return new CameraFovKeyframe(camera.fov);
        }
        if (type == CameraShakeKeyframeType.INSTANCE) {
            return new CameraShakeKeyframe(camera.cameraShakeXFrequency, camera.cameraShakeXAmplitude,
                camera.cameraShakeYFrequency, camera.cameraShakeYAmplitude, true);
        }
        return null;
    }

    /** Opens the timeline's rename popup for a camera. Shared with the camera inspector. */
    public static void renameCamera(EditorScene scene, EditorCamera camera) {
        pendingRenameCamera = camera;
        cameraNameString = ImGuiHelper.createResizableImString(scene.displayNameOf(camera));
        openRenameCameraPopup = true;
    }

    /**
     * Copies a camera and everything it owns: its properties and every track with its keyframes,
     * retargeted at the copy.
     *
     * <p>The track copies carry the original camera's id in {@code cameraId}, so each one is
     * explicitly repointed at the duplicate - otherwise the copy would own nothing and the original
     * would appear to own its rows twice. Cuts to the original are deliberately left alone: the
     * duplicate is a new viewpoint, not a replacement.
     */
    @Nullable
    public static EditorCamera duplicateCamera(EditorScene scene, EditorState state, EditorCamera camera) {
        sceneStamp = upgradeToWrite(state, sceneStamp, sceneStampIsWrite);
        return duplicateCameraInScene(scene, state, camera);
    }

    /**
     * Duplicates a camera into the scene, assuming the caller already holds the write lock.
     *
     * <p>Split out because the camera inspector edits under its own short-lived write stamp and must
     * not touch this window's stamp state; calling the method above from there would overwrite the
     * timeline's stamp with the inspector's and leave the timeline mutating state unlocked.
     */
    public static EditorCamera duplicateCameraInScene(EditorScene scene, EditorState state, EditorCamera camera) {
        String base = scene.displayNameOf(camera);
        EditorCamera duplicate = new EditorCamera(I18n.get("flashback.camera_inspector.duplicate_name", base), camera.kind);
        duplicate.id = UUID.randomUUID();

        // Not EditorCamera.copy(): that keeps the original's id, which is exactly what a duplicate
        // must not do - camera identity is what cuts and track ownership are keyed on.
        duplicate.x = camera.x;
        duplicate.y = camera.y;
        duplicate.z = camera.z;
        duplicate.yaw = camera.yaw;
        duplicate.pitch = camera.pitch;
        duplicate.roll = camera.roll;
        duplicate.fov = camera.fov;
        duplicate.overrideCameraShake = camera.overrideCameraShake;
        duplicate.cameraShakeXFrequency = camera.cameraShakeXFrequency;
        duplicate.cameraShakeXAmplitude = camera.cameraShakeXAmplitude;
        duplicate.cameraShakeYFrequency = camera.cameraShakeYFrequency;
        duplicate.cameraShakeYAmplitude = camera.cameraShakeYAmplitude;

        int cameraIndex = scene.cameras.size();
        List<KeyframeTrack> clones = new ArrayList<>();
        List<Integer> indices = new ArrayList<>();
        for (KeyframeTrack track : scene.tracksOfCamera(camera)) {
            KeyframeTrack clone = track.copy();
            clone.cameraId = duplicate.id;
            // A track's own name was typed for the original's rows; the camera's new name is the
            // honest label for the copy's rows.
            clone.customName = null;
            clones.add(clone);
            indices.add(scene.trackIndexOf(track));
            if (indices.get(indices.size() - 1) < 0) {
                return null;
            }
        }

        List<EditorSceneHistoryAction> undo = new ArrayList<>();
        List<EditorSceneHistoryAction> redo = new ArrayList<>();
        // Redo restores the camera before its rows, so the rows have an owner from the first action.
        redo.add(new EditorSceneHistoryAction.AddCamera(duplicate, cameraIndex, List.of()));
        for (int i = 0; i < clones.size(); i++) {
            redo.add(new EditorSceneHistoryAction.RestoreTrack(clones.get(i), indices.get(i)));
        }
        // Undo is exactly that backwards: the rows come out first, from the highest index down so
        // each removal leaves the ones below it where they were, and the camera goes last.
        for (int i = clones.size() - 1; i >= 0; i--) {
            undo.add(new EditorSceneHistoryAction.RemoveTrack(clones.get(i).keyframeType, indices.get(i)));
        }
        undo.add(new EditorSceneHistoryAction.RemoveCamera(duplicate));

        TimelineEdits.push(scene, state, undo, redo,
            I18n.get("flashback.camera_inspector.duplicated", base));
        return duplicate;
    }

    /**
     * The camera a keyframe shortcut should write to: the selected object if there is one, and
     * otherwise whichever camera is being output at the playhead.
     *
     * <p>Following the selection first keeps the keyboard shortcut consistent with the inspector -
     * pressing "add camera keyframe" while a camera is selected should key that camera, not whichever
     * one happens to be live at the playhead.
     */
    @Nullable
    private static EditorCamera cameraForEditing(Frame f) {
        UUID selected = CameraInspectorWindow.selectedCameraId();
        EditorCamera camera = selected == null ? null : scene.cameraById(selected);
        if (camera != null) {
            return camera;
        }
        return TimelineEdits.cameraForEditing(scene, f.cursorTicks);
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

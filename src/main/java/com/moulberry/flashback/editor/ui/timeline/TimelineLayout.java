package com.moulberry.flashback.editor.ui.timeline;

import com.moulberry.flashback.state.EditorCamera;
import com.moulberry.flashback.state.EditorScene;
import com.moulberry.flashback.state.KeyframeTrack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The timeline's vertical structure for one frame: which rows exist, in what order, and where each
 * one sits.
 *
 * <p>Everything that needs to know about rows - drawing, hit-testing, dragging - reads it from here,
 * so a click can only ever land on the row that was drawn.
 *
 * <p>Rows do not all have the same height: the camera-cut lane is the programme output and gets more
 * room, and section headings are compact. The heights are therefore stored per row rather than
 * assumed, which is also what lets the layout survive being restyled.
 *
 * <p>The class is free of ImGui and of any state beyond the scene it is built from, so row order,
 * geometry and the rules for reordering rows can be reasoned about (and tested) on their own.
 */
public final class TimelineLayout {

    /** Row sizes, in screen pixels, decided by the caller so the layout stays independent of ImGui. */
    public record Metrics(float rowHeight, float sectionHeight, float cutsLaneHeight, float topPadding) {}

    private final EditorScene scene;
    private final List<TimelineRow> rows;
    private final float[] tops;
    private final float[] heights;
    private final float totalHeight;

    private TimelineLayout(EditorScene scene, List<TimelineRow> rows, float[] tops, float[] heights) {
        this.scene = scene;
        this.rows = List.copyOf(rows);
        this.tops = tops;
        this.heights = heights;
        this.totalHeight = tops.length == 0 ? 0 : tops[tops.length - 1] + heights[heights.length - 1];
    }

    /**
     * How wide the row list should be.
     *
     * <p>A user-set width is honoured, but always within limits that keep the timeline usable: the
     * panel never grows enough to crowd out the canvas, and never shrinks below a readable column.
     * The stored width is in UI units rather than pixels, so it survives a change of GUI scale.
     *
     * @param storedWidth the user's choice in UI units, or 0 to use the default
     */
    public static float panelWidth(double storedWidth, float windowWidth, float uiScale, float timelineMinimum) {
        float minimum = 170 * uiScale;
        float maximum = Math.max(minimum, Math.min(420 * uiScale, windowWidth - timelineMinimum));
        float wanted = storedWidth > 0 ? (float) (storedWidth * uiScale) : 250 * uiScale;
        return Math.max(minimum, Math.min(maximum, wanted));
    }

    public static TimelineLayout build(EditorScene scene, Metrics metrics) {
        List<TimelineRow> rows = new ArrayList<>();
        List<Float> rowHeights = new ArrayList<>();

        KeyframeTrack switchTrack = scene.cameraSwitchTrack();
        List<KeyframeTrack> sceneTracks = new ArrayList<>();
        for (KeyframeTrack track : scene.keyframeTracks) {
            if (track != switchTrack && track.cameraId == null) {
                sceneTracks.add(track);
            }
        }

        boolean hasCameras = !scene.cameras.isEmpty();
        // A single kind of content needs no heading; both kinds do, or the boundary is invisible.
        boolean showSections = hasCameras && (!sceneTracks.isEmpty() || switchTrack != null);

        if (switchTrack != null) {
            rows.add(new TimelineRow.Track(switchTrack, null));
            rowHeights.add(metrics.cutsLaneHeight());
        }

        if (hasCameras) {
            if (showSections) {
                rows.add(new TimelineRow.Section(TimelineRow.Section.Kind.CAMERAS));
                rowHeights.add(metrics.sectionHeight());
            }
            for (EditorCamera camera : scene.cameras) {
                rows.add(new TimelineRow.CameraGroup(camera, camera.collapsed));
                rowHeights.add(metrics.rowHeight());
                if (camera.collapsed) {
                    continue;
                }
                for (KeyframeTrack track : scene.tracksOfCamera(camera)) {
                    rows.add(new TimelineRow.Track(track, camera));
                    rowHeights.add(metrics.rowHeight());
                }
            }
        }

        if (!sceneTracks.isEmpty()) {
            if (showSections) {
                rows.add(new TimelineRow.Section(TimelineRow.Section.Kind.SCENE));
                rowHeights.add(metrics.sectionHeight());
            }
            for (KeyframeTrack track : sceneTracks) {
                rows.add(new TimelineRow.Track(track, null));
                rowHeights.add(metrics.rowHeight());
            }
        }

        float[] tops = new float[rows.size()];
        float[] heights = new float[rows.size()];
        float y = metrics.topPadding();
        for (int i = 0; i < rows.size(); i++) {
            tops[i] = y;
            heights[i] = rowHeights.get(i);
            y += heights[i];
        }
        return new TimelineLayout(scene, rows, tops, heights);
    }

    public List<TimelineRow> rows() {
        return this.rows;
    }

    public int size() {
        return this.rows.size();
    }

    public TimelineRow row(int index) {
        return this.rows.get(index);
    }

    public float rowHeight(int rowIndex) {
        return this.heights[rowIndex];
    }

    /** The top edge of a row, in screen space. The single definition of where a row starts. */
    public float rowTop(float contentY, int rowIndex) {
        return contentY + this.tops[rowIndex];
    }

    public float rowBottom(float contentY, int rowIndex) {
        return contentY + this.tops[rowIndex] + this.heights[rowIndex];
    }

    public float bottom(float contentY) {
        return contentY + this.totalHeight;
    }

    /** The row under a screen-space Y, or -1 when the position is not over a row. */
    public int rowAt(float screenY, float contentY) {
        float y = screenY - contentY;
        for (int i = 0; i < this.tops.length; i++) {
            if (y >= this.tops[i] && y < this.tops[i] + this.heights[i]) {
                return i;
            }
            if (y < this.tops[i]) {
                return -1;
            }
        }
        return -1;
    }

    /** The row showing this exact track, or -1. */
    public int rowOfTrack(KeyframeTrack track) {
        for (int i = 0; i < this.rows.size(); i++) {
            if (this.rows.get(i).trackOrNull() == track) {
                return i;
            }
        }
        return -1;
    }

    @Nullable
    public KeyframeTrack trackAt(int rowIndex) {
        if (rowIndex < 0 || rowIndex >= this.rows.size()) {
            return null;
        }
        return this.rows.get(rowIndex).trackOrNull();
    }

    /**
     * The rows a dragged row may be moved among.
     *
     * <p>A camera's track stays with its camera and a scene track stays in the scene section, so
     * dragging can never quietly move a track into another viewpoint's group. Camera groups move
     * among camera groups. The cut lane is the programme output and keeps its place at the top.
     */
    public List<Integer> reorderGroup(int rowIndex) {
        List<Integer> group = new ArrayList<>();
        if (rowIndex < 0 || rowIndex >= this.rows.size()) {
            return group;
        }

        TimelineRow dragged = this.rows.get(rowIndex);
        if (dragged instanceof TimelineRow.CameraGroup) {
            for (int i = 0; i < this.rows.size(); i++) {
                if (this.rows.get(i) instanceof TimelineRow.CameraGroup) {
                    group.add(i);
                }
            }
            return group;
        }

        if (dragged instanceof TimelineRow.Track track) {
            if (KeyframeTrack.isCameraSwitch(track.track())) {
                // The cut lane is always first; there is nothing to reorder it against.
                return group;
            }
            EditorCamera owner = track.owner();
            for (int i = 0; i < this.rows.size(); i++) {
                if (this.rows.get(i) instanceof TimelineRow.Track other && other.owner() == owner) {
                    group.add(i);
                }
            }
        }
        return group;
    }

    /**
     * The insertion slot a drag is hovering, as a position among {@link #reorderGroup}, or -1 when
     * the pointer is not over a place the row could go.
     *
     * <p>Counted from each row's own centre, so it works whatever heights the rows have.
     */
    public int insertionSlot(int rowIndex, float pointerY, float contentY) {
        List<Integer> group = this.reorderGroup(rowIndex);
        if (group.isEmpty()) {
            return -1;
        }
        float y = pointerY - contentY;
        int slot = 0;
        for (int row : group) {
            if (y < this.tops[row] + this.heights[row] / 2) {
                break;
            }
            slot += 1;
        }
        return slot;
    }

    /** The screen Y of the gap a slot refers to, for drawing the insertion indicator. */
    public float slotY(int rowIndex, int slot, float contentY) {
        List<Integer> group = this.reorderGroup(rowIndex);
        if (group.isEmpty()) {
            return contentY;
        }
        if (slot >= group.size()) {
            return this.rowBottom(contentY, group.get(group.size() - 1));
        }
        return this.rowTop(contentY, group.get(Math.max(0, slot)));
    }

    /** Whether a drag from {@code rowIndex} to {@code slot} would actually change anything. */
    public boolean wouldMove(int rowIndex, int slot) {
        List<Integer> group = this.reorderGroup(rowIndex);
        int currentIndex = group.indexOf(rowIndex);
        if (currentIndex < 0 || slot < 0 || slot > group.size()) {
            return false;
        }
        // Dropping into the gap immediately before or after itself changes nothing.
        return slot != currentIndex && slot != currentIndex + 1;
    }

    /**
     * The flat track indices that hold a reorder group's tracks, ascending.
     *
     * <p>A reorder only permutes the tracks already occupying these slots, so it can never disturb a
     * track outside the group - which is what makes the drag safe to apply as a single undoable step.
     */
    public int[] trackSlotsOf(int rowIndex) {
        List<Integer> group = this.reorderGroup(rowIndex);
        int[] slots = new int[group.size()];
        for (int i = 0; i < group.size(); i++) {
            slots[i] = this.scene.trackIndexOf(this.rows.get(group.get(i)).trackOrNull());
        }
        java.util.Arrays.sort(slots);
        return slots;
    }

    /**
     * The order the group's tracks should end up in for a drop at {@code slot}, or an empty list when
     * the row is not a track or the drop is a no-op.
     */
    public List<KeyframeTrack> trackOrderAfterMove(int rowIndex, int slot) {
        List<Integer> group = this.reorderGroup(rowIndex);
        int currentIndex = group.indexOf(rowIndex);
        if (currentIndex < 0 || slot < 0 || slot > group.size() || !this.wouldMove(rowIndex, slot)) {
            return List.of();
        }

        List<KeyframeTrack> order = new ArrayList<>();
        for (int row : group) {
            order.add(this.rows.get(row).trackOrNull());
        }
        KeyframeTrack moved = order.remove(currentIndex);
        // The pointer's gap counts the dragged row itself, so remove that offset before inserting.
        int target = slot > currentIndex ? slot - 1 : slot;
        order.add(Math.max(0, Math.min(target, order.size())), moved);
        return order;
    }

    /**
     * The camera order the registry should end up in for a drop at {@code slot}, or an empty list when
     * nothing would change.
     */
    public List<EditorCamera> cameraOrderAfterMove(int rowIndex, int slot) {
        List<Integer> group = this.reorderGroup(rowIndex);
        int currentIndex = group.indexOf(rowIndex);
        if (currentIndex < 0 || slot < 0 || slot > group.size() || !this.wouldMove(rowIndex, slot)) {
            return List.of();
        }

        List<EditorCamera> order = new ArrayList<>(this.scene.cameras);
        EditorCamera moved = ((TimelineRow.CameraGroup) this.rows.get(rowIndex)).camera();
        order.remove(moved);
        int target = slot > currentIndex ? slot - 1 : slot;
        order.add(Math.max(0, Math.min(target, order.size())), moved);
        return order;
    }

}

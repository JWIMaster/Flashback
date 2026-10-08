package com.moulberry.flashback.editor.ui.timeline;

import com.moulberry.flashback.state.EditorScene;
import com.moulberry.flashback.state.KeyframeTrack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Which keyframes are selected, and which one the inspector is showing.
 *
 * <p>Selections are held as references to the track object itself rather than to its position in the
 * scene's list. A track keeps its identity when rows are reordered, when keyframes move, and when
 * other tracks are added or removed, so a selection cannot silently start pointing at a different
 * track - which is the class of bug that makes a timeline feel unreliable.
 *
 * <p>The inspector's keyframe is stored here too, as the "primary" reference, so there is exactly one
 * answer to "what is selected" instead of a selection list and a separate editing index that can
 * disagree.
 */
public final class TimelineSelection {

    /** One selected keyframe. */
    public record Ref(KeyframeTrack track, int tick) {}

    private final Set<Ref> refs = new LinkedHashSet<>();
    @Nullable
    private Ref primary = null;

    public boolean contains(KeyframeTrack track, int tick) {
        return this.refs.contains(new Ref(track, tick));
    }

    public boolean isEmpty() {
        return this.refs.isEmpty();
    }

    public int count() {
        return this.refs.size();
    }

    public Set<Ref> refs() {
        return Collections.unmodifiableSet(this.refs);
    }

    /** How many keyframes of this track are selected. Cheap enough to call per row per frame. */
    public int countOf(KeyframeTrack track) {
        int count = 0;
        for (Ref ref : this.refs) {
            if (ref.track() == track) {
                count += 1;
            }
        }
        return count;
    }

    public List<Ref> refsOf(KeyframeTrack track) {
        List<Ref> matching = new ArrayList<>();
        for (Ref ref : this.refs) {
            if (ref.track() == track) {
                matching.add(ref);
            }
        }
        return matching;
    }

    @Nullable
    public Ref primary() {
        return this.primary;
    }

    public void replace(KeyframeTrack track, int tick) {
        this.refs.clear();
        Ref ref = new Ref(track, tick);
        this.refs.add(ref);
        this.primary = ref;
    }

    public void add(KeyframeTrack track, int tick) {
        Ref ref = new Ref(track, tick);
        this.refs.add(ref);
        this.primary = ref;
    }

    public void toggle(KeyframeTrack track, int tick) {
        Ref ref = new Ref(track, tick);
        if (!this.refs.remove(ref)) {
            this.refs.add(ref);
            this.primary = ref;
        } else if (ref.equals(this.primary)) {
            this.primary = this.refs.isEmpty() ? null : this.refs.iterator().next();
        }
    }

    /** Makes an already selected keyframe the one the inspector shows. */
    public void setPrimary(@Nullable Ref ref) {
        if (ref != null && this.refs.contains(ref)) {
            this.primary = ref;
        }
    }

    public void addAll(TimelineSelection other) {
        this.refs.addAll(other.refs);
        if (this.primary == null) {
            this.primary = other.primary;
        }
    }

    public void remove(Ref ref) {
        this.refs.remove(ref);
        if (ref.equals(this.primary)) {
            this.primary = this.refs.isEmpty() ? null : this.refs.iterator().next();
        }
    }

    public void clear() {
        this.refs.clear();
        this.primary = null;
    }

    /**
     * Drops references that no longer mean anything: a track removed from the scene, or a keyframe
     * deleted from its track.
     *
     * <p>Called once a frame, so every other part of the UI can assume the selection is real.
     */
    public void prune(EditorScene scene) {
        Set<KeyframeTrack> live = Collections.newSetFromMap(new IdentityHashMap<>());
        live.addAll(scene.keyframeTracks);
        this.refs.removeIf(ref -> !live.contains(ref.track())
            || !ref.track().keyframesByTick.containsKey(ref.tick()));
        if (this.primary != null && !this.refs.contains(this.primary)) {
            this.primary = this.refs.isEmpty() ? null : this.refs.iterator().next();
        }
    }

    /** The keyframes of one track that are selected, for the copy and delete operations. */
    public List<Integer> ticksOf(KeyframeTrack track) {
        List<Integer> ticks = new ArrayList<>();
        for (Ref ref : this.refs) {
            if (ref.track() == track) {
                ticks.add(ref.tick());
            }
        }
        return ticks;
    }

}

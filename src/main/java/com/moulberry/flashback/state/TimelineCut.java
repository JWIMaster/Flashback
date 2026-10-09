package com.moulberry.flashback.state;

/**
 * A stretch of replay ticks that has been cut out of the edit.
 *
 * <p>Cutting is non-destructive: the replay on disk is never touched, and the cut is only a note in
 * the project saying that this stretch should not be played or exported. The ticks either side of it
 * then play back to back, which is what makes cutting pieces together possible.
 *
 * <p>{@link #start} is inclusive and {@link #end} is exclusive, matching how a tick range reads
 * elsewhere in the editor. Kept as a class with public fields rather than a record because it is
 * persisted by Gson, which reads and writes fields directly.
 */
public final class TimelineCut {

    /** First removed tick. */
    public int start;

    /** One past the last removed tick. */
    public int end;

    /** Used by Gson when reading a saved project. */
    private TimelineCut() {
    }

    public TimelineCut(int start, int end) {
        this.start = Math.min(start, end);
        this.end = Math.max(start, end);
    }

    public int length() {
        return this.end - this.start;
    }

    public boolean contains(int tick) {
        return tick >= this.start && tick < this.end;
    }

    /** Whether this cut and another share any tick, in which case they are really one cut. */
    public boolean touches(TimelineCut other) {
        return this.start <= other.end && other.start <= this.end;
    }

    public TimelineCut copy() {
        return new TimelineCut(this.start, this.end);
    }

}

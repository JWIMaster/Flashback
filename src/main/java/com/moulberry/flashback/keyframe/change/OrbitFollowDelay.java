package com.moulberry.flashback.keyframe.change;

import org.joml.Vector3d;

import java.util.UUID;

/**
 * The short history behind an orbit camera's "lag behind" option.
 *
 * <p>An orbit that is glued to its subject turns with every twitch of their head, which reads as
 * mechanical. Lagging means aiming at where the subject was a moment ago, so the camera trails them
 * and catches up smoothly. That needs a little memory of where they have been: this keeps the last
 * few seconds of one subject's position, keyed by replay tick.
 *
 * <p>The history is deliberately keyed by tick rather than by frame. A frame can ask for the same
 * instant more than once (the export's motion-blur pass samples neighbouring ticks), and a delay
 * measured in ticks then answers the same way every time instead of advancing once per sample.
 */
public final class OrbitFollowDelay {

    /** At twenty ticks a second this is five seconds, further than any usable lag. */
    private static final int CAPACITY = 100;

    /** A jump this large is a seek, not movement, so history across it says nothing. */
    private static final float MAX_GAP = 20.0f;

    private static UUID target;
    private static final float[] ticks = new float[CAPACITY];
    private static final double[] positions = new double[CAPACITY * 3];
    private static int count;
    private static int writeIndex;
    private static float newest = Float.NaN;

    private OrbitFollowDelay() {
    }

    /** Starts again with no history, remembering nothing about any subject. */
    public static void clear() {
        target = null;
        count = 0;
        writeIndex = 0;
        newest = Float.NaN;
    }

    /**
     * Adds where the subject is now.
     *
     * <p>Anything recorded for a different subject, or on the far side of a seek, is dropped: it
     * describes somewhere the camera is not following.
     */
    public static void record(UUID subject, float tick, Vector3d position) {
        if (!subject.equals(target) || Float.isNaN(newest) || tick < newest || tick - newest > MAX_GAP) {
            clear();
            target = subject;
        }
        if (!Float.isNaN(newest) && Math.abs(tick - newest) < 0.001f) {
            // Several passes in one frame ask about the same instant, and one entry answers them all.
            return;
        }
        ticks[writeIndex] = tick;
        positions[writeIndex * 3] = position.x;
        positions[writeIndex * 3 + 1] = position.y;
        positions[writeIndex * 3 + 2] = position.z;
        writeIndex = (writeIndex + 1) % CAPACITY;
        if (count < CAPACITY) {
            count++;
        }
        newest = tick;
    }

    /**
     * Where the subject was at {@code tick}, interpolated between the recorded ticks.
     *
     * <p>Returns null when nothing is known about that subject. A tick before the history begins is
     * answered with the oldest position, so the lag eases in over the first moments of an orbit
     * rather than snapping into place the instant the keyframe is reached.
     */
    public static Vector3d sample(UUID subject, float tick) {
        if (count == 0 || !subject.equals(target)) {
            return null;
        }

        int oldestIndex = (writeIndex - count + CAPACITY) % CAPACITY;
        if (tick <= ticks[oldestIndex]) {
            return positionAt(oldestIndex);
        }
        if (tick >= newest) {
            return positionAt((writeIndex - 1 + CAPACITY) % CAPACITY);
        }

        for (int i = 1; i < count; i++) {
            int index = (oldestIndex + i) % CAPACITY;
            if (ticks[index] >= tick) {
                int previous = (index - 1 + CAPACITY) % CAPACITY;
                float span = ticks[index] - ticks[previous];
                if (span <= 0.0f) {
                    return positionAt(index);
                }
                float amount = (tick - ticks[previous]) / span;
                return new Vector3d(
                    positions[previous * 3] + (positions[index * 3] - positions[previous * 3]) * amount,
                    positions[previous * 3 + 1] + (positions[index * 3 + 1] - positions[previous * 3 + 1]) * amount,
                    positions[previous * 3 + 2] + (positions[index * 3 + 2] - positions[previous * 3 + 2]) * amount
                );
            }
        }
        return positionAt((writeIndex - 1 + CAPACITY) % CAPACITY);
    }

    private static Vector3d positionAt(int index) {
        return new Vector3d(positions[index * 3], positions[index * 3 + 1], positions[index * 3 + 2]);
    }

}

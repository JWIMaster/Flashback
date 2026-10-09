package com.moulberry.flashback.state;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Cutting replay ticks out of the edit, without touching the recording.
 *
 * <p>The timeline maths is what playback and the export both read, so it is worth pinning down: a
 * mistake here would drop the wrong stretch of footage or leave a gap in an exported video.
 */
public class CutTest {

    private static int failures = 0;

    public static void main(String[] args) {
        cutsAndKeepsTicks();
        neighbouringCutsBecomeOne();
        theTimelineShortensByWhatWasCut();
        playbackStepsOverCuts();
        untidyProjectsAreNormalised();
        emptyRangesAreIgnored();
        cutsSurviveSavingAndLoading();
        cutsAreUndoable();

        if (failures > 0) {
            System.out.println("FAILURES: " + failures);
            System.exit(1);
        }
        System.out.println("All cut tests passed");
    }

    private static EditorState stateWith(int from, int to) {
        EditorState state = new EditorState();
        state.addCut(from, to);
        return state;
    }

    private static void cutsAndKeepsTicks() {
        EditorState state = stateWith(100, 200);
        check("a cut holds the ticks it was given",
            state.cuts.size() == 1 && state.cuts.get(0).start == 100 && state.cuts.get(0).end == 200);
        check("ticks inside the cut are cut", state.isCut(100) && state.isCut(150) && state.isCut(199));
        check("the tick the cut ends on is kept", !state.isCut(200));
        check("ticks outside the cut are kept", !state.isCut(99) && !state.isCut(2000));
        check("the cut can be found from a tick inside it",
            state.cutAt(150) != null && state.cutAt(150).start == 100);
        check("a kept tick has no cut", state.cutAt(50) == null);
    }

    private static void neighbouringCutsBecomeOne() {
        EditorState state = stateWith(100, 200);
        state.addCut(150, 250);
        check("an overlapping cut is merged into one",
            state.cuts.size() == 1 && state.cuts.get(0).start == 100 && state.cuts.get(0).end == 250);

        // Touching rather than overlapping: still one stretch of removed replay, so one cut.
        state.addCut(250, 300);
        check("a cut that starts where the last one ended is merged",
            state.cuts.size() == 1 && state.cuts.get(0).end == 300);

        state.addCut(500, 600);
        check("a separate cut is kept apart", state.cuts.size() == 2);
        check("the cuts stay in order",
            state.cuts.get(0).start < state.cuts.get(1).start);
    }

    private static void theTimelineShortensByWhatWasCut() {
        EditorState state = stateWith(100, 200);
        check("nothing is removed before the cut", state.removedBefore(50) == 0);
        check("half the cut is removed half way through it", state.removedBefore(150) == 50);
        check("the whole cut is removed by its end", state.removedBefore(200) == 100);
        check("later ticks are still one cut shorter", state.removedBefore(400) == 100);

        check("a kept tick moves up the timeline by the cuts before it", state.keptTick(250) == 150);
        check("ticks before the cut do not move", state.keptTick(50) == 50);
        check("the shortened replay is the length minus the cuts", state.keptLength(300) == 200);

        state.addCut(250, 300);
        check("two cuts remove both", state.keptLength(400) == 250);
    }

    private static void playbackStepsOverCuts() {
        EditorState state = stateWith(100, 200);
        check("a kept tick is left where it is", state.nextKeptTick(50) == 50);
        check("the tick a cut starts on moves to its end", state.nextKeptTick(100) == 200);
        check("a tick inside the cut moves to its end", state.nextKeptTick(150) == 200);
        check("a tick after the cut is left where it is", state.nextKeptTick(200) == 200);

        // Cuts can only be adjacent after being merged, but a project written by hand could still
        // hold two, and stepping must land past both rather than in the second one.
        EditorState handEdited = new EditorState();
        handEdited.cuts = new ArrayList<>(List.of(new TimelineCut(100, 200), new TimelineCut(200, 260)));
        check("stepping over a cut lands past every cut it touches", handEdited.nextKeptTick(150) == 260);
    }

    private static void untidyProjectsAreNormalised() {
        EditorState state = new EditorState();
        state.cuts = new ArrayList<>(List.of(
            new TimelineCut(400, 500),
            new TimelineCut(100, 200),
            new TimelineCut(150, 250),
            new TimelineCut(700, 700)));

        List<TimelineCut> normalised = state.normalisedCuts();
        check("normalising puts the cuts in order", normalised.get(0).start == 100 && normalised.get(1).start == 400);
        check("normalising merges the overlapping pair", normalised.get(0).end == 250);
        check("normalising drops an empty cut", normalised.size() == 2);

        EditorState oldProject = new EditorState();
        oldProject.cuts = null;
        check("a project with no cuts at all is empty rather than broken",
            oldProject.normalisedCuts().isEmpty() && !oldProject.isCut(0));
    }

    private static void emptyRangesAreIgnored() {
        EditorState state = new EditorState();
        check("cutting nothing records nothing", state.addCut(120, 120) == null && state.cuts.isEmpty());
        state.addCut(200, 100);
        check("a backwards range is taken as the same range",
            state.cuts.size() == 1 && state.cuts.get(0).start == 100 && state.cuts.get(0).end == 200);
        state.removeCut(state.cuts.get(0));
        check("a cut can be put back", state.cuts.isEmpty() && !state.isCut(150));

        // Restoring only part of a cut leaves the rest of it removed, and a middle restore leaves a
        // cut on each side.
        EditorState partial = stateWith(100, 300);
        partial.restoreRange(120, 150);
        check("restoring part of a cut shortens it", partial.cuts.size() == 2
            && partial.cuts.get(0).end == 120 && partial.cuts.get(1).start == 150);
        check("the ticks that were restored are kept again",
            !partial.isCut(120) && !partial.isCut(149) && partial.isCut(119) && partial.isCut(150));

        partial.restoreRange(0, 1000);
        check("restoring everything clears the cuts", partial.cuts.isEmpty());

        EditorState edge = stateWith(100, 200);
        edge.restoreRange(50, 100);
        check("restoring up to the start of a cut leaves it whole",
            edge.cuts.size() == 1 && edge.cuts.get(0).start == 100 && edge.cuts.get(0).end == 200);
        edge.restoreRange(50, 60);
        check("restoring a stretch that was kept changes nothing", edge.cuts.size() == 1);
    }

    private static void cutsSurviveSavingAndLoading() {
        Path file = null;
        try {
            file = Files.createTempFile("flashback-cut-test", ".json");
            EditorState state = stateWith(100, 200);
            state.addCut(400, 500);
            state.save(file);

            EditorState loaded = EditorState.load(file);
            check("a saved project remembers its cuts",
                loaded != null && loaded.cuts.size() == 2
                    && loaded.cuts.get(0).start == 100 && loaded.cuts.get(1).end == 500);
            check("a loaded project skips its cuts", loaded != null && loaded.isCut(150) && !loaded.isCut(300));
        } catch (Exception e) {
            failures += 1;
            System.out.println("FAIL: saving and loading cuts threw " + e);
        } finally {
            if (file != null) {
                try {
                    Files.deleteIfExists(file);
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * Cutting goes through the same undo history as every other edit, which means an action that
     * applies to the project rather than to a scene.
     */
    private static void cutsAreUndoable() {
        Path file = null;
        try {
            EditorState state = new EditorState();
            long stamp = state.acquireRead();
            EditorScene scene;
            try {
                scene = state.getCurrentScene(stamp);
            } finally {
                state.release(stamp);
            }

            List<TimelineCut> before = List.of();
            state.addCut(100, 200);
            List<TimelineCut> after = List.copyOf(state.normalisedCuts());

            scene.push(new EditorSceneHistoryEntry(
                List.of(new EditorSceneHistoryAction.SetCuts(after, before)),
                List.of(new EditorSceneHistoryAction.SetCuts(before, after)),
                "Cut"), state);
            check("recording a cut leaves it in place", state.isCut(150));

            scene.undo(state, description -> { });
            check("undo puts the cut stretch back", state.cuts.isEmpty() && !state.isCut(150));

            scene.redo(state, description -> { });
            check("redo cuts it out again", state.isCut(150));

            // The history is saved with the project, so the action has to survive a round trip.
            file = Files.createTempFile("flashback-cut-history", ".json");
            state.save(file);
            EditorState reloaded = EditorState.load(file);
            check("a saved project keeps the cut", reloaded != null && reloaded.isCut(150));
            long reloadedStamp = reloaded.acquireRead();
            EditorScene reloadedScene;
            try {
                reloadedScene = reloaded.getCurrentScene(reloadedStamp);
            } finally {
                reloaded.release(reloadedStamp);
            }
            reloadedScene.undo(reloaded, description -> { });
            check("undo still works after reloading", !reloaded.isCut(150) && reloaded.cuts.isEmpty());
        } catch (Exception e) {
            failures += 1;
            System.out.println("FAIL: cut undo threw " + e);
        } finally {
            if (file != null) {
                try {
                    Files.deleteIfExists(file);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static void check(String what, boolean condition) {
        if (condition) {
            System.out.println("ok:   " + what);
        } else {
            failures += 1;
            System.out.println("FAIL: " + what);
        }
    }

}

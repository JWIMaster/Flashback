package com.moulberry.flashback.state;

import java.util.UUID;

/** Returns the state the harness built, instead of loading a project from disk. */
public class EditorStateManager {

    public static EditorState injected;

    public static EditorState get(UUID identifier) {
        if (injected == null) {
            throw new IllegalStateException("The harness did not inject an editor state");
        }
        return injected;
    }

    public static EditorState getCurrent() {
        return injected;
    }

    public static void saveIfNeeded() {
    }

    public static void reset() {
    }
}

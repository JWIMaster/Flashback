package com.moulberry.flashback.state;

import com.moulberry.flashback.Flashback;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

public class EditorStateManager {

    private static long AUTOSAVE_INTERVAL_MILLIS = 30 * 1000; // 30 seconds

    private static final ReentrantLock lock = new ReentrantLock();
    private static UUID currentUuid = null;
    private static EditorState current = null;
    private static long lastSave = 0;

    public static void saveIfNeeded() {
        try {
            lock.lock();

            if (current == null || currentUuid == null) {
                return;
            }

            long currentTime = System.currentTimeMillis();

            if (!current.dirty) {
                lastSave = currentTime;
            } else if (currentTime < lastSave || currentTime - lastSave > AUTOSAVE_INTERVAL_MILLIS) {
                save();
            }
        } finally {
            lock.unlock();
        }

    }

    private static void save() {
        if (current == null || currentUuid == null) {
            return;
        }

        Path normalPath = getPath(currentUuid, false);
        Path oldPath = getPath(currentUuid, true);

        // Backup
        if (Files.exists(normalPath)) {
            try {
                Files.move(normalPath, oldPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {}
        }

        // Save
        current.save(normalPath);
        lastSave = System.currentTimeMillis();
    }

    private static void load() {
        lastSave = System.currentTimeMillis();

        Path normalPath = getPath(currentUuid, false);
        Path backupPath = getPath(currentUuid, true);

        // A project that cannot be read is never deleted. The usual reason is that it was written by
        // a newer Flashback that knows a keyframe type this version does not, and discarding someone's
        // editing work because of a version difference is far worse than leaving the file on disk. If
        // the backup loads it will be saved over the unreadable file on the next save, which moves
        // that file aside as the ".old" backup rather than destroying it.
        if (Files.exists(normalPath)) {
            current = EditorState.load(normalPath);
            if (current != null) {
                return;
            }
            Flashback.LOGGER.warn("Could not read editor state {}, leaving it in place", normalPath);
        }

        if (Files.exists(backupPath)) {
            current = EditorState.load(backupPath);
            if (current != null) {
                return;
            }
            Flashback.LOGGER.warn("Could not read editor state backup {}, leaving it in place", backupPath);
        }

        current = new EditorState();
    }

    public static void reset() {
        try {
            lock.lock();

            save();
            current = null;
            currentUuid = null;
        } finally {
            lock.unlock();
        }
    }

    @Nullable
    public static EditorState getCurrent() {
        if (Flashback.isExporting()) {
            return Flashback.EXPORT_JOB.getSettings().editorState();
        }
        if (!Flashback.isInReplay()) {
            return null;
        }
        return current;
    }

    public static EditorState get(UUID replayUuid) {
        Objects.requireNonNull(replayUuid);
        try {
            lock.lock();

            if (current == null || !Objects.equals(currentUuid, replayUuid)) {
                save();
                currentUuid = replayUuid;
                load();
            }

            return current;
        } finally {
            lock.unlock();
        }
    }

    private static Path getPath(UUID replayUuid, boolean old) {
        Objects.requireNonNull(replayUuid);

        String filename = replayUuid + ".json";
        if (old) {
            filename += ".old";
        }
        return Flashback.getDataDirectory()
                .resolve("editor_states")
                .resolve(filename);
    }

}

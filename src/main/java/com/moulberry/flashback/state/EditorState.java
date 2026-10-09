package com.moulberry.flashback.state;

import com.mojang.authlib.GameProfile;
import com.moulberry.flashback.FilePlayerSkin;
import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.FlashbackGson;
import com.moulberry.flashback.combo_options.GlowingOverride;
import com.moulberry.flashback.configuration.FlashbackConfigV1;
import com.moulberry.flashback.keyframe.Keyframe;
import com.moulberry.flashback.keyframe.KeyframeType;
import com.moulberry.flashback.keyframe.change.KeyframeChange;
import com.moulberry.flashback.keyframe.change.KeyframeChangeCameraFov;
import com.moulberry.flashback.keyframe.change.KeyframeChangeCameraPositionOnly;
import com.moulberry.flashback.keyframe.change.KeyframeChangeCameraRotationOnly;
import com.moulberry.flashback.keyframe.change.KeyframeChangeCameraShake;
import com.moulberry.flashback.keyframe.change.KeyframeChangeCameraSwitch;
import com.moulberry.flashback.keyframe.change.KeyframeChangeFov;
import com.moulberry.flashback.keyframe.change.KeyframeChangeTickrate;
import com.moulberry.flashback.keyframe.handler.KeyframeHandler;
import com.moulberry.flashback.keyframe.impl.CameraKeyframe;
import com.moulberry.flashback.keyframe.impl.CameraPositionKeyframe;
import com.moulberry.flashback.keyframe.impl.CameraRotationKeyframe;
import com.moulberry.flashback.keyframe.impl.CameraSwitchKeyframe;
import com.moulberry.flashback.keyframe.types.CameraFovKeyframeType;
import com.moulberry.flashback.keyframe.types.CameraKeyframeType;
import com.moulberry.flashback.keyframe.types.CameraPositionKeyframeType;
import com.moulberry.flashback.keyframe.types.CameraRotationKeyframeType;
import com.moulberry.flashback.keyframe.types.CameraShakeKeyframeType;
import com.moulberry.flashback.keyframe.types.CameraSwitchKeyframeType;
import com.moulberry.flashback.keyframe.types.FOVKeyframeType;
import com.moulberry.flashback.keyframe.types.SpectateKeyframeType;
import com.moulberry.flashback.playback.ReplayServer;
import com.moulberry.flashback.visuals.ReplayVisuals;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.level.GameType;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.StampedLock;
import com.moulberry.flashback.keyframe.handler.MinecraftKeyframeHandler;

public class EditorState {

    private static final org.slf4j.Logger LOGGER = Flashback.LOGGER;

    volatile transient boolean dirty = false;
    public volatile transient int modCount = ThreadLocalRandom.current().nextInt();
    private volatile transient int lastRealTimeMappingModCount = this.modCount;
    private volatile transient RealTimeMapping realTimeMapping = null;

    public final ReplayVisuals replayVisuals = new ReplayVisuals();

    private final StampedLock sceneLock = new StampedLock();
    private List<EditorScene> scenes;
    private int sceneIndex = 0;

    /**
     * Version of the persisted form, or 0 for "written before versioning existed".
     *
     * <p>Deliberately not initialised to {@link #CURRENT_SCHEMA_VERSION}: Gson runs this class's
     * no-argument constructor when loading, so a field initialiser would make every older project
     * claim to be current and skip its migration. The version is stamped when saving instead - see
     * {@link #save(Path)}.
     */
    public int schemaVersion = 0;
    public static final int CURRENT_SCHEMA_VERSION = 2;

    public double zoomMin = 0.0;
    public double zoomMax = 1.0;

    /**
     * Replay ticks cut out of the edit, in order.
     *
     * <p>Cutting never touches the replay: this is a note in the project that a stretch should not be
     * played or exported, so the ticks either side of it run together. Absent from projects written
     * before cuts existed, where the field's initialiser leaves it empty.
     */
    public List<TimelineCut> cuts = new ArrayList<>();

    /**
     * How wide the timeline's row list is, or 0 to size it to the window.
     *
     * <p>A view preference, stored with the project for the same reason the zoom is: it is part of
     * how this replay is being edited, and re-dragging it every session would be a small annoyance
     * every session.
     */
    public double timelinePanelWidth = 0.0;

    public Set<String> usedByPaths = new HashSet<>();

    public UUID audioSourceEntity = null;
    public Set<UUID> hideDuringExport = new HashSet<>();
    public boolean hideAllSpectators = false;
    public Set<UUID> muteVoice = new HashSet<>();
    public Set<UUID> hideNametags = new HashSet<>();
    public Map<UUID, GameProfile> skinOverride = new HashMap<>();
    public Map<UUID, FilePlayerSkin> skinOverrideFromFile = new HashMap<>();
    public Map<UUID, String> nameOverride = new HashMap<>();
    public Map<UUID, GlowingOverride> glowingOverride = new HashMap<>();
    public Set<UUID> hideTeamPrefix = new HashSet<>();
    public Set<UUID> hideTeamSuffix = new HashSet<>();
    public Set<UUID> hideBelowName = new HashSet<>();
    public Set<UUID> hideCape = new HashSet<>();
    public Set<String> filteredEntities = new HashSet<>();
    public Set<String> filteredParticles = new HashSet<>();
    public Map<UUID, EnumSet<EquipmentSlot>> hiddenEquipment = new HashMap<>();
    public Map<UUID, EnumSet<PlayerModelPart>> hiddenModelParts = new HashMap<>();

    public EditorState() {
        this.scenes = new ArrayList<>();
        this.scenes.add(new EditorScene("Scene 1"));

        FlashbackConfigV1 config = Flashback.getConfig();
        if (config.internal.enableOverrideFovByDefault) {
            this.replayVisuals.overrideFov = true;
            if (this.replayVisuals.overrideFovAmount < 0) {
                this.replayVisuals.overrideFovAmount = config.internal.defaultOverrideFov;
            }
        }
    }

    @ApiStatus.Internal
    public long acquireRead() {
        return this.sceneLock.readLock();
    }
    @ApiStatus.Internal
    public long acquireWrite() {
        return this.sceneLock.writeLock();
    }

    @ApiStatus.Internal
    public void release(long stamp) {
        this.sceneLock.unlock(stamp);
    }

    @ApiStatus.Internal
    public List<EditorScene> getScenes(long stamp) {
        if (!this.sceneLock.validate(stamp)) {
            throw new IllegalStateException("Invalid stamp!");
        }
        return this.scenes;
    }

    public int getSceneIndex() {
        return this.sceneIndex;
    }

    @ApiStatus.Internal
    public void setSceneIndex(int sceneIndex, long stamp) {
        if (!this.sceneLock.validate(stamp)) {
            throw new IllegalStateException("Invalid stamp!");
        }
        this.sceneIndex = sceneIndex;
    }

    @ApiStatus.Internal
    public EditorScene getCurrentScene(long stamp) {
        if (!this.sceneLock.validate(stamp)) {
            throw new IllegalStateException("Invalid stamp!");
        }
        return this.scenes.get(this.sceneIndex);
    }

    private EditorScene currentScene() {
        return this.scenes.get(this.sceneIndex);
    }

    @Nullable
    public Camera getAudioCamera() {
        if (this.audioSourceEntity == null) {
            return null;
        }

        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return null;
        }

        Entity sourceEntity = level.getEntities().get(this.audioSourceEntity);
        if (sourceEntity == null) {
            return null;
        }

        Camera dummyCamera = new Camera();
        dummyCamera.eyeHeight = sourceEntity.getEyeHeight();
        dummyCamera.setLevel(level);
        dummyCamera.setEntity(sourceEntity);
        dummyCamera.update(Minecraft.getInstance().deltaTracker);
        return dummyCamera;
    }

    public void markDirty() {
        this.dirty = true;
        this.modCount += 1;
    }

    /**
     * Cuts a stretch of ticks out of the edit, merging it into any cut it touches.
     *
     * <p>Merging rather than storing neighbours separately keeps one stretch of removed replay as one
     * cut, so the timeline draws one region and removing it once puts everything back.
     *
     * @return the cut that now covers the range, or null when the range was empty
     */
    public @Nullable TimelineCut addCut(int from, int to) {
        int start = Math.min(from, to);
        int end = Math.max(from, to);
        if (start == end) {
            return null;
        }

        TimelineCut added = new TimelineCut(start, end);
        List<TimelineCut> remaining = new ArrayList<>();
        for (TimelineCut existing : this.normalisedCuts()) {
            if (existing.touches(added)) {
                added = new TimelineCut(Math.min(added.start, existing.start), Math.max(added.end, existing.end));
            } else {
                remaining.add(existing);
            }
        }
        remaining.add(added);
        remaining.sort(Comparator.comparingInt(cut -> cut.start));
        this.cuts = remaining;
        this.markDirty();
        return added;
    }

    /**
     * Replaces the cuts wholesale, which is what undoing a cut edit needs.
     *
     * <p>Recording a whole list rather than one change keeps undo exact even when the edit merged two
     * cuts into one or split one in two.
     */
    public void setCuts(List<TimelineCut> cuts) {
        this.cuts = cuts == null ? new ArrayList<>() : new ArrayList<>(cuts);
        this.markDirty();
    }

    /** Puts a cut stretch back into the edit. */
    public void removeCut(TimelineCut cut) {
        if (this.cuts != null && this.cuts.remove(cut)) {
            this.markDirty();
        }
    }

    /**
     * Puts a stretch of ticks back into the edit, splitting any cut it sits inside.
     *
     * <p>Restoring part of a cut rather than all of it is what makes the tool usable on a long cut
     * that was only slightly too greedy: the rest of the cut stays removed.
     */
    public void restoreRange(int from, int to) {
        int start = Math.min(from, to);
        int end = Math.max(from, to);
        if (start == end) {
            return;
        }

        List<TimelineCut> remaining = new ArrayList<>();
        boolean changed = false;
        for (TimelineCut cut : this.normalisedCuts()) {
            if (cut.end <= start || cut.start >= end) {
                remaining.add(cut);
                continue;
            }
            changed = true;
            if (cut.start < start) {
                remaining.add(new TimelineCut(cut.start, start));
            }
            if (cut.end > end) {
                remaining.add(new TimelineCut(end, cut.end));
            }
        }
        if (changed) {
            this.cuts = remaining;
            this.markDirty();
        }
    }

    /** The cut covering this tick, or null when the tick is kept. */
    public @Nullable TimelineCut cutAt(int tick) {
        for (TimelineCut cut : this.normalisedCuts()) {
            if (cut.contains(tick)) {
                return cut;
            }
        }
        return null;
    }

    /** Whether this tick is inside a cut, and so is neither played nor exported. */
    public boolean isCut(int tick) {
        return this.cutAt(tick) != null;
    }

    /**
     * The first tick at or after {@code tick} that survives the cuts.
     *
     * <p>Playback uses this to step over removed stretches, so a cut is skipped rather than shown.
     */
    public int nextKeptTick(int tick) {
        int result = Math.max(0, tick);
        boolean moved = true;
        while (moved) {
            moved = false;
            for (TimelineCut cut : this.normalisedCuts()) {
                if (cut.contains(result)) {
                    result = cut.end;
                    moved = true;
                }
            }
        }
        return result;
    }

    /** How many ticks the cuts remove from the replay before {@code tick}. */
    public double removedBefore(double tick) {
        double removed = 0;
        for (TimelineCut cut : this.normalisedCuts()) {
            if (tick <= cut.start) {
                break;
            }
            removed += Math.min(tick, cut.end) - cut.start;
        }
        return removed;
    }

    /** Maps a tick in the replay onto the shorter timeline the cuts leave behind. */
    public double keptTick(double tick) {
        return tick - this.removedBefore(tick);
    }

    /** How long the replay runs once the cuts are removed. */
    public double keptLength(int totalTicks) {
        return this.keptTick(totalTicks);
    }

    /**
     * The cuts in order, with overlaps merged and empty ones dropped.
     *
     * <p>Saved projects are trusted but not assumed: a hand-edited file or one written by a future
     * build could hold overlapping cuts, and every reader here relies on them being disjoint and
     * ordered. A project with no cuts keeps the list it already has, so this stays cheap.
     */
    public List<TimelineCut> normalisedCuts() {
        if (this.cuts == null) {
            this.cuts = new ArrayList<>();
            return this.cuts;
        }
        boolean sorted = true;
        for (int i = 1; i < this.cuts.size(); i++) {
            TimelineCut previous = this.cuts.get(i - 1);
            TimelineCut current = this.cuts.get(i);
            if (previous == null || current == null || current.start < previous.end) {
                sorted = false;
                break;
            }
        }
        if (sorted) {
            return this.cuts;
        }

        List<TimelineCut> cleaned = new ArrayList<>();
        for (TimelineCut cut : this.cuts) {
            if (cut != null && cut.length() > 0) {
                cleaned.add(cut.copy());
            }
        }
        cleaned.sort(Comparator.comparingInt(cut -> cut.start));
        for (int i = 1; i < cleaned.size(); ) {
            TimelineCut previous = cleaned.get(i - 1);
            TimelineCut current = cleaned.get(i);
            if (previous.touches(current)) {
                cleaned.set(i - 1, new TimelineCut(Math.min(previous.start, current.start), Math.max(previous.end, current.end)));
                cleaned.remove(i);
            } else {
                i += 1;
            }
        }
        this.cuts = cleaned;
        return this.cuts;
    }

    public void save(Path path) {
        this.dirty = false;
        // Stamp what is actually being written, so a project is only ever recorded as being at a
        // version it has really been migrated to.
        this.schemaVersion = CURRENT_SCHEMA_VERSION;

        String serialized = FlashbackGson.COMPRESSED.toJson(this, EditorState.class);

        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, serialized, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE,
                    StandardOpenOption.SYNC);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Nullable
    public static EditorState load(Path path) {
        if (!Files.exists(path)) {
            return null;
        }

        String serialized = null;
        try {
            serialized = Files.readString(path);
            EditorState loaded = FlashbackGson.COMPRESSED.fromJson(serialized, EditorState.class);
            if (loaded != null) {
                loaded.repairDeserialisedState();
                if (loaded.migrateSchema()) {
                    // Persist the upgraded form through the normal save path, which takes a backup
                    // first. Writing here would clobber the user's project with no backup, which is
                    // exactly what must not happen to existing edits.
                    loaded.dirty = true;
                }
            }
            return loaded;
        } catch (Exception e) {
            Flashback.LOGGER.error("Error loading editor state", e);
            Flashback.LOGGER.error("JSON: {}", serialized);
            return null;
        }
    }

    /**
     * Restores the invariants that reflection-based deserialisation can break.
     *
     * <p>Gson creates an object with {@code Unsafe.allocateInstance} when its class has no
     * no-argument constructor, and that runs no field initialisers at all. A field missing from an
     * older project's JSON then arrives as null instead of the empty collection or default the rest
     * of the code assumes - which is how {@code EditorScene.cameras}, added after that project was
     * written, reached playback as null.
     *
     * <p>The no-argument constructors on the model classes prevent this for fields added in future;
     * this pass makes state that has already been read safe before anything looks at it.
     */
    private void repairDeserialisedState() {
        if (this.scenes == null) {
            this.scenes = new ArrayList<>();
        }
        this.scenes.removeIf(Objects::isNull);
        if (this.scenes.isEmpty()) {
            this.scenes.add(new EditorScene("Scene 1"));
        }
        if (this.sceneIndex < 0 || this.sceneIndex >= this.scenes.size()) {
            this.sceneIndex = 0;
        }

        for (EditorScene scene : this.scenes) {
            if (scene.keyframeTracks == null) {
                scene.keyframeTracks = new ArrayList<>();
            }
            if (scene.cameras == null) {
                scene.cameras = new ArrayList<>();
            }
            scene.keyframeTracks.removeIf(Objects::isNull);
            scene.cameras.removeIf(Objects::isNull);
            for (EditorCamera camera : scene.cameras) {
                if (camera.id == null) {
                    camera.id = UUID.randomUUID();
                }
                if (camera.kind == null) {
                    camera.kind = EditorCamera.Kind.FREE;
                }
            }
        }

        if (this.usedByPaths == null) this.usedByPaths = new HashSet<>();
        if (this.hideDuringExport == null) this.hideDuringExport = new HashSet<>();
        if (this.muteVoice == null) this.muteVoice = new HashSet<>();
        if (this.hideNametags == null) this.hideNametags = new HashSet<>();
        if (this.skinOverride == null) this.skinOverride = new HashMap<>();
        if (this.skinOverrideFromFile == null) this.skinOverrideFromFile = new HashMap<>();
        if (this.nameOverride == null) this.nameOverride = new HashMap<>();
        if (this.glowingOverride == null) this.glowingOverride = new HashMap<>();
        if (this.hideTeamPrefix == null) this.hideTeamPrefix = new HashSet<>();
        if (this.hideTeamSuffix == null) this.hideTeamSuffix = new HashSet<>();
        if (this.hideBelowName == null) this.hideBelowName = new HashSet<>();
        if (this.hideCape == null) this.hideCape = new HashSet<>();
        if (this.filteredEntities == null) this.filteredEntities = new HashSet<>();
        if (this.filteredParticles == null) this.filteredParticles = new HashSet<>();
        if (this.hiddenEquipment == null) this.hiddenEquipment = new HashMap<>();
        if (this.hiddenModelParts == null) this.hiddenModelParts = new HashMap<>();
    }

    public EditorState copy() {
        String serialized = FlashbackGson.COMPRESSED.toJson(this, EditorState.class);
        EditorState copy = FlashbackGson.COMPRESSED.fromJson(serialized, EditorState.class);
        copy.repairDeserialisedState();
        return copy;
    }

    public EditorState copyWithoutKeyframes() {
        String serialized = FlashbackGson.COMPRESSED.toJson(this, EditorState.class);
        EditorState editorState = FlashbackGson.COMPRESSED.fromJson(serialized, EditorState.class);
        editorState.repairDeserialisedState();
        for (EditorScene scene : editorState.scenes) {
            scene.keyframeTracks.clear();
        }
        return editorState;
    }

    public boolean isEntityHidden(Entity entity) {
        if (this.hideAllSpectators && entity instanceof Player player && player.gameMode() == GameType.SPECTATOR) {
            return true;
        } else {
            return this.hideDuringExport.contains(entity.getUUID());
        }
    }

    public boolean maybeHasHiddenEntities() {
        return this.hideAllSpectators || !this.hideDuringExport.isEmpty();
    }

    /** The scene being edited, or null if none is available. */
    @Nullable
    public EditorScene currentSceneOrNull() {
        if (this.scenes.isEmpty()) {
            return null;
        }
        int index = Math.max(0, Math.min(this.scenes.size() - 1, this.sceneIndex));
        return this.scenes.get(index);
    }

    /**
     * Brings a loaded state up to the current schema, in place and idempotently.
     *
     * <p>Schema 0 -> 1 introduces cameras. Before, a scene was one flat set of tracks and the camera
     * was implicitly whatever the camera tracks animated. Camera-scoped tracks (position, orbit,
     * entity tracking) now belong to a camera, and a scene that has any gets one camera holding
     * exactly those tracks, plus a switch keyframe at tick 0 that selects it. That switch is what
     * makes the migrated project behave bit-for-bit as it did: previously every camera track applied
     * unconditionally, and now the switch selects the only camera there is.
     *
     * <p>Schema 1 -> 2 gives each camera its own persistent values. A camera is now a thing that can
     * exist with no keyframes at all, holding its own position, rotation, fov and shake, and those
     * values are applied whenever no enabled track covers them. A project written before then has no
     * such values, so each camera is seeded from its own earliest camera keyframe - see
     * {@link #seedCameraStaticValues}. That is what stops opening an old project from teleporting the
     * view to the world origin the moment its camera track is disabled or deleted.
     *
     * <p>Scene-scoped tracks stay on the scene, because they describe the world rather than a
     * viewpoint and must not change when the camera cuts.
     *
     * <p>Nothing is discarded and the track order is preserved, so a project opens with its animation
     * intact. Applying this twice changes nothing the second time.
     *
     * @return true if anything changed, so the caller can persist the upgraded form
     */
    public boolean migrateSchema() {
        boolean changed = false;

        // Driven by what the state actually contains, not only by the recorded version. A scene with
        // camera-scoped tracks that no camera owns has not really been migrated whatever the version
        // says - which matters for a project touched by a build that stamped the version before the
        // migration was known to work. Detection also makes this genuinely idempotent.
        for (EditorScene scene : this.scenes) {
            if (scene == null || !sceneNeedsCameraMigration(scene)) {
                continue;
            }
            migrateSceneToCameraSchema(scene);
            changed = true;
        }

        // Only projects written before cameras carried their own values are seeded. A camera that
        // already has values - a new project, or one at schema 2 - is left exactly as it is, so this
        // cannot overwrite anything the user has set.
        if (this.schemaVersion < 2) {
            for (EditorScene scene : this.scenes) {
                if (scene == null || scene.cameras == null) {
                    continue;
                }
                for (EditorCamera camera : scene.cameras) {
                    if (camera != null && seedCameraStaticValues(camera, scene)) {
                        changed = true;
                    }
                }
            }
        }

        // Reflection and keyframe adapters do not validate persisted lens values. Repair every
        // source, otherwise the first evaluation would immediately undo the visual-override repair.
        for (EditorScene scene : this.scenes) {
            for (EditorCamera camera : scene.cameras) {
                if (camera.fov != -1 && camera.fov != saneOverrideFov(camera.fov)) {
                    camera.fov = saneOverrideFov(camera.fov);
                    changed = true;
                }
            }
            for (KeyframeTrack track : scene.keyframeTracks) {
                for (Keyframe key : track.keyframesByTick.values()) {
                    if (key instanceof com.moulberry.flashback.keyframe.impl.CameraFovKeyframe fov
                            && fov.fov != saneOverrideFov(fov.fov)) {
                        fov.fov = saneOverrideFov(fov.fov); changed = true;
                    } else if (key instanceof com.moulberry.flashback.keyframe.impl.FOVKeyframe fov
                            && fov.fov != saneOverrideFov(fov.fov)) {
                        fov.fov = saneOverrideFov(fov.fov); changed = true;
                    }
                }
            }
        }

        // Visual overrides are repaired on every load, not only for old schema versions: a project
        // can be written with a degenerate value by any build, and the symptom is a blank view rather
        // than an error, so the value is not allowed to survive a load.
        if (sanitiseVisualOverrides()) {
            changed = true;
        }

        if (this.schemaVersion < CURRENT_SCHEMA_VERSION) {
            this.schemaVersion = CURRENT_SCHEMA_VERSION;
            changed = true;
        }
        return changed;
    }

    /**
     * The smallest field of view an override may hold.
     *
     * <p>Below about a degree the projection is degenerate and nothing in the world draws, and a
     * field of view of zero - or of {@link Float#MIN_VALUE}, which a clamped drag can turn a negative
     * sentinel into - is the difference between a shot and a blank screen.
     */
    private static final float MIN_OVERRIDE_FOV = 1.0f;

    /**
     * A field of view that an override may actually use.
     *
     * <p>Shared by the load-time repair and the inspector, so a value written by either path is held
     * to the same rule. Package-private so the headless checks can exercise it without a game client.
     */
    static float saneOverrideFov(float amount) {
        return ReplayVisuals.saneFov(amount);
    }

    /**
     * Repairs visual overrides that were written with degenerate numbers.
     *
     * <p>The one that has actually bitten: a drag field whose lower bound was
     * {@link Float#MIN_VALUE} - the smallest POSITIVE float - clamped the fov sentinel of -1 up to
     * about zero, so a project could be saved with the override switched on and a field of view that
     * renders nothing. The override then falls back to the configured default, which is what the
     * editor would have used had it never been overridden at all. Values that are simply out of a
     * sensible range are only touched when they cannot mean anything: a fog distance is clamped to
     * zero, and a non-finite value in any of them is replaced by its default.
     *
     * @return whether anything was changed, so the repaired project is saved back
     */
    private boolean sanitiseVisualOverrides() {
        ReplayVisuals visuals = this.replayVisuals;
        if (visuals == null) {
            return false;
        }
        boolean changed = false;

        // -1 is the "not overridden" sentinel and is left alone while the override is off, so a
        // project that never used the override is not rewritten. Only an override that is actually in
        // force is held to a usable angle.
        if ((visuals.overrideFov || visuals.overrideFovAmount != -1)
                && (!Float.isFinite(visuals.overrideFovAmount) || visuals.overrideFovAmount < MIN_OVERRIDE_FOV || visuals.overrideFovAmount >= 180)) {
            visuals.overrideFovAmount = saneOverrideFov(visuals.overrideFovAmount);
            changed = true;
        }

        // Fog is a distance: nothing at or below zero can be meant, and a non-finite value would
        // collapse the frustum. Roll is an angle and shake is an amount, both of which may legitimately
        // be negative, so only their non-finite values are repaired.
        if (!Float.isFinite(visuals.overrideFogStart) || visuals.overrideFogStart < 0) {
            visuals.overrideFogStart = 0.0f;
            changed = true;
        }
        if (!Float.isFinite(visuals.overrideFogEnd) || visuals.overrideFogEnd < 0) {
            visuals.overrideFogEnd = Math.max(1.0f, visuals.overrideFogStart);
            changed = true;
        }
        if (!Float.isFinite(visuals.overrideRollAmount)) {
            visuals.overrideRollAmount = 0.0f;
            changed = true;
        }
        if (!Float.isFinite(visuals.cameraShakeXFrequency)) {
            visuals.cameraShakeXFrequency = 1.0f;
            changed = true;
        }
        if (!Float.isFinite(visuals.cameraShakeXAmplitude)) {
            visuals.cameraShakeXAmplitude = 0.0f;
            changed = true;
        }
        if (!Float.isFinite(visuals.cameraShakeYFrequency)) {
            visuals.cameraShakeYFrequency = 1.0f;
            changed = true;
        }
        if (!Float.isFinite(visuals.cameraShakeYAmplitude)) {
            visuals.cameraShakeYAmplitude = 0.0f;
            changed = true;
        }
        return changed;
    }

    /**
     * Copies a camera's own earliest keyframed position and rotation onto the camera itself.
     *
     * <p>Before cameras carried their own values, the camera WAS its keyframes: the only thing that
     * said where it stood was its camera track. Seeding from that means a project written then opens
     * with the camera where it always appeared to be, so removing or disabling the track later leaves
     * the view where it was instead of snapping it to the origin.
     *
     * <p>A camera whose keyframes say nothing about a property - a free camera whose only row tracks
     * an entity, say - keeps the defaults for it. There is nothing in the project to seed from, and
     * inventing a value would be worse than leaving the camera at rest.
     *
     * <p>Package-private so it can be exercised without a game client.
     *
     * @return true if any value was written
     */
    static boolean seedCameraStaticValues(EditorCamera camera, EditorScene scene) {
        if (camera.hasStaticValues()) {
            return false;
        }

        boolean changed = false;

        CameraKeyframe whole = earliestKeyframeOf(scene, camera, CameraKeyframeType.INSTANCE, CameraKeyframe.class);
        if (whole != null) {
            camera.x = whole.position.x;
            camera.y = whole.position.y;
            camera.z = whole.position.z;
            camera.yaw = whole.yaw;
            camera.pitch = whole.pitch;
            camera.roll = whole.roll;
            changed = true;
        }

        // A camera saved by a build between the two schemas could already have granular tracks. They
        // are more specific than the whole-camera keyframe, so they win where they exist.
        CameraPositionKeyframe position = earliestKeyframeOf(scene, camera, CameraPositionKeyframeType.INSTANCE, CameraPositionKeyframe.class);
        if (position != null) {
            camera.x = position.position.x;
            camera.y = position.position.y;
            camera.z = position.position.z;
            changed = true;
        }

        CameraRotationKeyframe rotation = earliestKeyframeOf(scene, camera, CameraRotationKeyframeType.INSTANCE, CameraRotationKeyframe.class);
        if (rotation != null) {
            camera.yaw = rotation.yaw;
            camera.pitch = rotation.pitch;
            camera.roll = rotation.roll;
            changed = true;
        }

        return changed;
    }

    /** The keyframe of one type with the smallest tick among the tracks this camera owns. */
    @Nullable
    private static <T extends Keyframe> T earliestKeyframeOf(EditorScene scene, EditorCamera camera,
                                                             KeyframeType<?> type, Class<T> keyframeClass) {
        if (scene.keyframeTracks == null) {
            return null;
        }

        T earliest = null;
        int earliestTick = Integer.MAX_VALUE;

        for (KeyframeTrack track : scene.keyframeTracks) {
            if (track == null || track.keyframeType != type || !camera.id.equals(track.cameraId)) {
                continue;
            }
            Map.Entry<Integer, Keyframe> entry = track.keyframesByTick.firstEntry();
            if (entry == null || entry.getKey() >= earliestTick || !keyframeClass.isInstance(entry.getValue())) {
                continue;
            }
            earliestTick = entry.getKey();
            earliest = keyframeClass.cast(entry.getValue());
        }

        return earliest;
    }

    /**
     * Whether a scene still holds camera-scoped tracks that belong to no camera.
     *
     * <p>That is the one thing this migration exists to fix, so it is also the right thing to test:
     * after a successful migration no such track can remain, because adding one is prevented and
     * deleting a camera deletes its rows.
     */
    private static boolean sceneNeedsCameraMigration(EditorScene scene) {
        if (scene.keyframeTracks == null) {
            return false;
        }

        for (KeyframeTrack track : scene.keyframeTracks) {
            if (track != null && track.cameraId == null && track.keyframeType != null
                    && EditorScene.isCameraScoped(track.keyframeType)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Folds a pre-camera scene's flat track list onto cameras, in place.
     *
     * <p>Every camera-scoped track type that used to apply directly is given an owner, so the scene
     * gains one camera per kind of viewpoint it had - normally just one. Scene-scoped tracks are
     * left alone. Nothing is moved or discarded, and a switch at tick 0 selects the camera that was
     * previously implicit, which is what keeps an existing project's animation playing identically.
     *
     * <p>Package-private so it can be exercised without a game client.
     */
    static void migrateSceneToCameraSchema(EditorScene scene) {
        // Deserialisation may not have run the field initialisers, so the collections this works on
        // are made certain before they are touched.
        if (scene.keyframeTracks == null) {
            scene.keyframeTracks = new ArrayList<>();
        }
        if (scene.cameras == null) {
            scene.cameras = new ArrayList<>();
        }

        // Which camera-scoped row types landed on which camera. Usually one camera, because before
        // this schema a scene had a single viewpoint; a spectate track is its own kind of camera.
        LinkedHashMap<EditorCamera, List<KeyframeTrack>> owners = new LinkedHashMap<>();

        for (KeyframeTrack track : scene.keyframeTracks) {
            if (track == null || track.cameraId != null || track.keyframeType == null) {
                continue;
            }
            if (!EditorScene.isCameraScoped(track.keyframeType)) {
                continue;
            }

            boolean spectate = track.keyframeType == SpectateKeyframeType.INSTANCE;
            EditorCamera owner = null;
            for (EditorCamera candidate : owners.keySet()) {
                if ((candidate.kind == EditorCamera.Kind.SPECTATE) == spectate) {
                    owner = candidate;
                    break;
                }
            }
            if (owner == null) {
                // Unnamed: the timeline generates a name for it, so migration invents no user-facing
                // text and stays deterministic.
                owner = new EditorCamera(null,
                    spectate ? EditorCamera.Kind.SPECTATE : EditorCamera.Kind.FREE);
                scene.cameras.add(owner);
                owners.put(owner, new ArrayList<>());
            }
            owners.get(owner).add(track);
        }

        if (owners.isEmpty()) {
            return;
        }

        for (Map.Entry<EditorCamera, List<KeyframeTrack>> entry : owners.entrySet()) {
            for (KeyframeTrack track : entry.getValue()) {
                track.cameraId = entry.getKey().id;
            }
        }

        // Select the first camera from tick 0 so a project that used to animate its camera
        // unconditionally still does. Without this the camera rows would exist but never apply.
        KeyframeTrack switchTrack = scene.findOrCreateCameraSwitchTrack();
        if (switchTrack.keyframesByTick.isEmpty()) {
            switchTrack.keyframesByTick.put(0, new CameraSwitchKeyframe(scene.cameras.get(0).id));
        }
    }

    /**
     * Applies a change to which camera is output, immediately rather than through a keyframe, so the
     * editor can preview the viewpoint the user just picked.
     */
    public void previewCamera(EditorCamera camera, float tick) {
        this.previewCamera(camera, tick, 0);
    }

    @ApiStatus.Internal
    public void previewCamera(EditorCamera camera, float tick, long stamp) {
        if (camera == null) return;
        boolean unlock = !this.sceneLock.validate(stamp);
        // A caller holding a timeline stamp must not re-enter the mapping updater or scene lock.
        if (unlock) {
            this.updateRealtimeMappingsIfNeeded();
            stamp = this.sceneLock.readLock();
        }
        try {
            this.applyCameraPass(new MinecraftKeyframeHandler(Minecraft.getInstance()), this.currentScene(), camera, tick);
        } finally {
            if (unlock) this.sceneLock.unlock(stamp);
        }
    }

    public void applyKeyframes(KeyframeHandler keyframeHandler, float tick) {
        this.applyKeyframes(keyframeHandler, tick, 0);
    }

    @ApiStatus.Internal
    public void applyKeyframes(KeyframeHandler keyframeHandler, float tick, long stamp) {
        boolean unlock = !this.sceneLock.validate(stamp);
        // A supplied stamp already protects the scene; StampedLock is not reentrant. Refresh only
        // outside the caller's critical section, otherwise even a read-to-write upgrade deadlocks.
        if (unlock) {
            updateRealtimeMappingsIfNeeded();
            stamp = this.sceneLock.readLock();
        }
        try {
            EditorScene scene = this.currentScene();
            this.applyCameraPass(keyframeHandler, scene, scene.resolveCameraAt(tick), tick);
        } finally {
            if (unlock) this.sceneLock.unlock(stamp);
        }
    }

    private record ResolvedChange(KeyframeTrack track, KeyframeChange change) {}

    /** Resolve once, then fold the same ordered writes for playback, preview and the inspector. */
    private List<ResolvedChange> resolveChanges(EditorScene scene, @Nullable EditorCamera camera,
                                                KeyframeHandler handler, float tick) {
        List<ResolvedChange> changes = new ArrayList<>();
        Set<Class<? extends KeyframeChange>> applied = new HashSet<>();
        // Deterministic held-change order: first occurrence of each class in timeline order.
        Map<Class<? extends KeyframeChange>, KeyframeTrack> held = new LinkedHashMap<>();
        KeyframeTrack switchTrack = scene.cameraSwitchTrack();
        for (KeyframeTrack track : scene.keyframeTracks) {
            if (track == null || !track.enabled || track == switchTrack || track.keyframeType == null
                    || (camera != null && track.cameraId != null && !camera.id.equals(track.cameraId))) continue;
            Class<? extends KeyframeChange> type = track.keyframeType.keyframeChangeType();
            if (type == null || !track.keyframeType.supportsHandler(handler)
                    || (!track.keyframeType.allowApplyingDuplicateKeyframeChanges() && applied.contains(type))) continue;
            KeyframeChange change = track.createKeyframeChange(tick, this.realTimeMapping);
            if (change == null) {
                if (handler.alwaysApplyLastKeyframe() && !track.keyframeType.neverApplyLastKeyframe()
                        && !track.keyframesByTick.isEmpty() && track.keyframesByTick.lastKey() <= tick) {
                    KeyframeTrack previous = held.get(type);
                    if (previous == null || track.keyframesByTick.lastKey() > previous.keyframesByTick.lastKey()) held.put(type, track);
                }
                continue;
            }
            if (change.getClass() != type) throw new IllegalStateException("Wrong change type on " + track.keyframeType.id());
            applied.add(type);
            held.remove(type);
            changes.add(new ResolvedChange(track, change));
        }
        for (Map.Entry<Class<? extends KeyframeChange>, KeyframeTrack> entry : held.entrySet()) {
            KeyframeChange change = entry.getValue().createKeyframeChange(entry.getValue().keyframesByTick.lastKey(), this.realTimeMapping);
            if (change != null) {
                if (change.getClass() != entry.getKey()) throw new IllegalStateException("Wrong held change type");
                changes.add(new ResolvedChange(entry.getValue(), change));
            }
        }
        return changes;
    }

    private void applyCameraPass(KeyframeHandler handler, EditorScene scene, @Nullable EditorCamera camera, float tick) {
        handler.beginCameraFrame();
        handler.setFollowedPosition(null);
        if (camera != null && camera.kind != EditorCamera.Kind.SPECTATE) handler.applySpectate(null);
        List<ResolvedChange> changes = this.resolveChanges(scene, camera, handler, tick);
        if (camera != null && camera.kind == EditorCamera.Kind.FREE) this.applyStaticCameraValues(handler, camera, changes);
        for (ResolvedChange resolved : changes) resolved.change().apply(handler);
    }

    private static boolean changesProperty(KeyframeChange change, CameraProperty property) {
        boolean wholePose = change instanceof com.moulberry.flashback.keyframe.change.KeyframeChangeCameraPosition
            || change instanceof com.moulberry.flashback.keyframe.change.KeyframeChangeCameraPositionOrbit
            || change instanceof com.moulberry.flashback.keyframe.change.KeyframeChangeTrackEntity;
        return switch (property) {
            case POSITION -> wholePose || change instanceof KeyframeChangeCameraPositionOnly;
            case ROTATION -> wholePose || change instanceof KeyframeChangeCameraRotationOnly;
            case FOV -> change instanceof KeyframeChangeCameraFov || change instanceof KeyframeChangeFov;
            case SHAKE -> change instanceof KeyframeChangeCameraShake;
        };
    }

    private void applyStaticCameraValues(KeyframeHandler handler, EditorCamera camera, List<ResolvedChange> changes) {
        boolean position = false, rotation = false, fov = false, shake = false;
        for (ResolvedChange resolved : changes) {
            KeyframeChange change = resolved.change();
            position |= changesProperty(change, CameraProperty.POSITION);
            rotation |= changesProperty(change, CameraProperty.ROTATION);
            fov |= changesProperty(change, CameraProperty.FOV);
            shake |= changesProperty(change, CameraProperty.SHAKE);
        }
        if (!position && handler.supportsKeyframeChange(KeyframeChangeCameraPositionOnly.class))
            handler.applyCameraPositionOnly(new Vector3d(camera.x, camera.y, camera.z));
        if (!rotation && handler.supportsKeyframeChange(KeyframeChangeCameraRotationOnly.class))
            handler.applyCameraRotationOnly(camera.yaw, camera.pitch, camera.roll);
        if (!fov && camera.fov != -1 && (handler.supportsKeyframeChange(KeyframeChangeFov.class)
                || handler.supportsKeyframeChange(KeyframeChangeCameraFov.class))) handler.applyFov(saneOverrideFov(camera.fov));
        if (!shake && camera.overrideCameraShake && handler.supportsKeyframeChange(KeyframeChangeCameraShake.class))
            handler.applyCameraShake(camera.cameraShakeXFrequency, camera.cameraShakeXAmplitude,
                camera.cameraShakeYFrequency, camera.cameraShakeYAmplitude);
    }

    /**
     * What a camera evaluates to at a tick: the values its own tracks produce, and which properties
     * those are.
     *
     * @param position        where the camera would be
     * @param yaw             its yaw in degrees
     * @param pitch           its pitch in degrees
     * @param roll            its roll in degrees
     * @param fov             its field of view, or -1 when nothing drives it and it has no override
     * @param shakeXFrequency the camera shake it would use, if shake is driven or opted into
     * @param shakeXAmplitude ...
     * @param shakeYFrequency ...
     * @param shakeYAmplitude ...
     * @param driven          the properties a track is animating at this tick
     */
    public record CameraEvaluation(Vector3d position, double yaw, double pitch, double roll, float fov,
                                   float shakeXFrequency, float shakeXAmplitude,
                                   float shakeYFrequency, float shakeYAmplitude,
                                   Set<CameraProperty> driven) {

        /** Whether a track is producing this property's value at the evaluated tick. */
        public boolean isDriven(CameraProperty property) {
            return this.driven.contains(property);
        }
    }

    /** One independently animatable property of a camera object. */
    public enum CameraProperty {
        POSITION, ROTATION, FOV, SHAKE
    }

    /** Evaluates the selected camera, even when another camera is currently output. Caller owns the scene stamp. */
    public CameraEvaluation evaluateCameraAt(EditorScene scene, EditorCamera camera, float tick) {
        CameraCapture capture = new CameraCapture(camera);
        for (ResolvedChange resolved : this.resolveChanges(scene, camera, capture, tick)) resolved.change().apply(capture);
        return new CameraEvaluation(capture.position, capture.yaw, capture.pitch, capture.roll, capture.fov,
            capture.shakeXFrequency, capture.shakeXAmplitude, capture.shakeYFrequency, capture.shakeYAmplitude,
            Set.copyOf(capture.driven));
    }

    /**
     * The winning animated track for a selected camera property, or null for a stored fallback.
     * Uses exactly the evaluation resolver and write order, including held changes and scene lanes.
     * Caller owns the scene stamp; this method acquires no locks and changes no camera state.
     */
    @Nullable
    public KeyframeTrack evaluatedCameraTrack(EditorScene scene, EditorCamera camera, float tick, CameraProperty property) {
        KeyframeTrack winner = null;
        CameraCapture capture = new CameraCapture(camera);
        for (ResolvedChange resolved : this.resolveChanges(scene, camera, capture, tick)) {
            if (changesProperty(resolved.change(), property)) winner = resolved.track();
        }
        return winner;
    }

    /** Captures writes in their actual application order instead of inventing property precedence. */
    private static final class CameraCapture implements KeyframeHandler {
        Vector3d position, followed;
        double yaw, pitch, roll;
        float fov, shakeXFrequency, shakeXAmplitude, shakeYFrequency, shakeYAmplitude;
        final Set<CameraProperty> driven = EnumSet.noneOf(CameraProperty.class);
        CameraCapture(EditorCamera camera) {
            this.position = new Vector3d(camera.x, camera.y, camera.z);
            this.yaw = camera.yaw; this.pitch = camera.pitch; this.roll = camera.roll;
            this.fov = camera.fov == -1 ? -1 : saneOverrideFov(camera.fov);
            this.shakeXFrequency = camera.cameraShakeXFrequency;
            this.shakeYFrequency = camera.cameraShakeYFrequency;
            this.shakeXAmplitude = camera.overrideCameraShake ? camera.cameraShakeXAmplitude : 0;
            this.shakeYAmplitude = camera.overrideCameraShake ? camera.cameraShakeYAmplitude : 0;
        }
        @Override public boolean alwaysApplyLastKeyframe() { return true; }
        @Override public Minecraft getMinecraft() { return Minecraft.getInstance(); }
        @Override public boolean supportsKeyframeChange(Class<? extends KeyframeChange> type) {
            return type == com.moulberry.flashback.keyframe.change.KeyframeChangeCameraPosition.class
                || type == com.moulberry.flashback.keyframe.change.KeyframeChangeCameraPositionOrbit.class
                || type == com.moulberry.flashback.keyframe.change.KeyframeChangeTrackEntity.class
                || type == KeyframeChangeCameraPositionOnly.class || type == KeyframeChangeCameraRotationOnly.class
                || type == KeyframeChangeFov.class || type == KeyframeChangeCameraFov.class || type == KeyframeChangeCameraShake.class;
        }
        @Override public void setFollowedPosition(Vector3d value) { this.followed = value; }
        @Override public Vector3d followedPosition() { return this.followed; }
        @Override public void applyCameraPosition(Vector3d value, double yaw, double pitch, double roll) {
            this.applyCameraPositionOnly(value); this.applyCameraRotationOnly(yaw, pitch, roll);
        }
        @Override public void applyCameraPositionOnly(Vector3d value) {
            this.position = new Vector3d(value); this.driven.add(CameraProperty.POSITION);
        }
        @Override public void applyCameraRotationOnly(double yaw, double pitch, double roll) {
            this.yaw = yaw; this.pitch = pitch; this.roll = roll; this.driven.add(CameraProperty.ROTATION);
        }
        @Override public void applyFov(float value) { this.fov = saneOverrideFov(value); this.driven.add(CameraProperty.FOV); }
        @Override public void applyCameraShake(float xf, float xa, float yf, float ya) {
            this.shakeXFrequency = xf; this.shakeXAmplitude = xa; this.shakeYFrequency = yf; this.shakeYAmplitude = ya;
            this.driven.add(CameraProperty.SHAKE);
        }
    }

    private void updateRealtimeMappingsIfNeeded() {
        long stamp = this.sceneLock.readLock();
        try {
            FlashbackConfigV1 config = Flashback.getConfig();
            if (!config.keyframes.useRealtimeInterpolation) {
                this.sceneLock.unlock(stamp);
                stamp = this.sceneLock.writeLock();

                this.lastRealTimeMappingModCount = this.modCount;
                this.realTimeMapping = null;
            } else if (this.realTimeMapping == null || this.lastRealTimeMappingModCount != this.modCount) {
                this.sceneLock.unlock(stamp);
                stamp = this.sceneLock.writeLock();

                if (this.realTimeMapping == null || this.lastRealTimeMappingModCount != this.modCount) {
                    this.calculateRealtimeMappings();
                }
            }
        } finally {
            this.sceneLock.unlock(stamp);
        }
    }

    private void calculateRealtimeMappings() {
        this.lastRealTimeMappingModCount = this.modCount;
        this.realTimeMapping = new RealTimeMapping();

        List<KeyframeTrack> applicableTracks = new ArrayList<>();
        int start = -1;
        int end = -1;
        int lastApplicableKeyframe = -1;

        for (KeyframeTrack keyframeTrack : this.currentScene().keyframeTracks) {
            // Ignore tracks that are disabled
            if (!keyframeTrack.enabled || keyframeTrack.keyframesByTick.isEmpty()) {
                continue;
            }

            // We only care about tickrate changes
            Class<? extends KeyframeChange> keyframeChangeType = keyframeTrack.keyframeType.keyframeChangeType();
            if (keyframeChangeType == null || !KeyframeChangeTickrate.class.isAssignableFrom(keyframeChangeType)) {
                continue;
            }

            applicableTracks.add(keyframeTrack);

            int trackStart = keyframeTrack.keyframesByTick.firstKey();
            int trackEnd = keyframeTrack.keyframesByTick.lastKey();

            if (start < 0 || trackStart < start) {
                start = trackStart;
            }
            if (end < 0 || trackEnd > end) {
                end = trackEnd;
            }

            if (!keyframeTrack.keyframeType.neverApplyLastKeyframe()) {
                if (lastApplicableKeyframe < 0 || trackEnd > lastApplicableKeyframe) {
                    lastApplicableKeyframe = trackEnd;
                }
            }
        }

        if (applicableTracks.isEmpty() || start < 0 || end < 0) {
            return;
        }

        float lastSpeed = Float.NaN;

        for (int tick = start; tick <= end; tick++) {
            for (KeyframeTrack keyframeTrack : applicableTracks) {
                KeyframeChange change = keyframeTrack.createKeyframeChange(tick, this.realTimeMapping);
                if (!(change instanceof KeyframeChangeTickrate changeTickrate)) {
                    continue;
                }

                float newSpeed = changeTickrate.tickrate() / 20.0f;
                if (newSpeed != lastSpeed) {
                    lastSpeed = newSpeed;
                    this.realTimeMapping.addMapping(tick, newSpeed);
                }
                break;
            }
        }

        // Check if the tick afterwards has a change, if it does then the last keyframe is probably a hold keyframe
        // So we can just apply that speed for the remainder
        for (KeyframeTrack keyframeTrack : applicableTracks) {
            KeyframeChange change = keyframeTrack.createKeyframeChange(end+1, this.realTimeMapping);
            if (!(change instanceof KeyframeChangeTickrate changeTickrate)) {
                continue;
            }

            float newSpeed = changeTickrate.tickrate() / 20.0f;
            if (newSpeed != lastSpeed) {
                this.realTimeMapping.addMapping(end+1, newSpeed);
            }
            return;
        }

        // We need to try applying the last keyframe for the remainder
        for (KeyframeTrack keyframeTrack : applicableTracks) {
            if (!keyframeTrack.keyframeType.neverApplyLastKeyframe() && keyframeTrack.keyframesByTick.lastKey() == lastApplicableKeyframe) {
                KeyframeChange change = keyframeTrack.createKeyframeChange(lastApplicableKeyframe, this.realTimeMapping);
                if (!(change instanceof KeyframeChangeTickrate changeTickrate)) {
                    break;
                }

                float newSpeed = changeTickrate.tickrate() / 20.0f;
                if (newSpeed != lastSpeed) {
                    this.realTimeMapping.addMapping(end+1, newSpeed);
                }
                return;
            }
        }

        // Failing to apply the last keyframe, we reset the speed to normal
        this.realTimeMapping.addMapping(end+1, 1.0f);
    }

    public void setExportTicks(int start, int end, int totalTicks) {
        long stamp = this.sceneLock.writeLock();
        try {
            this.currentScene().setExportTicks(start, end, totalTicks);
            this.markDirty();
        } finally {
            this.sceneLock.unlock(stamp);
        }
    }

    public record StartAndEnd(int start, int end){}

    public StartAndEnd getExportStartAndEnd() {
        int start = -1;
        int end = -1;

        long stamp = this.sceneLock.readLock();
        try {
            EditorScene scene = this.currentScene();
            if (scene != null && (scene.exportStartTicks >= 0 || scene.exportEndTicks >= 0)) {
                if (scene.exportStartTicks >= 0) {
                    start = scene.exportStartTicks;
                } else {
                    start = 0;
                }
                if (scene.exportEndTicks >= 0) {
                    end = scene.exportEndTicks;
                } else {
                    ReplayServer replayServer = Flashback.getReplayServer();
                    if (replayServer == null) {
                        end = start;
                    } else {
                        end = replayServer.getTotalReplayTicks();
                    }
                }
            }
        } finally {
            this.sceneLock.unlock(stamp);
        }

        return new StartAndEnd(start, end);
    }

    public StartAndEnd getFirstAndLastTicksInTracks() {
        int start = -1;
        int end = -1;

        long stamp = this.sceneLock.readLock();
        try {
            for (KeyframeTrack keyframeTrack : this.currentScene().keyframeTracks) {
                if (!keyframeTrack.enabled || keyframeTrack.keyframesByTick.isEmpty()) {
                    continue;
                }
                int min = keyframeTrack.keyframesByTick.firstKey();
                var entry = keyframeTrack.keyframesByTick.lastEntry();

                int max = entry.getKey();
                float lastCustomWidth = entry.getValue().getCustomWidthInTicks();
                if (lastCustomWidth > 0) {
                    max = entry.getKey() + (int) Math.ceil(lastCustomWidth);
                }

                if (start == -1) {
                    start = min;
                } else {
                    start = Math.min(start, min);
                }
                if (end == -1) {
                    end = max;
                } else {
                    end = Math.max(end, max);
                }
            }
        } finally {
            this.sceneLock.unlock(stamp);
        }

        return new StartAndEnd(start, end);
    }

}

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
import com.moulberry.flashback.keyframe.change.KeyframeChangeCameraSwitch;
import com.moulberry.flashback.keyframe.change.KeyframeChangeTickrate;
import com.moulberry.flashback.keyframe.handler.KeyframeHandler;
import com.moulberry.flashback.keyframe.impl.CameraSwitchKeyframe;
import com.moulberry.flashback.keyframe.types.CameraSwitchKeyframeType;
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
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public double zoomMin = 0.0;
    public double zoomMax = 1.0;

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

        if (this.schemaVersion < CURRENT_SCHEMA_VERSION) {
            this.schemaVersion = CURRENT_SCHEMA_VERSION;
            changed = true;
        }
        return changed;
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
        if (camera == null) {
            return;
        }
        EditorScene scene = this.currentScene();
        if (scene == null) {
            return;
        }

        KeyframeHandler keyframeHandler = new MinecraftKeyframeHandler(
            Minecraft.getInstance());

        if (camera.kind != EditorCamera.Kind.SPECTATE) {
            keyframeHandler.applySpectate(null);
        }
        for (KeyframeTrack track : scene.keyframeTracks) {
            if (camera.id.equals(track.cameraId)) {
                applyTrackChange(keyframeHandler, track, tick);
            }
        }
    }

    /**
     * Applies one track's value at {@code tick}, including the held last keyframe that handlers
     * which always want a value rely on.
     *
     * @return true if anything was applied
     */
    private boolean applyTrackChange(KeyframeHandler keyframeHandler, KeyframeTrack keyframeTrack, float tick) {
        if (!keyframeTrack.enabled || !keyframeTrack.keyframeType.supportsHandler(keyframeHandler)) {
            return false;
        }

        KeyframeChange change = keyframeTrack.createKeyframeChange(tick, this.realTimeMapping);
        if (change == null) {
            // A single keyframe, or a tick past the last one, produces no interpolated value: fall
            // back to holding the last keyframe for handlers that want a value every tick.
            if (keyframeHandler.alwaysApplyLastKeyframe() && !keyframeTrack.keyframeType.neverApplyLastKeyframe()
                    && !keyframeTrack.keyframesByTick.isEmpty()
                    && keyframeTrack.keyframesByTick.lastKey() <= tick) {
                change = keyframeTrack.createKeyframeChange(keyframeTrack.keyframesByTick.lastKey(), this.realTimeMapping);
            }
        }

        if (change == null) {
            return false;
        }
        change.apply(keyframeHandler);
        return true;
    }

    public void applyKeyframes(KeyframeHandler keyframeHandler, float tick) {
        this.applyKeyframes(keyframeHandler, tick, 0);
    }

    @ApiStatus.Internal
    public void applyKeyframes(KeyframeHandler keyframeHandler, float tick, long stamp) {
        Set<Class<? extends KeyframeChange>> applied = new HashSet<>();
        Map<Class<? extends KeyframeChange>, KeyframeTrack> maybeApplyLastTick = new HashMap<>();

        updateRealtimeMappingsIfNeeded();

        boolean unlock = false;
        if (!this.sceneLock.validate(stamp)) {
            stamp = this.sceneLock.readLock();
            unlock = true;
        }
        try {
            EditorScene scene = this.currentScene();

            // The viewpoint is decided first, because a camera owns its own tracks and only the
            // camera being output may animate it. Applying every camera's tracks would make the
            // result depend on row order and let two viewpoints fight over the camera.
            EditorCamera activeCamera = scene.resolveCameraAt(tick);

            // The switch lane only selects a camera; applying it here would apply a cut without the
            // camera it names.
            KeyframeTrack switchTrack = scene.cameraSwitchTrack();

            // When a switch is in force, only the selected camera may animate the view; otherwise two
            // viewpoints would fight and the result would depend on row order. Without a switch - a
            // project saved before cameras existed, for instance - every camera track applies exactly
            // as it always did.
            boolean filterToActiveCamera = activeCamera != null;

            if (filterToActiveCamera && activeCamera.kind != EditorCamera.Kind.SPECTATE) {
                // A positioned camera is not following anyone, so leaving the spectated entity is
                // part of switching to it. Without this the view stays attached to a player and the
                // camera's tracks animate something nobody is looking through.
                keyframeHandler.applySpectate(null);
            }

            for (KeyframeTrack keyframeTrack : scene.keyframeTracks) {
                // Ignore lines that are disabled
                if (!keyframeTrack.enabled) {
                    continue;
                }

                // The switch lane decides which camera is output; it is applied through
                // resolveCameraAt, not as a track of its own.
                if (keyframeTrack == switchTrack) {
                    continue;
                }

                // Only the selected camera's tracks may animate the view, so a camera's tracks can
                // never be mixed with another's.
                if (filterToActiveCamera && keyframeTrack.cameraId != null
                        && !keyframeTrack.cameraId.equals(activeCamera.id)) {
                    continue;
                }

                Class<? extends KeyframeChange> keyframeChangeType = keyframeTrack.keyframeType.keyframeChangeType();

                // Already applied a keyframe of this type earlier, skip
                if (keyframeChangeType == null || (!keyframeTrack.keyframeType.allowApplyingDuplicateKeyframeChanges() && applied.contains(keyframeChangeType))) {
                    continue;
                }

                if (!keyframeTrack.keyframeType.supportsHandler(keyframeHandler)) {
                    continue;
                }

                // Try to apply keyframes, mark applied if successful

                KeyframeChange change = keyframeTrack.createKeyframeChange(tick, this.realTimeMapping);
                if (change == null) {
                    if (keyframeHandler.alwaysApplyLastKeyframe() && !keyframeTrack.keyframeType.neverApplyLastKeyframe() && !keyframeTrack.keyframesByTick.isEmpty()) {
                        if (keyframeTrack.keyframesByTick.lastKey() <= tick) {
                            KeyframeTrack oldTrack = maybeApplyLastTick.get(keyframeChangeType);
                            if (oldTrack == null || keyframeTrack.keyframesByTick.lastKey() > oldTrack.keyframesByTick.lastKey()) {
                                maybeApplyLastTick.put(keyframeChangeType, keyframeTrack);
                            }
                        }
                    }
                    continue;
                }

                if (change.getClass() != keyframeChangeType) {
                    throw new IllegalStateException("Expected " + keyframeChangeType + ", got " + change.getClass() + ". Caused by: " + keyframeTrack.keyframeType.id());
                }

                applied.add(keyframeChangeType);
                maybeApplyLastTick.remove(keyframeChangeType);
                change.apply(keyframeHandler);
            }

            if (keyframeHandler.alwaysApplyLastKeyframe() && !maybeApplyLastTick.isEmpty()) {
                for (Map.Entry<Class<? extends KeyframeChange>, KeyframeTrack> entry : maybeApplyLastTick.entrySet()) {
                    KeyframeTrack keyframeTrack = entry.getValue();
                    KeyframeChange change = keyframeTrack.createKeyframeChange(keyframeTrack.keyframesByTick.lastKey(), this.realTimeMapping);

                    if (change == null) {
                        continue;
                    }

                    if (change.getClass() != entry.getKey()) {
                        throw new IllegalStateException("Expected " + entry.getKey() + ", got " + change.getClass() + ". Caused by: " + keyframeTrack.keyframeType.id());
                    }

                    change.apply(keyframeHandler);
                }
            }
        } finally {
            if (unlock) {
                this.sceneLock.unlock(stamp);
            }
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

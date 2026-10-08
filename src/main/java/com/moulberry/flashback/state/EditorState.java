package com.moulberry.flashback.state;

import com.mojang.authlib.GameProfile;
import com.moulberry.flashback.FilePlayerSkin;
import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.FlashbackGson;
import com.moulberry.flashback.keyframe.CameraSource;
import com.moulberry.flashback.keyframe.Keyframe;
import com.moulberry.flashback.keyframe.impl.CameraSwitchKeyframe;
import com.moulberry.flashback.keyframe.impl.SpectateKeyframe;
import com.moulberry.flashback.keyframe.types.SpectateKeyframeType;
import com.moulberry.flashback.keyframe.types.TimelapseKeyframeType;
import com.moulberry.flashback.keyframe.types.CameraKeyframeType;
import com.moulberry.flashback.keyframe.types.CameraSwitchKeyframeType;
import com.moulberry.flashback.combo_options.GlowingOverride;
import com.moulberry.flashback.configuration.FlashbackConfigV1;
import com.moulberry.flashback.keyframe.change.KeyframeChange;
import com.moulberry.flashback.keyframe.change.KeyframeChangeTickrate;
import com.moulberry.flashback.keyframe.handler.KeyframeHandler;
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

public class EditorState {

    volatile transient boolean dirty = false;
    public volatile transient int modCount = ThreadLocalRandom.current().nextInt();
    private volatile transient int lastRealTimeMappingModCount = this.modCount;
    private volatile transient RealTimeMapping realTimeMapping = null;

    public final ReplayVisuals replayVisuals = new ReplayVisuals();

    private final StampedLock sceneLock = new StampedLock();
    private final List<EditorScene> scenes;
    private int sceneIndex = 0;

    /**
     * Cameras available to cut between. One is created per scene by {@link #migrateSchema()} so that
     * projects saved before cameras existed keep working without the user doing anything.
     */
    public List<NamedCamera> cameras = new ArrayList<>();
    /** Index into {@link #cameras} used for live preview in the editor. */
    public int activeCameraIndex = 0;

    /**
     * Schema version of the persisted form. 0 means "written before cameras existed" - Gson leaves
     * an absent int at 0, so old files are detected without any explicit marker.
     */
    public int schemaVersion = 0;
    public static final int CURRENT_SCHEMA_VERSION = 2;

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

        // A new project is already at the current schema and starts with one camera.
        this.schemaVersion = CURRENT_SCHEMA_VERSION;
        this.cameras = new ArrayList<>();
        this.cameras.add(new NamedCamera("Camera 1"));
        this.activeCameraIndex = 0;

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
            if (loaded != null && loaded.migrateSchema()) {
                // Mark dirty so the upgrade persists through the normal save path, which takes a
                // backup before overwriting. Writing here directly would clobber the user's project
                // with no backup, which is exactly what we must not do to someone's existing edits.
                loaded.dirty = true;
            }
            return loaded;
        } catch (Exception e) {
            Flashback.LOGGER.error("Error loading editor state", e);
            Flashback.LOGGER.error("JSON: {}", serialized);
            return null;
        }
    }

    public EditorState copy() {
        String serialized = FlashbackGson.COMPRESSED.toJson(this, EditorState.class);
        return FlashbackGson.COMPRESSED.fromJson(serialized, EditorState.class);
    }

    public EditorState copyWithoutKeyframes() {
        String serialized = FlashbackGson.COMPRESSED.toJson(this, EditorState.class);
        EditorState editorState = FlashbackGson.COMPRESSED.fromJson(serialized, EditorState.class);
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

    /**
     * Applies a camera source immediately, without a keyframe, so the editor can preview the
     * viewpoint the user just picked.
     *
     * @param tick the current replay tick, used to evaluate that camera's own tracks
     */
    public void previewCameraSource(CameraSource source) {
        if (source == null) {
            return;
        }
        float tick = com.moulberry.flashback.Flashback.getReplayServer() != null
            ? com.moulberry.flashback.Flashback.getReplayServer().getReplayTick()
            : 0.0f;
        KeyframeHandler handler = new com.moulberry.flashback.keyframe.handler.MinecraftKeyframeHandler(
            net.minecraft.client.Minecraft.getInstance());
        this.applyCameraSourceToHandler(handler, source, tick);
    }

    /**
     * Which viewpoint is live at {@code tick}: the source named by the most recent switch keyframe
     * at or before it. A hard cut, so only the preceding keyframe matters - no interpolation.
     *
     * @return the active source, or null when no switch has occurred yet
     */
    @Nullable
    public CameraSource resolveCameraSource(float tick) {
        if (this.cameras == null) {
            return null;
        }

        KeyframeTrack switchTrack = null;
        for (KeyframeTrack track : this.currentScene().keyframeTracks) {
            if (track.keyframeType == CameraSwitchKeyframeType.INSTANCE) {
                switchTrack = track;
                break;
            }
        }
        if (switchTrack == null || switchTrack.keyframesByTick.isEmpty()) {
            return null;
        }

        Map.Entry<Integer, Keyframe> entry = switchTrack.keyframesByTick.floorEntry((int) tick);
        if (entry == null || !(entry.getValue() instanceof CameraSwitchKeyframe switchKeyframe)) {
            return null;
        }

        CameraSource source = switchKeyframe.source;
        if (source == null || source.sourceId() == null) {
            return null;
        }

        // A switch may name a source that no longer exists (camera deleted, track removed). Fall
        // back to the first camera rather than leaving the viewpoint undefined.
        if (this.findSourceTrack(source.sourceId()) == null) {
            NamedCamera camera = this.findCamera(null);
            if (camera != null) {
                return CameraSource.of(camera.id);
            }
            return null;
        }
        return source;
    }

    /** The camera with this id, or null. A null id means "the first camera". */
    @Nullable
    public NamedCamera findCamera(@Nullable UUID cameraId) {
        if (this.cameras == null || this.cameras.isEmpty()) {
            return null;
        }
        if (cameraId == null) {
            return this.cameras.get(0);
        }
        for (NamedCamera camera : this.cameras) {
            if (camera.id.equals(cameraId)) {
                return camera;
            }
        }
        return null;
    }

    /** The current scene's tracks, for display helpers that must not take the write lock. */
    public java.util.List<KeyframeTrack> currentSceneTracks() {
        return this.currentScene().keyframeTracks;
    }

    /** Any track belonging to this source: a camera track, or the spectate object itself. */
    @Nullable
    private KeyframeTrack findSourceTrack(UUID sourceId) {
        for (KeyframeTrack track : this.currentScene().keyframeTracks) {
            if (sourceId.equals(track.cameraId)) {
                return track;
            }
        }
        return null;
    }

    /**
     * True when this source id belongs to a spectate object rather than to a camera.
     *
     * <p>Such a source owns the output itself: its own keyframes are applied rather than any camera
     * tracks. A timelapse is NOT one of these - it is a sub-part of a camera, so it is applied as
     * part of that camera.
     */
    private boolean isNonCameraSource(UUID sourceId) {
        for (KeyframeTrack track : this.currentScene().keyframeTracks) {
            if (!sourceId.equals(track.cameraId)) {
                continue;
            }
            if (track.keyframeType instanceof SpectateKeyframeType) {
                return true;
            }
        }
        return false;
    }

    /**
     * Applies the active source's own camera-scoped tracks, plus any spectate target.
     *
     * <p>Normally a camera's tracks are reached through the scene's track loop, but a camera created
     * by migration has no matching track on the scene, so its tracks are applied directly here.
     * Duplicate application is harmless because the track loop's duplicate guard already skips a
     * change type that has been applied.
     */
    private void applyCameraSourceToHandler(KeyframeHandler keyframeHandler, @Nullable CameraSource source, float tick) {
        if (source == null) {
            return;
        }

        UUID sourceId = source.sourceId();
        if (sourceId == null) {
            return;
        }

        // A spectate source owns the viewpoint: apply its keyframe, which says which player to follow.
        if (this.isNonCameraSource(sourceId)) {
            for (KeyframeTrack track : this.currentScene().keyframeTracks) {
                if (!track.enabled || !sourceId.equals(track.cameraId)) {
                    continue;
                }
                if (!track.keyframeType.supportsHandler(keyframeHandler)) {
                    continue;
                }
                KeyframeChange change = track.createKeyframeChange(tick, this.realTimeMapping);
                if (change != null) {
                    change.apply(keyframeHandler);
                }
            }
            return;
        }

        // Otherwise it is a camera. Leaving a spectated entity is part of switching to a camera:
        // without this the view stays attached to the player and the camera's tracks are applied to
        // something nobody is looking through. Idempotent, so it is safe every tick.
        keyframeHandler.applySpectate(null);

        for (KeyframeTrack track : this.currentScene().keyframeTracks) {
            if (!track.enabled || !sourceId.equals(track.cameraId)) {
                continue;
            }
            if (!track.keyframeType.supportsHandler(keyframeHandler)) {
                continue;
            }
            KeyframeChange change = track.createKeyframeChange(tick, this.realTimeMapping);
            if (change != null) {
                change.apply(keyframeHandler);
            }
        }
    }

    /**
     * Brings a loaded state up to the current schema, in place and idempotently.
     *
     * <p>Schema 0 -> 1 introduces cameras. Camera-scoped tracks (position, orbit, FOV, shake,
     * entity-tracking) used to live on the scene; they now belong to a camera, so each scene that
     * has such tracks gets its own camera holding them. Scene-scoped tracks (time of day, speed,
     * freeze, audio, block overrides) stay on the scene, because those describe the world rather
     * than a viewpoint and must not change when the camera cuts.
     *
     * <p>This is deliberately additive: nothing is discarded, so a project edited before this
     * change opens with its animation intact rather than losing keyframes.
     *
     * @return true if anything was changed, so the caller can persist the upgraded form
     */
    public boolean migrateSchema() {
        if (this.schemaVersion >= CURRENT_SCHEMA_VERSION) {
            return false;
        }

        if (this.cameras == null) {
            this.cameras = new ArrayList<>();
        }

        if (this.schemaVersion < 1) {
            for (EditorScene scene : this.scenes) {
                if (scene == null || scene.keyframeTracks == null) {
                    continue;
                }

                List<KeyframeTrack> cameraScoped = new ArrayList<>();
                for (KeyframeTrack track : scene.keyframeTracks) {
                    if (track != null && track.keyframeType != null
                        && NamedCamera.isCameraScoped(track)) {
                        cameraScoped.add(track);
                    }
                }

                // Only create a camera if the scene actually had camera animation, otherwise every
                // scene would gain a redundant empty camera.
                if (cameraScoped.isEmpty()) {
                    continue;
                }

                String cameraName = scene.name != null && !scene.name.isBlank()
                    ? scene.name
                    : "Camera " + (this.cameras.size() + 1);
                NamedCamera camera = new NamedCamera(cameraName);
                this.cameras.add(camera);

                // Tag in place: the tracks stay in the scene's track list, so they remain real
                // timeline rows. The camera id is the only new information - no container, no
                // parallel copy, nothing that can drift out of sync with the timeline.
                for (KeyframeTrack track : cameraScoped) {
                    if (track.cameraId == null) {
                        track.cameraId = camera.id;
                    }
                }
            }

            // A project with no camera animation at all still needs one camera to be usable.
            if (this.cameras.isEmpty()) {
                this.cameras.add(new NamedCamera("Camera 1"));
            }

            this.activeCameraIndex = 0;
        }

        if (this.schemaVersion < 2) {
            int folded = 0;
            for (EditorScene scene : this.scenes) {
                if (scene == null || scene.keyframeTracks == null) {
                    continue;
                }
                folded += this.foldTimelapseSourcesIntoCameras(scene);
            }
            if (folded > 0) {
                Flashback.LOGGER.info("Attached {} standalone timelapse track(s) to a camera: a timelapse is now a part of the camera it is added under, rather than a viewpoint of its own", folded);
            }
        }

        this.schemaVersion = CURRENT_SCHEMA_VERSION;
        return true;
    }

    /**
     * Attaches any timelapse that still owns its own source id to a camera.
     *
     * <p>A timelapse used to be a source in its own right, so it appeared in the camera switch beside
     * the cameras. It is a sub-part of a camera now, so an old standalone one is hung off the camera
     * that was being output where the timelapse starts, and any cut that named the old id is pointed
     * at that camera. The timelapse covers the same stretch of timeline as before; only what it
     * belongs to changes.
     *
     * @return how many timelapse tracks were attached
     */
    private int foldTimelapseSourcesIntoCameras(EditorScene scene) {
        List<KeyframeTrack> tracks = scene.keyframeTracks;

        Set<UUID> viewpointIds = new LinkedHashSet<>();
        for (KeyframeTrack track : tracks) {
            if (track != null && track.cameraId != null && NamedCamera.isViewpointTrack(track)) {
                viewpointIds.add(track.cameraId);
            }
        }

        List<KeyframeTrack> tracksToAdd = new ArrayList<>();
        int folded = 0;
        for (KeyframeTrack timelapseTrack : tracks) {
            if (timelapseTrack == null || timelapseTrack.cameraId == null) {
                continue;
            }
            if (!(timelapseTrack.keyframeType instanceof TimelapseKeyframeType)) {
                continue;
            }

            UUID oldId = timelapseTrack.cameraId;
            if (viewpointIds.contains(oldId)) {
                continue; // Already hangs off a camera that has a viewpoint.
            }

            UUID cameraId = this.cameraOutputWhereTimelapseStarts(tracks, timelapseTrack, oldId, viewpointIds);
            if (cameraId == null) {
                // Nothing to attach it to, so give the scene the camera it would have had. It needs a
                // viewpoint track as well: a camera that only owns a sub-part never appears in the
                // camera switch, so the timelapse would never run.
                NamedCamera camera = new NamedCamera("Camera " + (this.cameras.size() + 1));
                this.cameras.add(camera);
                cameraId = camera.id;
                viewpointIds.add(cameraId);

                KeyframeTrack cameraTrack = new KeyframeTrack(CameraKeyframeType.INSTANCE);
                cameraTrack.cameraId = cameraId;
                tracksToAdd.add(cameraTrack);
            }

            timelapseTrack.cameraId = cameraId;

            for (KeyframeTrack switchTrack : tracks) {
                if (switchTrack == null || switchTrack.keyframeType != CameraSwitchKeyframeType.INSTANCE) {
                    continue;
                }
                for (Map.Entry<Integer, Keyframe> entry : switchTrack.keyframesByTick.entrySet()) {
                    if (entry.getValue() instanceof CameraSwitchKeyframe switchKeyframe
                        && switchKeyframe.source != null
                        && oldId.equals(switchKeyframe.source.sourceId())) {
                        entry.setValue(new CameraSwitchKeyframe(CameraSource.of(cameraId), switchKeyframe.interpolationType()));
                    }
                }
            }

            folded += 1;
        }

        tracks.addAll(tracksToAdd);
        return folded;
    }

    /**
     * The camera that was output where the timelapse begins, which is the camera the timelapse was
     * written against. Null when the scene has no camera at all.
     */
    private @Nullable UUID cameraOutputWhereTimelapseStarts(List<KeyframeTrack> tracks, KeyframeTrack timelapseTrack,
                                                            UUID oldId, Set<UUID> viewpointIds) {
        if (!timelapseTrack.keyframesByTick.isEmpty()) {
            int startTick = timelapseTrack.keyframesByTick.firstKey();

            KeyframeTrack switchTrack = null;
            for (KeyframeTrack track : tracks) {
                if (track != null && track.keyframeType == CameraSwitchKeyframeType.INSTANCE) {
                    switchTrack = track;
                    break;
                }
            }

            if (switchTrack != null) {
                for (Map.Entry<Integer, Keyframe> entry : switchTrack.keyframesByTick.descendingMap().entrySet()) {
                    if (entry.getKey() > startTick) {
                        continue;
                    }
                    if (entry.getValue() instanceof CameraSwitchKeyframe switchKeyframe
                        && switchKeyframe.source != null) {
                        UUID sourceId = switchKeyframe.source.sourceId();
                        if (sourceId != null && !sourceId.equals(oldId) && viewpointIds.contains(sourceId)) {
                            return sourceId;
                        }
                    }
                }
            }
        }

        if (viewpointIds.isEmpty()) {
            return null;
        }
        return viewpointIds.iterator().next();
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
            CameraSource activeSource = this.resolveCameraSource(tick);
            applyCameraSourceToHandler(keyframeHandler, activeSource, tick);

            for (KeyframeTrack keyframeTrack : this.currentScene().keyframeTracks) {
                // Ignore lines that are disabled
                if (!keyframeTrack.enabled) {
                    continue;
                }

                // The switch track is consumed by resolveCameraSource; applying it again here would
                // re-apply the cut without its camera's tracks.
                if (keyframeTrack.keyframeType == CameraSwitchKeyframeType.INSTANCE) {
                    continue;
                }

                // Camera-scoped tracks from non-active cameras must not be evaluated, otherwise two
                // viewpoints fight over the camera and the result depends on track order.
                // Only filter when a switch is actually in force. With no switch keyframe at all we
                // must behave exactly as before, or every existing project loses its camera
                // animation - which is what happened when this skipped camera tracks outright.
                if (keyframeTrack.cameraId != null && activeSource != null) {
                    if (!keyframeTrack.cameraId.equals(activeSource.sourceId())) {
                        continue;
                    }
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

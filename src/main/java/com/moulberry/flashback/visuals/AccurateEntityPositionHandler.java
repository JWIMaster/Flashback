package com.moulberry.flashback.visuals;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.action.PositionAndAngle;
import com.moulberry.flashback.packet.FlashbackAccurateEntityPosition;
import com.moulberry.flashback.playback.ReplayServer;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector2f;
import org.joml.Vector3d;

import java.util.List;

public class AccurateEntityPositionHandler {

    private static Int2ObjectMap<List<PositionAndAngle>> currentData = null;
    private static Int2ObjectMap<List<PositionAndAngle>> pendingData = null;

    private static final Int2ObjectMap<AccuratePositionTimeline> playbackData = new Int2ObjectOpenHashMap<>();
    private static ReplayServer playbackServer;
    private static long playbackEpoch = Long.MIN_VALUE;
    private static double frameReplayTick;
    private static boolean playbackFrame;
    private static boolean frameWorldFrozen;

    public static void reset() {
        currentData = null;
        pendingData = null;
        playbackData.clear();
        playbackServer = null;
        playbackEpoch = Long.MIN_VALUE;
        playbackFrame = false;
        frameWorldFrozen = false;
    }

    /** Sample one coherent source-domain time for both camera position and rotation this frame. */
    public static void beginFrame() {
        if (Flashback.getConfig().advanced.disableIncreasedFirstPersonUpdates) {
            reset();
            return;
        }
        ReplayServer server = Flashback.getReplayServer();
        if (server != playbackServer) {
            reset();
            playbackServer = server;
        }
        if (server == null) return;
        ReplayServer.PlaybackTime time = server.getPlaybackTime();
        if (time.epoch() > playbackEpoch) {
            playbackData.clear();
            playbackEpoch = time.epoch();
        }
        playbackFrame = time.epoch() == playbackEpoch;
        frameReplayTick = time.tick();
        ClientLevel level = Minecraft.getInstance().level;
        frameWorldFrozen = server.replayPaused || level != null && !level.tickRateManager().runsNormally();
    }

    private static AccuratePositionTimeline playbackTimeline(int entityId) {
        AccuratePositionTimeline timeline = playbackData.get(entityId);
        if (timeline == null || timeline.size() == 0) return null;
        // Bridge short delivery gaps, not indefinite absence after the recorded player stops
        // producing high-frequency data. Pauses/freeze keyframes intentionally retain the pose.
        if (!frameWorldFrozen && frameReplayTick - timeline.latestSourceTick() > 5) {
            playbackData.remove(entityId); // Do not revive an expired override when playback pauses.
            return null;
        }
        return timeline;
    }

    public static void tick() {
        currentData = pendingData;
        pendingData = null;
    }

    public static void update(FlashbackAccurateEntityPosition data) {
        if (!Flashback.isExporting() && data.replayTick() >= 0) {
            ReplayServer server = Flashback.getReplayServer();
            if (server != playbackServer) {
                reset();
                playbackServer = server;
            }
            if (data.replayEpoch() < playbackEpoch) return;
            if (data.replayEpoch() > playbackEpoch) {
                playbackData.clear();
                playbackEpoch = data.replayEpoch();
            }
            playbackData.computeIfAbsent(data.entityId(), ignored -> new AccuratePositionTimeline())
                .put(data.replayTick(), data.positionAndAngles());
            return;
        }
        // Deterministic export retains its existing client-partial-tick mapping.
        if (pendingData == null) {
            pendingData = new Int2ObjectOpenHashMap<>();
        }
        pendingData.put(data.entityId(), data.positionAndAngles());
    }

    @Nullable
    public static Vector2f getAccurateRotation(Entity entity, float partialTick) {
        if (!Flashback.isInReplay() || Flashback.getConfig().advanced.disableIncreasedFirstPersonUpdates) return null;
        if (playbackFrame && !Flashback.isExporting()) {
            AccuratePositionTimeline timeline = playbackTimeline(entity.getId());
            PositionAndAngle pose = timeline == null ? null : timeline.sample(frameReplayTick);
            return pose == null ? null : new Vector2f(pose.pitch(), pose.yaw());
        }
        if (currentData != null && currentData.containsKey(entity.getId())) {
            List<PositionAndAngle> positionAndAngles = currentData.get(entity.getId());
            float amount = partialTick * (positionAndAngles.size() - 1);

            int floorAmount = (int) amount;
            PositionAndAngle floorPosition = positionAndAngles.get(floorAmount);

            int ceilAmount = floorAmount + 1;

            if (ceilAmount >= positionAndAngles.size()) {
                return new Vector2f(floorPosition.pitch(), floorPosition.yaw());
            } else {
                PositionAndAngle ceilPosition = positionAndAngles.get(ceilAmount);
                float partialAmount = amount - floorAmount;

                float yaw = floorPosition.yaw() + Mth.wrapDegrees(ceilPosition.yaw() - floorPosition.yaw()) * partialAmount;
                float pitch = floorPosition.pitch() + Mth.wrapDegrees(ceilPosition.pitch() - floorPosition.pitch()) * partialAmount;

                return new Vector2f(Mth.wrapDegrees(pitch), Mth.wrapDegrees(yaw));
            }
        }
        return null;
    }

    @Nullable
    public static Vector3d getAccuratePosition(Entity entity, float partialTick) {
        if (!Flashback.isInReplay() || Flashback.getConfig().advanced.disableIncreasedFirstPersonUpdates) return null;
        if (entity.isPassenger() || !Minecraft.getInstance().options.getCameraType().isFirstPerson()) {
            return null;
        }

        if (playbackFrame && !Flashback.isExporting()) {
            AccuratePositionTimeline timeline = playbackTimeline(entity.getId());
            PositionAndAngle pose = timeline == null ? null : timeline.sample(frameReplayTick);
            return pose == null ? null : new Vector3d(pose.x(), pose.y(), pose.z());
        }
        if (currentData != null && currentData.containsKey(entity.getId())) {
            List<PositionAndAngle> positionAndAngles = currentData.get(entity.getId());
            float amount = partialTick * (positionAndAngles.size() - 1);

            int floorAmount = (int) amount;
            PositionAndAngle floorPosition = positionAndAngles.get(floorAmount);

            int ceilAmount = floorAmount + 1;

            if (ceilAmount >= positionAndAngles.size()) {
                return new Vector3d(floorPosition.x(), floorPosition.y(), floorPosition.z());
            } else {
                PositionAndAngle ceilPosition = positionAndAngles.get(ceilAmount);
                float partialAmount = amount - floorAmount;

                double x = floorPosition.x() + (ceilPosition.x() - floorPosition.x()) * partialAmount;
                double y = floorPosition.y() + (ceilPosition.y() - floorPosition.y()) * partialAmount;
                double z = floorPosition.z() + (ceilPosition.z() - floorPosition.z()) * partialAmount;

                return new Vector3d(x, y, z);
            }
        }
        return null;
    }

    public static void apply(ClientLevel level, DeltaTracker deltaTracker) {
        if (Flashback.getConfig().advanced.disableIncreasedFirstPersonUpdates) return;
        if (currentData == null || level == null) {
            return;
        }

        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(true);

        ReplayServer replayServer = Flashback.getReplayServer();
        if (replayServer != null) {
            for (Int2ObjectMap.Entry<List<PositionAndAngle>> entry : currentData.int2ObjectEntrySet()) {
                Entity entity = level.getEntity(entry.getIntKey());
                if (entity == null) {
                    continue;
                }

                List<PositionAndAngle> positionAndAngles = entry.getValue();
                float amount = partialTick * (positionAndAngles.size() - 1);

                int floorAmount = (int) amount;
                PositionAndAngle floorPosition = positionAndAngles.get(floorAmount);

                int ceilAmount = floorAmount + 1;

                if (ceilAmount >= positionAndAngles.size()) {
                    applyPosition(entity, floorPosition.x(), floorPosition.y(), floorPosition.z(), floorPosition.yaw(), floorPosition.pitch());
                } else {
                    PositionAndAngle ceilPosition = positionAndAngles.get(ceilAmount);
                    float partialAmount = amount - floorAmount;

                    double x = floorPosition.x() + (ceilPosition.x() - floorPosition.x()) * partialAmount;
                    double y = floorPosition.y() + (ceilPosition.y() - floorPosition.y()) * partialAmount;
                    double z = floorPosition.z() + (ceilPosition.z() - floorPosition.z()) * partialAmount;
                    float yaw = floorPosition.yaw() + Mth.wrapDegrees(ceilPosition.yaw() - floorPosition.yaw()) * partialAmount;
                    float pitch = floorPosition.pitch() + Mth.wrapDegrees(ceilPosition.pitch() - floorPosition.pitch()) * partialAmount;

                    applyPosition(entity, x, y, z,  Mth.wrapDegrees(yaw), Mth.wrapDegrees(pitch));
                }

            }
        }
    }

    private static void applyPosition(Entity entity, double x, double y, double z, float yaw, float pitch) {
        if (!entity.isPassenger() && Minecraft.getInstance().getCameraEntity() == entity && Minecraft.getInstance().options.getCameraType().isFirstPerson()) {
            entity.snapTo(x, y, z, yaw, pitch);
        }

        entity.setYRot(yaw);
        entity.setXRot(pitch);
        entity.setYHeadRot(yaw);
        if (entity instanceof LivingEntity livingEntity) {
            livingEntity.lerpHeadSteps = 0;
            livingEntity.yHeadRotO = livingEntity.yHeadRot;
            livingEntity.yRotO = livingEntity.getYRot();
            livingEntity.xRotO = livingEntity.getXRot();
        }
    }

}

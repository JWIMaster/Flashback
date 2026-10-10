package com.moulberry.flashback.playback;

import net.minecraft.client.DeltaTracker;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.file.Files;
import java.nio.file.Path;

public final class PlaybackTimingTest {
    public static void main(String[] args) throws Exception {
        replayDoesNotSendSyntheticTimeCorrections();
        normalTickBoundaryDoesNotRewindAnimationTime();
        capturePreservesRawClockDiscontinuities();
        captureIsBoundedAndFinishesOnlyOnce();
        incompleteCaptureCannotBeSerialized();
        pauseAndDisconnectCanFlushShortCaptures();
        playbackRefreshesIdleTimerWithoutChangingFpsCap();
        firstPersonExtractionBypassesOnlyWorldCulling();
        accurateCameraAlignsBeforeFrustumWithEffectivePartialTick();
        recordedTimeCorrectionsCannotRewindWeather();
        worldAnimationClockRespectsFreezeAndIntentionalSeeks();
        accurateWireTimingDoesNotChangeExistingRecordings();
        liveCameraUsesSourceTimeWithoutMutatingSimulation();
        System.out.println("All playback timing checks passed (in-game reproduction still required)");
    }

    private static void replayDoesNotSendSyntheticTimeCorrections() throws Exception {
        ClassNode replay = new ClassNode();
        new ClassReader(Files.readAllBytes(Path.of("build/classes/java/main/com/moulberry/flashback/playback/ReplayServer.class")))
            .accept(replay, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        MethodNode override = replay.methods.stream()
            .filter(m -> m.name.equals("forceGameTimeSynchronization") && m.desc.equals("()V"))
            .findFirst().orElseThrow(() -> new AssertionError("replay time synchronization override is missing"));
        int instructions = 0;
        for (AbstractInsnNode instruction : override.instructions) {
            if (instruction.getOpcode() < 0) continue;
            instructions++;
            check(instruction.getOpcode() == Opcodes.RETURN, "compiled replay time synchronization is a genuine no-op");
        }
        check(instructions == 1, "compiled override returns without generating a packet");

        ClassNode vanilla = new ClassNode();
        new ClassReader("net.minecraft.server.MinecraftServer").accept(vanilla, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        boolean virtualCall = false;
        for (MethodNode method : vanilla.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && call.name.equals(override.name)
                    && call.desc.equals(override.desc) && call.getOpcode() == Opcodes.INVOKEVIRTUAL) {
                    virtualCall = true;
                }
            }
        }
        check(virtualCall, "vanilla dispatches time synchronization through the method replay overrides");
        String handler = Files.readString(Path.of("src/main/java/com/moulberry/flashback/playback/ReplayGamePacketHandler.java"));
        int recorded = handler.indexOf("void handleSetTime(");
        int end = handler.indexOf("@Override", recorded);
        check(handler.substring(recorded, end).contains("forward(clientboundSetTimePacket)"),
            "recorded time packets remain authoritative, including snapshots and seeks");
    }

    private static void normalTickBoundaryDoesNotRewindAnimationTime() {
        DeltaTracker.Timer timer = new DeltaTracker.Timer(20, 0, value -> value);
        long ticks = 0;
        double previous = 0;
        // Run the actual vanilla timer, not a simulation of its partial-tick arithmetic.
        for (long millis = 1; millis <= 20_000; millis += 7) {
            ticks += timer.advanceGameTime(millis);
            double animationTime = ticks + timer.getGameTimeDeltaPartialTick(true);
            check(animationTime >= previous, "a wrapped residual is paired with an advanced animation endpoint");
            previous = animationTime;
        }
    }

    private static PlaybackTimingTrace.Sample sample(long nanos, long gameTime, float partial) {
        return new PlaybackTimingTrace.Sample(nanos, 100, gameTime, partial, 100.5,
            false, false, true, 7, 100, 1, 2, 3, 0, 1, 2, 120, "NONE",
            200, 10, 20, 30, 90, 0, 0.5f, 7, true);
    }

    private static void capturePreservesRawClockDiscontinuities() {
        PlaybackTimingTrace trace = new PlaybackTimingTrace();
        check(!trace.add(sample(5_000, 200, 0.9f)), "capture begins without disk work");
        check(!trace.add(sample(10_000, 199, 0.1f)), "backward game-time sample is preserved");
        check(trace.add(sample(15_000_005_000L, 210, 0.2f)), "capture ends after fifteen seconds");
        String[] lines = trace.toCsv().strip().split("\\n");
        check(lines.length == 4, "every sampled frame is recorded");
        check(lines[1].startsWith("0,100,200,0.9,"), "trace uses relative monotonic timestamps");
        check(lines[2].startsWith("5000,100,199,0.1,"), "trace does not hide backward clock corrections");
        for (String line : lines) check(line.split(",").length == 27, "CSV schema stays aligned");
    }

    private static void captureIsBoundedAndFinishesOnlyOnce() {
        PlaybackTimingTrace trace = new PlaybackTimingTrace();
        int completions = 0;
        for (int i = 0; i < 20_000; i++) {
            if (trace.add(sample(i, i, 0))) completions++;
        }
        check(completions == 1 && trace.finished(), "high frame rates cannot cause unlimited capture or repeated writes");
        check(trace.toCsv().lines().count() == 8193, "trace is limited to 8192 frames plus its header");
    }

    private static void incompleteCaptureCannotBeSerialized() {
        try {
            new PlaybackTimingTrace().toCsv();
            throw new AssertionError("incomplete capture was serialized");
        } catch (IllegalStateException expected) {
            // Serialization must happen after capture finishes, on the I/O executor.
        }
    }

    private static void pauseAndDisconnectCanFlushShortCaptures() {
        PlaybackTimingTrace trace = new PlaybackTimingTrace();
        check(!trace.finish(), "an empty capture creates no file");
        trace.add(sample(1000, 100, 0.25f));
        check(trace.finish(), "pause/disconnect flush a short capture");
        check(!trace.finish(), "lifecycle flush cannot write the same trace twice");
        check(trace.toCsv().lines().count() == 2, "short capture retains its sample");
        check(!trace.add(sample(2000, 101, 0.5f)), "a flushed capture cannot be mutated during background serialization");
    }

    private static ClassNode builtClass(String name) throws Exception {
        ClassNode node = new ClassNode();
        new ClassReader(Files.readAllBytes(Path.of("build/classes/java/main/" + name.replace('.', '/') + ".class")))
            .accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return node;
    }

    private static void playbackRefreshesIdleTimerWithoutChangingFpsCap() throws Exception {
        MethodNode hook = builtClass("com.moulberry.flashback.mixin.MixinMinecraft").methods.stream()
            .filter(m -> m.name.equals("runTick_setErrorSection")).findFirst().orElseThrow();
        boolean refresh = false;
        for (AbstractInsnNode instruction : hook.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                if (call.owner.equals("com/mojang/blaze3d/platform/FramerateLimitTracker")) {
                    refresh |= call.name.equals("onInputReceived");
                    check(!call.name.equals("setFramerateLimit"), "playback does not override the user's FPS cap");
                }
            }
        }
        check(refresh, "the compiled playback hook refreshes vanilla's AFK timer");
        String source = Files.readString(Path.of("src/main/java/com/moulberry/flashback/mixin/MixinMinecraft.java"));
        int refreshCall = source.indexOf("Minecraft.getInstance().getFramerateLimitTracker().onInputReceived();");
        int branch = source.lastIndexOf("if (!paused)", refreshCall);
        check(branch >= 0 && !source.substring(branch, refreshCall).contains("}"),
            "the idle timer is refreshed only during unpaused playback");
    }

    private static void firstPersonExtractionBypassesOnlyWorldCulling() throws Exception {
        MethodNode hook = builtClass("com.moulberry.flashback.mixin.MixinLevelExtractor").methods.stream()
            .filter(m -> m.name.equals("extractPlayerState")).findFirst().orElseThrow();
        boolean dispatcher = false;
        for (AbstractInsnNode instruction : hook.instructions) {
            if (instruction instanceof MethodInsnNode call && call.name.equals("extractEntity")) {
                check(call.owner.equals("net/minecraft/client/renderer/entity/EntityRenderDispatcher"),
                    "spectated first-person state cannot be replaced by the world-culling placeholder");
                dispatcher = true;
            }
        }
        check(dispatcher, "first-person state is still extracted by the actual entity renderer");
    }

    private static void accurateCameraAlignsBeforeFrustumWithEffectivePartialTick() throws Exception {
        MethodNode hook = builtClass("com.moulberry.flashback.mixin.playback.MixinCamera").methods.stream()
            .filter(m -> m.name.equals("afterSetPosition")).findFirst().orElseThrow();
        check(hook.desc.startsWith("(F"), "accurate camera alignment receives vanilla's effective partial tick");
        for (AbstractInsnNode instruction : hook.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                check(!call.name.equals("getGameTimeDeltaPartialTick"),
                    "accurate alignment cannot resample a different frozen/local-player partial tick");
            }
        }
        boolean targetsAlignment = java.util.stream.Stream.concat(
                hook.visibleAnnotations == null ? java.util.stream.Stream.empty() : hook.visibleAnnotations.stream(),
                hook.invisibleAnnotations == null ? java.util.stream.Stream.empty() : hook.invisibleAnnotations.stream())
            .filter(a -> a.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;"))
            .anyMatch(a -> a.values.contains("method")
                && a.values.get(a.values.indexOf("method") + 1).toString().contains("alignWithEntity"));
        check(targetsAlignment, "accurate alignment hooks the effective entity alignment, not update return");
        ClassNode camera = new ClassNode();
        new ClassReader("net.minecraft.client.Camera").accept(camera, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        check(camera.methods.stream().anyMatch(m -> m.name.equals("alignWithEntity") && m.desc.equals("(F)V")),
            "the actual vanilla alignment hook has the expected signature");
        MethodNode update = camera.methods.stream().filter(m -> m.name.equals("update")).findFirst().orElseThrow();
        int index = 0, alignment = -1, frustum = -1;
        for (AbstractInsnNode instruction : update.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                if (call.name.equals("alignWithEntity")) alignment = index;
                if (call.name.equals("prepareCullFrustum")) frustum = index;
            }
            index++;
        }
        check(alignment >= 0 && frustum > alignment, "vanilla computes culling only after accurate camera alignment");
    }

    private static void recordedTimeCorrectionsCannotRewindWeather() {
        WorldAnimationClock clock = new WorldAnimationClock();
        DeltaTracker.Timer timer = new DeltaTracker.Timer(20, 0, value -> value);
        long rawGameTime = 100;
        double previousVisual = clock.sample(rawGameTime), previousRaw = rawGameTime;
        int rawRewinds = 0;
        for (int frame = 1; frame <= 600; frame++) {
            int ticks = timer.advanceGameTime(frame * 7L);
            for (int tick = 0; tick < ticks; tick++) {
                clock.tick(rawGameTime);
                rawGameTime++;
            }
            // Reproduce recorded SetTime corrections arriving BETWEEN client tick boundaries.
            if (frame % 100 == 0) rawGameTime -= 2;
            if (frame % 100 == 50) rawGameTime++;
            double raw = rawGameTime + timer.getGameTimeDeltaPartialTick(true);
            double visual = clock.sample(rawGameTime) + timer.getGameTimeDeltaPartialTick(true);
            if (raw < previousRaw) rawRewinds++;
            check(visual >= previousVisual, "weather remains continuous despite recorded game-time corrections");
            previousVisual = visual;
            previousRaw = raw;
        }
        check(rawRewinds >= 6, "the regression fixture actually reproduces the original backward jumps");
    }

    private static void worldAnimationClockRespectsFreezeAndIntentionalSeeks() throws Exception {
        WorldAnimationClock clock = new WorldAnimationClock();
        check(clock.sample(100) == 100, "clock starts from the recorded world time");
        clock.tick(100);
        check(clock.sample(5000) == 101, "ordinary packet correction cannot jump the animation clock forward");
        check(clock.sample(50) == 101, "without a world tick, pause/freeze preserves animation time");
        check(clock.sample(50) == 101, "a rewind does not restart the weather scroll pattern");
        check(clock.sample(5000) == 101, "a forward seek does not jump the weather scroll pattern either");
        clock.reset();
        check(clock.sample(50) == 50, "leaving replay re-primes the clock from the world clock");
        String weather = Files.readString(Path.of("src/main/java/com/moulberry/flashback/mixin/visuals/MixinWeatherEffectRenderer.java"));
        check(weather.contains("if (Flashback.isInReplay())"),
            "replay preview and export share one continuous clock");
        check(!weather.contains("isExporting()"), "the export must not fall back to the jumpy recorded world clock");
        check(weather.contains("flashback$getAnimationGameTime()"), "weather uses the continuous world-tick clock");
        check(weather.contains("WeatherAnimationDiagnostics.used(rawGameTime, animationTick, true)"),
            "the trace measures the tick the renderer is actually given, not the raw level clock");
        String level = Files.readString(Path.of("src/main/java/com/moulberry/flashback/mixin/visuals/MixinClientLevel.java"));
        check(level.contains("@Inject(method = \"tickTime\", at = @At(\"HEAD\"))"),
            "animation ticks advance at vanilla's actual world tick boundary");
        int tickTime = level.indexOf("@Inject(method = \"tickTime\"");
        String tickTimeBody = level.substring(tickTime, level.indexOf("\n    }", tickTime));
        check(!tickTimeBody.contains("isExporting()"),
            "the export advances the weather clock at the same world tick boundary");
        String flashback = Files.readString(Path.of("src/main/java/com/moulberry/flashback/Flashback.java"));
        int seekHandler = flashback.indexOf("registerGlobalReceiver(FlashbackInstantlyLerp.TYPE");
        int handlerEnd = flashback.indexOf("registerGlobalReceiver(", seekHandler + 30);
        check(!flashback.substring(seekHandler, handlerEnd).contains("AnimationGameTime"),
            "the real seek/snapshot packet must not re-phase the weather pattern");
        String diagnostics = Files.readString(Path.of("src/main/java/com/moulberry/flashback/visuals/WeatherAnimationDiagnostics.java"));
        check(diagnostics.contains("TICK_EPSILON") && diagnostics.contains("INTENSITY_EPSILON"),
            "the weather diagnostic covers both scroll time and column intensity");
        check(diagnostics.contains("Weather clock engaged"),
            "the log proves whether the continuous weather clock is actually in use");
    }

    private static void accurateWireTimingDoesNotChangeExistingRecordings() throws Exception {
        var poses = java.util.List.of(
            new com.moulberry.flashback.action.PositionAndAngle(1, 2, 3, 4, 5),
            new com.moulberry.flashback.action.PositionAndAngle(11, 12, 13, 14, 15));
        var packet = new com.moulberry.flashback.packet.FlashbackAccurateEntityPosition(648, poses, 99, 3);
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        var golden = new java.io.DataOutputStream(bytes);
        golden.writeByte(0x88); golden.writeByte(0x05); golden.writeByte(2);
        for (var pose : poses) {
            golden.writeDouble(pose.x()); golden.writeDouble(pose.y()); golden.writeDouble(pose.z());
            golden.writeFloat(pose.yaw()); golden.writeFloat(pose.pitch());
        }
        var buffer = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            com.moulberry.flashback.packet.FlashbackAccurateEntityPosition.STREAM_CODEC.encode(buffer, packet);
            byte[] actual = new byte[buffer.readableBytes()];
            buffer.getBytes(0, actual);
            check(java.util.Arrays.equals(actual, bytes.toByteArray()), "old recording bytes do not gain timing metadata");
            var legacy = com.moulberry.flashback.packet.FlashbackAccurateEntityPosition.STREAM_CODEC.decode(buffer);
            check(legacy.positionAndAngles().equals(poses) && legacy.replayTick() == -1 && buffer.readableBytes() == 0,
                "existing recording bytes still decode without a source-timing suffix");
            buffer.clear();
            com.moulberry.flashback.packet.FlashbackAccurateEntityPosition.PLAYBACK_STREAM_CODEC.encode(buffer, packet);
            var wire = com.moulberry.flashback.packet.FlashbackAccurateEntityPosition.PLAYBACK_STREAM_CODEC.decode(buffer);
            check(wire.equals(packet) && buffer.readableBytes() == 0, "runtime transport preserves source tick and seek epoch");
        } finally {
            buffer.release();
        }
    }

    private static void liveCameraUsesSourceTimeWithoutMutatingSimulation() throws Exception {
        String minecraft = Files.readString(Path.of("src/main/java/com/moulberry/flashback/mixin/MixinMinecraft.java"));
        int apply = minecraft.indexOf("AccurateEntityPositionHandler.apply(");
        int exportBranch = minecraft.lastIndexOf("if (Flashback.isExporting())", apply);
        check(exportBranch >= 0 && !minecraft.substring(exportBranch, apply).contains("}"),
            "live playback never snaps simulation entities to rendered accurate poses");
        check(minecraft.contains("AccurateEntityPositionHandler.beginFrame();"), "camera frame samples source timing once");
        String server = Files.readString(Path.of("src/main/java/com/moulberry/flashback/playback/ReplayServer.java"));
        check(server.contains("private volatile PlaybackClock playbackClock"), "replay timing is published as one coherent snapshot");
        check(server.contains("this.currentTick, this.accuratePositionEpoch"), "wire data is tagged before its ActionNextTick");
        String handler = Files.readString(Path.of("src/main/java/com/moulberry/flashback/visuals/AccurateEntityPositionHandler.java"));
        check(handler.contains("data.replayEpoch() < playbackEpoch"), "late packets from a previous seek are ignored");
        check(handler.contains("timeline.sample(frameReplayTick)"), "live curves use absolute replay time, not local wrapped residuals");
        check(handler.contains("if (!Flashback.isExporting() && data.replayTick() >= 0)"),
            "deterministic export retains its established pending/current path");
        check(handler.contains("!Flashback.isInReplay() || Flashback.getConfig().advanced.disableIncreasedFirstPersonUpdates"),
            "cached poses cannot override normal gameplay or disabled high-frequency updates");
        check(handler.contains("frameReplayTick - timeline.latestSourceTick() > 5"),
            "missing camera data is bounded, not an indefinite camera lock");
        check(minecraft.split("AccurateEntityPositionHandler\\.reset\\(\\);", -1).length >= 5,
            "disconnect, no replay and both export boundaries invalidate pose caches");
        check(server.contains("this.tickRateManager().nanosecondsPerTick(), normalPlayback"),
            "seek intervals hold their target until actual normal advancement resumes");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

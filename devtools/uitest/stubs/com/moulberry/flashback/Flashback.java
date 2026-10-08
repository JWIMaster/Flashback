package com.moulberry.flashback;

import com.moulberry.flashback.configuration.FlashbackConfigV1;
import com.moulberry.flashback.playback.ReplayServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Minimal stand-in so the real editor UI can run without a game client. */
public class Flashback {

    public static final Logger LOGGER = LoggerFactory.getLogger("flashback-uitest");

    // The configuration reads these when it constructs its marker subcategories.
    public static final net.minecraft.client.KeyMapping createMarker1KeyBind = null;
    public static final net.minecraft.client.KeyMapping createMarker2KeyBind = null;
    public static final net.minecraft.client.KeyMapping createMarker3KeyBind = null;
    public static final net.minecraft.client.KeyMapping createMarker4KeyBind = null;
    public static boolean isBobbyLoaded = false;

    private static final FlashbackConfigV1 CONFIG = createConfig();
    private static ReplayServer replayServer;

    private static FlashbackConfigV1 createConfig() {
        try {
            var constructor = FlashbackConfigV1.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            FlashbackConfigV1 config = constructor.newInstance();
            config.keyframes.useRealtimeInterpolation = false;
            return config;
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static FlashbackConfigV1 getConfig() {
        return CONFIG;
    }

    public static ReplayServer getReplayServer() {
        return replayServer;
    }

    public static void setReplayServer(ReplayServer server) {
        replayServer = server;
    }
}

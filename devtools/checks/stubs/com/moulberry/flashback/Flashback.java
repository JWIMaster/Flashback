package com.moulberry.flashback;

import com.moulberry.flashback.configuration.FlashbackConfigV1;
import net.minecraft.client.KeyMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minimal stand-in used only to run the headless migration/evaluation tests. The real Flashback
 * class registers key mappings and reads client options during static initialisation, which needs a
 * game client.
 */
public class Flashback {
    public static final Logger LOGGER = LoggerFactory.getLogger("flashback-test");

    public static final KeyMapping createMarker1KeyBind = null;
    public static final KeyMapping createMarker2KeyBind = null;
    public static final KeyMapping createMarker3KeyBind = null;
    public static final KeyMapping createMarker4KeyBind = null;

    public static boolean isBobbyLoaded = false;

    private static FlashbackConfigV1 config;

    static {
        try {
            var ctor = FlashbackConfigV1.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            config = ctor.newInstance();
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
        config.keyframes.useRealtimeInterpolation = false;
    }

    public static FlashbackConfigV1 getConfig() {
        return config;
    }
}

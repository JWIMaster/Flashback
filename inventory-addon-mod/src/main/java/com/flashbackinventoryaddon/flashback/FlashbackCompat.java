package com.flashbackinventoryaddon.flashback;

import com.flashbackinventoryaddon.FlashbackInventoryAddon;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.network.AbstractClientPlayerEntity;

import java.lang.reflect.Method;

public final class FlashbackCompat {

    private static final String FLASHBACK_CLASS = "com.moulberry.flashback.Flashback";

    private static boolean flashbackPresent;
    private static Method isExportingMethod;
    private static Method isInReplayMethod;
    private static Method getSpectatingPlayerMethod;

    private FlashbackCompat() {
    }

    public static void bootstrap() {
        flashbackPresent = FabricLoader.getInstance().isModLoaded("flashback");
        if (!flashbackPresent) {
            return;
        }

        try {
            Class<?> clazz = Class.forName(FLASHBACK_CLASS);
            isExportingMethod = clazz.getMethod("isExporting");
            isInReplayMethod = clazz.getMethod("isInReplay");
            getSpectatingPlayerMethod = clazz.getMethod("getSpectatingPlayer");
        } catch (Exception e) {
            FlashbackInventoryAddon.LOGGER.warn("Flashback detected but reflection setup failed; disabling GUI capture", e);
            flashbackPresent = false;
        }
    }

    public static boolean isFlashbackPresent() {
        return flashbackPresent;
    }

    public static boolean isExporting() {
        return flashbackPresent && callBoolean(isExportingMethod);
    }

    public static boolean isInReplay() {
        return flashbackPresent && callBoolean(isInReplayMethod);
    }

    public static AbstractClientPlayerEntity getSpectatingPlayer() {
        if (!flashbackPresent || getSpectatingPlayerMethod == null) {
            return null;
        }
        try {
            Object value = getSpectatingPlayerMethod.invoke(null);
            if (value instanceof AbstractClientPlayerEntity player) {
                return player;
            }
        } catch (Exception e) {
            FlashbackInventoryAddon.LOGGER.debug("Unable to query Flashback spectating player", e);
        }
        return null;
    }

    private static boolean callBoolean(Method method) {
        if (method == null) {
            return false;
        }
        try {
            Object value = method.invoke(null);
            return Boolean.TRUE.equals(value);
        } catch (Exception e) {
            FlashbackInventoryAddon.LOGGER.debug("Flashback boolean call failed", e);
            return false;
        }
    }
}

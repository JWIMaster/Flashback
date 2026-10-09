package com.flashbackinventoryaddon;

import com.flashbackinventoryaddon.config.ConfigManager;
import com.flashbackinventoryaddon.config.ModConfig;
import com.flashbackinventoryaddon.flashback.FlashbackCompat;
import com.flashbackinventoryaddon.render.GuiRenderController;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FlashbackInventoryAddon implements ClientModInitializer {

    public static final String MOD_ID = "flashbackinventoryaddon";
    public static final Logger LOGGER = LoggerFactory.getLogger("FlashbackInventoryAddon");

    private static ModConfig config;

    @Override
    public void onInitializeClient() {
        config = ConfigManager.load();
        FlashbackCompat.bootstrap();
        GuiRenderController.reloadConfig(config);

        if (FlashbackCompat.isFlashbackPresent()) {
            LOGGER.info("Flashback detected; GUI capture hooks ready");
        } else {
            LOGGER.warn("Flashback not present; GUI capture hooks idle");
        }
    }

    public static ModConfig getConfig() {
        return config;
    }
}

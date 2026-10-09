package com.flashbackinventoryaddon.config;

import com.flashbackinventoryaddon.FlashbackInventoryAddon;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ConfigManager {

    private static final String FILE_NAME = FlashbackInventoryAddon.MOD_ID + ".json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private ConfigManager() {
    }

    public static ModConfig load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
        ModConfig config = new ModConfig();

        if (Files.exists(path)) {
            try {
                ModConfig loaded = GSON.fromJson(Files.readString(path), ModConfig.class);
                if (loaded != null) {
                    config = loaded;
                }
            } catch (Exception e) {
                FlashbackInventoryAddon.LOGGER.error("Failed to read config {}, using defaults", FILE_NAME, e);
            }
        }

        try {
            String contents = GSON.toJson(config);
            Files.createDirectories(path.getParent());
            Files.writeString(path, contents);
        } catch (IOException e) {
            FlashbackInventoryAddon.LOGGER.warn("Failed to save config {}", FILE_NAME, e);
        }

        return config;
    }
}

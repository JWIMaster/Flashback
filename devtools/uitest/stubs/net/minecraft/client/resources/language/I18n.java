package net.minecraft.client.resources.language;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Stand-in for Minecraft's translations, reading the mod's own language file.
 *
 * <p>Only used by the offscreen UI harness: the labels the editor draws come from here, so what the
 * harness renders is the text a player would see.
 */
public class I18n {

    private static final Map<String, String> VALUES = load();

    private static Map<String, String> load() {
        try {
            var json = com.google.gson.JsonParser.parseString(
                Files.readString(Path.of("src/main/resources/assets/flashback/lang/en_us.json")));
            Map<String, String> values = new java.util.HashMap<>();
            for (var entry : json.getAsJsonObject().entrySet()) {
                values.put(entry.getKey(), entry.getValue().getAsString());
            }
            return values;
        } catch (Exception e) {
            throw new RuntimeException("Could not read the language file", e);
        }
    }

    public static String get(String key, Object... args) {
        String value = VALUES.getOrDefault(key, key);
        return args == null || args.length == 0 ? value : String.format(value, args);
    }

    public static boolean exists(String key) {
        return VALUES.containsKey(key);
    }
}

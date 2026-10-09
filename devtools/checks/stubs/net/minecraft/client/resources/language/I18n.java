package net.minecraft.client.resources.language;

/**
 * Stand-in for the game's translation lookup, which needs a loaded language.
 *
 * <p>Editor code that labels its undo entries - creating a camera, adding a keyframe - calls this,
 * so suites that drive those paths would otherwise fail on the missing language manager. Returning
 * the key keeps the paths runnable, and a failure message quoting a key makes it plain that no
 * translation was involved.
 */
public class I18n {

    public static String get(String key, Object... args) {
        return key;
    }

}

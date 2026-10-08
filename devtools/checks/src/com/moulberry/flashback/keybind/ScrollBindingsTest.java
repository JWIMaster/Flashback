package com.moulberry.flashback.keybind;

import com.moulberry.flashback.configuration.FlashbackConfigV1;
import com.moulberry.flashback.editor.keybinds.Keybind;
import com.moulberry.flashback.editor.keybinds.Keybinds;

/**
 * The scroll gestures have to survive being written to the config and read back.
 *
 * <p>They are modifier-only bindings, and the writer used to save them as plain "none" - which loads
 * as "no binding at all", so zooming with a modifier stopped working forever for anyone whose config
 * had been saved. This reproduces exactly that and checks the repair.
 */
public class ScrollBindingsTest {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        Keybind zoom = Keybinds.TIMELINE_ZOOM_SCROLL;
        Keybind pan = Keybinds.TIMELINE_MOVE_SCROLL;

        check("the zoom gesture is a modifier-only binding", zoom.isModifierOnly());
        check("zoom is declared with a modifier", zoom.toConfigValue().startsWith("ctrl+"));
        check("pan is declared with a modifier", pan.toConfigValue().startsWith("shift+"));

        // What the writer used to produce, and what an affected config therefore contains.
        zoom.loadFromConfigValue("none");
        pan.loadFromConfigValue("none");
        check("a plain 'none' really does disable the gesture", zoom.getKey() == 0 && pan.getKey() == 0);

        // The repair runs on every load, so this is what an affected player gets.
        FlashbackConfigV1 config = new FlashbackConfigV1ForTest().create();
        config.keybinds.put("timeline_zoom_scroll", "none");
        config.keybinds.put("timeline_move_scroll", "none");
        Keybinds.repairScrollBindings(config);

        check("the repair gives zoom its modifier back", zoom.getKey() != 0 && zoom.isCtrlMod());
        check("the repair gives pan its modifier back", pan.getKey() != 0 && pan.isShiftMod());
        check("the repaired values round-trip through the config",
            zoom.toConfigValue().equals("ctrl+none") && pan.toConfigValue().equals("shift+none"));

        // And a config written by this version must load back correctly.
        zoom.loadFromConfigValue("none");
        zoom.loadFromConfigValue("ctrl+none");
        check("'ctrl+none' loads as the zoom gesture again",
            zoom.getKey() == Keybind.FAKE_SCROLL_KEY && zoom.isCtrlMod() && !zoom.isShiftMod());

        if (failures > 0) {
            System.out.println("FAILURES: " + failures);
            System.exit(1);
        }
        System.out.println("All scroll binding tests passed");
    }

    /** The config class has a private constructor, as the game builds it by reflection. */
    private static final class FlashbackConfigV1ForTest {
        FlashbackConfigV1 create() throws Exception {
            var constructor = FlashbackConfigV1.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        }
    }

    private static void check(String what, boolean condition) {
        if (condition) {
            System.out.println("ok:   " + what);
        } else {
            failures += 1;
            System.out.println("FAIL: " + what);
        }
    }
}

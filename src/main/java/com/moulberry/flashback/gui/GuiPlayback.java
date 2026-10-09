package com.moulberry.flashback.gui;

import com.moulberry.flashback.Flashback;

/**
 * Whether the containers a replay recorded should actually appear on screen.
 *
 * <p>A replay records the packets the server sent, which includes every chest, crafting table and
 * furnace the player opened - but the player was not really playing, so the replay deliberately threw
 * those away. Showing them is what makes a replay show what the player saw.
 *
 * <p>They are shown whenever the replay plays them, including with the editor open, because seeing
 * what the recorded player had on screen is the point of it.
 */
public final class GuiPlayback {

    private GuiPlayback() {
    }

    public static boolean enabled() {
        try {
            return Flashback.getConfig().internal.showGuisInReplays;
        } catch (Throwable t) {
            return false;
        }
    }

    /** True when a recorded container should be shown right now. */
    public static boolean shouldShow() {
        return enabled();
    }

}

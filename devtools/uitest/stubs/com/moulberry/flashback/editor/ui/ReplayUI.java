package com.moulberry.flashback.editor.ui;

import imgui.moulberry90.ImGui;
import imgui.moulberry90.ImGuiIO;

/**
 * Stand-in for the editor's UI shell.
 *
 * <p>The timeline asks this class about scale, modifier keys and notifications. Providing those from
 * the harness keeps the real timeline code running unchanged while leaving out the parts that need a
 * window, a graphics context and the game.
 */
public class ReplayUI {

    /** Set by the harness to emulate holding the platform modifier. */
    public static boolean ctrlDown = false;
    /** GUI scale, as the game's options would set it. */
    public static float uiScale = 1f;

    public static ImGuiIO getIO() {
        return ImGui.getIO();
    }

    public static int scaleUi(int value) {
        return (int) (value * getUiScale());
    }

    public static float getUiScale() {
        return uiScale;
    }

    public static boolean isCtrlOrCmdDown() {
        ImGuiIO io = ImGui.getIO();
        return ctrlDown || io.getKeyCtrl() || io.getKeySuper();
    }

    public static boolean isActive() {
        return true;
    }

    public static boolean isImGuiContextActive() {
        return true;
    }

    public static void setInfoOverlay(String text) {
        System.out.println("[info] " + text);
    }

    public static synchronized void setInfoOverlayShort(String text) {
        System.out.println("[info] " + text);
    }

    public static boolean consumeConfirm() {
        return false;
    }

    public static boolean consumeCancel() {
        return false;
    }

    public static boolean hasAnyPopupOpen() {
        return ImGui.isPopupOpen("", imgui.moulberry90.flag.ImGuiPopupFlags.AnyPopup);
    }
}

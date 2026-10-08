package uitest;

import imgui.moulberry90.ImGui;

/** Proves the offscreen renderer reproduces real ImGui output before it is trusted on the editor. */
public final class SmokeTest {
    public static void main(String[] args) {
        Harness harness = new Harness(640, 260, 2f);
        harness.setBeforeFrame(() -> {
            ImGui.setNextWindowPos(10, 10);
            ImGui.setNextWindowSize(620, 240);
        });
        harness.setUi(() -> {
            ImGui.begin("Harness smoke test");
            ImGui.text("Text rendering, widgets and clipping all come from real ImGui.");
            ImGui.separator();
            if (ImGui.button("A button")) {
                ImGui.text("clicked");
            }
            ImGui.sameLine();
            ImGui.textColored(0xFFFFB74D, "\ue04b Camera 1   \ue313  \ue8f4  \ue5d2  \ue14e");
            ImGui.separator();
            ImGui.textDisabled("disabled text");
            ImGui.sameLine();
            ImGui.text("normal text");
            ImGui.end();
        });
        for (int i = 0; i < 3; i++) {
            harness.frame();
        }
        harness.renderer().save("devtools/uitest/out/smoke.png");
        System.out.println("wrote smoke.png after " + harness.frames() + " frames, draw lists=" + harness.lastCmdLists()
            + " vertices=" + harness.lastVertices());
    }
}

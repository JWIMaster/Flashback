package uitest;

import imgui.moulberry90.ImDrawData;
import imgui.moulberry90.ImGui;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Prints what ImGui actually produced, for when the renderer shows nothing. */
public final class DebugDrawData {
    public static void main(String[] args) {
        Harness harness = new Harness(640, 260, 1f);
        harness.setBeforeFrame(() -> {
            ImGui.setNextWindowPos(10, 10);
            ImGui.setNextWindowSize(400, 200);
        });
        harness.setUi(() -> {
            ImGui.begin("Debug");
            ImGui.text("Hello");
            ImGui.end();
        });
        harness.frame();
        harness.frame();
        ImDrawData dd = ImGui.getDrawData();
        System.out.println("displaySize=" + dd.getDisplaySizeX() + "x" + dd.getDisplaySizeY());
        System.out.println("cmdLists=" + dd.getCmdListsCount() + " totalVtx=" + dd.getTotalVtxCount());
        System.out.println("sizeOfImDrawVert=" + ImDrawData.sizeOfImDrawVert()
            + " sizeOfImDrawIdx=" + ImDrawData.sizeOfImDrawIdx());
        for (int list = 0; list < dd.getCmdListsCount(); list++) {
            // Copy straight away: the binding reuses one staging buffer for every accessor.
            ByteBuffer staging = dd.getCmdListVtxBufferData(list);
            byte[] vtxBytes = new byte[staging.limit()];
            staging.get(0, vtxBytes);
            int vtxLimit = staging.limit();
            staging = dd.getCmdListIdxBufferData(list);
            byte[] idxBytes = new byte[staging.limit()];
            staging.get(0, idxBytes);
            int idxLimit = staging.limit();
            java.nio.ByteBuffer vtx = java.nio.ByteBuffer.wrap(vtxBytes).order(ByteOrder.LITTLE_ENDIAN);
            System.out.println(" list " + list + ": vtxSize=" + dd.getCmdListVtxBufferSize(list)
                + " vtxBytesLimit=" + vtxLimit
                + " idxSize=" + dd.getCmdListIdxBufferSize(list) + " idxBytesLimit=" + idxLimit
                + " cmds=" + dd.getCmdListCmdBufferSize(list));
            System.out.print("   first vertex floats:");
            for (int i = 0; i < 6 * 5; i++) {
                System.out.print(" " + vtx.getFloat(i * 4));
            }
            System.out.println();
            for (int cmd = 0; cmd < dd.getCmdListCmdBufferSize(list); cmd++) {
                var clip = dd.getCmdListCmdBufferClipRect(list, cmd);
                System.out.println("   cmd " + cmd + ": elems=" + dd.getCmdListCmdBufferElemCount(list, cmd)
                    + " idxOff=" + dd.getCmdListCmdBufferIdxOffset(list, cmd)
                    + " vtxOff=" + dd.getCmdListCmdBufferVtxOffset(list, cmd)
                    + " tex=" + dd.getCmdListCmdBufferTextureId(list, cmd)
                    + " clip=" + clip.x + "," + clip.y + ".." + clip.z + "," + clip.w);
            }
        }
    }
}

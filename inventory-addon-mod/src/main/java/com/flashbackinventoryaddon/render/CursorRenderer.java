package com.flashbackinventoryaddon.render;

import net.minecraft.client.gui.DrawContext;

public final class CursorRenderer {

    private CursorRenderer() {
    }

    public static void render(DrawContext drawContext, int mouseX, int mouseY) {
        int color = 0xFFFFFFFF;
        int shadow = 0x88000000;

        // Simple arrow-style cursor
        drawContext.fill(mouseX, mouseY, mouseX + 2, mouseY + 12, color);
        drawContext.fill(mouseX, mouseY, mouseX + 8, mouseY + 2, color);
        drawContext.fill(mouseX + 2, mouseY + 2, mouseX + 6, mouseY + 6, color);

        // Add a small shadow so it remains visible on bright backgrounds
        drawContext.fill(mouseX + 1, mouseY + 12, mouseX + 3, mouseY + 13, shadow);
        drawContext.fill(mouseX + 8, mouseY + 1, mouseX + 9, mouseY + 3, shadow);
    }
}

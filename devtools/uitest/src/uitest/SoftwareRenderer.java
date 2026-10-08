package uitest;

import imgui.moulberry90.ImDrawData;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import javax.imageio.ImageIO;

/**
 * Rasterises ImGui's draw data in software, so the editor's UI can be rendered and inspected
 * without a graphics context, a window, or Minecraft.
 *
 * <p>ImGui produces triangles, a font atlas and a list of scissored draw commands; that is the whole
 * contract a backend has to satisfy. Implementing it here in Java means the real UI code runs
 * unchanged and produces a real picture, which is the difference between reasoning about the layout
 * and looking at it.
 */
public final class SoftwareRenderer {

    private final int width;
    private final int height;
    /** Framebuffer pixels per logical UI pixel, so text can be rendered legibly for inspection. */
    private final float scale;
    private final int[] pixels;

    private byte[] atlas;
    private int atlasWidth;
    private int atlasHeight;
    private int vertexStride = 20;
    private int indexStride = 2;
    /** Signed pixel offsets applied to every vertex, for drawing a viewport at its own position. */
    private int offsetPacked = 0;

    public SoftwareRenderer(int width, int height, float scale) {
        this.width = width;
        this.height = height;
        this.scale = scale;
        this.pixels = new int[width * height];
    }

    public void setAtlas(ByteBuffer rgba, int width, int height) {
        this.atlas = new byte[rgba.capacity()];
        rgba.get(0, this.atlas);
        this.atlasWidth = width;
        this.atlasHeight = height;
    }

    public void clear(int argb) {
        java.util.Arrays.fill(this.pixels, argb);
    }

    public BufferedImage image() {
        BufferedImage image = new BufferedImage(this.width, this.height, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, this.width, this.height, this.pixels, 0, this.width);
        return image;
    }

    public void save(String path) {
        try {
            File file = new File(path);
            file.getParentFile().mkdirs();
            ImageIO.write(image(), "png", file);
        } catch (Exception e) {
            throw new RuntimeException("Could not write " + path, e);
        }
    }

    /** Draws a viewport's data at its own position, as a multi-viewport backend does. */
    public void drawAt(ImDrawData drawData, float offsetX, float offsetY) {
        int saved = this.offsetPacked;
        this.offsetPacked = (Math.round(offsetX * this.scale) & 0xFFFF) << 16 | (Math.round(offsetY * this.scale) & 0xFFFF);
        try {
            this.draw(drawData);
        } finally {
            this.offsetPacked = saved;
        }
    }

    /** Draws a viewport's data at its own position. */
    public void draw(ImDrawData drawData, float offsetX, float offsetY) {
        drawAt(drawData, offsetX, offsetY);
    }

    public void draw(ImDrawData drawData) {
        int vertexStride = ImDrawData.sizeOfImDrawVert();
        int indexStride = ImDrawData.sizeOfImDrawIdx();
        int listCount = drawData.getCmdListsCount();
        for (int list = 0; list < listCount; list++) {
            // The binding hands back one shared staging buffer, so each must be copied out before the
            // next accessor is called or its contents are already gone.
            ByteBuffer staging = drawData.getCmdListVtxBufferData(list);
            byte[] vertexBytes = new byte[staging.limit()];
            staging.get(0, vertexBytes);
            staging = drawData.getCmdListIdxBufferData(list);
            byte[] indexBytes = new byte[staging.limit()];
            staging.get(0, indexBytes);
            ByteBuffer vertices = ByteBuffer.wrap(vertexBytes).order(ByteOrder.LITTLE_ENDIAN);
            this.vertexStride = vertexStride;
            this.indexStride = indexStride;
            int cmdCount = drawData.getCmdListCmdBufferSize(list);
            for (int cmd = 0; cmd < cmdCount; cmd++) {
                int elemCount = drawData.getCmdListCmdBufferElemCount(list, cmd);
                int idxOffset = drawData.getCmdListCmdBufferIdxOffset(list, cmd);
                int vtxOffset = drawData.getCmdListCmdBufferVtxOffset(list, cmd);
                var clip = drawData.getCmdListCmdBufferClipRect(list, cmd);
                int clipX0 = Math.round(clip.x * this.scale);
                int clipY0 = Math.round(clip.y * this.scale);
                int clipX1 = Math.round(clip.z * this.scale);
                int clipY1 = Math.round(clip.w * this.scale);
                for (int e = 0; e < elemCount; e += 3) {
                    // Indices are relative to this command's vertex offset, as they are for a backend.
                    int i0 = vtxOffset + this.index(indexBytes, idxOffset + e);
                    int i1 = vtxOffset + this.index(indexBytes, idxOffset + e + 1);
                    int i2 = vtxOffset + this.index(indexBytes, idxOffset + e + 2);
                    triangle(vertices, i0, i1, i2, clipX0, clipY0, clipX1, clipY1);
                }
            }
        }
    }

    /** ImDrawVert is pos(2f), uv(2f), col(4 bytes RGBA), 20 bytes per vertex. */
    private void triangle(ByteBuffer vertices, int a, int b, int c,
                          int clipX0, int clipY0, int clipX1, int clipY1) {
        int offsetX = (short) (this.offsetPacked >> 16);
        int offsetY = (short) (this.offsetPacked & 0xFFFF);
        float ax = vertex(vertices, a, 0) * this.scale + offsetX;
        float ay = vertex(vertices, a, 1) * this.scale + offsetY;
        float bx = vertex(vertices, b, 0) * this.scale + offsetX;
        float by = vertex(vertices, b, 1) * this.scale + offsetY;
        float cx = vertex(vertices, c, 0) * this.scale + offsetX;
        float cy = vertex(vertices, c, 1) * this.scale + offsetY;

        // Twice the signed area. ImGui submits both windings and disables face culling in its
        // backends, so orient the triangle instead of rejecting it.
        float area = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
        if (area == 0) {
            return;
        }
        if (area < 0) {
            float swapX = bx;
            float swapY = by;
            bx = cx;
            by = cy;
            cx = swapX;
            cy = swapY;
            area = -area;
        }

        int minX = Math.max(Math.max(0, clipX0), (int) Math.floor(Math.min(ax, Math.min(bx, cx))));
        int maxX = Math.min(Math.min(this.width, clipX1), (int) Math.ceil(Math.max(ax, Math.max(bx, cx))));
        int minY = Math.max(Math.max(0, clipY0), (int) Math.floor(Math.min(ay, Math.min(by, cy))));
        int maxY = Math.min(Math.min(this.height, clipY1), (int) Math.ceil(Math.max(ay, Math.max(by, cy))));
        if (minX >= maxX || minY >= maxY) {
            return;
        }

        float invArea = 1f / area;
        // Vertex attributes, so the weights below can interpolate them directly.
        float au = vertex(vertices, a, 2);
        float av = vertex(vertices, a, 3);
        float bu = vertex(vertices, b, 2);
        float bv = vertex(vertices, b, 3);
        float cu = vertex(vertices, c, 2);
        float cv = vertex(vertices, c, 3);
        int ac = vertexColour(vertices, a);
        int bc = vertexColour(vertices, b);
        int cc = vertexColour(vertices, c);

        for (int y = minY; y < maxY; y++) {
            float py = y + 0.5f;
            for (int x = minX; x < maxX; x++) {
                float px = x + 0.5f;
                // One weight per vertex, from the edge opposite it.
                float wa = ((cx - bx) * (py - by) - (cy - by) * (px - bx)) * invArea;
                float wb = ((ax - cx) * (py - cy) - (ay - cy) * (px - cx)) * invArea;
                float wc = 1f - wa - wb;
                if (wa < 0 || wb < 0 || wc < 0) {
                    continue;
                }

                float u = wa * au + wb * bu + wc * cu;
                float v = wa * av + wb * bv + wc * cv;
                int red = Math.round(wa * ((ac >> 16) & 0xFF) + wb * ((bc >> 16) & 0xFF) + wc * ((cc >> 16) & 0xFF));
                int green = Math.round(wa * ((ac >> 8) & 0xFF) + wb * ((bc >> 8) & 0xFF) + wc * ((cc >> 8) & 0xFF));
                int blue = Math.round(wa * (ac & 0xFF) + wb * (bc & 0xFF) + wc * (cc & 0xFF));
                int alpha = Math.round(wa * ((ac >>> 24) & 0xFF) + wb * ((bc >>> 24) & 0xFF) + wc * ((cc >>> 24) & 0xFF));

                int coverage = sample(u, v);
                if (coverage >= 0) {
                    // The atlas is coverage in alpha and white in RGB, so it modulates the colour.
                    red = red * coverage / 255;
                    green = green * coverage / 255;
                    blue = blue * coverage / 255;
                    alpha = alpha * coverage / 255;
                }
                if (alpha == 0) {
                    continue;
                }
                this.pixels[y * this.width + x] = over(red, green, blue, alpha, this.pixels[y * this.width + x]);
            }
        }
    }

    private int index(byte[] indices, int position) {
        int offset = position * this.indexStride;
        if (offset + this.indexStride > indices.length) {
            return 0;
        }
        if (this.indexStride == 4) {
            return (indices[offset] & 0xFF) | ((indices[offset + 1] & 0xFF) << 8)
                | ((indices[offset + 2] & 0xFF) << 16) | ((indices[offset + 3] & 0xFF) << 24);
        }
        return (indices[offset] & 0xFF) | ((indices[offset + 1] & 0xFF) << 8);
    }

    private float vertex(ByteBuffer vertices, int index, int component) {
        int offset = index * this.vertexStride + component * 4;
        if (offset + 4 > vertices.limit()) {
            return 0;
        }
        return vertices.getFloat(offset);
    }

    private int vertexColour(ByteBuffer vertices, int index) {
        int base = index * this.vertexStride + 16;
        if (base + 4 > vertices.limit()) {
            return 0;
        }
        int r = vertices.get(base) & 0xFF;
        int g = vertices.get(base + 1) & 0xFF;
        int b = vertices.get(base + 2) & 0xFF;
        int a = vertices.get(base + 3) & 0xFF;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** Atlas coverage at a texture coordinate, or -1 when the sample is outside the atlas. */
    private int sample(float u, float v) {
        if (this.atlas == null) {
            return -1;
        }
        int x = (int) (u * this.atlasWidth);
        int y = (int) (v * this.atlasHeight);
        if (x < 0 || y < 0 || x >= this.atlasWidth || y >= this.atlasHeight) {
            return -1;
        }
        int offset = (y * this.atlasWidth + x) * 4;
        if (offset + 3 >= this.atlas.length) {
            return -1;
        }
        return this.atlas[offset + 3] & 0xFF;
    }

    private static int over(int r, int g, int b, int alpha, int destination) {
        int dr = (destination >> 16) & 0xFF;
        int dg = (destination >> 8) & 0xFF;
        int db = destination & 0xFF;
        int or = (r * alpha + dr * (255 - alpha)) / 255;
        int og = (g * alpha + dg * (255 - alpha)) / 255;
        int ob = (b * alpha + db * (255 - alpha)) / 255;
        return (or << 16) | (og << 8) | ob;
    }
}

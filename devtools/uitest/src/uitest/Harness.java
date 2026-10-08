package uitest;

import imgui.moulberry90.ImDrawData;
import imgui.moulberry90.ImFontAtlas;
import imgui.moulberry90.ImFontConfig;
import imgui.moulberry90.ImFontGlyphRangesBuilder;
import imgui.moulberry90.ImGui;
import imgui.moulberry90.ImGuiIO;
import imgui.moulberry90.flag.ImGuiKey;
import imgui.moulberry90.flag.ImGuiMouseButton;
import imgui.moulberry90.type.ImInt;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs the real editor UI without Minecraft: an ImGui context with the same fonts, a software
 * backend, and synthetic input.
 *
 * <p>ImGui's input is a queue of events that the next frame consumes, so driving the UI is a matter
 * of queueing mouse and key events and then running a frame. That is what makes it possible to
 * actually click the buttons, drag the clips and check what happened, rather than reasoning about
 * code and hoping.
 */
public final class Harness {

    private final int width;
    private final int height;
    private final float scale;
    private final SoftwareRenderer renderer;
    private final ImGuiIO io;
    private final Path fonts = Path.of("src/main/resources/assets/flashback");
    private Runnable ui = () -> {};
    private int lastVertices;
    private int lastCmdLists;
    private int viewportWindows;
    private Runnable beforeFrame = () -> {};

    private float mouseX = -1;
    private float mouseY = -1;
    private final List<Runnable> pending = new ArrayList<>();
    private int frames;

    /** @param width  logical UI width, as ImGui sees it
     *  @param height logical UI height
     *  @param renderScale framebuffer pixels per logical pixel */
    public Harness(int width, int height, float renderScale) {
        this.width = width;
        this.height = height;
        this.scale = renderScale;
        this.renderer = new SoftwareRenderer(Math.round(width * renderScale), Math.round(height * renderScale),
            renderScale);

        ImGui.createContext();
        this.io = ImGui.getIO();
        this.io.setDisplaySize(width, height);
        this.io.setDeltaTime(1 / 60f);
        this.io.setIniFilename(null);
        buildFonts(this.io.getFonts());
        ImGui.styleColorsDark();
    }

    /** The same fonts, sizes and glyph coverage the real UI builds, read from the real ranges. */
    private void buildFonts(ImFontAtlas atlas) {
        try {
            float uiScale = 1f;
            ImFontConfig config = new ImFontConfig();
            config.setOversampleH(2);
            config.setOversampleV(2);
            config.setName("Inter (Medium), 16px");
            config.setGlyphOffset(0, 0);

            ImFontGlyphRangesBuilder builder = new ImFontGlyphRangesBuilder();
            builder.addRanges(atlas.getGlyphRangesDefault());
            builder.addText("\u00b7\u2013\u2014\u2190\u2191\u2192\u2193\u2318\u2303\u2387\u21e7\u2756\u26a0\u2018\u2019\u201c\u201d\u2026");
            // Every character the interface can actually show, exactly as the real font builder does
            // with the loaded language file, so no label renders as a missing glyph.
            builder.addText(languageCharacters());
            short[] textRanges = builder.buildRanges();
            atlas.addFontFromMemoryTTF(Files.readAllBytes(this.fonts.resolve("inter-medium.ttf")), 16f,
                config, textRanges);

            config.setMergeMode(true);
            config.setGlyphOffset(0, (int) (5 * uiScale));
            atlas.addFontFromMemoryTTF(Files.readAllBytes(this.fonts.resolve("materialiconsround-regular.otf")),
                20f, config, iconRanges());
            config.setMergeMode(false);
            atlas.build();

            ImInt atlasWidth = new ImInt();
            ImInt atlasHeight = new ImInt();
            ByteBuffer pixels = atlas.getTexDataAsRGBA32(atlasWidth, atlasHeight);
            this.renderer.setAtlas(pixels, atlasWidth.get(), atlasHeight.get());
            config.destroy();
        } catch (Exception e) {
            throw new RuntimeException("Could not build the harness fonts", e);
        }
    }

    /** Every character used by any string in the English language file. */
    private String languageCharacters() throws Exception {
        StringBuilder text = new StringBuilder();
        var json = com.google.gson.JsonParser.parseString(
            Files.readString(Path.of("src/main/resources/assets/flashback/lang/en_us.json")));
        for (var entry : json.getAsJsonObject().entrySet()) {
            text.append(entry.getValue().getAsString());
        }
        return text.toString();
    }

    /**
     * The icon glyphs the editor uses, read from the same list the real UI registers.
     *
     * <p>Reading it rather than copying it means the harness cannot drift from the real font setup:
     * an icon that the UI can draw is an icon this renderer has.
     */
    private short[] iconRanges() throws Exception {
        List<Integer> codepoints = new ArrayList<>();
        String source = Files.readString(Path.of("src/main/java/com/moulberry/flashback/editor/ui/ReplayUI.java"));
        int start = source.indexOf("private static short[] buildMaterialIconRanges");
        int end = source.indexOf("\n    }", start);
        Matcher matcher = Pattern.compile("addChar\\('\\\\u([0-9a-fA-F]{4})'\\)")
            .matcher(source.substring(start, end));
        while (matcher.find()) {
            codepoints.add(Integer.parseInt(matcher.group(1), 16));
        }
        System.out.println("[harness] icon glyphs registered: " + codepoints.size()
            + (codepoints.isEmpty() ? "" : String.format(" (first %04x, last %04x)",
                codepoints.get(0), codepoints.get(codepoints.size() - 1))));
        ImFontGlyphRangesBuilder builder = new ImFontGlyphRangesBuilder();
        for (int codepoint : codepoints) {
            builder.addChar((char) codepoint);
        }
        return builder.buildRanges();
    }

    public SoftwareRenderer renderer() {
        return this.renderer;
    }

    public ImGuiIO io() {
        return this.io;
    }

    public void setUi(Runnable ui) {
        this.ui = ui;
    }

    /** Runs before the frame is built, for positioning the window under test. */
    public void setBeforeFrame(Runnable beforeFrame) {
        this.beforeFrame = beforeFrame;
    }

    public int frames() {
        return this.frames;
    }

    // -- Input -----------------------------------------------------------------------------------

    public void moveMouse(float x, float y) {
        this.mouseX = x;
        this.mouseY = y;
        float logicalX = x;
        float logicalY = y;
        this.pending.add(() -> this.io.addMousePosEvent(logicalX, logicalY));
    }

    public void mouseDown(int button) {
        this.pending.add(() -> this.io.addMouseButtonEvent(button, true));
    }

    public void mouseUp(int button) {
        this.pending.add(() -> this.io.addMouseButtonEvent(button, false));
    }

    public void scroll(float vertical, float horizontal) {
        this.pending.add(() -> this.io.addMouseWheelEvent(horizontal, vertical));
    }

    public void key(int key, boolean down) {
        this.pending.add(() -> this.io.addKeyEvent(key, down));
    }

    public void type(char character) {
        this.pending.add(() -> this.io.addInputCharacter(character));
    }

    /** A click: press and release with the pointer already where it should be. */
    public void click(float x, float y, int button) {
        moveMouse(x, y);
        frame();
        mouseDown(button);
        frame();
        mouseUp(button);
        frame();
    }

    public void click(float x, float y) {
        click(x, y, ImGuiMouseButton.Left);
    }

    /**
     * A drag, delivered in steps like a real pointer would be.
     *
     * <p>Several intermediate frames matter: ImGui decides whether a press became a drag only after
     * the pointer moves, and the editor's own drag states are updated per frame.
     */
    public void drag(float fromX, float fromY, float toX, float toY, int steps, int button) {
        moveMouse(fromX, fromY);
        frame();
        mouseDown(button);
        frame();
        for (int i = 1; i <= steps; i++) {
            float t = i / (float) steps;
            moveMouse(fromX + (toX - fromX) * t, fromY + (toY - fromY) * t);
            frame();
        }
        mouseUp(button);
        frame();
    }

    public void drag(float fromX, float fromY, float toX, float toY) {
        drag(fromX, fromY, toX, toY, 6, ImGuiMouseButton.Left);
    }

    // -- Frame loop ------------------------------------------------------------------------------

    public void frame() {
        this.frames += 1;
        for (Runnable event : this.pending) {
            event.run();
        }
        this.pending.clear();

        // ImGui resets pending window geometry in NewFrame, so anything that positions a window has
        // to run after it and before the window is begun.
        ImGui.newFrame();
        this.beforeFrame.run();
        this.ui.run();
        ImGui.render();

        // With viewports enabled each window can be its own viewport with its own draw data, so
        // rendering only the main one would silently drop popups and detached windows.
        this.renderer.clear(0xFF1E1E1E);
        ImDrawData drawData = ImGui.getDrawData();
        this.renderer.draw(drawData);
        this.lastVertices = drawData.getTotalVtxCount();
        this.lastCmdLists = drawData.getCmdListsCount();

        var platformIO = ImGui.getPlatformIO();
        int viewportCount = platformIO.getViewportsSize();
        for (int i = 0; i < viewportCount; i++) {
            var viewport = platformIO.getViewports(i);
            if (viewport.getID() == ImGui.getMainViewport().getID()) {
                continue;
            }
            ImDrawData viewportDrawData = viewport.getDrawData();
            if (viewportDrawData == null || viewportDrawData.getCmdListsCount() == 0) {
                continue;
            }
            this.renderer.drawAt(viewportDrawData, viewport.getPosX(), viewport.getPosY());
            this.lastVertices += viewportDrawData.getTotalVtxCount();
            this.lastCmdLists += viewportDrawData.getCmdListsCount();
            this.viewportWindows += 1;
        }
    }

    public int lastVertices() {
        return this.lastVertices;
    }

    public int lastCmdLists() {
        return this.lastCmdLists;
    }

    /** How many extra windows were drawn through their own viewport. */
    public int viewportWindows() {
        return this.viewportWindows;
    }

    public float mouseX() {
        return this.mouseX;
    }

    public float mouseY() {
        return this.mouseY;
    }

    public int windowWidth() {
        return Math.round(this.width / this.scale);
    }

    public int windowHeight() {
        return Math.round(this.height / this.scale);
    }
}

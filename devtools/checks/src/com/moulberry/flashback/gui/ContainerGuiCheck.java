package com.moulberry.flashback.gui;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.playback.ReplayGamePacketHandler;
import com.moulberry.flashback.record.IgnoredPacketSet;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * The rules a replayed container has to obey, checked against the code rather than by watching it.
 *
 * <p>Every one of these is a fault that has already happened once, and none of them is visible from
 * the inside of the game: packets that were never written, so the feature silently did nothing;
 * hooks on methods that a container screen overrides, so they never ran for the screens they were
 * for; a screen that took the mouse; an overlay whose background was never asked for; and a menu
 * whose slots were the viewer's own, so that watching a replay rearranged the viewer's inventory.
 */
public class ContainerGuiCheck {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : "src/main/java");
        if (!Files.isDirectory(root)) {
            System.out.println("FAIL: source root not found: " + root);
            System.exit(1);
        }

        theRecordingKeepsContainersOnlyWhenAsked();
        everyContainerPacketHasItsOwnHandler();
        noContainerHandlerThrowsOrReachesTheClient(root);
        theOverlayShowsTheGameScreenWithoutTakingOver(root);
        theHooksAreOnTheMethodsThatActuallyRun(root);
        theRecordingWritesTheClosesTheServerDoesNot(root);

        if (failures > 0) {
            System.out.println("FAILURES: " + failures);
            System.exit(1);
        }
        System.out.println("All container display checks passed");
    }

    /**
     * A container packet in both the "worth recording" set and the "never recorded" set is dropped
     * by the second, which is a way for the whole feature to do nothing without failing anything.
     */
    private static void theRecordingKeepsContainersOnlyWhenAsked() {
        Set<Class<?>> containers = IgnoredPacketSet.containerPackets();
        Set<Class<?>> always = IgnoredPacketSet.alwaysIgnored();

        check("there are container packets worth recording", !containers.isEmpty());
        for (Class<?> type : containers) {
            check(type.getSimpleName() + " is not in the never-recorded set", !always.contains(type));
        }

        Flashback.getConfig().internal.showGuisInReplays = true;
        for (Class<?> type : containers) {
            check(type.getSimpleName() + " is recorded when containers are shown",
                !IgnoredPacketSet.isIgnoredType(type));
        }

        Flashback.getConfig().internal.showGuisInReplays = false;
        for (Class<?> type : containers) {
            check(type.getSimpleName() + " is dropped when they are not",
                IgnoredPacketSet.isIgnoredType(type));
        }

        // Put it back, so that a later suite does not inherit a flag it did not set.
        Flashback.getConfig().internal.showGuisInReplays = true;
    }

    /**
     * A packet with no handler of its own falls through to one that reports the packet as
     * unsupported, and that exception is not caught: the replay's tick loop dies with it.
     */
    private static void everyContainerPacketHasItsOwnHandler() {
        for (Class<?> type : IgnoredPacketSet.containerPackets()) {
            Method handler = handlerFor(type);
            check(type.getSimpleName() + " has a handler in the replay's packet handler", handler != null);
            if (handler != null) {
                check(type.getSimpleName() + "'s handler is public, so the packet can reach it",
                    Modifier.isPublic(handler.getModifiers()));
            }
        }
    }

    /** Reads the handlers and refuses the two things that broke playback before. */
    private static void noContainerHandlerThrowsOrReachesTheClient(Path root) throws Exception {
        String source = Files.readString(
            root.resolve("com/moulberry/flashback/playback/ReplayGamePacketHandler.java"));

        for (Class<?> type : IgnoredPacketSet.containerPackets()) {
            Method handler = handlerFor(type);
            if (handler == null) {
                continue;
            }
            String body = methodBody(source, handler.getName());
            check("the handler for " + type.getSimpleName() + " was found in the source", body != null);
            if (body == null) {
                continue;
            }
            check("the handler for " + type.getSimpleName() + " does not report the packet as unsupported",
                !body.contains("UnsupportedPacketException"));
            check("the handler for " + type.getSimpleName() + " does not build anything on the client",
                !body.contains("Minecraft.getInstance"));
        }
    }

    /**
     * The game's own screen draws the container, which is the whole point, and it is asked for it in
     * the one way that includes the container's background.
     *
     * <p>A screen's background is not part of {@code extractRenderState}: it is drawn by
     * {@code extractRenderStateWithTooltipAndSubtitles}, which is the call the game makes for the
     * screen a player has open. Asking for the first alone produces the labels and the items with no
     * container around them, which is exactly what a bare "Large Chest" floating in the sky is.
     */
    private static void theOverlayShowsTheGameScreenWithoutTakingOver(Path root) throws Exception {
        String display = Files.readString(root.resolve("com/moulberry/flashback/gui/GuiDisplay.java"));
        check("the overlay draws the game's own screen", display.contains("ScreenConstructor"));
        check("the overlay asks for the screen's background as well as its contents",
            display.contains("extractRenderStateWithTooltipAndSubtitles")
                && !display.contains(".extractRenderState(graphics"));

        for (String forbidden : List.of("setScreenAndShow", ".setScreen(", "MenuScreens.create",
            "initializeContents", ".removed(", "player.containerMenu")) {
            check("drawing a container does not use " + forbidden, !display.contains(forbidden));
        }
    }

    /**
     * A container screen overrides {@code init} and {@code removed}, so an injection into the ones
     * on {@code Screen} never runs for a container screen at all. That is a hook that compiles, is
     * present, and is blind to exactly the screens the feature is about.
     */
    private static void theHooksAreOnTheMethodsThatActuallyRun(Path root) throws Exception {
        String container = Files.readString(
            root.resolve("com/moulberry/flashback/mixin/gui/MixinAbstractContainerScreen.java"));
        check("a container's own close is hooked where it runs",
            container.contains("method = \"removed()V\"") && container.contains("GuiRecording.screenClosed"));

        String screen = Files.readString(
            root.resolve("com/moulberry/flashback/mixin/gui/MixinScreenGuiRecorder.java"));
        check("a screen being shown is hooked on added, not on init",
            screen.contains("added()V") && screen.contains("GuiRecording.screenShown"));
    }

    /**
     * Closing a container is not in a recording unless the recording writes it.
     *
     * <p>{@code ServerGamePacketListenerImpl.handleContainerClose} calls
     * {@code ServerPlayer.doCloseContainer}, which sends nothing - only the server closing a
     * container for its own reasons goes through {@code closeContainer} and sends a packet. So a
     * recording written without this holds the opening of every chest and the closing of none.
     */
    private static void theRecordingWritesTheClosesTheServerDoesNot(Path root) throws Exception {
        String recording = Files.readString(root.resolve("com/moulberry/flashback/gui/GuiRecording.java"));
        String closed = methodBody(recording, "screenClosed");
        check("the recording handles a screen closing", closed != null);
        if (closed != null) {
            check("closing a container is written into the recording",
                closed.contains("FlashbackRemoteContainer.close"));
            check("every container screen is closed, not only the player's own inventory",
                closed.contains("AbstractContainerScreen") && !closed.contains("InventoryMenu"));
        }
    }

    private static Method handlerFor(Class<?> packetType) {
        for (Method method : ReplayGamePacketHandler.class.getDeclaredMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (parameters.length == 1 && parameters[0] == packetType) {
                return method;
            }
        }
        return null;
    }

    /** The braces of a method in a source file, found by name. */
    private static String methodBody(String source, String name) {
        int from = 0;
        while (true) {
            int at = source.indexOf(name + "(", from);
            if (at < 0) {
                return null;
            }
            from = at + name.length();
            if (at > 0 && Character.isJavaIdentifierPart(source.charAt(at - 1))) {
                continue;
            }

            int depth = 0;
            int i = at + name.length();
            for (; i < source.length(); i++) {
                char c = source.charAt(i);
                if (c == '(') {
                    depth++;
                } else if (c == ')' && --depth == 0) {
                    i++;
                    break;
                }
            }

            int brace = source.indexOf('{', i);
            int semicolon = source.indexOf(';', i);
            if (brace < 0 || (semicolon >= 0 && semicolon < brace)) {
                continue;
            }

            int bodyDepth = 0;
            for (int j = brace; j < source.length(); j++) {
                char c = source.charAt(j);
                if (c == '{') {
                    bodyDepth++;
                } else if (c == '}' && --bodyDepth == 0) {
                    return source.substring(brace, j + 1);
                }
            }
            return null;
        }
    }

    private static void check(String what, boolean condition) {
        if (!condition) {
            failures += 1;
            System.out.println("FAIL: " + what);
        } else {
            System.out.println("ok:   " + what);
        }
    }

}

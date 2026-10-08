package com.moulberry.flashback;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Static check for ImGui begin/end pairing in the timeline sources.
 *
 * <p>ImGui keeps its own stacks, and unbalancing one of them does not fail where the mistake is - it
 * fails later, as an assertion or corrupted state somewhere unrelated. That is exactly what happened
 * when a popup was left open by an early return, so the rule is checked mechanically: every opener
 * has its closer, and no {@code return} happens between them.
 */
public class ImGuiPairingCheck {

    /** Openers sharing a closer, so a closer that serves several openers is not double counted. */
    private record Group(List<String> openers, String closer) {}

    private static final List<Group> GROUPS = List.of(
        new Group(List.of("ImGuiHelper.beginPopup(", "ImGui.beginPopup(", "ImGuiHelper.beginPopupModal(",
                         "ImGui.beginPopupModal(", "ImGuiHelper.beginPopupModalCloseable("), "ImGui.endPopup()"),
        new Group(List.of("ImGui.beginChild(", "ImGuiHelper.beginChild("), "ImGui.endChild()"),
        new Group(List.of("ImGui.beginCombo("), "ImGui.endCombo()"),
        new Group(List.of("ImGui.beginTooltip(", "ImGuiHelper.beginTooltip("), "ImGui.endTooltip()"),
        new Group(List.of("ImGui.beginDisabled("), "ImGui.endDisabled()"),
        new Group(List.of("ImGui.pushID("), "ImGui.popID()"),
        new Group(List.of("ImGui.pushClipRect(", ".pushClipRect("), ".popClipRect()"),
        new Group(List.of("ImGui.pushTextWrapPos("), "ImGui.popTextWrapPos()")
    );

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        for (String file : args) {
            check(Path.of(file));
        }
        if (failures > 0) {
            System.out.println("FAILURES: " + failures);
            System.exit(1);
        }
        System.out.println("All ImGui begin/end pairs are balanced");
    }

    private static void check(Path file) throws Exception {
        List<String> lines = Files.readAllLines(file);
        for (Group group : GROUPS) {
            int opens = 0;
            int closes = 0;
            for (String line : lines) {
                for (String opener : group.openers()) {
                    if (line.contains(opener)) {
                        opens += 1;
                    }
                }
                if (line.contains(group.closer())) {
                    closes += 1;
                }
            }
            if (opens != closes) {
                failures += 1;
                System.out.printf("FAIL %s: %d open vs %d close of %s%n",
                    file.getFileName(), opens, closes, group.closer());
            }
        }

        // Style vars can be popped a few at a time, so count the argument.
        int pushes = 0;
        int pops = 0;
        for (String line : lines) {
            if (line.contains("pushStyleVar(") || line.contains("ImGuiHelper.pushStyleVar(")) {
                pushes += 1;
            }
            Matcher m = Pattern.compile("popStyleVar\\((\\d*)\\)").matcher(line);
            while (m.find()) {
                String arg = m.group(1);
                pops += arg.isEmpty() ? 1 : Integer.parseInt(arg);
            }
        }
        if (pushes != pops) {
            failures += 1;
            System.out.printf("FAIL %s: %d style var pushes vs %d pops%n", file.getFileName(), pushes, pops);
        }

        // Nothing may escape a block that needs closing: an early return, break or continue would
        // leave ImGui's state stack holding an entry that is never released.
        List<String> openers = List.of(
            "ImGuiHelper.beginPopup(", "ImGui.beginPopup(", "ImGui.beginPopupModal(",
            "ImGuiHelper.beginPopupModal(", "ImGui.beginChild(", "ImGui.beginCombo(",
            "ImGui.beginTooltip(", "ImGui.beginDisabled(", "ImGui.pushID(",
            "ImGui.pushClipRect(", ".pushClipRect(", "ImGui.pushStyleVar(",
            "ImGui.pushTextWrapPos(");
        List<String> closers = List.of(
            "endPopup()", "endChild()", "endCombo()", "endTooltip()", "endDisabled()",
            "popID()", "popClipRect()", "popStyleVar()", "popTextWrapPos()");

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (openers.stream().noneMatch(line::contains)) {
                continue;
            }
            int depth = 0;
            boolean seenBrace = false;
            for (int j = i; j < Math.min(lines.size(), i + 120); j++) {
                String inner = lines.get(j);
                depth += count(inner, '{') - count(inner, '}');
                if (inner.contains("{")) {
                    seenBrace = true;
                }
                if (j > i && seenBrace && depth <= 0) {
                    break;
                }
                if (j > i && closers.stream().anyMatch(inner::contains)) {
                    break;
                }
                if (j > i && Pattern.compile("\\b(return|break|continue)\\b").matcher(inner).find()) {
                    boolean isReturn = Pattern.compile("\\breturn\\b").matcher(inner).find();
                    // A return always skips the closer. break/continue only do so when they belong to
                    // the block itself; inside a nested loop they merely advance that loop, and the
                    // closer still runs afterwards.
                    if (isReturn || depth == 1) {
                        failures += 1;
                        System.out.printf("FAIL %s:%d: %s escapes %s%n",
                            file.getFileName(), j + 1, inner.trim(), line.trim());
                    }
                }
            }
        }
    }

    private static int count(String text, char c) {
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == c) {
                n += 1;
            }
        }
        return n;
    }
}

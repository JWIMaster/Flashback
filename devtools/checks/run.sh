#!/bin/bash
# Runs the headless test suites against the current build.
#
# These check the parts of the editor that can be reasoned about without a game: the migration of old
# projects, which camera a tick resolves to, the timeline's layout and selection rules, the orbit
# camera's geometry, the scroll bindings' round trip, how container changes are read as item
# movements, and that every ImGui begin has its end.
#
# Some of them need a stand-in for the game client, which is in stubs/.
set -e
cd "$(dirname "$0")/../.."
export JAVA_HOME="/Library/Java/JavaVirtualMachines/jdk-25.0.2.jdk/Contents/Home"

# The game ships one Gson; a development cache may hold an older one, and picking that mismatches the
# library the tests are built against. The game's own copy wins, and the newest of those at that.
GSON_DIR=$(ls -d "$HOME/Library/Application Support/PrismLauncher/libraries/com/google/code/gson/gson/"*/ 2>/dev/null | sort -V | tail -1)
if [ -n "$GSON_DIR" ]; then
    GSON_VERSION=$(basename "${GSON_DIR%/}")
    GSON="${GSON_DIR%/}/gson-$GSON_VERSION.jar"
else
    GSON=$(ls -d ~/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson/*/*/gson-*.jar 2>/dev/null | head -1)
fi
MC=$(ls .gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-*/*/minecraft-merged-*.jar 2>/dev/null | head -1)
LIBS=$(find ~/.gradle/caches/modules-2 ~/.gradle/caches/fabric-loom .gradle/loom-cache \
    "$HOME/Library/Application Support/PrismLauncher/libraries" \
    -name '*.jar' 2>/dev/null | grep -v sources | tr '\n' ':')

OUT=devtools/checks/out
rm -rf "$OUT"
mkdir -p "$OUT/stubs" "$OUT/classes"

find devtools/checks/stubs -name '*.java' > "$OUT/stub-sources.txt"
find devtools/checks/src -name '*.java' > "$OUT/sources.txt"

SLF4J=$(ls -d ~/.gradle/caches/modules-2/files-2.1/org.slf4j/slf4j-api/*/*/slf4j-api-*.jar 2>/dev/null | head -1)
CP="build/classes/java/main:deps/imgui-binding-1.90.0.jar:$GSON:$SLF4J:$MC"
"$JAVA_HOME/bin/javac" -nowarn -cp "$CP" -d "$OUT/stubs" @"$OUT/stub-sources.txt"
"$JAVA_HOME/bin/javac" -nowarn -cp "$OUT/stubs:$CP:$LIBS" -d "$OUT/classes" @"$OUT/sources.txt"

RUN="$OUT/stubs:$OUT/classes:build/classes/java/main:deps/imgui-binding-1.90.0.jar:$GSON:$SLF4J:$MC:$LIBS"
failures=0

# A suite reports itself with an exit status and ends with a one-line summary. Piping into tail to
# get that line would report tail's status instead of the suite's, so a failing suite would read as
# a pass, which is the one thing a check runner must never do.
run_check() {
    local name="$1"
    shift
    printf '%-46s ' "$name"
    local output
    if output=$("$JAVA_HOME/bin/java" -cp "$RUN" "$@" 2>&1); then
        printf '%s\n' "$output" | tail -1
    else
        failures=$((failures + 1))
        printf '%s\n' "$output" | grep -E '^(FAIL|FAILURES|Exception|.*Error)' | head -5
        printf '%s\n' "$output" | tail -1
    fi
}

for suite in \
    com.moulberry.flashback.state.MigrationTest \
    com.moulberry.flashback.state.EvaluationTest \
    com.moulberry.flashback.state.CameraObjectTest \
    com.moulberry.flashback.state.CameraInspectorRegressionTest \
    com.moulberry.flashback.state.CutTest \
    com.moulberry.flashback.state.LoadCompatTest \
    com.moulberry.flashback.editor.ui.timeline.LayoutTest \
    com.moulberry.flashback.keyframe.OrbitTest \
    com.moulberry.flashback.keybind.ScrollBindingsTest \
    com.moulberry.flashback.gui.GuiLogTest \
    com.moulberry.flashback.gui.ContainerWireTest \
    com.moulberry.flashback.playback.PlaybackTimingTest \
    com.moulberry.flashback.playback.AccuratePositionTimelineTest \
    com.moulberry.flashback.playback.WeatherColumnOrientationTest \
    com.moulberry.flashback.playback.WeatherTextureCoordinatesTest; do
    run_check "$(basename "$suite")" "$suite"
done

run_check "ImGuiPairingCheck" com.moulberry.flashback.ImGuiPairingCheck \
    src/main/java/com/moulberry/flashback/editor/ui/windows/TimelineWindow.java \
    src/main/java/com/moulberry/flashback/editor/ui/windows/CameraInspectorWindow.java
run_check "ContainerGuiCheck" com.moulberry.flashback.gui.ContainerGuiCheck src/main/java
run_check "MixinTargetCheck" com.moulberry.flashback.MixinTargetCheck src/main/java

if ! bash devtools/checks/run-replay-inventory.sh; then
    failures=$((failures + 1))
fi

if [ "$failures" -ne 0 ]; then
    echo "$failures suite(s) failed"
    exit 1
fi

#!/bin/bash
# Builds and runs the offscreen UI harness. Usage: devtools/uitest/run.sh <MainClass> [args...]
set -e
cd "$(dirname "$0")/../.."
export JAVA_HOME="/Library/Java/JavaVirtualMachines/jdk-25.0.2.jdk/Contents/Home"
GSON=$(ls -d ~/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson/*/*/gson-*.jar 2>/dev/null | head -1)
SLF4J=$(ls -d ~/.gradle/caches/modules-2/files-2.1/org.slf4j/slf4j-api/*/*/slf4j-api-*.jar 2>/dev/null | head -1)
JOML=$(ls -d ~/.gradle/caches/modules-2/files-2.1/org.joml/joml/*/*/joml-*.jar 2>/dev/null | head -1)
# Everything the game itself loads, so nothing the mod touches is missing a dependency.
LIBS=$(find "$HOME/Library/Application Support/PrismLauncher/libraries" -name '*.jar' 2>/dev/null | grep -v sources | tr '\n' ':')
# Fabric libraries the mod declares for itself (lattice and friends).
NESTED=$(find ~/.gradle/caches/modules-2/files-2.1/com.moulberry -name '*.jar' 2>/dev/null | grep -v sources | tr '\n' ':')
DEPS="deps/imgui-binding-1.90.0.jar:deps/imgui-natives.jar:$GSON:$SLF4J:$JOML:$LIBS:$NESTED"
MC=$(ls .gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-*/*/minecraft-merged-*.jar 2>/dev/null | head -1)
# The built mod carries its own bundled libraries (lattice, mixinsquared, the imgui natives).
MODJAR=$(ls -t build/libs/flashback-*.jar 2>/dev/null | grep -v sources | head -1)
OUT=devtools/uitest/out/classes
STUBS=devtools/uitest/out/stubs
mkdir -p "$OUT" "$STUBS" devtools/uitest/out
find devtools/uitest/src -name '*.java' > devtools/uitest/out/sources.txt
find devtools/uitest/stubs -name '*.java' > devtools/uitest/out/stub-sources.txt
# Stand-ins first on the classpath, so they shadow the real classes they replace.
"$JAVA_HOME/bin/javac" -nowarn -cp "$DEPS:build/classes/java/main:$MC" -d "$STUBS" @devtools/uitest/out/stub-sources.txt
"$JAVA_HOME/bin/javac" -nowarn -cp "$DEPS:$STUBS:build/classes/java/main:$MC" -d "$OUT" @devtools/uitest/out/sources.txt
# Stubs and harness first, so they shadow the real classes they stand in for.
"$JAVA_HOME/bin/java" -cp "$STUBS:$OUT:build/classes/java/main:$MODJAR:$DEPS:$MC" "$@"

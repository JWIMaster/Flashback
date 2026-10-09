#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
out="$root/build/replay-inventory-check"
mkdir -p "$out"
javac -d "$out" \
  "$root/src/main/java/com/moulberry/flashback/gui/InventoryMenuSlots.java" \
  "$root/devtools/checks/ReplayInventorySlotsTest.java"
java -cp "$out" ReplayInventorySlotsTest

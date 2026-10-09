# Fabric Basic Template (Minecraft 1.21.1)

Starter Fabric server mod pinned to Minecraft 1.21.1. Provides a single `/playergames` command and is intended as a minimal baseline for Codex generations that do not require specific gameplay features.

## What's Included
- Server-side entrypoint `com.playergames.plugin.PGPlugin` with logging example
- Fabric API + Loader dependencies preconfigured in `gradle.properties`
- UTF-8 resource filtering for `fabric.mod.json`

## Build
```bash
./gradlew build
```

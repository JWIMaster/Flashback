# Flashback inventory addon

- Adds a client-side helper that makes Flashback replay exports capture every GUI layer (HUD, screens, chat, pause menus, mod UIs, mouse cursor) while spectating in first-person.
- Only activates when Flashback is installed, the replay renderer/exporter is running, and a replay world is active; normal gameplay is untouched to keep overhead at zero.
- Renders the HUD and any open `Screen` into the same framebuffer Flashback downloads by building a `DrawContext` with the game render buffers after the world render, with depth testing disabled and GUI blending enabled so overlays appear exactly as in-game.
- Draws a lightweight on-frame cursor glyph at the scaled mouse position so inventories and other menus are visible in captured footage.
- Simple config file (`flashbackinventoryaddon.json` in the config folder) with `enableGuiInFlashbackReplays` (default `true`) to toggle the feature without uninstalling the mod.
- Safe fallback when Flashback is missing or not exporting; all hooks stay idle in that case.

## Notes / Limitations
- Honors Flashback’s own visibility settings; if the replay UI hides certain HUD elements, this mod does not override those choices.
- Cursor overlay is a minimal in-frame marker rather than the OS cursor graphic but tracks the correct scaled position during exports.
